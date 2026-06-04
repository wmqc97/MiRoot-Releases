<p align="center">
  <img src="assets/MiRoot.png" alt="MiRoot" width="192"/>
</p>

<h1 align="center">MiRoot V2.1</h1>

<p align="center">
  <strong>小米 17 Pro 系列手机背屏增强工具</strong><br>
  背屏投屏 · 歌词投影 · 车控增强 · 配置云备份
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-2.1-blue" alt="version"/>
  <img src="https://img.shields.io/badge/API-28%2B-brightgreen" alt="API"/>
  <img src="https://img.shields.io/badge/license-Apache%202.0-green" alt="license"/>
</p>

---

## 简介

MiRoot 是一个面向具备背屏（副屏）小米手机的 Android 辅助工具，专为小米 17 Pro 等配备背屏的手机设计。它通过 Root / Shizuku 权限与系统接口，实现背屏应用投影、歌词投影、车控增强、充电动画自定义、**WebDAV 配置云备份**等功能。

### V2.1 亮点

- **WebDAV 云备份**：支持坚果云等 WebDAV；手动上传/下载 `miroot_backup.zip`；本机导出/导入；备份包内记录导出时间与大小，恢复前二次确认
- **星瑞车控增强**：桌面小组件、车辆历史数据库与图表、背屏地图/油价、可自定义车模与底部按钮
- **界面统一**：主 Tab 与二级页顶栏留白、标题字号一致；音乐页标题简化为「音乐」
- **歌词与录屏**：多平台歌词 API 重构；Shizuku 场景录屏/截图导出与文档完善

### 核心功能

**副屏桌面**
- 蜂窝式（Honeycomb）副屏启动器，支持应用网格与快捷启动
- 副屏桌面轮盘导航与自定义布局
- 副屏应用安装与管理界面
- 返回手势与底部手势控制

**歌词投影**
- **SuperLyric 引擎**：逐字歌词时间轴解析，支持酷我音乐等源的实时歌词同步
- 多平台歌词 API 客户端与智能融合匹配
- 中文分词器（基于 jieba 词库）
- 音乐媒体会话（MediaSession）集成

**车控增强**
- 车载控制指令服务与车辆状态查询/投射
- **桌面小组件**远程车控
- **车辆数据历史**（车况 / 操作 / 告警）本地库与可视化
- 背屏地图、油价与可自定义车模
- 吉利数字钥匙近场解锁（Geely Digital Key）

**充电动画**
- 陀螺仪水波纹、自定义水体颜色与场景背景
- 充电动画预览与背屏手势一键唤起

**配置云备份（V2.1）**
- WebDAV 手动同步与本机 ZIP 导入导出
- 备份偏好设置、车辆历史库与应用内资源（不含登录凭据与 WebDAV 密码）

**屏幕录制 / 截图**
- 副屏录屏（支持音频内录）
- Shizuku 场景公共相册导出优化

**主题注入 · 授权 · 显示控制**
- 主题元数据注入、离线激活、爱发电赞助集成等（见历史版本说明）

## 截图

<p align="center">
  <img src="assets/webshishihuiyiluzhipingmuicon.png" width="120" alt="录屏"/>
  <img src="assets/pingmujietu.png" width="120" alt="截图"/>
  <img src="assets/yinle.png" width="120" alt="音乐"/>
  <img src="assets/touping.png" width="120" alt="投屏"/>
  <img src="assets/car/bg.webp" width="120" alt="车控"/>
  <img src="assets/car/xingru.webp" width="120" alt="星瑞"/>
</p>

## 环境要求

| 项目 | 要求 |
|------|------|
| Android | API 28+ (Android 9) |
| 目标 API | 36 (Android 16) |
| Root | 可选（部分功能需要 Root 或 Shizuku） |
| 架构 | arm64-v8a |

## 快速开始

### 下载

最新正式版 APK 从 **MiRoot-Releases** 仓库获取：

**[https://github.com/wmqc97/MiRoot-Releases/releases/latest](https://github.com/wmqc97/MiRoot-Releases/releases/latest)**

推荐文件名：`MiRoot_2.1_Release.apk`（与版本号对应）

### 源码

本仓库为 **源码与文档**：[https://github.com/wmqc97/MiRoot](https://github.com/wmqc97/MiRoot)

### 构建

```bash
git clone https://github.com/wmqc97/MiRoot.git
cd MiRoot

# local.properties（勿提交）：签名密钥、爱发电 Token、高德 Key 等

./gradlew :app:assembleRelease
```

构建产物：`app/build/outputs/apk/release/MiRoot_<version>_Release.apk`

### 安装

1. 从 MiRoot-Releases 安装 Release APK
2. 打开 MiRoot，在「状态」页完成 Root / Shizuku 等授权
3. 可选：在「状态」页配置 WebDAV 云备份

## 项目结构

```
MiRoot/
├── app/src/main/java/com/wmqc/miroot/
│   ├── backup/          # WebDAV 云备份（V2.1）
│   ├── car/             # 车控、小组件、车辆历史
│   ├── charging/        # 充电动画
│   ├── lyrics/          # 歌词系统
│   ├── rear/            # 背屏与桌面
│   ├── record/          # 录屏
│   └── ui/              # 主界面
├── assets/car/          # 车控资源（构建时同步）
└── docs/                # 开发文档
```

## 文档

| 文档 | 说明 |
|------|------|
| [Shizuku 权限注意事项](docs/Shizuku权限注意事项.md) | 录屏、存储与 shell 约束 |
| [高德地图 Key 配置](docs/高德地图Key配置.md) | 车控地图 Web 服务 Key |
| [智能歌词融合规则](docs/智能歌词融合规则.md) | 歌词多源融合 |
| [背屏录屏踩坑记录](docs/背屏录屏声音录制踩坑记录.md) | 内录注意事项 |

更多文档见 [docs/](docs/)。

## 许可证

```
Copyright 2025-2026 wmqc97

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```
