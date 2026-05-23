# 车控广播调用 API 文档（MiRoot）

本文档汇总 **MiRoot**（包名 `com.wmqc.miroot`）中所有车控相关的广播调用方式，适用于后台服务、通知点击、自动化工具（Tasker / MacroDroid）、锁屏主题（MAML）、ADB 调试等。

## 包名与 Action 命名空间

- **强烈建议**发送广播时显式限定包名，避免被其它应用伪造或误匹配：

  ```text
  intent.setPackage("com.wmqc.miroot")
  ```

  ADB 示例中请在末尾带上组件包名：`com.wmqc.miroot`（见下文）。

- **两套 Action（指令与查询任选其一；投屏仅 MiRoot）**

  | 用途 | 历史 Action（`com.tgwgroup…` 前缀，见下文示例） | MiRoot 命名空间 |
  |------|--------------------------------------------------|-----------------|
  | 打开/停止投屏（见 §1） | — | `ACTION_OPEN_CAR_CONTROL_PROJECTION`：无 Extra 时**一键切换**；或带 Extra `start`/`stop`；另有 `ACTION_STOP_CAR_CONTROL_PROJECTION` 专用停止 |
  | **仅停止**投屏（见 §1） | — | `com.wmqc.miroot.car.ACTION_STOP_CAR_CONTROL_PROJECTION` |
  | 车控指令 | `…ACTION_CAR_CONTROL_COMMAND` | `com.wmqc.miroot.car.ACTION_CAR_CONTROL_COMMAND` |
  | 车控回执（默认） | `…ACTION_CAR_CONTROL_COMMAND_RESULT` | `com.wmqc.miroot.car.ACTION_CAR_CONTROL_COMMAND_RESULT`（仅作 `replyAction` 等字符串使用） |
  | 查询车辆状态 | `…ACTION_QUERY_VEHICLE_STATUS` | `com.wmqc.miroot.car.ACTION_QUERY_VEHICLE_STATUS` |
  | 状态查询回执（默认） | `…ACTION_VEHICLE_STATUS_RESULT` | `com.wmqc.miroot.car.ACTION_VEHICLE_STATUS_RESULT`（可作 `replyAction`） |

- 接收入口：`CarControlBroadcastReceiver`（`android:exported="true"`），源码常量见 `com.wmqc.miroot.car.CarControlIntents`。

---

## 1. 车控投屏（打开 / 关闭界面）

用于打开背屏的车控投屏界面（`RearScreenCarControlActivity`），或关闭车控投屏。投屏广播**仅**注册 MiRoot Action（见 `AndroidManifest` 中 `CarControlBroadcastReceiver`）。

对 **`ACTION_OPEN_CAR_CONTROL_PROJECTION`**：**不传** `EXTRA_CAR_PROJECTION_OP`（或传空）时，MiRoot 根据当前背屏车控界面是否已在运行**自动在启停间切换**；传入显式 `start` / `stop` 时行为与旧版一致（固定开始或停止）。

### 方式 A：统一 Action，可选 Extra `com.wmqc.miroot.car.EXTRA_CAR_PROJECTION_OP`

**广播 Action**

```text
com.wmqc.miroot.car.ACTION_OPEN_CAR_CONTROL_PROJECTION
```

### 方式 B：专用「停止」Action（无需 Extra）

等价于方式 A 且 `EXTRA_CAR_PROJECTION_OP=stop`（显式停止；与「无 Extra 一键切换」不同）。

```text
com.wmqc.miroot.car.ACTION_STOP_CAR_CONTROL_PROJECTION
```

### 参数（Extras，仅方式 A）

| 参数 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| `com.wmqc.miroot.car.EXTRA_CAR_PROJECTION_OP` | string | 否 | **省略或空**：一键切换。`start` / `stop`：显式开启或关闭 | `start` |

### 示例

**一键切换（Java，推荐主题单按钮）**

```java
Intent intent = new Intent("com.wmqc.miroot.car.ACTION_OPEN_CAR_CONTROL_PROJECTION");
intent.setPackage("com.wmqc.miroot");
context.sendBroadcast(intent);
```

**显式开启（Java）**

```java
Intent intent = new Intent("com.wmqc.miroot.car.ACTION_OPEN_CAR_CONTROL_PROJECTION");
intent.setPackage("com.wmqc.miroot");
intent.putExtra("com.wmqc.miroot.car.EXTRA_CAR_PROJECTION_OP", "start");
context.sendBroadcast(intent);
```

**关闭（ADB，专用停止 Action）**

```bash
adb shell am broadcast -a com.wmqc.miroot.car.ACTION_STOP_CAR_CONTROL_PROJECTION com.wmqc.miroot
```

**关闭（ADB，同一打开 Action + Extra）**

