<p align="center">
  <img src="assets/MiRoot.png" alt="MiRoot" width="192"/>
</p>

<h1 align="center">MiRoot</h1>

<p align="center">
  <strong>小米汽车副屏增强工具</strong><br>
  Xposed 模块 · Android 车载 · 副屏投屏 · 歌词投影 · 车控增强
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-1.8.9-blue" alt="version"/>
  <img src="https://img.shields.io/badge/API-28%2B-brightgreen" alt="API"/>
  <img src="https://img.shields.io/badge/Xposed-101%2B-orange" alt="Xposed"/>
  <img src="https://img.shields.io/badge/license-Apache%202.0-green" alt="license"/>
</p>

---

## 简介

MiRoot 是一个基于 Xposed 框架的 Android 车载副屏增强模块，专为小米 SU7 等车型设计。它通过 Hook 系统服务与副屏框架，实现副屏应用投影、歌词投影、车控增强、充电动画自定义等功能。

### 核心功能

**副屏桌面**
- 蜂窝式（Honeycomb）副屏启动器，支持应用网格与快捷启动
- 副屏桌面轮盘导航与自定义布局
- 副屏应用安装与管理界面
- 返回手势与底部手势控制

**歌词投影**
- **SuperLyric 引擎**：逐字歌词时间轴解析，支持酷我音乐等源的实时歌词同步
- 歌词 API 客户端：多源歌词获取与缓存
- 智能歌词融合匹配（MixedLyricsLineMatcher）
- 中文分词器（基于 jieba 词库）
- 音乐媒体会话（MediaSession）集成
- 歌词动画与跑马灯效果
- 音乐投影快捷开关（QSTile）

**车控增强**
- 车载控制指令服务与广播接收
- 车辆状态查询与投射
- 自定义按钮配置
- 车控界面显示管理
- 吉利数字钥匙近场解锁（Geely Digital Key）

**充电动画**
- 充电占位 Activity，无缝过渡动画
- 陀螺仪水波纹效果
- 充电动画恢复与回退策略
- 副屏充电界面

**屏幕录制**
- 副屏录屏服务（支持音频内录）
- 音频捕获辅助（AudioPlaybackCapture）
- 录屏快捷开关

**主题注入**
- 主题元数据注入与动态加载
- 目录封面绑定
- 视频替换
- AI 壁纸主题辅助

**授权与激活**
- 离线激活系统（HMAC-SHA256 签名验证）
- 激活状态广播与查询
- Root/Shizuku 权限管理

**显示控制**
- 副屏/主屏显示控制管理
- 显示控制示例 Activity

**爱发电赞助集成**
- 爱发电开放 API 接入
- 赞助状态查询与解锁

**屏幕截图**
- 副屏截图协调器
- 截图快捷开关

## 截图

<p align="center">
  <img src="assets/webshishihuiyiluzhipingmuicon.png" width="120" alt="录屏"/>
  <img src="assets/pingmujietu.png" width="120" alt="截图"/>
  <img src="assets/yinle.png" width="120" alt="音乐"/>
  <img src="assets/touping.png" width="120" alt="投屏"/>
  <img src="assets/car/bg.webp" width="120" alt="车控"/>
  <img src="assets/car/xingrui.webp" width="120" alt="星瑞"/>
</p>

## 环境要求

| 项目 | 要求 |
|------|------|
| Android | API 28+ (Android 9) |
| 目标 API | 36 (Android 16) |
| Xposed | API 101+ |
| Root | 可选（部分功能需要 Root 或 Shizuku） |
| 架构 | arm64-v8a |

## 快速开始

### 构建

```bash
# 克隆仓库
git clone https://github.com/wmqc97/MiRoot.git
cd MiRoot

# local.properties 中的配置（可选，勿提交到 git）：
# - 签名密钥（keystore 相关配置）
# - 爱发电 API Token

# 构建 Release APK
./gradlew :app:assembleRelease
```

构建产物位于 `app/build/outputs/apk/release/`。

### 安装

