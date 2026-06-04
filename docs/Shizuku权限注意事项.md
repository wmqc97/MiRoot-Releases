# Shizuku 权限注意事项

本文档记录 MiRoot 在 **仅 Shizuku、无 Root** 场景下的实测结论与开发约束，避免重复踩坑。  
（背屏录屏音频相关见 [背屏录屏声音录制踩坑记录.md](./背屏录屏声音录制踩坑记录.md)。）

---

## 1. MiRoot 中的 Shizuku 是什么

- Shizuku 让应用通过 **ADB 级 shell 用户** 执行 `sh -c` 命令，等价于「有 shell 权限、无 root」。
- MiRoot 统一走 [`PrivilegedShell`](../app/src/main/java/com/wmqc/miroot/capability/PrivilegedShell.kt)：**每次命令优先 `su -c`，失败再 Shizuku**。
- 路由判定：[`EnvironmentProbe.privilegedShellRouteSync()`](../app/src/main/java/com/wmqc/miroot/capability/EnvironmentProbe.kt) → `ROOT` / `SHIZUKU` / `NONE`。
- **有 Root 时行为与 Shizuku 不同**：Root 下 shell `cp` 写公共目录通常可用；Shizuku 下必须按本文档约束。

### 授权与状态

| 检查项 | 说明 |
|--------|------|
| Shizuku 服务运行 | `Shizuku.pingBinder()` |
| 已授权 MiRoot | `Shizuku.checkSelfPermission() == GRANTED` |
| 冷启动误判 | 须在 `MainActivity` 注册 `OnBinderReceivedListener` / `OnBinderDeadListener`，通过 `PermissionCache` 刷新，不能只在启动时检一次 |

状态页可运行 **「录制存储方案测试」**（[`SaveStrategyTestActivity`](../app/src/main/java/com/wmqc/miroot/record/SaveStrategyTestActivity.kt)）验证本机 Shizuku 存储能力。

---

## 2. 核心原则（务必记住）

### 2.1 公共目录导出：用 ContentResolver，不要用 shell cp

在 Shizuku 下，**shell `cp` 到 Movies / Pictures 等公共路径** 常见现象：

- 命令 `exit=0`，但应用侧 `File(path).length()` 为 **0** 或 **文件不可见**；
- 相册 / 文件管理器看不到成品。

**正确做法**：应用进程用 **MediaStore + ContentResolver.openOutputStream** 写入（见 [`PublicMediaExport`](../app/src/main/java/com/wmqc/miroot/shell/PublicMediaExport.kt)）。

```kotlin
// Shizuku 且无 Root 时强制走 MediaStore
preferContentResolverForPublicSave() == true  // route == SHIZUKU
```

日志中 `usedCR=true` 表示走了 ContentResolver，Shizuku 下这是**正常且期望**的路径。

### 2.2 中间文件：shell 写公共路径，应用读前需 chown/chmod

`screenrecord` / `screencap` 由 shell 创建的文件，应用默认可能读不到。停止后须：

1. [`PublicMediaExport.ensureAppReadable(path)`](../app/src/main/java/com/wmqc/miroot/shell/PublicMediaExport.kt)（`chown` 当前 UID + `chmod 644`）；
2. 校验 **字节数 > 0**（`shellFileSize` / `File.length()`）。

**不要**假设 shell 写完应用就能直接 `FileInputStream` 打开。

### 2.3 合成输入：优先 app 进程 copyTo，不要 shell cp 进 cache

Shizuku 下 **shell cp → app 私有 cache/files** 同样可能失败（测试策略 6、7 未通过）。

**正确做法**（[`RearScreenRecordService`](../app/src/main/java/com/wmqc/miroot/record/RearScreenRecordService.kt) 停止管线）：

```kotlin
videoForNext.inputStream().use { input ->
    compositeInput.outputStream().use { output -> input.copyTo(output) }
}
```

