# 海贝音乐 Render 切换 / 状态机 / 操作队列 一比一对齐计划

## 一、现状对比总览

| 机制 | 海贝音乐 | RawSMusic 现状 | 差距 |
|------|----------|----------------|------|
| **Render 抽象** | `MediaRender` 抽象类 + 7 种子类，统一 `devices()/init()/enableForceRate()` 接口 | 无抽象层，`FfmpegAudioPlayer` + `UsbExclusiveManager` 硬编码双路径 | 缺少统一 Render 接口 |
| **Render 切换** | `WorkerThread` 异步切换，`native_setOutputDevice()` 不释放引擎 | `stop() → release() → play()` 完整重建，解码器状态丢失 | 切换延迟高、状态丢失 |
| **状态机** | `StateMachineFactory` 注解驱动，`@Transition` 声明合法转换，非法转换自动拦截 | `_playState` StateFlow 手动赋值，无转换合法性检查 | 可在任意状态做任意操作 |
| **操作队列** | `PlayOpEventHandlerThread` 容量 100，`put()` 先 clear 再 offer，每次 handle 后 sleep 50ms | `transportMutex` 互斥锁 + `pendingSeekPosition` 零散机制 | 快速操作可重叠，无串行化保证 |

---

## 二、分阶段实施计划

### 阶段 1：状态机 — 注解驱动的状态转换合法性检查

**目标**：在 `PlayerController` 中引入状态机，拦截非法状态转换。

#### 1.1 新建 `PlaybackStateMachine.kt`

```
路径: module/player/src/main/kotlin/com/rawsmusic/module/player/statemachine/
文件: PlaybackStateMachine.kt
```

**海贝对标**：`com.hiby.music.sdk.util.statemachine.StateMachineFactory`

**实现内容**：

```kotlin
// 状态枚举（对齐海贝 PlayerState）
enum class PlaybackState {
    IDLE,       // 对应海贝 Stop(InitState)
    PREPARING,  // 对应海贝 Preparing
    PLAYING,    // 对应海贝 Playing
    PAUSED,     // 对应海贝 Pause
    STOPPED,    // 对应海贝 Stop
    ERROR       // 对应海贝 Error
}

// 转换注解
@Target(AnnotationTarget.FUNCTION)
annotation class StateTransition(vararg val from: PlaybackState, val to: PlaybackState)

// 状态机核心
class PlaybackStateMachine {
    @Volatile
    var currentState: PlaybackState = PlaybackState.IDLE
        private set

    private val lock = Any()

    // 检查转换是否合法
    fun canTransition(from: PlaybackState, to: PlaybackState): Boolean

    // 执行转换，非法则返回 false
    fun transition(to: PlaybackState, action: () -> Unit = {}): Boolean

    // 重置到 IDLE
    fun reset()
}
```

**合法转换表**（对齐海贝 `@Transitions`）：

| 操作 | from → to | 海贝对应 |
|------|-----------|----------|
| `play()` | IDLE→PREPARING, PREPARING→PREPARING, PLAYING→PREPARING, PAUSED→PREPARING, STOPPED→PREPARING, ERROR→PREPARING | `@Transition(from=*, to="Preparing")` |
| `prepared()` | PREPARING→PLAYING, STOPPED→PLAYING | `@Transition(from="Preparing",to="Playing")` |
| `pause()` | PLAYING→PAUSED | `@Transition(from="Playing",to="Pause")` |
| `resume()` | PAUSED→PLAYING | `@Transition(from="Pause",to="Playing")` |
| `stop()` | PREPARING→STOPPED, PLAYING→STOPPED, PAUSED→STOPPED, STOPPED→STOPPED, ERROR→STOPPED | `@Transition(from=*,to="Stop")` |
| `error()` | PREPARING→ERROR, PLAYING→ERROR, PAUSED→ERROR, STOPPED→ERROR, ERROR→ERROR | `@Transition(from=*,to="Error")` |
| `seek()` | NotChange (不改变状态) | `@Transitions(NotChange=true)` |