1. 安装 APK 到设备
2. 在 Xposed 模块中启用 MiRoot
3. 重启 SystemUI（或重启设备）
4. 打开 MiRoot 应用完成权限授权

## 项目结构

```
MiRoot/
├── app/
│   ├── src/main/
│   │   ├── java/com/wmqc/miroot/
│   │   │   ├── afdian/          # 爱发电赞助集成
│   │   │   ├── capability/      # 权限管理（Root/Shizuku）
│   │   │   ├── car/             # 车控功能
│   │   │   ├── charging/        # 充电动画
│   │   │   ├── display/         # 显示控制
│   │   │   ├── license/         # 离线激活
│   │   │   ├── lsp/             # LSPosed 模块入口
│   │   │   ├── lyrics/          # 歌词系统（SuperLyric 引擎）
│   │   │   ├── rear/            # 副屏功能
│   │   │   │   └── desktop/     # 副屏桌面（Honeycomb）
│   │   │   ├── record/          # 屏幕录制
│   │   │   ├── service/         # 后台服务
│   │   │   ├── shell/           # Shell 命令与截图
│   │   │   ├── theme/           # 主题注入
│   │   │   ├── ui/              # UI 界面
│   │   │   │   ├── apps/        # 应用管理
│   │   │   │   ├── features/    # 功能页
│   │   │   │   ├── main/        # 主页
│   │   │   │   ├── more/        # 更多
│   │   │   │   ├── music/       # 音乐设置
│   │   │   │   ├── permission/  # 权限页
│   │   │   │   └── theme/       # 主题选择
│   │   │   └── viewmodel/       # ViewModel
│   │   ├── assets/
│   │   │   ├── car/             # 车控图片资源
│   │   │   ├── shell/           # 车型图
│   │   │   ├── jieba/           # 歌词分词词库
│   │   │   └── theme_inject/    # 主题注入配置
│   │   ├── res/                 # 资源文件
│   │   └── resources/META-INF/  # Xposed 模块元数据
│   └── src/test/                # 单元测试
├── assets/                      # 文档用图片
├── docs/                        # 开发文档
├── scripts/                     # 辅助脚本
└── tools/                       # 工具脚本
```

## 技术栈

- **语言**：Kotlin + Java
- **框架**：Xposed / LSPosed
- **构建**：Gradle + Kotlin DSL + Version Catalog
- **UI**：Jetpack Compose + XML Layout
- **权限**：Shizuku API + Root Shell
- **歌词解析**：SuperLyric 协议、酷我音乐 API、LRC 解析

## 文档

详细文档位于 [docs/](docs/) 目录（中文）：

| 文档 | 说明 |
|------|------|
| [SuperLyric 接入与排障](docs/SuperLyric接入与排障说明.md) | SuperLyric 协议集成指南 |
| [SuperLyric 逐字接入](docs/SuperLyric逐字接入.md) | 逐字歌词 API 使用说明 |
| [Kuwo 歌词接入与分析](docs/酷我歌词接入与解析.md) | 酷我音乐歌词对接 |
| [Kuwo 歌词广播参考](docs/酷我歌词广播参考文档.md) | 酷我广播协议 |
| [智能歌词融合规则](docs/智能歌词融合规则.md) | 歌词多源融合策略 |
| [第三方 API 备忘](docs/歌词第三方API备忘.md) | 歌词 API 参考 |
| [第三方应用获取歌词](docs/第三方应用获取歌词_MediaSession集成.md) | MediaSession 集成 |
| [充电动画判断与迁移策略](docs/充电动画判断、迁移策略与窗口属性说明.md) | 充电动画系统 |
| [离线激活码生成算法](docs/离线激活码生成算法.md) | 激活码算法 |
| [背屏主体应用信息](docs/背屏主体应用信息.md) | 副屏应用信息 |
| [背屏投影返回手势](docs/背屏投影返回手势与退出闪屏说明.md) | 手势控制 |
| [外部广播启动投影](docs/外部广播启动投影.md) | 外部触发投影 |
| [车控广播调用 API](docs/车控广播调用API文档.md) | 车控接口 |

## 许可证

```
Copyright 2025-2026 wmqc97

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
