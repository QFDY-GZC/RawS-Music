# Poweramp 音频扫描架构分析

> 基于 jadx 反编译 `com.maxmpz.audioplayer` 包分析

---

## 1. 整体架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                        触发层 (Trigger)                          │
│  App启动 / 存储挂载 / ContentObserver / 用户手动 / 外部API       │
└──────────────────────────────┬──────────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                   调度层 (scanner.B - AppScannerSupport)         │
│  节流 / 去抖 / 冷却期检查 / Handler延迟消息                      │
└──────────────────────────────┬──────────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                分发层 (ScanDispatcherService)                    │
│  ACTION_SCAN_DIRS → 目录扫描    ACTION_SCAN_TAGS → 标签重扫      │
└──────────────────────────────┬──────────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│              目录扫描层 (DirAndSAFScanner)                        │
│  6个ScanWorker线程并行 / JNI native_scan / SAF路径适配           │
└──────────────────────────────┬──────────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│               标签读取层 (TagReader / FFMpeg / Mod)               │
│  JNI native_scan_file → TagAndMeta 数据对象                      │
└──────────────────────────────┬──────────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│             数据库写入层 (library.B / scanner.X)                  │
│  批量INSERT: folders, folder_files, artists, albums, playlists   │
└──────────────────────────────┬──────────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                   状态广播层 (MsgBus)                             │
│  dir_scan_started / tag_scan_started / tag_scan_finished         │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 触发层

### 2.1 App 启动触发
- **入口**: `Application.onCreate()` → 创建 `scanner.B` 实例
- **方法**: `scanner.B.A()` — 初始扫描入口
- **逻辑**: 检查 App 版本是否变化（触发 MilkScanService 迁移），然后调用 `m2452("initial scan")` 判断是否需要扫描
- **条件**: 自动扫描开关开启时（`C0731.O0`, `C0731.Q0`），调度延迟扫描

### 2.2 存储挂载触发
- **入口**: `StorageBroadcastReceiver` 监听 `MEDIA_MOUNTED` / `USB_STATE`
- **方法**: `scanner.B.m2451(Intent)` — 处理存储事件
- **逻辑**: 媒体挂载或 USB 断开（MTP）时，调度延迟目录扫描

### 2.3 内容变化触发
- **入口**: `scanner.C0761` (ContentObserver) 监听内容变化
- **方法**: `scanner.B.m2453("content observer scan")`
- **逻辑**: 去抖后调度延迟扫描，递增间隔避免频繁扫描

### 2.4 用户手动触发
- **入口**: `Application.onBusMsg` 处理 `cmd_app_rescan` 消息
- **逻辑**: 直接触发扫描，跳过节流检查

---

## 3. 调度层 (AppScannerSupport)

**类**: `com.maxmpz.audioplayer.scanner.B`

### 3.1 核心方法

| 方法 | 功能 |
|------|------|
| `A()` | 初始扫描入口（仅执行一次） |
| `m2451(Intent)` | 存储事件处理 |
| `m2452(String)` | 扫描门控（返回 true 则跳过扫描） |
| `m2453(String)` | 内容变化触发（去抖） |
| `m2454(String, int, int)` | 内部调度（发送 Handler 延迟消息） |

### 3.2 节流机制

```
m2452("scan request") 门控检查:
  ├── disable 计数 > 0 → 跳过
  ├── 扫描已在运行 → 跳过
  ├── 距上次扫描 < 3秒 → 跳过
  ├── 距上次 SAF 操作 < 11秒 → 跳过
  └── 距上次调度 < 5秒 → 跳过
```

### 3.3 Handler 调度
- 使用主线程 `HandlerC0002` 发送延迟消息
- 消息触发 `ScanDispatcherService.startService()`

---

## 4. 分发层 (ScanDispatcherService)

**类**: `com.maxmpz.audioplayer.scanner.ScanDispatcherService`

- **类型**: `IntentService`（串行执行）
- **静态字段 `x`**: 扫描状态（0=空闲, 1=扫描中）

### 4.1 两种扫描模式

| Intent Action | 方法 | 用途 |
|---------------|------|------|
| `ACTION_SCAN_DIRS` | `m2455()` | 完整目录扫描（发现新文件 + 读取标签） |
| `ACTION_SCAN_TAGS` | `A()` | 仅标签重扫（不重新遍历目录） |

