# MiRoot V2.4 正式版发布说明

## 下载

- APK：[MiRoot-Releases v2.4.0](https://github.com/wmqc97/MiRoot-Releases/releases/tag/v2.4.0)
- 源码：[MiRoot v2.4.0](https://github.com/wmqc97/MiRoot/releases/tag/v2.4.0)

## 更新摘要

### 新增

- **视频替换底包兼容旧版 `.backup` 后缀**：`.bak` 统一为视频主题专用备份格式；优先查找 `.bak`，兼容旧版 `.backup`（自动迁移为 `.bak`）；创建备份前检查两种后缀避免覆盖；`mv` 后增加 `rootFileExists` 校验 + `cp` fallback 保障 Shizuku 兼容

### 修复

- **手势注入正则 bug**：`removeGestureFromVarConfig` 中 Kotlin 字符串模板插值错误导致 `OnOff` name 属性正则匹配失败，现已修正为 `["']${name}["']` 正确匹配双单引号
- **手势注入幂等性**：`patchManifestForGesture` 与 `patchVarConfigForGesture` 新增清理后检查，避免 `runCatching` 静默失败时重复注入导致数据重复写入

## 安装包

| 文件 | 说明 |
|------|------|
| `MiRoot_2.4_Release.apk` | 正式签名 Release（arm64-v8a） |

`versionCode`: 38 · `versionName`: 2.4
