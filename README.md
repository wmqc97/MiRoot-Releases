<p align="center">
  <img src="MiRoot.png" alt="MiRoot" width="128"/>
</p>

<h1 align="center">MiRoot Releases</h1>

<p align="center">
  <strong>小米 17 Pro 系列背屏增强工具 — 正式版 APK 发布仓库</strong><br>
  背屏投屏 · 歌词投影 · 车控增强 · MCP 服务 · 配置云备份
</p>

<p align="center">
  <a href="https://github.com/wmqc97/MiRoot-Releases/releases/latest">
    <img src="https://img.shields.io/badge/download-V3.1-blue" alt="download"/>
  </a>
  <img src="https://img.shields.io/badge/version-3.1-blue" alt="version"/>
  <img src="https://img.shields.io/badge/API-28%2B-brightgreen" alt="API"/>
  <img src="https://img.shields.io/badge/license-Apache%202.0-green" alt="license"/>
</p>

---

## 关于

本仓库仅托管 [MiRoot](https://github.com/wmqc97/MiRoot) **正式版 APK**，不含源码。MiRoot 面向小米 17 Pro 等背屏手机，提供背屏应用投影、歌词投影、车控增强、充电动画、WebDAV 配置云备份，以及可供 AI 客户端调用的 MCP HTTP/SSE 服务。源码、文档与 Issue 请前往主仓库。

## 最新版本 · V3.1

| 项目 | 说明 |
|------|------|
| 版本号 | **3.1**（`versionCode` 45） |
| 安装包 | `MiRoot_3.1_*.apk` |

### V3.1 更新摘要

- **背屏歌词与音乐投影增强**：优化主屏/背屏歌词切换、歌词状态同步、网络歌词匹配与 SuperLyric 解析
- **视觉效果设置完善**：新增/整理摄像头环指示、频谱可视化、边缘光效、专辑图背景与歌词显示预设
- **车控与 MCP 服务增强**：AI 客户端可通过 MCP HTTP/SSE 查询车辆状态、控制车辆、打开背屏桌面/歌词/小游戏、查询油耗与健康报告
- **资源与构建发布整理**：重整车控与背屏机型资源，优化 Release 包命名、配置云备份、权限检查、开源声明与稳定性细节

完整说明见主仓库 [README](https://github.com/wmqc97/MiRoot/blob/main/README.md)。

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
