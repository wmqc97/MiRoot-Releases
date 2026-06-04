# 高德地图 Key 配置说明



车控页地图、逆地理地址、小组件车辆位置使用高德能力，代码入口：



- `AmapApiService.kt` — HTTP 调用 **Web 服务**（逆地理、静态地图）

- `OsmMapHelper.kt` — 高德失败时的 **OpenStreetMap** 回退（无需 Key，但有坐标系偏差，见下文）

- 可选：`com.amap.api:3dmap` / `search` — **Android 地图 SDK**（需 Android 平台 Key）



## 为什么填了 Key 仍不能逆地理 / 静态地图？



本机实测当前 `AmapApiService.AMAP_KEY` 调用 Web 服务返回：



```json

{"info":"USERKEY_PLAT_NOMATCH","infocode":"10009","status":"0"}

```



**原因：高德 1 个 Key 只能绑定 1 种服务平台。**



| 你很可能创建的类型 | 能用的接口 | 不能用的接口 |

|-------------------|-----------|-------------|

| **Android 平台**（包名 + SHA1） | Android 3D 地图 SDK、搜索 SDK、定位 SDK | `restapi.amap.com` 逆地理/静态图 |

| **Web 服务** | `restapi.amap.com` 逆地理、静态图、路径规划 | Android SDK、JS API |

| **Web 端 (JS API)** | WebView 里 `webapi.amap.com/maps` | Web 服务 HTTP、Android SDK |



MiRoot 车控页当前通过 **HTTP 调 Web 服务** 做地址和静态图，必须用 **Web 服务 Key**，不能只用 Android Key。



## 正确配置（推荐：两个 Key）



在 [高德控制台](https://console.amap.com/dev/key/app) 同一应用下 **添加两个 Key**：



### Key A — Android 平台（已有可继续用）



| 项 | 值 |

|---|---|

| 服务平台 | **Android 平台** |

| PackageName | `com.wmqc.miroot` |

| 发布版 SHA1 | `67:00:03:88:81:83:7F:76:A2:86:D9:4E:ED:F1:8E:52:72:FD:CE:B0` |

| 调试版 SHA1 | `F8:31:8B:4A:26:D4:89:98:F0:99:AB:67:E6:BD:49:36:D4:B7:F4:41` |



写入代码：`AmapApiService.AMAP_KEY`（将来接 Android 地图 SDK / Manifest meta-data 也用此 Key）。



### Key B — Web 服务（新建，解决逆地理与静态图）



| 项 | 值 |

|---|---|

| 服务平台 | **Web 服务**（创建 Key 时选此项，不是 Android） |

| 安全码 | Web 服务 Key **不需要** 包名/SHA1 |



写入本机 `local.properties`（勿提交 Git）：



```properties

AMAP_WEB_SERVICE_KEY=这里粘贴Web服务Key

```



重新编译后，`AmapApiService.webServiceKey` 会优先使用该值。



### 可选 Key C — Web 端 (JS API)



若要在 WebView 内嵌可交互高德地图，再单独建 **Web 端 (JS API)** Key（与 Web 服务、Android 不能混用）。



## 坐标要不要「转换」？



- 星瑞车机 API 上报的经纬度一般为 **GCJ-02（火星坐标）**，与高德一致。

- 代码里 `VehiclePositionHelper.parseCoordinates` 会把毫秒值 ÷3600000，并纠正部分经纬度字段对调。

- **不需要**再对高德接口做 WGS84↔GCJ 转换。

- 当前 **OSM 回退**使用 WGS84 瓦片/Nominatim，与 GCJ 有几百米偏差；高德 Web 服务或 Android SDK 正常后应优先走高德。



## 开源地图项目与高德的关系



| 项目 | 类型 | 能否直接当「高德 Key 接入」 | 说明 |

|------|------|---------------------------|------|

| [osmdroid](https://github.com/osmdroid/osmdroid) | 开源栅格瓦片 | 可拼高德瓦片 URL，**无官方 Key 绑定**，有合规与 GCJ 偏移问题 | 社区常用自定义 `TileSource` 加载 `autonavi.com/appmaptile` |

| [MapLibre](https://github.com/maplibre/maplibre-native) | 开源矢量引擎 | **不**提供高德矢量样式；国内一般换天地图/自建样式 | 需自行数据源 |

| **高德 Android 3D SDK** | 官方闭源 | **推荐**：`implementation 'com.amap.api:3dmap:latest.integration'` + Android Key | [入门指南](https://lbs.amap.com/api/android-sdk/gettingstarted) |

| **高德 Web 服务 API** | 官方 HTTP | **当前 MiRoot 用法**：逆地理 + 静态图 + Web Key | [创建 Web 服务 Key](https://lbs.amap.com/api/webservice/create-project-and-key) |

| **高德搜索 SDK** | 官方 Android | Android Key + `GeocodeSearch` 逆地理，**不走** restapi HTTP | 适合只想要地址、不想引整图 SDK 时 |



结论：**没有**「一个开源库 + 你现有的 Android Key」就能搞定 Web 逆地理；要么 **再申请 Web 服务 Key**，要么 **集成高德 Android SDK/搜索 SDK**。



## 接入高德 Android 地图 SDK（若要做可交互地图）



1. `app/build.gradle.kts` 增加：

   ```kotlin

   implementation("com.amap.api:3dmap-location-search:latest.integration")

   ```

2. `AndroidManifest.xml` 的 `<application>` 内：

   ```xml

   <meta-data

       android:name="com.amap.api.v2.apikey"

       android:value="你的Android平台Key" />

   ```

3. Compose 里用 `AndroidView` 承载 `MapView`，生命周期对接 `onCreate/onResume/onPause/onDestroy`。

4. 逆地理可用同应用 **搜索 SDK** 的 `GeocodeSearch.getFromLocationAsyn`（仍用 Android Key）。



包体积与隐私合规成本高于当前「静态图 + HTTP」方案。



## 本仓库当前车控地图策略



1. 优先：高德 **Web 服务** 静态图 + 逆地理（需 `AMAP_WEB_SERVICE_KEY`）

2. 失败回退：**OSM** 瓦片 + Nominatim 地址（无需 Key，精度略差）

3. 导航：唤起高德 App（`amapuri://`，与 Key 类型无关）



## 调试



```bash

./gradlew :app:printSigningFingerprints

```



Logcat 过滤 `AmapApiService`，若见 `USERKEY_PLAT_NOMATCH (10009)` 即 Web 服务 Key 未配置或填错。



## 代码中的 Key



| 用途 | 配置位置 |

|------|----------|

| Android 平台 | `AmapApiService.AMAP_KEY` |

| Web 服务 HTTP | `local.properties` → `AMAP_WEB_SERVICE_KEY` → `BuildConfig` |



勿将 Key 提交公开仓库。