**关键差异**：海贝用 Java 反射 + 动态代理实现，我们用 Kotlin 直接实现（避免反射开销，编译期可检查）。

#### 1.2 改造 `PlayerController`

将所有 `_playState.value =` 赋值替换为 `stateMachine.transition()`：

```kotlin
// 之前
_playState.value = PlayState.PREPARING

// 之后
if (!stateMachine.transition(PlaybackState.PREPARING)) {
    AppLogger.w(TAG, "Illegal transition: ${stateMachine.currentState} → PREPARING, ignored")
    return
}
_playState.value = stateMachine.currentState // 同步给 StateFlow
```

**改造点清单**（当前代码中所有 `_playState.value =` 赋值位置）：

| 位置 | 当前赋值 | 改为 |
|------|----------|------|
| `playInternal()` :2026 | `PREPARING` | `stateMachine.transition(PREPARING)` |
| `pause()` :4258 | `PAUSED` | `stateMachine.transition(PAUSED)` |
| `resume()` :4368 | `PLAYING` | `stateMachine.transition(PLAYING)` |
| `stop()` :4444 | `STOPPED` | `stateMachine.transition(STOPPED)` |
| `seekUsbExclusiveInternal()` :4698 | `PREPARING` | `stateMachine.transition(PREPARING)` |
| `seekUsbExclusiveInternal()` :4639 | `PLAYING`/`PAUSED` | `stateMachine.transition(PLAYING/PAUSED)` |
| `recoverUsbExclusiveAsync()` :4889-4910 | 多个状态 | 每个赋值点替换 |
| `applyUsbOutputSettingsChanged()` :2966 | `PREPARING` | `stateMachine.transition(PREPARING)` |

**验证标准**：在 PAUSED 状态下调用 `resume()` 只允许 PAUSED→PLAYING，在 STOPPED 状态下调用 `pause()` 被拦截。

---

### 阶段 2：操作队列 — Play/Seek 事件串行化

**目标**：引入操作事件队列，保证 play/seek 操作串行执行，防止快速切歌/seek 竞态。

#### 2.1 新建 `PlaybackEventQueue.kt`

```
路径: module/player/src/main/kotlin/com/rawsmusic/module/player/statemachine/
文件: PlaybackEventQueue.kt
```

**海贝对标**：`MediaPlayer.PlayOpEventHandlerThread`

**实现内容**：

```kotlin
sealed class PlaybackEvent {
    abstract suspend fun handle()

    data class PlayEvent(
        val song: AudioFile,
        val queue: List<AudioFile>,
        val index: Int
    ) : PlaybackEvent() {
        override suspend fun handle() { /* 调用 playInternal */ }
    }

    data class SeekEvent(
        val positionMs: Long,
        val songPath: String
    ) : PlaybackEvent() {
        override suspend fun handle() { /* 调用 seekInternal */ }
    }

    object PauseEvent : PlaybackEvent() {
        override suspend fun handle() { /* 调用 pauseInternal */ }
    }

    object ResumeEvent : PlaybackEvent() {
        override suspend fun handle() { /* 调用 resumeInternal */ }
    }

    object StopEvent : PlaybackEvent() {
        override suspend fun handle() { /* 调用 stopInternal */ }
    }

    data class RenderSwitchEvent(
        val config: RenderConfig
    ) : PlaybackEvent() {
        override suspend fun handle() { /* 调用 renderSwitchInternal */ }
    }
}

class PlaybackEventQueue(
    private val scope: CoroutineScope,
    private val handler: PlaybackEventHandler
) {
    private val queue = Channel<PlaybackEvent>(capacity = 64)
    private val currentEvent = MutableStateFlow<PlaybackEvent?>(null)

    init {
        scope.launch(Dispatchers.Default) {
            for (event in queue) {
                currentEvent.value = event
                try {
                    event.handle()
                } catch (t: Throwable) {
                    AppLogger.e("PlaybackEventQueue", "Event failed", t)
                }
                delay(30) // 对齐海贝 SystemClock.sleep(50L)，缩短到 30ms
                currentEvent.value = null
            }
        }
    }

    // 对齐海贝 put(): clear + offer（只保留最新操作）
    fun submit(event: PlaybackEvent) {
        queue.trySend(event)
    }

    // 对齐海贝 clearEvents()
    fun clearPending() {
        // Channel 不支持 clear，用 cancel+重建 或 filter 机制
    }
}
```

