# MiRoot MCP Server v2.0 — 小米 MiClaw 接入服务

通过 **小米 MiClaw（AI 小龙虾）** MCP 协议，将 MiRoot 手机功能注册为标准工具——让语音助手可以直接查询手机状态、控制车辆、操作媒体播放等。

> MiClaw 是小米 AI 推出的 MCP (Model Context Protocol) 框架，允许手机本地 HTTP 服务将能力暴露给 AI 助手调用。

---

## 架构概览

```
手机端 MiRoot MCP Server    ──SSE──>   小米 AI 小龙虾 (MiClaw)
                           <──POST──
  Flask + SSE /messages       JSON-RPC 2.0
  localhost:5000              tools/list, tools/call

  ┌─ 注册工具 ────────┐
  │ 🔋 电池状态 · 充电动画    │
  │ 📱 设备信息 · 屏幕控制    │
  │ 🚗 车况查询 · 远程指令    │
  │ 🎵 媒体播放 · 歌词查看    │
  └────────────────────────┘
```

## MiClaw 接入说明

MiClaw 的 MCP 使用 **SSE (Server-Sent Events)** 作为传输层，遵循 **JSON-RPC 2.0** 协议。本服务完全兼容该规范：

| 端点 | 方法 | 说明 |
|---|---|---|
| `/sse` | GET | SSE 事件流端点，MiClaw 从此订阅服务端推送 |
| `/messages?sessionId=` | POST | JSON-RPC 消息端点，接收 MiClaw 的调用请求 |
| `/health` | GET | 健康检查 |

### 注册到 MiClaw

在 MiClaw 平台注册 MCP Server 时，填入：

```
名称：MiRoot 手机助手
SSE 端点：http://<handphoneIP>:5000/sse
认证：可选（启用 MIROOT_MCP_AUTH_ENABLED 后需配置 API Key）
```

## 工具清单

本服务共注册 **10 个工具**，覆盖四大功能领域：

### 🔋 电池与充电

| 工具 | 功能 | 语音示例 |
|---|---|---|
| `get_battery_status` | 查询电池状态（电量、充电状态、温度、电压、健康度） | “帮我看看手机还剩多少电” |
| `set_charging_animation` | 设置充电动画（水波纹/渐变/图案风格+颜色） | “把充电动画改成水波纹蓝色” |
| `get_battery_history` | 获取充放电历史、循环次数、健康趋势 | “最近一周的充电记录” |

### 📱 设备与屏幕

| 工具 | 功能 | 语音示例 |
|---|---|---|
| `get_device_info` | 查询设备信息（型号、系统、WiFi、蓝牙、存储、内存、屏幕） | “手机还剩多少存储” |
| `control_screen` | 控制主屏/背屏（亮度调节、息屏亮屏、背屏模式切换） | “把屏幕亮度调到50” |

### 🚗 星瑾车控

| 工具 | 功能 | 语音示例 |
|---|---|---|
| `get_car_status` | 查询车辆实时状态（发动机、门锁、车窗、胎压、油量、里程） | “车现在什么状态” |
| `get_car_history` | 获取车辆历史数据（油耗统计、里程趋势、行程分析） | “最近一周的油耗怎么样” |
| `execute_car_command` | 执行车控指令（锁车/解锁、开关窗、开关后备箱、启停引擎、闪灯鸣笝） | “帮我把车门锁上” |

### 🎵 媒体播放

| 工具 | 功能 | 语音示例 |
|---|---|---|
| `get_now_playing` | 获取当前播放的媒体信息（歌曲名、歌手、歌词、播放进度） | “现在在放什么歌” |
| `control_media_playback` | 播放控制（播放/暂停/上一曲/下一曲） | “下一首” / “暂停播放” |

## 快速开始

```bash
cd miroot-mcp-server
pip install -r requirements.txt
python server.py
```

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MIROOT_MCP_PORT` | 5000 | 服务监听端口 |
| `MIROOT_MCP_HOST` | 0.0.0.0 | 监听地址 |
| `MIROOT_MCP_DEBUG` | false | 开启调试日志 |
| `MIROOT_MCP_MOCK` | true | Mock 模式（无需真机即可开发和测试） |
| `MIROOT_MCP_AUTH_ENABLED` | false | 启用 API Key 认证 |
| `MIROOT_MCP_API_KEY` | (无) | API 密钥 |

## 测试

```bash
# 健康检查
curl http://localhost:5000/health

# 列出所有工具
curl -X POST http://localhost:5000/messages \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'

# 调用电池状态工具
curl -X POST http://localhost:5000/messages \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_battery_status","arguments":{}}}'

# 调用车控指令
curl -X POST http://localhost:5000/messages \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"execute_car_command","arguments":{"command":"lock_doors"}}}'
```

## 部署到手机

1. 将 ​`miroot-mcp-server/` 部署到手机的 Linux 环境（推荐 Termux）
2. 设置 ​`MIROOT_MCP_MOCK=false` 以对接真机系统 API
3. 确保手机与 MiClaw 在同一局域网
4. 在 MiClaw 平台注册 MCP Server，填入手机 IP 和端口
5. 即可通过语音助手直接操控手机和车辆

## 技术栈

- **Flask** — HTTP + SSE 服务
- **JSON-RPC 2.0** — MCP 协议消息格式
- **SSE (Server-Sent Events)** — 服务端推送通道
- **Python 3.10+** — 运行环境
