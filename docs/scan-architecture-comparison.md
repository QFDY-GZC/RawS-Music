# 音频扫描架构对比：Poweramp vs RawSMusic

---

## 1. 架构总览对比

| 维度 | Poweramp | RawSMusic | 差距 |
|------|----------|-----------|------|
| **文件发现** | JNI native 遍历 + SAF | MediaStore 查询 + File 遍历 | Poweramp 更底层，性能更高 |
| **标签读取** | 全 JNI (TagReader + FFmpeg + Mod) | FFmpeg JNI + TagLib JNI | 基本持平 |
| **数据库** | SQLite (预编译语句 + 批量INSERT) | MMKV + Gson JSON 序列化 | **差距大**：MMKV 不适合复杂查询 |
| **并发模型** | 6 线程并行扫描 | 单线程顺序扫描 (Flow + Dispatchers.IO) | **差距大** |
| **SAF 支持** | 完整 SAF 适配 | 无 SAF 支持 | **缺失** |
| **增量扫描** | DIR_SCAN / TAG_SCAN 分离 | incrementalScan 增量插入 | Poweramp 更精细 |
| **节流/去抖** | 多级冷却期 (3s/11s/5s) + 去抖 | 无节流机制 | **缺失** |
| **状态广播** | MsgBus 统一状态通知 | StateFlow 局部状态 | 基本持平 |
| **CUE 支持** | 数据库级 CUE 条目 | 内存展开 CUE 曲目 | Poweramp 更完善 |

---

## 2. 逐层对比

### 2.1 触发层

| 触发方式 | Poweramp | RawSMusic |
|----------|----------|-----------|
| App 启动 | `scanner.B.A()` — 检查版本 + 自动扫描开关 | `StartupScanHelper.start()` — 权限通过后直接扫描 |
| 存储挂载 | `StorageBroadcastReceiver` → `m2451()` | **无** |
| 内容变化 | `ContentObserver` → `m2453()` 去抖 | **无** |
| 用户手动 | `cmd_app_rescan` 消息 | `ScannerViewModel.startScan()` |
| 版本更新 | `MilkScanService` 迁移 | `MusicRepository.clearAll()` 清空重扫 |

**RawSMusic 缺失**：
- ❌ 存储挂载/卸载监听（用户插入 SD 卡或 USB 不会自动扫描）
- ❌ ContentObserver 内容变化监听（其他 App 添加音乐不会触发扫描）
- ❌ 自动扫描开关

---

### 2.2 调度/节流层

**Poweramp**：
```
m2452("scan request") 门控:
  ├── disable 计数 > 0 → 跳过
  ├── 扫描已在运行 → 跳过
  ├── 距上次扫描 < 3秒 → 跳过
  ├── 距上次 SAF 操作 < 11秒 → 跳过
  └── 距上次调度 < 5秒 → 跳过
```
- Handler 延迟消息调度
- 内容变化去抖（递增间隔）

**RawSMusic**：
- ❌ 无节流机制
- ❌ 无去抖机制
- ❌ 无扫描状态互斥（可能重复触发扫描）

---

### 2.3 文件发现层

| 方面 | Poweramp | RawSMusic |
|------|----------|-----------|
| **发现方式** | JNI `native_scan()` + SAF URI | `ContentResolver.query(MediaStore)` + `File.walk()` |
| **并行度** | 6 个 ScanWorker 线程 + BlockingQueue | 单线程顺序遍历 |
| **隐藏文件** | `m2476()` 跳过 `.` / `_` 开头 | 无过滤 |
| **SAF 适配** | `AbstractC0623.m2009()` 自动判断 | 无 SAF |
| **性能** | 极高（native + 多线程） | 中等（Java + 单线程） |

**RawSMusic 问题**：
- 单线程扫描大库时耗时长
- 依赖 MediaStore 的 `DATA` 列（Android 11+ 已废弃，可能返回空）
- 无 SAF 支持，无法扫描 SD 卡/USB 等非标准路径

---

### 2.4 标签读取层

| 方面 | Poweramp | RawSMusic |
|------|----------|-----------|
| **主引擎** | `TagReader` (JNI native_scan_file) | `FfmpegMetadataReader` (FFmpegBridge JNI) |
| **FFmpeg** | `FFMpegTagReader` (JNI) | 同上 |
| **Mod 格式** | `ModTagReader` (JNI) | ❌ 无 |
| **WAV** | TagReader 统一处理 | TagLib JNI 专用处理 |
| **DSD** | TagReader 统一处理 | `parseDsdHeader()` 手动解析 |
| **QuickScan** | 无（全量读取） | ✅ 有（跳过 FFmpeg，仅用 MediaStore 字段） |
| **按需补全** | 无（扫描时全量） | ✅ `enrichSong()` 按需读取完整标签 |
| **ReplayGain** | ✅ 支持 | ✅ 支持 |
| **歌词** | ✅ 同步/非同步歌词 | ✅ 同步/非同步歌词 |
| **CUE** | 数据库级展开 | 内存展开 |

