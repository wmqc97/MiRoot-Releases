# 小米背屏（副屏）集成参考 — 供新项目对照

本文档整理 **MiRearScreenSwitcher 3.4** 中与 **MIUI 背屏显示、触摸与系统返回** 相关的清单与启动策略，便于新项目移植时逐项核对。  
说明：MIUI 行为随 ROM 变化，以下以当前仓库实现为准；若官方策略有更新，请以真机验证为准。

---

## 1. 为什么新项目「背屏手势不响应」

常见原因有三类（可同时存在）：

1. **未声明 MIUI 背屏策略** — 系统可能不把副屏触摸/手势交给你的窗口。  
2. **界面没有真正跑在背屏 display 上** — 仅主屏有 Activity，背屏划动不会进你的进程。  
3. **小米背屏中心前台抢占** — `com.xiaomi.subscreencenter` 在前台时，手势由官方层处理，你的全屏页收不到事件。

3.4 通过 **清单 meta-data + 指定 display 启动 +（可选）force-stop 官方背屏进程** 组合解决上述问题。

---

## 2. 清单：`AndroidManifest.xml` 必配项

### 2.1 Application 级（全局「准入」声明）

在 `<application>` 节点内增加（与 3.4 一致，`value` 为字符串 `"1"`）：

```xml
<!-- MIUI：允许应用参与背屏相关策略（项目内俗称「背屏准入证」） -->
<meta-data
    android:name="miui.rear.policy"
    android:value="1"/>
```

**参考位置**：本仓库 `android/app/src/main/AndroidManifest.xml` 中 `<application>` 起始处。

### 2.2 需要在背屏全屏交互的 Activity 级

对每个 **固定在副屏使用、需要接收触摸/系统返回** 的 `Activity`，在对应 `<activity>` 内增加：

```xml
<meta-data
    android:name="miui.rear.policy"
    android:value="miui"/>
```

**3.4 中带此声明的 Activity 示例**（可作命名与粒度参考）：

- `RearScreenLyricsActivity`（背屏歌词）
- `RearScreenCarControlActivity`（背屏车控）
- `RearScreenNotificationActivity`（背屏通知动画）
- `RearScreenChargingActivity`（背屏充电动画）
- `RearScreenWakeupActivity`（背屏点亮）

新项目：将上述 `meta-data` 挂到你的「背屏专用」Activity 上即可，无需每个 Activity 都加。

---

## 3. 启动方式：必须出现在背屏 display（一般为 displayId = 1）

手势与 Back 事件是发给 **该 display 上前台窗口** 的。仅 `startActivity()` 默认主屏时，背屏操作不会进入你的界面。

### 3.1 Shell / Root（与 3.4 TaskService 一致）

```text
am start --display 1 -n <package>/<Activity完整类名>
```

或使用 3.4 中「主屏占位 + `service call activity_task` 移动任务到背屏」的等价流程（见 `ProjectionHelper.java`）。

### 3.2 纯应用内 API（视系统与权限而定）

- 使用 `ActivityOptions.setLaunchDisplayId(1)`（或系统提供的多显示器启动 API）启动目标 Activity。  
- 需确认目标机型上 **display 1 是否为副屏**；部分环境建议以 `DisplayManager` / `adb shell dumpsys display` 核实。

**新项目检查点**：先确认日志或调试输出里该 Activity 的 `getDisplay().getDisplayId()` 在背屏场景下是否为 **1**（或与你们定义的副屏 id 一致）。

---

## 4. 与 `com.xiaomi.subscreencenter` 的关系（可选但影响大）

小米副屏官方 Launcher / 手势由 **`com.xiaomi.subscreencenter`** 相关进程承载。其在前台时，背屏边缘手势、返回等可能**优先交给官方层**，你的应用表现为「不跟手」。

### 4.1 3.4 的做法（需 Root 或同等执行 shell 的能力）

