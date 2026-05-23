# SuperLyric 接入与排障说明

本文针对 MiRoot 当前实现，整理以下内容：

- SuperLyric 支持软件与适配方式
- `SuperLyricApi` 接收/发布接口说明
- 歌词获取失败的已知原因与本仓库修复点

---

## 1. SuperLyric 支持软件与适配方式

### 1.1 角色模型

SuperLyric 是基于 Binder 的实时歌词总线，分为两类角色：

- 发布者（Publisher）：音乐应用/歌词提供方，向服务发送实时歌词行
- 接收者（Receiver）：Xposed 模块或第三方显示端，订阅并接收歌词回调

MiRoot 在此链路中属于 **接收者**。

### 1.2 SuperLyric 3.1 支持应用（源码快照）

参考 `SuperLyric-3.1` 的 `SupportApps.java`，内置媒体应用包含（节选）：

- `com.netease.cloudmusic`（网易云）
- `com.tencent.qqmusic`（QQ 音乐）
- `com.kugou.android` / `com.kugou.android.lite`（酷狗）
- `cn.kuwo.player`（酷我）
- `com.apple.android.music`（Apple Music）

说明：

- 该清单是 **SuperLyric 3.1 内置 Hook 支持范围**，不是 Android 系统通用媒体白名单。
- 对不在该清单内的应用，通常不会有 SuperLyric 发布链路，接收端应依赖自身 extras/metadata/API 回退逻辑。

### 1.3 MiRoot 当前适配策略

MiRoot 在 `SuperLyricApi` 里采用“三段式兜底”：

1. 先走 SuperLyric Binder 回调缓存（`ISuperLyricReceiver`）
2. 再尝试从 `MediaController` 的 `extras/metadata` 读取通用歌词字段
3. 最后回退到网络歌词 API（由 `RearScreenLyricsActivity` 调度）

对应源码：

- `app/src/main/java/com/wmqc/miroot/lyrics/SuperLyricApi.java`
- `app/src/main/java/com/wmqc/miroot/lyrics/RearScreenLyricsActivity.java`

---

## 2. SuperLyricApi 接收/发布接口说明

以下接口来自 `com.github.HChenX:SuperLyricApi`（当前项目依赖 `3.4`）。

### 2.1 接收端（MiRoot 使用）

- `SuperLyricHelper.isAvailable()`
  - 检查服务是否可用
- `SuperLyricHelper.registerReceiver(ISuperLyricReceiver)`
  - 注册接收器
- `SuperLyricHelper.isReceiverRegistered(ISuperLyricReceiver)`
  - 查询接收器是否仍然有效注册
- `SuperLyricHelper.unregisterReceiver(ISuperLyricReceiver)`
  - 取消注册
- `ISuperLyricReceiver#onLyric(String publisher, SuperLyricData data)`
  - 实时歌词事件
- `ISuperLyricReceiver#onStop(String publisher, SuperLyricData data)`
  - 停止/暂停事件

### 2.2 发布端（第三方音乐应用使用）

- `SuperLyricHelper.registerPublisher()`
  - 注册发布者（发送前必须调用）
- `SuperLyricHelper.sendLyric(SuperLyricData)`
  - 发送歌词数据（主歌词/副歌词/翻译/附加字段）
- `SuperLyricHelper.sendStop(SuperLyricData)`
  - 发送停止事件
- `SuperLyricHelper.unregisterPublisher()`
  - 取消发布者注册

### 2.3 服务端行为（来自 SuperLyric 3.1 服务实现）

`SuperLyricService` 的关键行为：

- 仅当调用方已注册发布者（`mPublishers.contains(publisher)`）时，`sendLyric/sendStop` 才会广播给接收器
- 注册接收器使用 `RemoteCallbackList` 维护，`isReceiverRegistered` 基于 Binder 对象判断
- 发布者进程死亡时会触发 `onStop` 广播并移除发布者注册