**关键差异**：
- 海贝用 `ArrayBlockingQueue(100)` + `Thread`，我们用 Kotlin `Channel` + 协程
- 海贝 `put()` 先 `clear()` 再 `offer()`（只保留最新），我们用 `Channel` 的 `trySend()` 配合 `conflate()` 语义
- 海贝每次 handle 后 `sleep(50ms)`，我们缩短到 `delay(30)`（协程调度更轻量）

#### 2.2 改造 `PlayerController` 公共方法

```kotlin
// 之前
fun play(song: AudioFile, queue: List<AudioFile> = emptyList(), index: Int = 0) {
    scope.launch {
        transportMutex.withLock {
            playInternal(song, queue, index)
        }
    }
}

// 之后
fun play(song: AudioFile, queue: List<AudioFile> = emptyList(), index: Int = 0) {
    eventQueue.submit(PlaybackEvent.PlayEvent(song, queue, index))
}

fun pause() {
    eventQueue.submit(PlaybackEvent.PauseEvent)
}

fun resume() {
    eventQueue.submit(PlaybackEvent.ResumeEvent)
}

fun stop() {
    eventQueue.submit(PlaybackEvent.StopEvent)
}

fun seekTo(positionMs: Long) {
    eventQueue.submit(PlaybackEvent.SeekEvent(positionMs, _currentSong.value?.path ?: ""))
}
```

**操作去重**（对齐海贝 `put()` 的 `clear()` 逻辑）：
- 连续 `PlayEvent`：只保留最后一个（用户快速切歌）
- 连续 `SeekEvent`：只保留最后一个（用户拖进度条）
- `StopEvent` / `PauseEvent`：清空队列中所有待执行的 `PlayEvent` / `SeekEvent`

**验证标准**：
1. 快速连续调用 `play()` 5 次（不同歌曲），只播放最后一首
2. 播放中快速 seek 10 次，只执行最后一次 seek
3. `stop()` 后队列中的 `SeekEvent` 被丢弃，不执行

---

### 阶段 3：Render 抽象层 — 统一输出接口

**目标**：抽象出统一的 `AudioRender` 接口，为后续 hot-switch 打基础。

#### 3.1 新建 `AudioRender.kt` 接口

```
路径: module/player/src/main/kotlin/com/rawsmusic/module/player/render/
文件: AudioRender.kt
文件: BaseAudioRender.kt
文件: SystemAudioRender.kt
文件: UsbExclusiveRender.kt
文件: DirectAaudioRender.kt
```

**海贝对标**：`MediaPlayer.MediaRender` / `MediaRenderCommon` / `UsbRender` / `AudioTrackRender`

**接口定义**（对齐海贝 `MediaRender`）：

```kotlin
interface AudioRender {
    /** 唯一标识 */
    val id: String

    /** 显示名称 */
    val displayName: String

    /** 设备类型 bitmask（对齐海贝 devices()） */
    val deviceType: Int

    /** 是否可用 */
    val isEnabled: Boolean

    /** 初始化 render */
    suspend fun init()

    /** 释放 render 资源 */
    suspend fun release()

    /** 设置输出格式 */
    suspend fun setOutputFormat(sampleRate: Int, channels: Int, bitDepth: Int)

    /** 写入 PCM 数据 */
    suspend fun writePcm(data: ByteArray, offset: Int, length: Int): Int

    /** 暂停 */
    suspend fun pause()

    /** 恢复 */
    suspend fun resume()

    /** flush */
    suspend fun flush()

    /** 音量控制 */
    val volumeController: VolumeController?

    /** 是否支持强制采样率 */
    var forceSampleRate: Int
    var forceRateEnabled: Boolean
}
```

