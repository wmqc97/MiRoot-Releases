# ============================================================
# MiRoot MCP Server — Configuration
# 手机本地 HTTP 服务接入 MCP (小米 AI 小龙虾 / MiClaw)
# ============================================================

import os

class Config:
    # 服务端口
    PORT = int(os.environ.get("MIROOT_MCP_PORT", 5000))
    HOST = os.environ.get("MIROOT_MCP_HOST", "0.0.0.0")

    # 调试模式
    DEBUG = os.environ.get("MIROOT_MCP_DEBUG", "false").lower() == "true"

    # ============ 安全 ============
    # API_KEY 认证（开发时可设为 None 跳过）
    API_KEY = os.environ.get("MIROOT_MCP_API_KEY", None)
    # 安全模式：开发时设为 False，生产必须开启
    AUTH_ENABLED = os.environ.get("MIROOT_MCP_AUTH_ENABLED", "false").lower() == "true"

    # ============ MCP 协议 ============
    MCP_PROTOCOL_VERSION = "2025-03-26"
    SERVER_NAME = "miroot-mcp-server"
    SERVER_VERSION = "2.0.0"

    # ============ SSE ============
    SSE_KEEPALIVE_TIMEOUT = 30  # 心跳超时（秒）

    # ============ 手机本地模拟 ============
    # 实际部署时，这些值来自 Android 系统 API
    # 开发模式下使用模拟数据
    MOCK_MODE = os.environ.get("MIROOT_MCP_MOCK", "true").lower() == "true"
