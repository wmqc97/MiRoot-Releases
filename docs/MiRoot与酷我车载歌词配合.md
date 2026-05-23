# MiRoot 与酷我车载版（`cn.kuwo.kwmusiccar`）歌词配合说明

本文说明 **MiRoot 当前如何从系统取歌词**，以及为何与酷我通过 **`MediaSession.setExtras` → `AUDIO_LYRIC`（JSON）** 下发的完整歌词**尚未自动对齐**，并给出在 MiRoot 侧可落地的配合方式。

---

## 1. 酷我侧数据来源（摘要）

酷我把**整首逐行歌词**放在会话 **Extras** 里，而不是标准 `MediaMetadata` 歌词键：

| 键 | 类型 | 含义 |
|----|------|------|
| `AUDIO_LYRIC` | `String` | 一段 JSON，内含 `AUDIO_LYRIC` 数组（`startTime` / `time` / `text`）与 `resultCode` |

详见工程内《第三方应用获取歌词_MediaSession集成.md》。

---

## 2. MiRoot 当前实现（代码事实）

### 2.1 权限与入口

- 使用 **`MiRootNotificationListenerService`**（通知监听）后，方可调用  
  `MediaSessionManager.getActiveSessions(ComponentName(MiRootNotificationListenerService))`。
- 背屏歌词主界面 **`RearScreenLyricsActivity`** 中 **`setupMediaController()`** 取会话列表后 **固定使用 `controllers.get(0)`**（第一个活跃会话），未按包名筛选酷我。

### 2.2 `MusicInfoHelper.getMusicInfoFromMediaController`

- 只从 **`MediaMetadata`** 里尝试若干**常见歌词键**（如 `android.media.metadata.LYRICS`、`lyrics` 等）。
- **未读取** `MediaController.getExtras()`，因此 **拿不到酷我的 `AUDIO_LYRIC` JSON**。

相关代码：`app/src/main/java/com/wmqc/miroot/lyrics/MusicInfoHelper.java`（约 94–114 行）。

### 2.3 `RearScreenLyricsActivity` 的 `MediaController.Callback`

- 仅注册了 **`onMetadataChanged`**、**`onPlaybackStateChanged`**。
- **未注册** **`onExtrasChanged`**，酷我更新 extras 时 **不会** 触发 MiRoot 刷新歌词。

### 2.4 `loadLyrics` 回退策略

- 先从 `MusicInfoHelper` / 通知里取「歌词字符串」；若仍没有，再走 **第三方歌词 API**（`MusicInfoHelper.getLyricsFromAPI`）。
- 对「疑似车载蓝牙仅一行当前歌词」会**故意丢弃**，避免覆盖完整 LRC（见 `loadLyrics` 内注释，约 2320–2324 行）。

**结论**：在**不改代码**的情况下，MiRoot 对酷我更可能走 **API 搜词**，而不是用酷我 App 内已下载的 **Extras 完整歌词**。

---

## 3. 若要「配合」酷我显示完整歌词：推荐改法（MiRoot 侧）

### 3.1 读取 Extras（必做）

在 `MusicInfoHelper.getMusicInfoFromMediaController`（或单独工具方法）中：

1. `Bundle extras = controller.getExtras();`（空则跳过）
2. `String raw = extras.getString("AUDIO_LYRIC");`
3. 解析 JSON：`resultCode == 20000` 且内层 `AUDIO_LYRIC` 数组非空时，视为有效逐行歌词。

### 3.2 转为 MiRoot 现有展示结构

MiRoot 背屏使用 **`EnhancedLRCParser.EnhancedLyricLine`** 列表驱动 **`ModernLyricsView`**。

酷我 JSON 每行含 **`startTime`（ms）**、**`text`**。需要映射为 `EnhancedLyricLine`（至少填**行起始时间**与**文本**；若解析器需要结束时间，可用下一行 `startTime` 或 `startTime + time` 推导）。

### 3.3 会话选择（建议）

当存在多个 `MediaController` 时，不要只用 `get(0)`，应：

- 优先匹配 **`cn.kuwo.kwmusiccar`**，或  
- 用户指定的「音乐包名」设置项。

否则当前台是其他播放器时，会一直拿到错误会话。

### 3.4 监听 Extras 更新（建议）

在 `RearScreenLyricsActivity.setupMediaController` 的 `MediaController.Callback` 中增加：

- **`onExtrasChanged(Bundle extras)`**：解析 `AUDIO_LYRIC`，更新 `enhancedLyricLines` 并 `setLyricsToView`（或与现有 `loadLyrics` 去重，避免 API 覆盖本地完整词）。

### 3.5 与现有 API 回退的优先级

建议顺序：

1. **Extras `AUDIO_LYRIC`（酷我）** → 完整逐行  
2. 通知/METADATA 长文本（若可信）  
3. 最后再用 **API**

这样可与 `loadLyrics` 里「防单行蓝牙歌词」策略一致，并减少网络依赖。

---

## 4. 与「投屏广播」的关系

`docs/外部广播启动投屏.md` 中的 Action（如 `ACTION_OPEN_MUSIC_PROJECTION`）仅用于 **MiRoot 背屏投屏界面启停与状态**（无 `EXTRA_MUSIC_PROJECTION_OP` 时为**一键切换**；显式 `start`/`stop` 仍可固定启停），**不传递歌词正文**。歌词仍应通过 **MediaSession**（或你们另加的广播/Provider）获取。

---

## 5. 文件索引

| 位置 | 作用 |
|------|------|
| `lyrics/MusicInfoHelper.java` | 当前从 MediaMetadata 取词，需扩展 extras |
| `lyrics/RearScreenLyricsActivity.java` | `setupMediaController`、`loadLyrics`、展示逻辑 |
| `lyrics/EnhancedLRCParser.java` | 行结构定义与 LRC 解析 |
| `service/MiRootNotificationListenerService.kt` | 通知监听，用于 `getActiveSessions` |

---

*与酷我反编译文档同源字段名 `AUDIO_LYRIC`；若酷我升级变更 JSON 结构，需同步调整解析。*
