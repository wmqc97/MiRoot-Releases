车控投屏位图资源（同步到 app/src/main/assets/car 并打入 APK）
============================================================

将 PNG/JPG 等文件放在本目录（与仓库根目录 assets/car 对应）。
构建前由 Gradle 任务 syncCarControlAssets 复制到 app/src/main/assets/car/。

常用文件名（与代码一致，均为 car/ 下的相对路径）：
  （全屏背景已改为随系统深浅色的纯色底，不再读取 car_control_projection_bg）
  xingrui.png                    — 默认车模（优先于 res/drawable/xingrui 矢量占位）
  you.png                        — 油量小图标（可选；缺省用 res/drawable/you）

按钮图标（可选，文件名与 res/drawable 资源名一致）：
  ic_car_index_lock.png
  ic_car_index_lock_on.png
  ic_car_index_find_car.png
  ic_car_index_engine.png
  ic_car_index_engine_on.png
  ic_ac_unit.png
  ic_ac_unit_on.png
  ic_car_index_open_window.png
  ic_car_index_open_window_on.png
  ic_car_index_wind.png
  ic_car_index_wind_on.png
  ic_car_index_trunk.png
  ic_car_index_trunk_on.png
  ic_seat_heating.png
  ic_seat_heating_on.png
  ic_seat_heating_driver.png
  ic_seat_heating_driver_on.png
  ic_seat_heating_passenger.png
  ic_seat_heating_passenger_on.png

若某 PNG 不存在，界面自动回退到现有 res/drawable（XML/矢量）。