即：**shell 负责录到公共临时路径 → ensureAppReadable → 应用自己 copy 到 cache → Media3 合成 → ContentResolver 导出**。

### 2.4 PID 文件：放在 `/data/local/tmp`，不要写 Movies

screenrecord 的 PID 写在 [`/data/local/tmp/miroot_record/`](../app/src/main/java/com/wmqc/miroot/record/RecordPaths.kt)，stderr 重定向到 `/dev/null`（不落盘）。**不要**把 PID / 诊断文件写到 Movies 或 app 私有目录给 shell 写。

### 2.5 启动 screenrecord：detach，不要长连接持有进程

**错误**：`PrivilegedShell.startShell("screenrecord ...")` 并在 Java/Kotlin 侧 **长期持有 Process**。

**后果**：Shizuku Binder 不稳定，**点击录制闪退**。

**正确**：一次性 `execCmd` + **nohup 后台** + 写 PID 文件（[`launchScreenrecordDetached`](../app/src/main/java/com/wmqc/miroot/record/RearScreenRecordService.kt)）：

```bash
nohup screenrecord --display-id N ... "路径" >/dev/null 2>&1 \
  </dev/null & echo $! > /data/local/tmp/miroot_record/screenrecord.pid
```

成功判定：**进程存活 + 捕获文件字节增长**，不要只信 pid 文件。

### 2.6 勿在 UI 主线程阻塞探测 Root

录屏按钮等热路径应使用 **`PermissionCache.channel`**（已缓存的 Shizuku/Root 状态），避免每次点击 `privilegedShellRouteSync()` 触发最长约 12s 的 Root 探测导致 ANR/卡顿。

---

## 3. 路径约定（背屏录屏）

统一定义见 [`RecordPaths`](../app/src/main/java/com/wmqc/miroot/record/RecordPaths.kt)：

| 用途 | 路径 | 写入方 |
|------|------|--------|
| 原始 MP4 临时文件 | `/sdcard/MiRoot/record_capture/MiRoot_*.mp4` | shell（screenrecord） |
| PID | `/data/local/tmp/miroot_record/screenrecord.pid` | shell |
| 最终成品 | `Movies/MiRoot_*.mp4`（MediaStore） | app（ContentResolver） |

PCM 内录音频：写在 **app cache**（`AudioCaptureHelper`），不经过 shell。

---

## 4. 存储方案测试结论（HyperOS / API 36 实测）

状态页 **录制存储方案测试** 在 Shizuku 下典型结果：

| 策略 | 结果 |
|------|------|
| Shell cp → Movies | ❌ 失败（0 字节或不可见） |
| Shell cp → Movies + mediaScan | ❌ 失败 |
| ContentResolver → Movies | ✅ 通过 |
| ContentResolver → Movies（IS_PENDING） | ✅ 通过 |
| ContentResolver → Downloads | ✅ 通过 |
| Shell cp → app files/ | ❌ 失败 |
| Shell cp → app cache/ | ❌ 失败 |
| FileOutputStream → app files/ | ✅ 通过 |
| FileOutputStream → app cache/ | ✅ 通过 |

**结论**：Shizuku = **shell 写 sdcard 临时 + app 读；app 写公共目录只能 ContentResolver；app 私有目录只能 app 自己写**。

---

## 5. 背屏录屏完整管线（Shizuku）

```
[开始]
  prepareShellRecordWorkDir + mkdir capture 目录
  launchScreenrecordDetached → /sdcard/MiRoot/record_capture/*.mp4
  （可选）MediaProjection + AudioCaptureHelper → cache/*.pcm

[停止]
  kill -2 screenrecord → 等待落盘
  ensureAppReadable(临时 mp4)
  inputStream.copyTo(cache)  // 合成输入，不用 shell cp
  Media3 合成（带壳 / 合并 PCM）
  PublicMediaExport.saveVideoToMovies → usedCR=true
  清理临时文件（勿误删 MediaStore 同路径 0 字节占位以外的成品）
```

