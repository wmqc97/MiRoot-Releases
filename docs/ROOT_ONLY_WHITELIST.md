# MiRoot 权限执行统一与 ROOT-ONLY 白名单

本文档用于说明 MiRoot 当前“ROOT 优先 + Shizuku 兼容”落地结果，以及必须保留 ROOT 独占的场景。

## 统一执行标准

- 标准入口：`PrivilegedShell.execCmd(cmd)`
- 统一策略：先尝试 ROOT（`su -c`），失败自动回退 Shizuku
- 适用范围：屏幕控制、进程控制、文件操作、系统设置写入、状态读取

> 说明：对于“需要读取实时 stdout/stderr 或保持异步子进程句柄”的场景，使用 `PrivilegedShell.startShell(...)`。该入口与 `execCmd` 使用同一权限选路策略（ROOT 优先，失败回退 Shizuku），仅返回形态不同，不改变业务行为。

## ROOT-ONLY 白名单（必须 Root）

以下能力保留 ROOT 独占，统一通过 `PrivilegedShell.startRootShellOnly(...)`：

1. `TaskService.executeShellCommandAsRoot(...)`
2. `TaskService.executeShellCommandWithResultAsRoot(...)`
3. 主题/壁纸中对他应用私有路径、敏感路径的“强制 root 优先”调用链：
   - `AiWallpaperThemeHelper.shellResultRootFirst(...)`（首选 `executeShellCommandWithResultAsRoot`）
   - `AiWallpaperThemeHelper.shellCommandRootFirst(...)`（首选 `executeShellCommandAsRoot`）

上述场景涉及高权限文件访问与敏感路径操作（如他应用目录、主题管理器相关路径），Shizuku 在部分 ROM/SELinux 策略下不保证完全等价，因此保留 root-only 通道。

## ROOT + Shizuku 自动兼容（非白名单默认）

以下能力均应走统一权限层自动选路（ROOT 优先，失败回退 Shizuku）：

- DPI 修改与恢复（`wm density`）
- 屏幕旋转与显示控制（`wm user-rotation` / `settings` / `am`）
- 背屏窗口与任务迁移（`am task move-task-to-display` 等）
- 进程控制（`am force-stop`、`ps`、`kill`）
- 媒体与截图链路中的命令执行（`screencap`、`am broadcast`、`mkdir/cp/rm`）
- 日志与状态读取（`dumpsys`、`getprop` 等）

## 本轮已完成落地项

- `PrivilegedShell` 新增统一布尔执行入口：`execCmd(cmd)`
- `ShellCompat` 执行实现统一代理到 `PrivilegedShell`
- `TaskService` root-only 入口改为直接使用 `PrivilegedShell.startRootShellOnly(...)`
- 现有业务逻辑（歌词、解析、投屏、显示、渲染、通知、分词、SuperLyric 兜底）未改动，仅替换底层权限执行入口