### 4.2 参数控制
- `onlyIfEmpty`: 仅数据库为空时扫描
- `fastScan`: 快速扫描（跳过某些检查）
- `eraseTags`: 清除已有标签
- `fullRescan`: 完全重扫（忽略缓存）
- `resolvePlaylists`: 解析播放列表
- `importSystemPlaylists`: 导入系统播放列表
- `reparsePlaylists`: 重新解析播放列表
- `scanProviders`: 扫描 ContentProvider 数据源

---

## 5. 目录扫描层

### 5.1 DirScanner（抽象基类）
- **JNI 方法**: `native_init()`, `native_scan()`, `native_release()`
- **回调接口**: `InterfaceC0760`
  - `startDirectory(path, ...)` — 进入目录
  - `fileFound(name, type, mode, size, mtime, ...)` — 发现文件
  - `endDirectory(path, ...)` — 离开目录

### 5.2 DirAndSAFScanner（具体实现）
- 继承 `DirScanner`
- **SAF 判断**: `AbstractC0623.m2009(path)` — 判断是否为 SAF 路径
- **并行扫描**: `B()` 方法启动 **6 个 ScanWorker 线程**
- **工作分发**: `ArrayBlockingQueue<ScanWorkItem>`

### 5.3 ScanWorker（扫描工作线程）
- **类**: `com.maxmpz.audioplayer.scanner.folder.e`
- 继承 `Thread`
- 每个线程独立持有: `Dirent` 结构体、`StringBuilder` 缓冲区、SAF 上下文
- **JNI 方法**: `native_fdopendir()`, `native_readdir()`, `native_closedir()`
- **隐藏文件过滤**: `m2476()` — 跳过 `.` 或 `_` 开头的文件/目录

### 5.4 Dirent（目录条目）
```java
class Dirent {
    String d_name;     // 文件名
    int d_type;        // DT_DIR=4, DT_REG=8
    int st_mode;       // 文件模式
    long st_mtime;     // 修改时间
    long st_size;      // 文件大小
}
```

---

## 6. 标签读取层

### 6.1 TagReader（主标签引擎）
- **JNI**: `native_create`, `native_scan_file`, `native_release`
- **方法**:
  - `A(String, int, int)` — 通过文件路径扫描
  - `m2461(String, ParcelFileDescriptor, ...)` — 通过 FD 扫描（SAF）
- **专辑封面**: 通过 ashmem FD 提取
- **编码处理**: `CharsetDecoder` 处理非 UTF-8 标签

### 6.2 FFMpegTagReader
- **JNI**: `native_scan_file()`, `native_scan_file_type()`
- 用于 FFmpeg 支持的格式

### 6.3 ModTagReader
- 用于 MOD/S3M/XM/IT 等 tracker 格式

### 6.4 TagAndMeta（数据容器）
```java
class TagAndMeta {
    String title, artist, albumArtist, album, genre;
    String comment, composer, lyrics, cueSheet;
    int year, track, durationMS, bitrate;
    int sampleRate, bitsPerSample, channels, codec;
    boolean hasVideo;
    byte[] wave;           // 波形数据
    byte[] albumArt;       // 专辑封面
    ParcelFileDescriptor albumArtFD;
    // 扫描结果标志
    static final int NOT_SCANNED = 0;
    static final int HAS_TAG = 1;
    static final int HAS_PROPERTIES = 2;
    static final int HAS_AA = 4;      // 有封面
    static final int HAS_LYRICS = 8;  // 有歌词
}
```

---

## 7. 数据库写入层

### 7.1 CategoryBatchCache（分类批量缓存）
**类**: `com.maxmpz.audioplayer.scanner.library.B`

- **功能**: 批量插入/查找 artists, album_artists, composers, genres, albums
- **SQL**: 预编译语句 + `COLLATE NOCASE` 大小写不敏感
- **排序字段**: 去掉前缀 "the/an/a" 后生成 sort 字段
- **默认条目**: 每个分类初始化 ID=1000 的 "unknown" 条目

### 7.2 PlaylistProcessor
**类**: `com.maxmpz.audioplayer.scanner.X`

- **操作表**: `folder_files`, `folders`, `playlists`, `playlist_entries`
- **播放列表格式**: m3u8, m3u, pls, wpl
- **14+ 预编译 SQL 语句**

### 7.3 数据库 Schema