**抽象基类**（对齐海贝 `MediaRenderCommon`）：

```kotlin
abstract class BaseAudioRender(
    override val deviceType: Int
) : AudioRender {
    override var forceSampleRate: Int = 0
    override var forceRateEnabled: Boolean = false
    override val volumeController: VolumeController? = null
    // 公共逻辑
}
```

**三个具体实现**：

| Render | 海贝对应 | 封装内容 |
|--------|----------|----------|
| `SystemAudioRender` | `AudioTrackRender(223)` | `FfmpegAudioPlayer` 的 AudioTrack/OpenSL/AAudio 路径 |
| `UsbExclusiveRender` | `UsbRender(227)` | `UsbExclusiveManager` + `UsbAudioEngine` |
| `DirectAaudioRender` | `HibyHiResRender(228)` | `NativeAudioEngine` Direct AAudio 路径 |

#### 3.2 新建 `RenderManager.kt`

```
路径: module/player/src/main/kotlin/com/rawsmusic/module/player/render/
文件: RenderManager.kt
```

**海贝对标**：`MediaPlayer.selectRender()` + `changeRender()` + `mRenders SparseArray`

```kotlin
class RenderManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val renders = mutableMapOf<Int, AudioRender>()
    private var currentRender: AudioRender? = null
    private val renderLock = Mutex()

    // 对齐海贝 mRenders SparseArray
    fun registerRender(render: AudioRender)
    fun unregisterRender(deviceType: Int)

    // 对齐海贝 selectRender()
    fun selectRender(): AudioRender?

    // 对齐海贝 changeRender() — 异步切换
    suspend fun changeRender(targetType: Int): Boolean

    // 对齐海贝 getCurrentRender()
    fun getCurrentRender(): AudioRender? = currentRender
}
```

**`selectRender()` 逻辑**（对齐海贝）：

```kotlin
fun selectRender(): AudioRender? {
    // 1. USB 独占设备已连接 → UsbExclusiveRender
    if (usbExclusiveManager.isDeviceConnected()) {
        return renders[DEVICE_USB_EXCLUSIVE]
    }
    // 2. Direct AAudio 可用且用户选择 → DirectAaudioRender
    if (audioOutputMode == DIRECT && isDirectAvailable()) {
        return renders[DEVICE_DIRECT_AAUDIO]
    }
    // 3. 默认 → SystemAudioRender
    return renders[DEVICE_SYSTEM]
}
```

**验证标准**：`RenderManager.selectRender()` 在 USB 插入/拔出时返回正确的 Render。

---

### 阶段 4：Render 热切换 — 不释放引擎

**目标**：切换输出路径时不完整重建，保留解码器状态。

**这是最复杂的阶段**，因为海贝有自研 native 引擎支持 `native_setOutputDevice()`，我们的 native 层目前不支持运行时切换输出 sink。

#### 4.1 短期方案：减少重建开销

在不改 native 层的前提下，优化 `applyUsbOutputSettingsChanged()` 的重建流程：

```kotlin
// 当前流程（完整重建）：
ffmpegPlayer.stop()
usbExclusiveManager.stopStreaming()
sharedUsbAudioEngine.release()
usbExclusiveManager.resetPlaybackPipeline()
play(song, queue, index)  // 完整重新初始化

// 优化流程（保留解码器，只换输出）：
val savedPosition = _position.value
val savedDecoderState = ffmpegPlayer.saveDecoderState() // 新增
ffmpegPlayer.detachOutput()  // 新增：只断开输出，不停止解码
sharedUsbAudioEngine.release()
usbExclusiveManager.resetPlaybackPipeline()
ffmpegPlayer.attachOutput(newRender)  // 新增：连接新输出
ffmpegPlayer.seekTo(savedPosition)     // 恢复位置
```