**RawSMusic 优势**：
- ✅ QuickScan 模式启动快
- ✅ 按需补全避免启动时全量 FFmpeg 扫描

**RawSMusic 缺失**：
- ❌ 无 MOD/S3M/XM/IT 支持
- DSD 解析是手动的，不如 native 健壮

---

### 2.5 数据库/持久化层

| 方面 | Poweramp | RawSMusic |
|------|----------|-----------|
| **存储引擎** | SQLite | MMKV + Gson JSON |
| **Schema** | 4 张表：folders, folder_files, playlists, playlist_entries | 2 个 JSON blob：songs_data, music_songs |
| **查询能力** | SQL JOIN / WHERE / ORDER BY | 全量反序列化 + 内存过滤 |
| **批量写入** | 预编译语句 + 批量 INSERT | 整体 JSON 序列化覆盖写入 |
| **索引** | ✅ 数据库索引 | ❌ 无索引 |
| **分类聚合** | 数据库级 GROUP BY | 内存聚合 (artists/albums/genres/folders) |
| **并发安全** | SQLite 事务锁 | `@Volatile` + 单写者模式 |

**RawSMusic 问题**：
- ❌ **MMKV 不适合结构化数据查询**：每次查询都需反序列化整个 JSON
- ❌ 无索引，大数据量时搜索/过滤性能差
- ❌ 无事务保护，并发写入可能丢数据
- ❌ 分类聚合在内存中完成，占用内存
- ❌ SongDao 和 MusicRepository 两套存储共存（可能数据不一致）

---

### 2.6 SAF (Storage Access Framework)

| 方面 | Poweramp | RawSMusic |
|------|----------|-----------|
| **SAF 路径判断** | `m2009()` 自动判断 | ❌ 无 |
| **URI 权限管理** | 持久化 URI + 卷映射 | ❌ 无 |
| **文件操作** | SAF PFD / InputStream | 直接 File 路径 |
| **SD 卡/USB** | 通过 SAF 扫描 | ❌ 无法扫描 |

**RawSMusic 问题**：
- Android 11+ 的 scoped storage 限制下，直接文件路径访问受限
- 无法扫描用户通过 SAF 授权的外部存储

---

### 2.7 状态管理

| 方面 | Poweramp | RawSMusic |
|------|----------|-----------|
| **状态机制** | MsgBus 消息总线 | StateFlow |
| **扫描状态** | `state_app_scanning` (0/1) | `ScanState` (Idle/Scanning/Completed/Error) |
| **进度** | 无详细进度 | `ScanProgress(current, total)` |
| **UI 响应** | ScanProgress 进度条 | ScannerViewModel StateFlow |

**RawSMusic 优势**：
- ✅ 有详细进度信息
- ✅ StateFlow 类型安全

---

## 3. 关键差距总结

### 🔴 严重缺失（影响核心功能）

1. **无 SAF 支持** — Android 11+ 无法扫描 SD 卡/USB
2. **MMKV 替代 SQLite** — 大库性能差，无法做复杂查询
3. **单线程扫描** — 大库（万首+）扫描耗时长
4. **无存储事件监听** — 外部存储变化不会自动触发扫描

### 🟡 中等缺失（影响体验）

5. **无节流/去抖** — 可能重复触发扫描
6. **SongDao / MusicRepository 双存储** — 数据一致性风险
7. **依赖 MediaStore DATA 列** — Android 11+ 已废弃

### 🟢 RawSMusic 优势

1. **QuickScan 模式** — 启动速度快
2. **按需标签补全** — 避免启动时全量 FFmpeg 扫描
3. **StateFlow 进度** — 类型安全的 UI 状态管理
4. **ReplayGain 支持** — 与 Poweramp 持平

---

## 4. 改进建议（按优先级）

### P0：必须改进

1. **引入 SQLite (Room)** 替代 MMKV 作为歌曲数据库
   - 支持索引、JOIN、GROUP BY 等复杂查询
   - 事务保护，并发安全
   - 为后续功能（智能播放列表、去重等）打基础

2. **添加 SAF 支持**
   - `ACTION_OPEN_DOCUMENT_TREE` 让用户授权外部存储
   - SAF URI → 文件路径映射
   - 通过 SAF PFD 读取元数据

3. **多线程扫描**
   - 使用 `Dispatchers.IO` + `Channel` 或 `Flow` 实现并行
   - 标签读取是 CPU 密集型，适合多线程

### P1：建议改进

4. **添加存储事件监听**
   - `BroadcastReceiver` 监听 `MEDIA_MOUNTED` / `MEDIA_REMOVED`
   - `ContentObserver` 监听 MediaStore 变化

5. **扫描节流/去抖**
   - 冷却期检查（避免短时间内重复扫描）
   - 去抖（存储事件批量触发时合并）

6. **统一存储层**
   - 移除 SongDao，统一使用 MusicRepository
   - 或将 MusicRepository 迁移到 Room

### P2：可选改进

7. **MOD 格式支持** — 如果有用户需求
8. **数据库级 CUE 展开** — 当前内存展开在大 CUE 时可能卡顿
9. **扫描进度持久化** — 中断后可恢复扫描