这意味着接收端看到“服务可用但始终无回调”时，需要优先排查发布端是否真正执行过 `registerPublisher()`。

### 2.4 3.1 API 测试界面（AboutLayout）要点

参考 `SuperLyric-3.1` 的 `AboutLayout.kt`：

- 基本状态区会展示
  - `SuperLyricHelper.isAvailable()`
  - `SuperLyricHelper.getApiVersion()`
  - `SuperLyricHelper.isPublisherRegistered()`
- 模拟发布区会执行
  - `sendLyric(SuperLyricData().setLyric(...).setTranslation(...))`
  - `sendStop(SuperLyricData())`
- 模拟接收区会执行
  - `registerReceiver` / `unregisterReceiver`
  - `isReceiverRegistered` 校验
  - 实时显示 `onLyric/onStop` 收到的 `publisher` 与 `data`

这套测试逻辑可作为 MiRoot 侧排障对照模板。

---

## 3. 歌词获取失败问题与修复

### 3.1 现象

在部分场景中，虽然已接入 SuperLyric，但 MiRoot 偶发“未命中歌词”，表现为：

- `SuperLyricApi` 未拿到有效内容，随后回退网络 API
- 切歌/服务重启后，持续无法通过 SuperLyric 拿词

### 3.2 根因（当前实现层面）

1. **接收器注册状态可能失效**
   - 旧逻辑只记录“曾经注册过”，未确认“当前仍注册”
   - 当远端服务重启或 Binder 状态变化后，可能出现假注册状态

2. **`SuperLyricData.extra` 中歌词字段未参与解析**
   - 某些发布实现会把歌词放入 `extra`（如 `lyrics/lrc/AUDIO_LYRIC`）
   - 旧逻辑仅解析 `lyric/secondary/translation`，导致漏词

### 3.3 本次修复点

已在 `SuperLyricApi.java` 实施：

- 注册校验增强
  - `ensureSuperLyricReceiverRegistered()` 增加 `isReceiverRegistered(...)` 校验
  - 若查询/注册异常，重置本地注册标记，避免一直处于假成功状态

- **接收端预热（与 [SuperLyricApi](https://github.com/HChenX/SuperLyricApi) ModuleDemo 一致）**
  - `MiRootApplication` / `addRealtimeListener` /「仅 SuperLyric」拉词前均会 `ensureReceiverRegistered()`。
  - 投屏 `onDestroy` **不再** `unregisterReceiver`（只 `removeRealtimeListener`），避免下次仅模块来源整段无 `onLyric`。
  - 「仅 SuperLyric」首包等待延长至约 3.2s，并支持 `peekLatestFallbackPayload()` 宽松命中最新 Binder 缓存。

- 回调内容解析增强
  - 新增从 `SuperLyricData.extra` 提取歌词候选字段：
    - `super_lyric`
    - `superLyric`
    - `superlyric`
    - `lyric`
    - `lyrics`
    - `lrc`
    - `AUDIO_LYRIC`

该改动可显著降低“有数据但未解析命中”的失败率。

---

## 4. ProGuard / R8（Release 必留）

官方 [SuperLyricApi](https://github.com/HChenX/SuperLyricApi) README **强烈建议**保持 API 包不被混淆，MiRoot 已在 `app/proguard-rules.pro` 写入：

```proguard
-keep class com.hchen.superlyricapi.** { *; }
```

Release 构建开启 R8 时请勿删除或收窄该规则；否则可能出现「模块有推送、MiRoot 收不到 / 仅 SuperLyric 无词」等 Binder 异常。

---

## 5. 建议排障顺序

1. 确认 `SuperLyricHelper.isAvailable()` 为 `true`
2. 检查是否进入 `registerReceiver` 且 `isReceiverRegistered` 返回有效
3. 打印 `onLyric` 回调里 `publisher/title/artist/lyrics length`
4. 检查 `data.extra` 是否携带非标准歌词字段
5. 若仍无命中，再看 `MediaController extras/metadata` 与网络 API 回退链路

