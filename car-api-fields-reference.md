# 星瑞车控 API 字段参考

## 数据来源

- **车辆列表 API**: `https://api.xchanger.cn/device_platform/user/vehicle?userId={userId}`
- **车辆状态 API**: `https://api.xchanger.cn/geelyTCAccess/tcservices/vehicle/status/{vin}?userId={userId}&latest=false&target=more,basic`

## VehicleStatusInfo 字段一览

### ✅ 已解析 + 已展示

| 字段 | 含义 | 来源 JSON 路径 | 展示位置 |
|------|------|---------------|----------|
| `plateNo` | 车牌 | `data.list[0].plateNo` | 车辆状态卡片 |
| `seriesName` | 车系 | `data.list[0].seriesName` | 车辆状态卡片 |
| `modelName` | 车型 | `data.list[0].modelName` | 车辆状态卡片 |
| `colorName` | 颜色 | `data.list[0].colorName` | 车辆状态卡片 |
| `fuelLevel` | 油量(L) | `runningStatus.fuelLevel` | 油量弧形表 |
| `fuelLevelStatus` | 油量百分比 | `runningStatus.fuelLevelStatus` | 油量弧形表 |
| `distanceToEmpty` | 续航(km) | `basicStatus.distanceToEmpty` | 油量弧形表 |
| `voltage` | 电瓶电压(V) | `mainBatteryStatus.voltage` | 电瓶芯片 |
| `stateOfCharge` | 电瓶电量(%) | `mainBatteryStatus.stateOfCharge` | 电瓶芯片 |
| `avgSpeed` | 平均车速 | `runningStatus.avgSpeed` | 平均车速芯片 |
| `interiorTemp` | 车内温度 | `climateStatus.interiorTemp` | 内外温芯片 |
| `exteriorTemp` | 车外温度 | `climateStatus.exteriorTemp` | 内外温芯片 |
| `latitude` | 纬度 | `position.latitude` | 地图定位 |
| `longitude` | 经度 | `position.longitude` | 地图定位 |
| `tyreStatus(D/P/DR/PR)` | 四轮胎压(kPa) | `maintenanceStatus.tyreStatus*` | 胎压卡片 |
| `tyrePreWarning(D/P)` | 前轮胎压预警 | `maintenanceStatus.tyrePreWarning*` | 胎压卡片 |
| `engineStatus` | 发动机状态 | `basicStatus.engineStatus` | 按钮状态联动 |
| `winStatusDriver` | 主驾车窗 | `climateStatus.winPosDriver` | 按钮状态联动 |
| `doorLockStatusDriver` | 主驾门锁 | `drivingSafety.doorLockStatusDriver` | 车辆状态摘要 |
| `trunkOpenStatus` | 后备箱 | `drivingSafety.trunkOpenStatus` | 按钮状态联动 |
| `updateDateTime` | 更新时间 | `basicStatus.updateTime` | 油量表下方 |
| `vin` | 车架号 | `data.list[0].vin` | 车辆状态卡片 |
| `speed` | 实时车速 | `basicStatus.speed` | (按钮联动) |
| `aveFuelConsumption` | 平均油耗 | `runningStatus.aveFuelConsumption` | 里程能耗卡片 |
| `engineCoolantTemperature` | 冷却液温度 | `runningStatus.engineCoolantTemperature` | 里程能耗卡片 |
| `odometer` | 总里程 | `maintenanceStatus.odometer` | 里程能耗卡片 |
| `distanceToService` | 保养里程 | `maintenanceStatus.distanceToService` | - |
| `mileageEnergyRows` | 动态里程能耗项 | (由 buildVehicleQueryBroadcastJson 生成) | 里程能耗卡片 |
| `vehicleStatusRows` | 动态车辆状态项 | (由 buildVehicleQueryBroadcastJson 生成) | 车辆状态摘要 |

### ✅ 已解析但未展示

| 字段 | 含义 | 来源 |
|------|------|------|
| `serviceWarningStatus` | 保养警告 | `maintenanceStatus` |
| `engineSpeed` | 发动机转速(rpm) | `drivingBehaviourStatus` |
| `cruiseControlStatus` | 定速巡航 | `drivingBehaviourStatus` |
| `transimissionGearPostion` | 变速箱档位 | `drivingBehaviourStatus` |
| `airCleanSts` | 空气净化 | `climateStatus` |
| `preClimateActive` | 预约空调 | `climateStatus` |
| `ventilateStatus` | 通风状态 | `climateStatus` |
| `drvHeatSts` | 主驾座椅加热实际状态 | `climateStatus` |
| `passHeatingSts` | 副驾座椅加热实际状态 | `climateStatus` |
| `sunroofOpenStatus` | 天窗状态 | `climateStatus` |
| `seatBeltStatusDriver` | 主驾安全带 | `drivingSafetyStatus` |
| `vehicleAlarm` | 车辆报警 | `drivingSafetyStatus` |
| `electricParkBrakeStatus` | 电子手刹 | `drivingSafetyStatus` |
| `theftActivated` | 防盗状态 | `theftNotification` |
| `fuelType` | 燃油类型 | - |
| `iccid` | SIM卡ICCID | `data.list[0]` |
| `modelCode` | 车型编码 | `data.list[0]` |
| `factoryCode` | 工厂代码 | `data.list[0]` (声明未解析) |
| `colorCode` | 颜色代码 | `data.list[0]` |
| `defaultVehicle` | 是否默认车辆 | `data.list[0]` (声明未解析) |

