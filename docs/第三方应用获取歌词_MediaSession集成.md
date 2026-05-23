# 酷我车载版：第三方应用通过 MediaSession 获取歌词（集成说明）

本文档基于反编译工程 **包名 `cn.kuwo.kwmusiccar`**（酷我音乐车载 / kwmusiccar）相关 smali 分析整理，供第三方应用与桌面歌词、车机 HUD 等场景对接。  
**说明**：歌词通过 **`MediaSession.setExtras(Bundle)`** 下发，非系统标准字段；第三方需自行解析 JSON，并处理未就绪、切歌、失败等情况。

---

## 1. 核心结论（代码路径）

| 项目 | 值 |
|------|-----|
| 应用包名 | `cn.kuwo.kwmusiccar` |
| 媒体会话服务 | `cn.kuwo.mod.mediaSession.KwMediaSessionService` |
| 服务类型 | 继承 `androidx.media.MediaBrowserServiceCompat`，清单中 **`android:exported="true"`** |
| MediaBrowser Action | `android.media.browse.MediaBrowserService` |
| `onGetRoot` 返回的 rootId | **`cn.kuwo.kwmusiccar`**（见 `KwMediaSessionService.smali` `onGetRoot`） |
| `MediaSessionCompat` 构造 tag | **`KwMediaSessionService`**（与类名相同，便于 `MediaSessionManager` 侧过滤） |
| 歌词写入 | `KwMediaSessionService` 方法 `E(Lcn/kuwo/mod/lyric/a;Lcn/kuwo/mod/lyric/LyricsDefine$DownloadStatus;)` → `MediaSessionCompat.j(Bundle)`（即 `setExtras`） |
| 歌词打包 | `cn.kuwo.mod.lyric.d#b(...)` → `Bundle` 仅含一个 **`String` 键 `AUDIO_LYRIC`** |
| 触发时机 | `PlayerStateManager` 内匿名回调 `l4(...)` 在歌词观察者通知时调用 `KwMediaSessionService#E`（见 `PlayerStateManager$c.smali`） |

---

## 2. 推荐接入方式：MediaBrowserCompat（较稳定）

服务已导出且实现 `MediaBrowserService`，第三方 **无需** `MEDIA_CONTENT_CONTROL` 即可通过 **`MediaBrowserCompat`** 绑定并拿到 **`MediaSession.Token`**，再创建 **`MediaControllerCompat`** 读取 `extras`。

### 2.1 ComponentName

```text
包名: cn.kuwo.kwmusiccar
类名: cn.kuwo.mod.mediaSession.KwMediaSessionService
```

### 2.2 连接要点

1. 使用 **`MediaBrowserCompat(Context, componentName, connectionCallback, rootHints)`**，其中 `componentName` 指向上述服务；系统会通过 `android.media.browse.MediaBrowserService` 与 `KwMediaSessionService` 的 intent-filter 匹配。
2. 在 **`onConnected`** 中：  
   `MediaControllerCompat(context, MediaBrowserCompat.getSessionToken())`  
   注册 **`MediaControllerCompat.Callback#onExtrasChanged(Bundle)`** —— 歌词在 extras 更新时由此回调，比轮询 `getExtras()` 更可靠。
3. 首次连接成功后，若回调未触发，可 **主动** `controller.getExtras()` 读取当前缓存。
4. 处理 **断开重连**：`onConnectionSuspended` / `onConnectionFailed` 时延迟重试，避免酷我进程未启动或后台被杀。

### 2.3 权限与生命周期

- 一般不需要声明特殊权限即可 **跨应用绑定已导出的 MediaBrowserService**（以目标系统与厂商策略为准）。
- 建议在 **`Application` 或前台 Service** 中维持长连接；若仅 Activity 内连接，页面销毁后应释放 `MediaBrowserCompat.disconnect()`。

---

## 3. `AUDIO_LYRIC` 数据格式（`cn.kuwo.mod.lyric.d`）

### 3.1 Bundle 层

- **`Bundle` 中仅使用字符串键：** `AUDIO_LYRIC`
- **值类型：** `String`，内容为 **一整段 JSON**（由内部 `pd` JSON 类型 `toString()` 序列化，见 `d.smali` 的 `b()` 方法）。

### 3.2 JSON 顶层结构（逻辑）

解析后应包含（与内部 `pd/n` 序列化一致）：