参考成功管线（Shizuku，`usedCR=true`）：临时 mp4 有字节 → 合成 → `PublicMediaExport.saveVideoToMovies` 成功 → 相册可见 `Movies/MiRoot_*.mp4`。

---

## 6. 与 Root 的差异速查

| 场景 | Root | Shizuku |
|------|------|---------|
| shell cp → Movies | 通常可用 | ❌ 不可靠 |
| 导出到相册 | cp + scan 或 MediaStore | **必须 MediaStore** |
| screenrecord 输出路径 | 可多种 | 建议 `/sdcard/MiRoot/record_capture/` |
| 持有 screenrecord 进程 | 相对稳 | ❌ 易 Binder 崩溃 |
| 读 shell 生成文件 | 常可直接读 | 需 `ensureAppReadable` |
| 写 app cache | app 或 root | **仅 app**（FileOutputStream） |

---

## 7. 常见错误与症状

| 错误做法 | 典型症状 |
|----------|----------|
| shell cp 成品到 Movies | 相册无文件；或 0 字节 ghost 文件 |
| screenrecord 输出到 app cache | 「录屏进程未启动」；workSize=0 |
| `startShell` 长连 screenrecord | 点录制闪退 |
| PID 写在 app 私有目录 | 启动判定失败 |
| 导出后 `rm` legacy Movies 路径不校验大小 | 误删 ContentResolver 刚写入的成品 |
| 主线程同步 probe Root | 点击录制卡顿 / ANR |
| 只用 pid 文件判断启动成功 | 误报成功或失败 |

---

## 8. 修改相关代码时的检查清单

- [ ] Shizuku 分支写 **Movies / Pictures / Downloads** 是否走 `PublicMediaExport` / MediaStore？
- [ ] 是否避免 **shell cp 到 app 私有目录** 作为合成/导出前置步骤？
- [ ] screenrecord 是否 **nohup detach**，未使用 `startShell` 长持有？
- [ ] 临时视频是否在 **shell 可写的 sdcard 路径**，而非 `cacheDir`？
- [ ] 停止后是否 **ensureAppReadable + 字节数校验**？
- [ ] `deleteZeroByteLegacyGhost` 是否只在 **size==0** 时删除 legacy 路径？
- [ ] UI 热路径是否使用 **PermissionCache**，避免阻塞 Root 探测？
- [ ] 新功能若需 shell 写文件，是否已在 **SaveStrategyTest** 中加策略或手动测 Shizuku？

---

## 9. 相关代码与文档

| 模块 | 文件 |
|------|------|
| Shell 统一入口 | `app/.../capability/PrivilegedShell.kt` |
| 路由探测 | `app/.../capability/EnvironmentProbe.kt` |
| 公共媒体导出 | `app/.../shell/PublicMediaExport.kt` |
| 背屏录屏服务 | `app/.../record/RearScreenRecordService.kt` |
| 路径常量 | `app/.../record/RecordPaths.kt` |
| 存储批量测试 | `app/.../record/SaveStrategyTestActivity.kt` |
| 背屏截图（同类导出） | `app/.../shell/RearScreenshotCoordinator.kt` |
| 录屏音频 | [背屏录屏声音录制踩坑记录.md](./背屏录屏声音录制踩坑记录.md) |

---

## 10. 排障建议

1. **状态页 → 录制存储方案测试**：确认 ContentResolver 策略通过。
2. **logcat** 过滤 `MiRoot-Record` / `RecordSynth` 查看启动与导出阶段。
3. 改存储或启动逻辑后，在 **无 Root、仅 Shizuku** 真机复测一遍，不要只在 Root 机验证。

---

*最后更新：2026-05-30（基于 MiRoot 2.0 / API 36 Shizuku 背屏录屏排障经验整理）*