### ❌ 尚未解析（API 有但 Java 未声明）

#### 安全状态 (drivingSafetyStatus)

| 字段 | 含义 | 值映射 |
|------|------|--------|
| `doorLockStatusPassenger` | 副驾门锁 | 0=未上锁, 1=已上锁, 2=部分上锁 |
| `doorLockStatusDriverRear` | 左后门锁 | 同上 |
| `doorLockStatusPassengerRear` | 右后门锁 | 同上 |
| `doorOpenStatusPassenger` | 副驾开门 | 0=关闭, 1=打开 |
| `doorOpenStatusDriverRear` | 左后开门 | 同上 |
| `doorOpenStatusPassengerRear` | 右后开门 | 同上 |
| `seatBeltStatusPassenger` | 副驾安全带 | 0=false, 1=true |
| `trunkLockStatus` | 后备箱锁 | 0=关闭, 1=开启 |
| `handBrakeStatus` | 机械手刹 | 0=释放, 1=拉起 |
| `engineHoodOpenStatus` | 引擎盖 | 0=关闭, 1=打开 |
| `brakePedalDepressed` | 制动踏板 | 0=false, 1=true |

#### 维护状态 (maintenanceStatus)

| 字段 | 含义 | 值映射 |
|------|------|--------|
| `tyrePreWarningDriverRear` | 左后胎压预警 | 0=正常, 1=警告 |
| `tyrePreWarningPassengerRear` | 右后胎压预警 | 同上 |
| `engineOilPressureWarning` | 机油压力警告 | 同上 |

#### 气候状态 (climateStatus)

| 字段 | 含义 | 值映射 |
|------|------|--------|
| `rlHeatingSts` | 左后座椅加热 | 0=false, 1=true |
| `rrHeatingSts` | 右后座椅加热 | 同上 |
| `winPosPassenger` | 副驾窗位 | 0=关闭, 其他=打开 |
| `winPosDriverRear` | 左后窗位 | 同上 |
| `winPosPassengerRear` | 右后窗位 | 同上 |

#### 位置/其他

| 字段 | 含义 | 来源 | 备注 |
|------|------|------|------|
| `altitude` | 海拔(m) | basicStatus | - |
| `direction` | 行驶方向(度) | basicStatus | - |
| `posCanBeTrusted` | 定位可信度 | basicStatus | bool |
| `carLocatorStatUploadEn` | 定位上传状态 | - | bool |
| `marsCoordinates` | 火星坐标系 | - | bool |
| `aveFuelConsumptionInLatestDrivingCycle` | 本次循环油耗 | runningStatus | L/100km |
| `winCloseReminder` | 车窗未关提醒 | - | 0=正常, 1=警告 |

#### 车辆列表 API 未解析字段

| 字段 | 含义 |
|------|------|
| `ihuId` | 车机IMEI |
| `msisdn` | 车机SIM卡号码 |
| `seriesCodeVs` | 车系编码 |
| `matCode` | 材质代码 |

## 值映射参考

```
车门锁:    0=未上锁  1=已上锁  2=部分上锁
车门开关:  0=关闭    1=打开
后备箱:    0=关闭    1=打开
车窗:      0=关闭    其他=打开
天窗:      0=关闭    其他=打开
发动机:    engine_off=已熄火  engine_on=运行中
手刹:      0=释放    1=拉起
安全带:    0=未系    1=已系
防盗:      0=未激活  1=已激活  2=开启
报警:      0=正常    1=报警
警告:      0=正常    1=警告
巡航:      0=关闭    1=开启
燃油类型:  0=汽油    1=柴油    2=电动
布尔值:    false/0=关闭  true/1=开启
```

## 开放 Issue

- `TyrePreWarningDriverRear` / `TyrePreWarningPassengerRear`: 在 `VehicleStatusInfo` 中未声明（仅有前轮两个），后轮预警字段缺失
- `chargingStatus` / `remainingChargingTime`: 仅在 `buildVehicleQueryBroadcastJson` 中出现，可能是插电混动车型专有字段