- **`AUDIO_LYRIC`**：`Array`，逐行歌词；仅在下载状态为 **SUCCESS** 时由 `c()` 填充非空列表，否则多为空数组。
- **`resultCode`**：整型，表示歌词下载/处理状态（见下表）。

示例（结构示意，真实字段以解析后为准）：

```json
{
  "AUDIO_LYRIC": [
    {
      "startTime": 0,
      "time": 5230,
      "text": "第一句歌词"
    }
  ],
  "resultCode": 20000
}
```

### 3.3 单行字段含义（`c()` 中写入）

| 字段 | 含义（根据 smali 逻辑） |
|------|-------------------------|
| `startTime` | 该行起始时间（毫秒，与内部时间轴一致） |
| `time` | 与下一行起点的相对间隔或展示用时长（毫秒），末行会与歌曲时长等计算逻辑相关 |
| `text` | 该行歌词文本 |

**注意**：这不是原始 LRC 文本（无 `[mm:ss.xx]` 行）；若业务需要标准 LRC，需在己方根据 `startTime`/`text` 自行拼接。

### 3.4 `resultCode` 与内部 `DownloadStatus` 对应（`d#a` + `d#a()` 映射）

内部枚举顺序为：`BEGIN`、`SUCCESS`、`FAILED`、`NONE`。映射后的 `resultCode` 如下（与 `d.smali` 中十六进制常量一致）：

| 含义 | resultCode |
|------|------------|
| SUCCESS（成功，通常带完整行列表） | `20000`（0x4e20） |
| BEGIN（开始） | `20001`（0x4e21） |
| NONE | `20002`（0x4e22） |
| FAILED | `20003`（0x4e23） |
| 空/异常 | `-1`（内部先置为 -1 时） |

集成方应以 **`resultCode == 20000` 且 `AUDIO_LYRIC` 数组非空** 作为「可展示完整逐行歌词」的可靠条件。

---

## 4. 可选：MediaSessionManager.getActiveSessions

若已通过 **通知监听** 等获得 `ComponentName`，可用 `MediaSessionManager` 枚举活跃会话并匹配：

- 包名：`cn.kuwo.kwmusiccar`
- 会话 tag：`KwMediaSessionService`

该路径依赖系统对 **MediaSession 列表** 的开放策略，不同机型差异较大；**优先仍使用 MediaBrowser 直连**。

---

## 5. 与「投屏广播」文档的关系

本仓库中的 `外部广播启动投屏.md` 描述的是 **MiRoot（`com.wmqc.miroot`）背屏音乐投屏** 的启停与状态广播，**不携带歌词正文**。  
酷我侧通过 **`com.wmqc.miroot.lyrics.ACTION_MUSIC_PROJECTION_STATE_CHANGED`** 等仅同步投屏状态；**完整歌词内容**仍应以 **MediaSession extras 中的 `AUDIO_LYRIC`** 为准。

---

## 6. 稳定性建议（第三方实践）

1. **订阅 `onExtrasChanged`**，避免仅依赖单次 `getExtras()`。  
2. **切歌后** extras 可能短暂为空或仍为旧歌，需结合 `MediaMetadata` 的 `MEDIA_ID` / 标题等比对，防止旧歌词错位。  
3. **JSON 解析**放在子线程，避免阻塞主线程。  
4. **超长歌曲** 注意 JSON 体积与 `Bundle` 传递上限，异常时做降级（只显示当前行等）。  
5. 若需「绝对稳定」的跨应用通道，可考虑在改包层增加 **专用广播** 或 **ContentProvider** 再发一份相同数据（本官方包未提供）。

---

## 7. 源码/反编译对照索引

| 文件 | 说明 |
|------|------|
| `smali/cn/kuwo/mod/mediaSession/KwMediaSessionService.smali` | `E()`、`q()`、`onGetRoot`、`setSessionToken`、`MediaSessionCompat` tag |
| `smali/cn/kuwo/mod/lyric/d.smali` | `b()` 打包 `AUDIO_LYRIC` JSON；`c()` 逐行 |
| `smali/cn/kuwo/mod/playcontrol/PlayerStateManager$c.smali` | `l4` → `KwMediaSessionService#E` |
| `AndroidManifest.xml` | `KwMediaSessionService` exported、intent-filter |

---

*文档版本：与当前反编译树一致；若升级官方 APK，请以新版本反编译结果为准。*
