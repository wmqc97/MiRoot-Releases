# 歌词第三方 API 备忘（可用性 / 类型 / 调用）

本文汇总 **截至 2026-04-19** 在开发机网络环境下做过 HTTP 实测的第三方歌词来源，便于后续在 MiRoot 或其它模块中做**回退拉词**、联调与排障。

> **合规提醒**：除明确商业授权接口外，第三方聚合接口可能存在版权与条款风险；产品化使用前请自行评估授权与隐私政策。

---

## 1. 总览表（推荐优先级）

| 优先级 | 服务 | 可用性（实测） | 鉴权 | 主要返回类型 | 典型用途 |
|--------|------|----------------|------|--------------|----------|
| 1 | [LRCLIB](https://lrclib.net/docs) | 可用 | 无 Key | JSON（含 `plainLyrics`、`syncedLyrics` LRC 文本） | 搜索 → 选曲 → 取词；社区库，覆盖中英文热门较好 |
| 2 | [LrcAPI 公开](https://docs.lrc.cx/docs/QuickStart) | 部分路径可用 | 无 Key（公开实例） | `text/plain` 风格 LRC；`jsonapi` 为大 JSON | 直接按「歌名 + 歌手」拉 LRC |
| 3 | [Musixmatch](https://developer.musixmatch.com/) | 需有效 Key 才可用 | API Key | JSON（官方文档 `track.lyrics.get` 等） | 正版商业场景 |

下列接口在同期探测中表现为 **失效、空响应或 TLS 不稳定**，**不建议**作为默认依赖（见第 4 节）。

---

## 2. LRCLIB

- **官方文档**：https://lrclib.net/docs  
- **Base URL**：`https://lrclib.net`  
- **鉴权**：不需要 API Key（文档鼓励带 `User-Agent`，标明应用名、版本与项目链接）。

### 2.1 端点一览

| 端点 | 方法 | 说明 | 响应 Content-Type（实测） |
|------|------|------|---------------------------|
| `/api/search` | GET | 按关键词或 `track_name` / `artist_name` / `album_name` 搜索，最多约 20 条 | `application/json` 数组 |
| `/api/get` | GET | 精确签名：`track_name`、`artist_name`、`album_name`、`duration`（秒）；可触发外源补全，较慢 | JSON 单条 |
| `/api/get-cached` | GET | 同上，仅查库内缓存，不外拉 | JSON 单条 |
| `/api/get/{id}` | GET | 用搜索返回的 `id` 取词 | JSON 单条 |

### 2.2 单条记录常用字段（调用侧）

| 字段 | 类型（逻辑） | 说明 |
|------|--------------|------|
| `id` | number | LRCLIB 记录 ID，用于 `/api/get/{id}` |
| `trackName` / `artistName` / `albumName` | string | 元数据 |
| `duration` | number | 秒；用于 `/api/get` 精确匹配（±2s 容差，见官方文档） |
| `instrumental` | boolean | 是否纯音乐 |
| `plainLyrics` | string | 纯文本歌词 |
| `syncedLyrics` | string | **LRC 行**（带时间轴），可直接交给现有 LRC 解析逻辑 |

### 2.3 实测示例（可直接替换参数）

```http
GET https://lrclib.net/api/search?track_name=Anti-Hero&artist_name=Taylor%20Swift
GET https://lrclib.net/api/search?q=%E6%99%B4%E5%A4%A9%20%E5%91%A8%E6%9D%B0%E4%BC%A6
GET https://lrclib.net/api/get/{id}
```

### 2.4 集成注意

- **User-Agent**：建议固定为可识别字符串，便于对方统计与限流策略（文档说明当前无强制限流，仍建议礼貌使用）。
- **超时**：`/api/get` 可能较慢，建议单独较长超时（如 20–30s），与 `/api/search`、`/api/get-cached` 区分。
- **匹配**：同名多版本多，宜结合 `duration`、专辑名或二次用户选择，避免错词。

---

## 3. LrcAPI（公开实例）

- **文档首页**：https://docs.lrc.cx/  
- **快速开始（公开 Base）**：https://docs.lrc.cx/docs/QuickStart  
- **公开歌词 Base**：`https://api.lrc.cx`  
- **鉴权**：公开文档中的线上实例无 Key；自建部署时可配置 `--auth`。

### 3.1 路径与可用性

| 路径 | 方法 | 可用性（2026-04-19 实测） | 返回类型 | 说明 |
|------|------|---------------------------|----------|------|
| `/lyrics` | GET | **可用** | 歌词 **LRC 文本**（文档称 `Content-Type` 可能标为 `text/html`，以正文为准） | Query：`title`、`artist`、`album`（均可空，见文档） |
| `/jsonapi` | GET | **可用** | **大体积 JSON**（内含 Apple Music 风格元数据、`lyric_path` 等） | 需自行解析，非「整段 LRC 字符串」直出 |
| `/api/v1/lyrics/single` | GET | **不可用（404）** | — | 文档仍写 v1，但公开 `api.lrc.cx` 上未通 |
| `/api/v1/lyrics/advance` | GET | **不可用（404）** | — | 同上 |

### 3.2 实测示例

```http
GET https://api.lrc.cx/lyrics?title=%E6%99%B4%E5%A4%A9&artist=%E5%91%A8%E6%9D%B0%E4%BC%A6
GET https://api.lrc.cx/jsonapi?title=Shape%20of%20You&artist=Ed%20Sheeran
```

### 3.3 集成注意

- 文档写明公开 API **经酷狗等渠道**，可能较慢、准确度一般；建议 **超时 + 缓存 + 失败回退**（例如回退到 LRCLIB）。
- `jsonapi` 更适合需要**平台侧逐字/逐行 URL** 的场景，与「直接 LRC 文件」管线不同，不要与 `/lyrics` 混为一种解析器。

---

## 4. 同期探测未通过或不可靠（备忘，避免误用）

以下结论基于 **单次环境**（Windows + 指定日期），仅作排障参考；对方服务可能随后修复。

| 服务 / URL | 现象 | 建议 |
|------------|------|------|
| `https://jx.iqfk.top/api/lyric` | HTTP 200 但正文为 **PHP Parse error** | 视为已坏，勿用 |
| `https://api.mtbbs.top/...` | **TLS 握手失败**（PowerShell / curl 均不稳定） | 不在客户端默认依赖；若必须，需单独验证证书链与 Cipher |
| `http://api.aa1.cn/Music/lrc?...` | HTTP 200，**正文长度 0** | 勿用 |
| `https://api.textyl.co/...` | **SSL 连接失败** | 未测通 |
| Musixmatch + 无效 `apikey` | JSON `header.status_code` **401** | 使用注册 Key 后再测 |
| Zyla 市场商品页 | **403**，非可调用歌词端点 | 需在平台订阅后使用其提供的真实 Host |

---

## 5. 与 MiRoot 代码对接时的类型建议（Kotlin / Java）

| 来源 | 建议封装返回类型 | 说明 |
|------|------------------|------|
| LRCLIB `search` | `List<LrcLibTrack>` → 选 `id` | DTO 映射 `id`、`duration`、`syncedLyrics` 是否非空 |
| LRCLIB `get` / `get/{id}` | `LyricsPayload(plain: String?, syncedLrc: String?)` | 优先使用 `syncedLyrics` |
| LrcAPI `/lyrics` | `String`（整段 LRC） | 与文件式 LRC 相同 |
| LrcAPI `/jsonapi` | `JsonElement` 或专用 DTO | 先解析再决定是否请求 `lyric_path` 子资源 |

---

## 6. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-04-19 | 首版：根据本机 HTTP 实测整理 LRCLIB、LrcAPI 可用路径及失败列表 |
