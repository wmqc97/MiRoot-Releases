<p align="center">
  <img src="MiRoot.png" alt="MiRoot" width="128"/>
</p>

<h1 align="center">MiRoot Releases</h1>

<p align="center">
  <strong>小米 17 Pro 系列背屏增强工具 — 正式版 APK 发布仓库</strong><br>
  背屏投屏 · 歌词投影 · 星瑞车控 · WebDAV 配置云备份
</p>

<p align="center">
  <a href="https://github.com/wmqc97/MiRoot-Releases/releases/latest">
    <img src="https://img.shields.io/badge/download-V2.4-blue" alt="download"/>
  </a>
  <img src="https://img.shields.io/badge/version-2.4-blue" alt="version"/>
  <img src="https://img.shields.io/badge/API-28%2B-brightgreen" alt="API"/>
  <img src="https://img.shields.io/badge/license-Apache%202.0-green" alt="license"/>
</p>

---

## 关于

本仓库仅托管 [MiRoot](https://github.com/wmqc97/MiRoot) **正式版 APK**，不含源码。MiRoot 面向小米 17 Pro 等背屏手机，提供背屏应用投影、歌词、车控小组件、车辆历史、充电动画与 **WebDAV 配置云备份** 等功能。源码、文档与 Issue 请前往主仓库。

## 最新版本 · V2.4

| 项目 | 说明 |
|------|------|
| 版本号 | **2.4**（`versionCode` 38） |
| 安装包 | `MiRoot_2.4_Release.apk` |
| 标签 | [v2.4.0](https://github.com/wmqc97/MiRoot-Releases/releases/tag/v2.4.0) |

### V2.4 更新摘要

- **视频替换底包兼容旧版 `.backup` 后缀**：`.bak` 统一为视频主题专用备份；兼容旧版 `.backup` 自动迁移；`mv` 后增加校验 + `cp` fallback 保障 Shizuku 兼容
- **手势注入修复**：正则插值 bug 修正；新增幂等性检查避免重复注入

完整说明见主仓库 [docs/RELEASE_v2.4.md](https://github.com/wmqc97/MiRoot/blob/main/docs/RELEASE_v2.4.md)。

## 下载

👉 **[下载最新 Release APK](https://github.com/wmqc97/MiRoot-Releases/releases/latest)**

应用内「检查更新」指向本仓库 `releases/latest`。

## 安装

1. 下载并安装 Release APK（arm64-v8a）
2. 打开 MiRoot，在「状态」页完成 Root / Shizuku 等授权
3. 可选：状态页 → **配置云备份** 配置 WebDAV

## 环境要求

| 项目 | 要求 |
|------|------|
| Android | API 28+ (Android 9) |
| 架构 | arm64-v8a |
| Root / Shizuku | 部分功能需要（见主仓库文档） |

## 源码与反馈

- 源码：https://github.com/wmqc97/MiRoot  
- 问题反馈：请在 **MiRoot** 主仓库提交 Issue

## 许可证

[Apache License 2.0](https://github.com/wmqc97/MiRoot/blob/main/LICENSE)