```bash
adb shell am broadcast -a com.wmqc.miroot.car.ACTION_OPEN_CAR_CONTROL_PROJECTION \
  --es com.wmqc.miroot.car.EXTRA_CAR_PROJECTION_OP stop \
  com.wmqc.miroot
```

更多示例见 `docs/外部广播启动投屏.md` § 四。

---

## 2. 车控功能（广播调用，后台直接执行）

用于 **不打开车控界面** 直接执行车控功能（内部走 `CarControlCommandService` → `VehicleControlService`），并通过**回执广播**返回结果。

### 广播 Action（请求）

```text
com.tgwgroup.MiRearScreenSwitchers.ACTION_CAR_CONTROL_COMMAND
com.wmqc.miroot.car.ACTION_CAR_CONTROL_COMMAND
```

### 回执 Action（默认）

```text
com.tgwgroup.MiRearScreenSwitchers.ACTION_CAR_CONTROL_COMMAND_RESULT
```

若希望回执发到 MiRoot 命名空间，请在请求里设置：

```text
replyAction=com.wmqc.miroot.car.ACTION_CAR_CONTROL_COMMAND_RESULT
```

（主题的 `BroadcastBinder` 的 `action` 必须与之一致。）

### 请求参数（Extras）

| 参数 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| `function` | string | ✅ | 功能名（支持中文按钮名 / 英文别名） | `unlock` / `解锁` |
| `durationMinutes` | int | 否 | 持续时间（分钟），用于点火 / 空调 / 座椅加热等；不传则使用默认 | `10` |
| `temperature` | int | 否 | 空调温度（℃），仅用于打开空调 | `24` |
| `level` | int | 否 | 座椅加热档位（1–3） | `2` |
| `seat` | string | 否 | `all` / `driver` / `passenger`（用于座椅加热） | `driver` |
| `requestId` | string | 否 | 请求 ID（回执原样回传） | `abc123` |
| `replyAction` | string | 否 | 自定义回执 action，不传用默认 | `com.xxx.REPLY` |
| `timeoutMs` | long | 否 | 超时（毫秒）。超时会回执 `callbackPhase=execution`、`success=false`、`message=执行超时` | `15000` |
| `successVibrateMs` | long | 否 | 执行成功后的长振时长（毫秒），默认由实现决定 | `550` |

### 支持的 function 列表

| function（英文） | 中文别名（兼容） | 说明 |
|------------------|------------------|------|
| `unlock` | `解锁` | 解锁 |
| `lock` | `锁车` | 锁车 |
| `findCar` | `寻车` | 鸣笛双闪 |
| `openTrunk` | `尾箱` / `开后备箱` / `打开后备箱` / `打开尾箱` | 打开尾箱 |
| `openWindow` | `开窗` | 开窗 |
| `closeWindow` | `关窗` | 关窗 |
| `ventilate` | `透气` | 透气 |
| `startEngine` | `点火` | 点火（默认 10 分钟，可用 `durationMinutes` 覆盖） |
| `stopEngine` | `熄火` | 熄火 |
| `openAirConditioner` | `打开空调` | 打开空调（默认 10 分钟 / 24℃，可用 `durationMinutes` / `temperature` 覆盖） |
| `closeAirConditioner` | `关闭空调` | 关闭空调 |
| `openSeatHeating` | `打开座椅加热` / `座椅加热` | 打开座椅加热（默认 10 分钟 / 2 档；`seat`=`driver`/`passenger`/`all`） |
| `openSeatHeatingDriver` | `主驾加热` | 仅主驾（等价于 `openSeatHeating` + `seat=driver`） |
| `openSeatHeatingPassenger` | `副驾加热` | 仅副驾（等价于 `openSeatHeating` + `seat=passenger`） |
| `closeSeatHeating` | `关闭座椅加热` | 关闭座椅加热（`seat` 可选） |
| `navigateToCar` | `导航到车` | 导航到车辆位置 |

另支持部分英文短别名（大小写不敏感），例如：`find`、`findcar`、`horn`、`trunk`、`opentrunk`、`openac`、`ac_on`、`driverheat` / `seatheat_driver`、`passengerheat` / `seatheat_passenger` 等，与源码 `CarControlCommandService.normalizeFunction` 一致。

### 回执顺序与内容（Extras）

MiRoot 会发送 **至少两次**回执（同一 `replyAction`）：

1. **`callbackPhase=received`**：已收到请求（`success` 为字符串 `"true"`，`message` 约「已收到车控指令」），**不表示**远程接口已执行完成。
2. **`callbackPhase=execution`**：执行失败、超时或未知功能；或 **`callbackPhase=vehicleStatus`**：执行成功后附带完整车辆状态 JSON。

