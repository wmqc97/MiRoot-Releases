# 歌词第三方 API 备忘（可用性 / 类型 / 调用）

本文汇总 MiRoot 中实际接入的第三方歌词来源、接口细节与集成注意事项。

> **合规提醒**：除明确商业授权接口外，第三方聚合接口可能存在版权与条款风险；产品化使用前请自行评估授权与隐私政策。

---

## 1. 总览表（优先级与调用链路）

```
主源（串行尝试，按播放 App 包名）
  ├─ 汽水 com.luna.music        →  qsgc → 酷狗
  ├─ 网易 com.netease.cloudmusic →  网易 API → 酷狗 → qsgc
  ├─ QQ  com.tencent.qqmusic    →  QQ API → 酷狗 → qsgc
  ├─ 酷我 cn.kuwo.*              →  酷我 API → 酷狗 → qsgc（车机版见下）
  └─ 其他 / 酷狗                 →  酷狗 → qsgc

↓ 全部失败 ↓

兜底源（并行拉取）
  ├─ LRCLIB            →  带时间轴歌词（syncedLyrics）
  └─ lyrics.ovh        →  纯文本，最后手段

实时推送源（不参与开放兜底链，优先于网络 API）
  ├─ 酷我车载 cn.kuwo.kwmusiccar  →  LRCX 广播（优先）→ AUDIO_LYRIC → 网络 API 兜底
  └─ SuperLyric API               →  Binder IPC 实时逐字推送
```