- **使用前**：`am force-stop com.xiaomi.subscreencenter`（见 `TaskService.disableSubScreenLauncher()`）。  
- **用完后**：`pm enable` 包与组件，并在副屏 `am start --display 1` 拉起官方 Launcher（见 `TaskService.enableSubScreenLauncher()`）。

新项目若无 Root，可能无法完全复现该策略，需在真机上接受「与官方手势共存或冲突」的差异。

---

## 5. 建议的 Activity 属性（与 3.4 背屏页对齐）

下列不是 MIUI 专有，但有利于副屏任务独立、减少焦点错乱（可按需裁剪）：

| 属性 | 建议 | 说明 |
|------|------|------|
| `launchMode` | `singleInstance` | 背屏单例任务，减少与主屏栈互相干扰 |
| `taskAffinity` | 单独字符串 | 与主界面不同 affinity，便于分屏/多显示任务管理 |
| `excludeFromRecents` | `true` | 多见于 3.4 临时/投屏类界面 |
| `showWhenLocked` / `turnScreenOn` | `true` | 锁屏场景仍可显示与点亮（按需） |
| `directBootAware` | `true` | 按需：直启加密前可用 |
| `configChanges` | `orientation\|screenSize\|…` | 减少配置变更导致重建、丢触摸焦点 |
| `exported` | 若需 `am start` 外部拉起则为 `true` | 与安全评审一并考虑 |

透明主题等按产品需求选择；避免出现误设 `FLAG_NOT_TOUCHABLE` 导致整窗不收触摸。

---

## 6. 系统「返回」与应用内「手势」

- **系统返回键 / 系统边缘返回**：由 Android/MIUI 分发给**当前获焦** Activity；新项目应保证背屏 Activity 正常 `onBackPressed` 或 `OnBackPressedDispatcher` 行为（3.4 中车控页在 `onBackPressed` 里做统一清理）。  
- **应用内手势**（如歌词上下滑动、横向切歌）：属于自建 `GestureDetector` / View 触摸逻辑，与 `miui.rear.policy` **无关**，需自行实现。

勿混淆二者：仅加 `meta-data` 不会自动产生「滑动切歌」等业务手势。

---

## 7. 新项目自检清单（建议保存为评审表）

- [ ] `<application>` 已配置 `miui.rear.policy` = `1`  
- [ ] 每个背屏专用 `<activity>` 已配置 `miui.rear.policy` = `miui`  
- [ ] 背屏界面启动命令或代码确认落在 **副屏 display**（如 `displayId == 1`）  
- [ ]（可选）是否与官方 `subscreencenter` 协调：停用/恢复策略与权限是否具备  
- [ ] 无 `NOT_TOUCHABLE` 等误伤触摸的窗口标志  
- [ ] `singleInstance` + 独立 `taskAffinity` 是否与产品预期一致  
- [ ] 真机实测：背屏点击、滑动、返回是否均进入预期 Activity  

---

## 8. 本仓库快速索引

| 内容 | 路径 |
|------|------|
| Application / Activity 清单示例 | `android/app/src/main/AndroidManifest.xml` |
| 禁用 / 恢复小米背屏中心（shell） | `android/app/src/main/java/.../TaskService.java`（`disableSubScreenLauncher` / `enableSubScreenLauncher`） |
| 背屏启动与移动任务 | `android/app/src/main/java/.../ProjectionHelper.java` |
| 车控背屏 Activity 与清理 | `RearScreenCarControlActivity.java` |
| 歌词背屏 Activity | `RearScreenLyricsActivity.java` |

---

## 9. 版本与免责声明

- 文档依据：**MiRearScreenSwitcher 3.4-Root** 仓库当前实现整理。  
- MIUI 非公开保证的接口行为（如 `miui.rear.policy` 的取值语义）可能随系统升级变化，**以真机验证为准**。  
- 涉及 `force-stop`、`pm enable` 等操作可能影响用户副屏官方体验，需在文档/用户协议中说明风险与恢复方式。

---

*文档用于新项目参考；修改本文件请勿删除「自检清单」与「免责声明」段落，便于回归评审。*