| 参数 | 类型 | 说明 |
|------|------|------|
| `requestId` | string | 原样回传 |
| `function` | string | 原样回传 |
| `seat` | string | 原样回传 |
| `durationMinutes` | int | 原样回传 |
| `temperature` | int | 原样回传 |
| `level` | int | 原样回传 |
| `callbackPhase` | string | `received` / `execution` / `vehicleStatus` |
| `success` | **string** | **`"true"`** 或 **`"false"`**（非 boolean，便于 MAML `type="string"`） |
| `message` | string | 结果说明 |
| `data` | string | 在 `vehicleStatus` 阶段为车辆 JSON；其它阶段多为空 |
| `postStatus` | string | 车辆状态 JSON（成功路径与 `data` 对齐，主题可绑定此键） |

回执 **不**调用 `setPackage`，以便其它进程中的 `BroadcastBinder` 能收到。

### 示例

**解锁（ADB）**

```bash
adb shell am broadcast -a com.wmqc.miroot.car.ACTION_CAR_CONTROL_COMMAND \
  --es function unlock \
  --es requestId unlock_001 \
  --el timeoutMs 15000 \
  com.wmqc.miroot
```

**打开空调 15 分钟、23℃（Java）**

```java
Intent intent = new Intent("com.tgwgroup.MiRearScreenSwitchers.ACTION_CAR_CONTROL_COMMAND");
intent.setPackage("com.wmqc.miroot");
intent.putExtra("function", "openAirConditioner");
intent.putExtra("durationMinutes", 15);
intent.putExtra("temperature", 23);
intent.putExtra("requestId", "ac_001");
context.sendBroadcast(intent);
```

### 注意事项

1. **登录依赖**：需要已在 MiRoot 内登录车控账号并生成有效登录态 / 车辆参数（与 `0.json` 逻辑一致），否则会失败。
2. **网络依赖**：需要联网访问车控接口。
3. **超时**：超时后会发送 `execution` 回执并中断工作线程；若业务上需严格单次回执，请在主题侧以 `requestId` + `callbackPhase` 去重或只处理最终阶段。

---

## 3. 车辆状态查询（广播调用，返回车辆信息 JSON）

查询车辆**实时状态**，结果以 JSON 字符串写入回执的 `data` / `postStatus`。内容由 `VehicleStatusService.getVehicleStatus` 拉取接口后，经 **`VehicleStatusService.buildVehicleQueryBroadcastJson`** 组装，与 MiRoot **设置页「车辆状态」卡片**中「【车辆状态】」「【里程能源】」的展示规则一致（含未知项是否输出为空串）。

> 投屏 HUD 上的续航、油量%、总里程、温度、短格式更新时间等展示解析，与上述数据同源规则见源码 `VehicleStatusService` 中的 `parseDistanceToEmptyKm`、`parseFuelLevelPercent`、`formatOdometerKmDigitsOrNull` 等（仅供应用内 UI；**广播 JSON 仍为接口侧原始字段经翻译/拼接后的结构**，见下文）。

### 广播 Action（请求）

```text
com.tgwgroup.MiRearScreenSwitchers.ACTION_QUERY_VEHICLE_STATUS
com.wmqc.miroot.car.ACTION_QUERY_VEHICLE_STATUS
```

### 回执 Action（默认）

```text
com.tgwgroup.MiRearScreenSwitchers.ACTION_VEHICLE_STATUS_RESULT
```

自定义：`replyAction=com.wmqc.miroot.car.ACTION_VEHICLE_STATUS_RESULT`。

### 请求参数（Extras）

| 参数 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| `requestId` | string | 否 | 请求 ID（回执原样回传） | `status_001` |
| `replyAction` | string | 否 | 自定义回执 action，不传用默认 | `com.xxx.STATUS_REPLY` |

### 回执内容（Extras）

| 参数 | 类型 | 说明 |
|------|------|------|
| `requestId` | string | 原样回传 |
| `success` | **string** | **`"true"`** / **`"false"`** |
| `message` | string | 错误原因等 |
| `data` | string | JSON 字符串（精简字段），见下方结构 |
| `postStatus` | string | 与 `data` 相同，便于与车控指令回执字段统一绑定 |

### `data` JSON 结构（与 `buildVehicleQueryBroadcastJson` 一致）

根级字段说明：