| 优先级 | 服务 | 可用性 | 鉴权 | 类型 | 典型用途 |
|--------|------|--------|------|------|----------|
| 主源 | [汽水歌词 qsgc](#2-汽水歌词聚合-qsgc) | ✅ 可用（偶慢） | 无 Key | LRC 文本 | 汽水音乐 |
| 主源 | [酷狗音乐 Kugou](#3-酷狗音乐-kugou) | ✅ 可用 | 无 Key | krc/LRC | 通用兜底、酷狗 App |
| 主源 | [网易云 Netease](#3a-网易云音乐-netease) | ✅ 可用 | Referer | LRC | 网易云音乐 App |
| 主源 | [QQ 音乐](#3b-qq-音乐) | ✅ 可用 | Referer | LRC | QQ 音乐 App |
| 主源 | [酷我 Kuwo](#3c-酷我音乐-kuwo) | ✅ 可用 | 无 Key | LRC | 酷我手机版；车机网络兜底 |
| 兜底 | [LRCLIB](#4-lrclib) | ✅ 可用 | 无 Key | JSON（syncedLyrics） | 社区库 |
| 兜底 | [lyrics.ovh](#5-lyricsovh) | ✅ 可用 | 无 Key | JSON 纯文本 | 最后手段 |
| 实时 | [酷我车载 Kuwo Car](#6-酷我车载-kuwo-car) | ✅ 可用 | 安装酷我 | 广播 + AUDIO_LYRIC | **车机版优先广播** |
| 实时 | [SuperLyric API](#7-superlyric-api) | ✅ 可用 | Xposed 模块 | Binder IPC | 逐字歌词实时推送 |

代码常量见 `LyricsApiEndpoints.java`。

---

## 2. 汽水歌词聚合（qsgc）

- **端点**：`https://apiv1.yrain.top/qsgc.php?msg={歌名-歌手}`
- **方法**：GET
- **鉴权**：不需要 API Key
- **超时**：连接 6s / 读取 14s（冷启动常 3~8s，移动网络偶发 >10s）

### 调用方式

```
GET https://apiv1.yrain.top/qsgc.php?msg=晴天-周杰伦
```

`msg` 参数格式为 `歌名-歌手`（歌手可选，无歌手时只传歌名）。

### 返回处理

返回内容可能是：
1. **直出 LRC 文本** — 直接可用
2. **JSON 包裹** — 通过 `QishuiLyricsJsonParser` 自动递归提取 `content`/`lyric`/`lyrics`/`lrc` 等字段

返回的内容会经过繁转简（`Traditional-Simplified`）。

### 代码参考

```java
// LyricsAPIClient.java
private static final String QSGC_LYRICS_API = "https://apiv1.yrain.top/qsgc.php?msg=%s";

String msg = title + "-" + artist;
String encodedMsg = URLEncoder.encode(msg, "UTF-8");
String url = String.format(QSGC_LYRICS_API, encodedMsg);
String response = httpGet(url, 6000, 14000);

// 解析可能存在的 JSON 包裹
String lyrics = QishuiLyricsJsonParser.INSTANCE.extractLyricContent(response);
```

---

## 3. 酷狗音乐（Kugou）

使用 HTTPS `mobilecdn` 搜歌、`m.kugou.com` krc 取词。候选 id 为音频 **hash**。

### 3.1 搜歌

```
GET https://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword={关键词}&page=1&pagesize=30
```

响应 JSON 结构：
```json
{
  "data": {
    "info": [
      {
        "songname": "晴天",
        "singername": "周杰伦",
        "hash": "A1B2C3D4E5F6..."
      }
    ]
  }
}
```

### 3.2 取词

```
GET https://m.kugou.com/app/i/krc.php?cmd=100&hash={hash}&timelength=999999
```

返回可能是 LRC 格式（以 `[` 开头）或 JSON 格式（含 `content` 字段）。

### 3.3 关键词策略

```java
// 生成多组关键词尝试
List<String> keywords = LyricsMatcher.generateSearchKeywords(title, artist);
// 最多尝试前 3 组
int maxKeywords = Math.min(3, keywords.size());
```

### 3.4 候选评分

使用 `LyricsMatcher.scoreInt()` 对搜索结果评分，第一关键词阈值 10 分，后续 20 分，取前 3 候选。

### 3.5 代码常量

```java
LyricsApiEndpoints.KUGOU_SEARCH_URL  // https://mobilecdn.kugou.com/api/v3/search/song
LyricsApiEndpoints.KUGOU_LYRIC_URL   // https://m.kugou.com/app/i/krc.php
```

---

## 3a. 网易云音乐（Netease）

- **搜歌**：`GET https://music.163.com/api/search/get/web?s={关键词}&type=1&limit=30`
- **取词**：`GET https://music.163.com/api/song/lyric?id={songId}&lv=1&kv=1&tv=-1&os=pc`
- **请求头**：`Referer: https://music.163.com/`（必须）
- **候选 id**：`result.songs[].id`
- **歌词字段**：`lrc.lyric`（优先），其次 `klyric.lyric`

```java
LyricsApiEndpoints.NETEASE_SEARCH_URL
LyricsApiEndpoints.NETEASE_LYRIC_URL
```

播放包名 `com.netease.cloudmusic` 时，`NetworkLyricsOrchestrator` 主源链：**网易 → 酷狗 → qsgc**。

---

## 3b. QQ 音乐

- **搜歌**：`GET https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w={关键词}&format=json&p=1&n=20&cr=1&new_json=1`
- **取词**：`GET https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&songmid={mid}&g_tk=5381`
- **请求头**：`Referer: https://y.qq.com/`
- **候选 id**：`data.song.list[].songmid`
- **歌词字段**：`lyric` 或 `lrc`（响应可能带 JSONP 外壳，客户端会剥离）

```java
LyricsApiEndpoints.QQ_SEARCH_URL
LyricsApiEndpoints.QQ_LYRIC_URL
```

播放包名 `com.tencent.qqmusic` 时主源链：**QQ → 酷狗 → qsgc**。

---

## 3c. 酷我音乐（Kuwo）

- **搜歌**：`GET http://search.kuwo.cn/r.s?all={关键词}&ft=music&rformat=json&encoding=utf8&rn=30`
- **取词**：`GET http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId={id}`
- **候选 id**：`abslist[].MUSICRID`（去掉 `SONG_` 前缀）或 `DC_TARGETID`
- **歌词**：`data.lrclist` 数组或 `data.lrc` 文本

```java
LyricsApiEndpoints.KUWO_SEARCH_URL
LyricsApiEndpoints.KUWO_LYRIC_URL
```

**车机版 `cn.kuwo.kwmusiccar`**：背屏仍 **优先** `LYRIC_FULL` / `LYRIC_PROGRESS` 广播与 `AUDIO_LYRIC`（见 [§6](#6-酷我车载-kuwo-car)），仅在本机通道超时或失败后才以包名走酷我网络 API 兜底。

---

## 4. LRCLIB

- **官方文档**：https://lrclib.net/docs
- **Base URL**：`https://lrclib.net`
- **鉴权**：不需要 API Key（建议携带 `User-Agent`）

### 4.1 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/search` | GET | `?q=关键词` 搜索，返回 JSON 数组 |
| `/api/get` | GET | 精确匹配（`track_name`+`artist_name`+`album_name`+`duration`），较慢 |
| `/api/get-cached` | GET | 仅查库内缓存 |
| `/api/get/{id}` | GET | 用搜索返回的 `id` 取词 |

### 4.2 调用方式

```java
private static final String LRCLIB_SEARCH_API = "https://lrclib.net/api/search?q=%s";
// 超时：连接 5s / 读取 8s
```

搜索策略：
1. 先以 `"歌名 歌手"` 搜索
2. 无结果时仅用 `"歌名"` 搜索

### 4.3 返回字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | number | 记录 ID |
| `trackName` / `artistName` / `albumName` | string | 元数据 |
| `duration` | number | 歌曲时长（秒） |
| `instrumental` | boolean | 是否纯音乐 |
| `plainLyrics` | string | 纯文本歌词 |
| `syncedLyrics` | string | **带时间轴 LRC**，优先使用 |

### 4.4 实测示例

```http
GET https://lrclib.net/api/search?q=Anti-Hero%20Taylor%20Swift
GET https://lrclib.net/api/search?q=%E6%99%B4%E5%A4%A9
```

---

## 5. lyrics.ovh

- **端点**：`https://api.lyrics.ovh/v1/{artist}/{title}`
- **方法**：GET
- **鉴权**：无 Key
- **返回**：JSON `{ "lyrics": "..." }`，纯文本无时间轴

### 调用方式

```java
private static final String LYRICS_OVH_API = "https://api.lyrics.ovh/v1/%s/%s";

String url = String.format(LYRICS_OVH_API, encodedArtist, encodedTitle);
// 超时：连接 5s / 读取 10s（使用默认 CONNECT_TIMEOUT / READ_TIMEOUT）
```

> 注意：`lyrics.ovh` 仅返回纯文本歌词，不包含时间轴，只能作为最后兜底。

---

## 6. 酷我车载（Kuwo Car）

> **优先级**：`KuwoBroadcastLyricBridge`（LRCX 广播）→ `MediaBrowser` / `AUDIO_LYRIC` → 酷我网络 API（§3c）→ 酷狗 / qsgc / 开放兜底。网络 API **不会**抢在广播之前执行。

通过系统 `MediaBrowser` 连接酷我车载的 `KwMediaSessionService`，获取 `MediaSession.Token` 后创建 `MediaController`，从 `extras` 中读取 `AUDIO_LYRIC` 字段。若酷我插件已开启「歌词对外广播」，则 `cn.kuwo.kwmusiccar.action.LYRIC_FULL` / `LYRIC_PROGRESS` 优先于上述通道（见 `docs/酷我歌词广播参考文档.md`）。

### 连接信息

- **包名**：`cn.kuwo.kwmusiccar`
- **Service 类**：`cn.kuwo.mod.mediaSession.KwMediaSessionService`
- **依赖**：需已安装酷我车载并保持播放状态

### 调用链路

```java
// 1. 创建 MediaBrowser 连接
ComponentName cn = new ComponentName(
    "cn.kuwo.kwmusiccar",
    "cn.kuwo.mod.mediaSession.KwMediaSessionService"
);
MediaBrowser browser = new MediaBrowser(context, cn, connectionCallback, null);
browser.connect();

// 2. onConnected 中获取 Token → 创建 MediaController
MediaSession.Token token = browser.getSessionToken();
MediaController controller = new MediaController(context, token);

// 3. 注册 extras 变化监听
controller.registerCallback(new MediaController.Callback() {
    @Override
    public void onExtrasChanged(Bundle extras) {
        String audioLyricJson = extras.getString("AUDIO_LYRIC");
        // 解析...
    }
});
```

### AUDIO_LYRIC JSON 格式

```json
{
  "resultCode": 20000,
  "AUDIO_LYRIC": [
    { "startTime": 0, "text": "第一句歌词" },
    { "startTime": 5000, "text": "第二句歌词" }
  ]
}
```

由 `KuwoAudioLyricParser` 解析为 `EnhancedLRCParser.ParseResult`，包含 `startTime`（毫秒）和 `text`。

### 策略

```kotlin
// KuwoCarLyricsPolicy：判断当前活跃播放会话是否为酷我
fun shouldUseKuwoCarLyrics(controller: MediaController?): Boolean {
    return controller?.packageName == "cn.kuwo.kwmusiccar"
        && controller?.playbackState?.state == PlaybackState.STATE_PLAYING
}
```

---

## 7. SuperLyric API

基于 HChenX/SuperLyricApi 的 Xposed 模块，通过 Binder IPC 接收系统级的实时逐字歌词推送。

### 依赖

- **AIDL 接口**：`com.hchen.superlyricapi.ISuperLyricReceiver`
- **核心类**：`SuperLyricHelper`、`SuperLyricData`、`SuperLyricLine`、`SuperLyricWord`

### 调用方式

```java
// 注册接收器
SuperLyricHelper.registerReceiver(new ISuperLyricReceiver.Stub() {
    @Override
    public void onLyricChanged(SuperLyricData data) {
        // 实时收到逐字歌词数据
        List<SuperLyricLine> lines = data.getLyricLine();
        for (SuperLyricLine line : lines) {
            List<SuperLyricWord> words = line.getWord();
            // words 含每个字的 startTime / endTime / text
        }
    }
});
```

### 歌词字段读取优先级

SuperLyricApi 会从多个来源尝试读取歌词，按以下 Key 顺序扫描 `MediaSession.extras` / `MediaMetadata`：

```
super_lyric → superLyric → superlyric → lyric → lyrics → lrc → AUDIO_LYRIC
```

### 实时回调监听

```java
SuperLyricApi.addRealtimeListener(new SuperLyricApi.RealtimeListener() {
    void onRealtimeLyric(String publisher, String title, String artist,
                         String text, SuperLyricFallbackPayload payload, long atMs) {
        // 实时更新 UI
    }
});
```

### 缓存机制

- 缓存 TTL：12 秒（`SUPER_LYRIC_CACHE_TTL_MS`）
- 新鲜窗口：4 秒（`SUPER_LYRIC_FRESH_ACCEPT_MS`）
- 首包等待：模块来源 3.2s，普通来源 900ms
- Binder 高频推送时使用 `AtomicReference` 只保留最新一包，防止队列堆积

---

## 8. 编排逻辑（NetworkLyricsOrchestrator）

```java
// 非汽水：按包名 resolvePlatformProvider → tryPlatform → tryKugou → tryQsgc
// 汽水：tryQsgc → tryKugou
// 全部失败后：parallelFetch(lrclib, lyrics.ovh)  // 超时 12s
```

### 关键配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `SECONDARY_PARALLEL_TIMEOUT_SEC` | 12s | 兜底并行拉取超时 |
| `qishuiQsgcOnlyPrimary` | false | 是否仅 qsgc 作为主源（跳过酷狗） |
| `enableOpenFallback` | true | 是否启用开放兜底（lrclib + lyrics.ovh） |

---

## 9. 已弃用 / 不可用接口

以下接口在历史版本中曾使用，现已不再依赖：

| 服务 / URL | 原因 | 替代 |
|------------|------|------|
| `https://jx.iqfk.top/api/lyric` | PHP Parse Error | qsgc / 酷狗 |
| `https://api.mtbbs.top/` | TLS 握手不稳定 | LRCLIB |
| `http://api.aa1.cn/Music/lrc` | 响应体为空 | LRCLIB |
| `https://api.textyl.co/` | SSL 连接失败 | lyrics.ovh |
| `https://api.lrc.cx/lyrics` | 准确度一般，速度慢 | LRCLIB |
| `https://api.lrc.cx/jsonapi` | 体积过大，解析复杂 | SuperLyric |

---

## 10. 与 MiRoot 代码对接的类型建议

| 来源 | 封装类型 | 说明 |
|------|----------|------|
| 平台搜歌 | `List<LyricsMatcher.Candidate>` | `LyricsAPIClient.searchLyrics(..., provider)` |
| 平台取词 | `LyricsResult` | `LyricsAPIClient.getLyricsById(..., provider)` |
| 酷狗 hash | 同上 | provider=`kugou` |
| 网易 songId | 同上 | provider=`netease` |
| QQ songmid | 同上 | provider=`qq` |
| 酷我 musicId | 同上 | provider=`kuwo` |
| qsgc | `LyricsResult(lyrics, source, success)` | 经 `QishuiLyricsJsonParser` 提取 |
| LRCLIB | `LyricsResult(lyrics, source, success)` | 优先 `syncedLyrics` |
| lyrics.ovh | `LyricsResult(lyrics, source, success)` | 纯文本，无时间轴 |
| 酷我 AUDIO_LYRIC | `EnhancedLRCParser.ParseResult` | `List<EnhancedLyricLine>(time, text)` |
| SuperLyric | `SuperLyricFallbackPayload` | 逐字时间戳 `SuperLyricWord` |

---

## 11. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-06-03 | 新增网易 / QQ / 酷我网络 API；酷狗改 HTTPS；`LyricsApiEndpoints` 统一常量；按包名平台优先链；明确车机版酷我广播优先 |
| 2026-05-23 | 全面更新：新增汽水 qsgc、酷狗 Kugou、lyrics.ovh 接口文档；新增酷我车载 MediaBrowser 接入说明；新增 SuperLyric API Binder 实时推送文档；补充编排逻辑 `NetworkLyricsOrchestrator` 说明；移除已弃用接口 |
| 2026-04-19 | 首版：根据 HTTP 实测整理 LRCLIB、LrcAPI 可用路径及失败列表 |