#### folders 表
| 字段 | 类型 | 说明 |
|------|------|------|
| `_id` | INTEGER | 主键 |
| `path` | TEXT | 文件夹路径 |
| `short_name` | TEXT | 短名称 |
| `parent_name` | TEXT | 父目录名 |
| `is_cue` | INTEGER | 是否为 CUE 目录 |

#### folder_files 表
| 字段 | 类型 | 说明 |
|------|------|------|
| `_id` | INTEGER | 主键 |
| `name` | TEXT | 文件名 |
| `title_tag` | TEXT | 标签标题 |
| `artist_tag` | TEXT | 标签艺术家 |
| `album_tag` | TEXT | 标签专辑 |
| `album_artist_tag` | TEXT | 标签专辑艺术家 |
| `genre_tag` | TEXT | 标签流派 |
| `composer_tag` | TEXT | 标签作曲家 |
| `duration` | INTEGER | 时长(ms) |
| `file_type` | INTEGER | 文件类型 |
| `file_created_at` | INTEGER | 文件创建时间 |
| `tag_status` | INTEGER | 标签状态 |
| `track_number` | INTEGER | 曲目号 |
| `year` | INTEGER | 年份 |
| `rating` | INTEGER | 评分 |
| `folder_id` | INTEGER | 关联 folders._id |
| `played_times` | INTEGER | 播放次数 |
| `last_pos` | INTEGER | 上次播放位置 |
| `meta` | TEXT | 元数据(JSON) |

#### playlists 表
| 字段 | 类型 | 说明 |
|------|------|------|
| `_id` | INTEGER | 主键 |
| `playlist` | TEXT | 播放列表名 |
| `mtime` | INTEGER | 修改时间 |
| `playlist_path` | TEXT | 文件路径 |

#### playlist_entries 表
| 字段 | 类型 | 说明 |
|------|------|------|
| `playlist_id` | INTEGER | 关联 playlists._id |
| `folder_file_id` | INTEGER | 关联 folder_files._id |
| `sort` | INTEGER | 排序序号 |
| `cue_offset_ms` | INTEGER | CUE 偏移 |
| `folder_path` | TEXT | 文件夹路径 |
| `file_name` | TEXT | 文件名 |

---

## 8. SAF (Storage Access Framework) 层

### 8.1 SAFUtils（静态工具类）
**类**: `com.maxmpz.audioplayer.data.saf.AbstractC0623`

| 方法 | 功能 |
|------|------|
| `m2009(String)` | 判断路径是否为 SAF 路径 |
| `m2010(Context, ...)` | 枚举所有挂载卷，映射 SAF URI → 文件路径 |
| `m2001(List, HashMap)` | 处理持久化 URI 权限 |
| `K(Service, String)` | 检查卷是否已挂载 |
| `y(Uri)` | 从 document URI 提取 SAF 路径 |

### 8.2 SAFUtilsContext（实例工具类）
**类**: `com.maxmpz.audioplayer.data.saf.X`

| 方法 | 功能 |
|------|------|
| `m1999(Context, String)` | 文件路径 → document URI |
| `P(Context, ...)` | 通过 SAF 打开 ParcelFileDescriptor |
| `m1998(Context, String)` | 打开 InputStream（SAF 或直接文件） |
| `B(Context, String)` | 删除文件（直接或 SAF） |

---

## 9. 状态广播

| 消息 | 含义 |
|------|------|
| `msg_app_dir_scan_started` | 目录扫描开始 |
| `msg_app_tag_scan_started` | 标签扫描开始 |
| `msg_app_tag_scan_finished` | 标签扫描完成 |
| `state_app_scanning` | 扫描状态标志 |
| `cmd_app_rescan` | 触发重扫 |
| `cmd_app_disable_scan` | 禁用扫描 |

---

## 10. 关键设计特点

1. **全 JNI 扫描**: 目录遍历和标签读取全部走 native 代码，性能极高
2. **6 线程并行**: 目录扫描使用固定 6 线程池 + BlockingQueue 分发
3. **多级节流**: 冷却期（3s/11s/5s）+ 去抖 + disable 计数
4. **SAF 兼容**: 自动判断路径类型，传统路径走 native，SAF 路径走 ContentProvider
5. **批量写入**: 预编译 SQL 语句 + 批量 INSERT，减少数据库 I/O
6. **增量扫描**: 区分 DIR_SCAN（发现文件）和 TAG_SCAN（重读标签），支持增量更新
7. **状态总线**: MsgBus 统一管理扫描状态通知，UI 组件订阅响应