| 字段 | 类型 | 说明 |
|------|------|------|
| `updateTime` | string | 接口中的更新时间字段（原始字符串，常为时间戳或接口原样） |
| `updateDateTime` | string | 应用侧格式化的日期时间（如 `yyyy-MM-dd HH:mm:ss`），解析失败时可能为空 |
| `vehicleStatus` | object | 中文键名 → 已翻译展示文案；**值为未知时为空串 `""`**（键仍存在）。键：`发动机`、`门锁`、`车门`、`车窗`、`后备箱`、`天窗` |
| `vehicleStatusItems` | array | 固定顺序的状态项，便于按下标解析。每项：`{ "name": "发动机", "state": "运行中" }`，未知时 `state` 为 `""` |
| `mileageEnergy` | object | 中文键名 → **已带单位**的字符串，未知为空串。键：`总里程`、`续航`、`油量`、`油量%`、`电瓶`、`油耗`（单位分别为 `km`、`km`、`L`、`%`、`V`、`L/100km`） |
| `mileageEnergyItems` | array | 固定顺序的度量项。每项：`name`、`unit`、`state`（如 `420km`）、`value`（如 `420`）；未知时 `state`、`value` 均为 `""` |
| `vehicleStatusText` | string | 人类可读摘要，前缀 `【车辆状态】`，仅拼接已知项 |
| `mileageEnergyText` | string | 人类可读摘要，前缀 `【里程能源】`，仅拼接已知项 |

示例（演示结构；真实接口下未知项多为空串）：

```json
{
  "updateTime": "1700000000",
  "updateDateTime": "2026-03-17 12:34:56",
  "vehicleStatus": {
    "发动机": "运行中",
    "门锁": "已锁",
    "车门": "",
    "车窗": "已关",
    "后备箱": "已关",
    "天窗": "已关"
  },
  "vehicleStatusItems": [
    { "name": "发动机", "state": "运行中" },
    { "name": "门锁", "state": "已锁" },
    { "name": "车门", "state": "" },
    { "name": "车窗", "state": "已关" },
    { "name": "后备箱", "state": "已关" },
    { "name": "天窗", "state": "已关" }
  ],
  "mileageEnergy": {
    "总里程": "12345km",
    "续航": "420km",
    "油量": "45L",
    "油量%": "62%",
    "电瓶": "12.6V",
    "油耗": "7.5L/100km"
  },
  "mileageEnergyItems": [
    { "name": "总里程", "unit": "km", "state": "12345km", "value": "12345" },
    { "name": "续航", "unit": "km", "state": "420km", "value": "420" },
    { "name": "油量", "unit": "L", "state": "45L", "value": "45" },
    { "name": "油量%", "unit": "%", "state": "62%", "value": "62" },
    { "name": "电瓶", "unit": "V", "state": "12.6V", "value": "12.6" },
    { "name": "油耗", "unit": "L/100km", "state": "7.5L/100km", "value": "7.5" }
  ],
  "vehicleStatusText": "【车辆状态】 发动机:运行中 门锁:已锁 车窗:已关 后备箱:已关 天窗:已关",
  "mileageEnergyText": "【里程能源】 总里程:12345km 续航:420km 油量:45L 油量%:62% 电瓶:12.6V 油耗:7.5L/100km"
}
```

### 示例

**查询（ADB）**

```bash
adb shell am broadcast -a com.wmqc.miroot.car.ACTION_QUERY_VEHICLE_STATUS \
  --es requestId status_adb_001 \
  com.wmqc.miroot
```

### 注意事项

1. **登录依赖**：需要有效登录态与车辆信息；未就绪时 `success` 为 `"false"`，`message` 约为 **「请先登录或车辆信息未绑定」**，`data` / `postStatus` 为空。
2. **网络依赖**：需要联网访问车辆状态接口。
3. **data 为 JSON 字符串**：调用方自行 `JSONObject` / 反序列化解析。
4. **短期缓存**：`VehicleStatusQueryService` 在距上一次**成功**拉取结束不足 **5 秒**时，会直接复用缓存 JSON 回执，不再发起 HTTP，避免主题高频轮询压接口。缓存仅影响广播查询路径，应用内设置页等直接调用 `VehicleStatusService` 不受影响。
5. **回执不设包名**：与 §2 相同，回执 **不**调用 `setPackage("com.wmqc.miroot")`，以便其它进程中的 `BroadcastBinder` 接收。

请求侧 Extra 键名与 `VehicleStatusQueryService` 常量一致：`requestId`、`replyAction`（对应源码 `EXTRA_REQUEST_ID`、`EXTRA_REPLY_ACTION`）。

---

## 相关文档与源码

- 总览（含音乐投屏等）：[外部调API文档.md](./外部调API文档.md)
- 主题侧 MAML 发送时 Action 与 Extra 键名请以本文 §2 / §3 为准；若沿用历史 `com.tgwgroup…` 前缀的 Action 字符串，与本表左列及代码示例中的完整名等价。
- 源码：`CarControlIntents`、`CarControlBroadcastReceiver`、`CarControlProjectionService`、`CarControlCommandService`、`VehicleStatusQueryService`、`VehicleControlService`、`VehicleStatusService`（`buildVehicleQueryBroadcastJson` 及展示用解析工具方法）。