**需要 native 层新增的接口**：

| 接口 | 用途 | 海贝对应 |
|------|------|----------|
| `native_detach_output()` | 断开当前输出 sink，解码器继续运行 | `native_pause()` |
| `native_attach_output(deviceId)` | 连接新输出 sink | `native_setOutputDevice(deviceId)` |
| `native_flush_output()` | 只 flush 输出，不 flush 解码器 | `native_flush()` |
| `native_resume_output()` | 恢复输出 | `native_resume()` |

#### 4.2 中期方案：native 层支持多输出 sink

在 `usb_audio_engine.cpp` 和 `native_audio_engine.cpp` 中实现输出 sink 切换：

```cpp
// usb_audio_engine.cpp
struct UsbAudioEngine {
    // 当前输出 sink
    enum class OutputSink { NONE, USB_ISO, AUDIOTRACK, AAUDIO_DIRECT, OPENSL };
    OutputSink currentSink;

    // 切换 sink（不释放解码器）
    int switchOutputSink(OutputSink newSink);
};
```

#### 4.3 WorkerThread 对齐

**海贝 `WorkerThread.handleMessage()` 流程**：

```
1. native_pause()
2. sleep(1000ms) // 等待设备稳定
3. native_setOutputDevice(newDeviceId) // 切换输出
4. native_flush()
5. if (isPlaying) native_resume()
```

**我们的对齐实现**：

```kotlin
// RenderManager.changeRender()
suspend fun changeRender(targetType: Int): Boolean {
    val target = renders[targetType] ?: return false
    val current = currentRender ?: return false
    if (current.deviceType == targetType) return true

    renderLock.withLock {
        // 1. 暂停当前输出（对齐 native_pause）
        current.pause()
        delay(100) // 等待设备稳定（海贝 1000ms，我们缩短）

        // 2. 切换输出（对齐 native_setOutputDevice）
        //    短期：detach + attach
        //    中期：native switchOutputSink
        ffmpegPlayer.switchOutputSink(target)

        // 3. flush（对齐 native_flush）
        target.flush()

        // 4. 如果正在播放则恢复（对齐 if(isPlaying) native_resume）
        if (stateMachine.currentState == PlaybackState.PLAYING) {
            target.resume()
        }

        currentRender = target
        notifyRenderChanged(target)
    }
    return true
}
```

**验证标准**：
1. USB 插入时从 `SystemAudioRender` 切换到 `UsbExclusiveRender`，播放不中断（< 500ms 切换延迟）
2. USB 拔出时从 `UsbExclusiveRender` 切换到 `SystemAudioRender`，播放继续
3. 切换后播放位置连续，不从头开始

---

## 三、实施优先级与依赖关系

```
阶段 1: 状态机          ──────────────┐
  (无依赖，可立即开始)                  │
                                       ├──→ 阶段 3: Render 抽象 ──→ 阶段 4: 热切换
阶段 2: 操作队列         ──────────────┘
  (无依赖，可立即开始)
```

| 阶段 | 工作量 | 优先级 | 依赖 | 风险 |
|------|--------|--------|------|------|
| 1. 状态机 | 2-3 天 | P0 | 无 | 低：纯 Kotlin，不影响 native |
| 2. 操作队列 | 2-3 天 | P0 | 无 | 中：需处理协程取消语义 |
| 3. Render 抽象 | 4-5 天 | P1 | 阶段 1+2 | 中：需重构 PlayerController 大量方法 |
| 4. 热切换 | 5-7 天 | P2 | 阶段 3 + native 改造 | 高：涉及 native 层多输出 sink |

---

## 四、海贝关键代码 vs 我们的对齐实现

### 4.1 状态机对比

