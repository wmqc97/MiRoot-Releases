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
    <img src="https://img.shields.io/badge/download-V3.7-blue" alt="download"/>
  </a>
  <img src="https://img.shields.io/badge/version-3.7-blue" alt="version"/>
  <img src="https://img.shields.io/badge/API-28%2B-brightgreen" alt="API"/>
  <img src="https://img.shields.io/badge/license-Apache%202.0-green" alt="license"/>
</p>

---

## 关于

本仓库仅托管 [MiRoot](https://github.com/wmqc97/MiRoot) **正式版 APK**，不含源码。MiRoot 面向小米 17 Pro 等背屏手机，提供背屏应用投影、歌词投影、车控增强、充电动画、WebDAV 配置云备份，以及可供 AI 客户端调用的 MCP HTTP/SSE 服务。源码、文档与 Issue 请前往主仓库。

## 最新版本 · V3.7

| 项目 | 说明 |
|------|------|
| 版本号 | **3.7**（`versionCode` 51） |
| 安装包 | `MiRoot_3.7_20260808_232028.apk` |

### V3.7 更新摘要

- **主题包管理流程重构**：一键扫描/预览/应用/编辑/注入手势，顶部新增「主题功能」菜单
- **应用主题可勾选注入手势**：已注入主题显示手势角标，系统主题编辑器内可随时关闭单主题手势
- **手势注入更通用**：注入块提升至最顶层，兼容新旧注入形态、可重复注入/移除，带全屏动画层的主题右边缘手势不再失效
- **指纹校验与解压缓存不依赖 Root/Shizuku**：注入后自动重建缓存，主题目录不再丢失
- **主题包联动安装 JS 脚本**：包内 UriRoute 脚本本地直装，无需文件系统与跨应用权限，Android 10+ 全兼容
- **全局 UI 统一**：主 Tab 与二级页面大标题风格统一，音乐页重构为仪表盘导航卡片 + 底部弹窗设置，删除确认弹窗统一为悬浮卡片风格
- **MCP 车控优化**：服务卡片改为信息密集仪表盘，工具按类型分组管理；车辆状态按需拉取 + 60 秒节流
- **歌词体验**：新增歌词边框特效卡片并适配深色模式，修复歌词设置弹窗滑动闪烁与随机色切换延迟

### V3.6 更新摘要

- **车控桌面小组件**：支持 2×2、6×2、6×4 三种尺寸，主屏直达车控
- **车控全面深色模式适配**：车辆状态统一解析为中文显示，账号头像上传对齐 GKUI 规范
- **歌词自适应**：歌词行数按背屏尺寸自动计算，新增行槽倍率、高亮渐变羽化与行切换提前量
- **状态页新增背屏歌词状态卡片**：实时查看歌词来源与分词状态

### V3.5 更新摘要

- **歌词滚动过渡**：换行使用缓出动画取代硬切跳转，滚动时长按距离自适应
- **逐字高亮修正**：修复换行瞬间下一行首字高亮跳过的时序偏差
- **修复**：MIXED 智能歌词源与 NETWORK_ONLY 模式换行 scrollY 硬切导致的视觉割裂

### V3.4 更新摘要

- **抖音视频解析下载**：分享链接可直接解析视频与图集，支持全屏预览、滑动切换与保存到本地
- **解析视频直接应用为背屏主题**：全屏播放页新增「应用为背屏视频主题」，跟随主题页的循环播放、声音与音量设置
- **常亮开关状态修复**：通知栏「停止」与外部广播不再改写「始终常亮」开关，仅停止本次服务
- **投屏常亮误关闭修复**：修复进入功能页时「投屏常亮」被静默写回关闭的问题

### V3.3 更新摘要

- **新增右侧三槽位手势**：背屏手势由三个扩展为六个，新增右边缘向左滑、向上滑、向下滑动作
- **WebDAV 自动云备份**：支持自动同步、多槽位轮换与冲突保护，提升云端备份可靠性
- **歌词与频谱修复**：修复歌词波浪、频谱、边缘光效及多项歌词视觉效果设置异常
- **主题与服务优化**：重新设计主题应用确认弹窗，增强主题工作目录、手势注入与持久服务稳定性，减少重复常驻通知
- **车控与其他修复**：合并重复停车历史记录，修复背屏遮挡感应路由、充电监听通知及其他已知问题

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