| 海贝 | RawSMusic 对齐 |
|------|----------------|
| `StateMachineFactory.createProxy(IMediaPlayer.class, new PlayerHandler())` | `PlaybackStateMachine()` 直接实例化 |
| `@MachineState(name="Playing")` 注解 | `enum class PlaybackState` 枚举 |
| `@Transition(from="Playing", to="Pause")` 注解 | `transition(PAUSED)` 内部检查 `canTransition()` |
| `@Transitions(NotChange=true)` | `seek()` 不调用 `transition()` |
| 反射 + 动态代理拦截 | `synchronized` + 直接检查 |
| `synchronized void pause()` | `suspend fun pause()` + `stateMachine.transition()` |

### 4.2 操作队列对比

| 海贝 | RawSMusic 对齐 |
|------|----------------|
| `ArrayBlockingQueue<PlayerOPEvent>(100)` | `Channel<PlaybackEvent>(64)` |
| `eventsQueue.take()` 阻塞 | `for (event in queue)` 协程接收 |
| `put()` 先 `clear()` 再 `offer()` | `submit()` + `conflate()` 语义 |
| `SystemClock.sleep(50L)` | `delay(30)` |
| `PlayerPlayOPEvent.handle()` 调 `native_play` | `PlayEvent.handle()` 调 `playInternal()` |
| `PlayerSeekOPEvent.handle()` 调 `native_seek` | `SeekEvent.handle()` 调 `seekInternal()` |
| `clearEvents()` on pause/stop | `clearPending()` on pause/stop |

### 4.3 Render 切换对比

| 海贝 | RawSMusic 对齐 |
|------|----------------|
| `SparseArray<MediaRender> mRenders` | `mutableMapOf<Int, AudioRender>` |
| `selectRender()` 优先级判断 | `selectRender()` 相同优先级 |
| `changeRender(render.devices())` | `changeRender(targetType)` |
| `WorkerThread` (HandlerThread) | `RenderManager` (CoroutineScope) |
| `native_setOutputDevice(id)` 不释放引擎 | 短期：detach/attach；中期：native switchOutputSink |
| `native_pause() → sleep(1000) → setOutput → flush → resume` | `pause() → delay(100) → switch → flush → resume()` |
| `notifyRenderChange()` 回调 | `notifyRenderChanged()` StateFlow |

---

## 五、验证矩阵

| 测试场景 | 预期行为 | 验证方法 |
|----------|----------|----------|
| STOPPED 状态下 pause() | 拦截，不执行 | 日志: `Illegal transition: STOPPED → PAUSED` |
| PAUSED 状态下 resume() | 执行 PAUSED→PLAYING | 状态变为 PLAYING |
| 快速连续 play 5 首歌 | 只播放最后一首 | 队列只保留最新 PlayEvent |
| 快速连续 seek 10 次 | 只执行最后一次 | 队列只保留最新 SeekEvent |
| stop() 后队列中的 seek 被丢弃 | seek 不执行 | 队列清空 |
| USB 插入切换 Render | < 500ms 切换，播放继续 | 位置连续 |
| USB 拔出切换 Render | 切换到 SystemAudioRender | 播放不中断 |
| Render 切换中再请求切换 | 第二次请求排队或拒绝 | `_isRenderSwitching` 保护 |
| 播放中切换采样率 | 短暂静音后继续播放 | 位置连续，新采样率生效 |

---

## 六、风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 状态机拦截导致合法操作被误拦 | 播放卡死 | 保留 `forceTransition()` 应急接口，绕过检查 |
| 协程 Channel 背压导致内存增长 | OOM | Channel 容量 64 + `conflate` 语义 |
| Render 抽象层引入性能开销 | 延迟增加 | `suspend fun` + 协程，避免反射 |
| native 多输出 sink 改造复杂 | 工期延长 | 分两阶段：短期 detach/attach，中期 native 支持 |
| USB 热切换时序竞态 | 爆音/崩溃 | `renderLock` 互斥 + delay 等待设备稳定 |
