package com.wmqc.miroot.car

import android.content.Context
import android.content.Intent
import com.wmqc.miroot.lyrics.LogHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.wmqc.miroot.lyrics.MusicProjectionService

/**
 * HTTP + MCP/SSE 服务器.
 * 同时支持:
 * 1. 传统 REST API (/api/v1/...)
 * 2. MCP HTTP/SSE 传输协议 (GET /sse, POST /message, OPTIONS)
 */
class CarControlHttpServer(
    private val context: Context,
    private val port: Int,
    private val apiKey: String,
) {
    companion object {
        private const val TAG = "CarControlHttpServer"

        @JvmStatic
        fun getLocalIpAddress(): String {
            try {
                val niEnum = NetworkInterface.getNetworkInterfaces()
                while (niEnum.hasMoreElements()) {
                    val ni = niEnum.nextElement()
                    if (ni.isLoopback || !ni.isUp) continue
                    val name = ni.name.lowercase()
                    if (name.contains("wlan") || name.contains("eth") || name.contains("rndis")) {
                        val addrs = ni.inetAddresses
                        while (addrs.hasMoreElements()) {
                            val a = addrs.nextElement()
                            if (!a.isLoopbackAddress && a is java.net.Inet4Address) {
                                return a.hostAddress ?: ""
                            }
                        }
                    }
                }
            } catch (_: SocketException) {}
            return "127.0.0.1"
        }

        // MCP JSON-RPC 工具定义
        private val MCP_TOOLS: JSONArray
            get() = JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "vehicle_control")
                    put("description", "控制车辆功能：解锁/上锁/寻车/开后备箱/启停引擎/开关窗/通风/空调/座椅加热/导航")
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("function", JSONObject().apply {
                                put("type", "string")
                                put("description", "控制功能")
                                put("enum", JSONArray().apply {
                                    put("unlock"); put("lock"); put("findCar"); put("openTrunk")
                                    put("startEngine"); put("stopEngine"); put("openWindow"); put("closeWindow")
                                    put("ventilate"); put("openAirConditioner"); put("closeAirConditioner")
                                    put("openSeatHeating"); put("closeSeatHeating"); put("navigateToCar")
                                })
                            })
                            put("durationMinutes", JSONObject().apply {
                                put("type", "integer")
                                put("description", "持续分钟数")
                                put("default", 10)
                            })
                            put("temperature", JSONObject().apply {
                                put("type", "integer")
                                put("description", "温度设定")
                                put("default", 24)
                            })
                            put("level", JSONObject().apply {
                                put("type", "integer")
                                put("description", "档位")
                                put("default", 2)
                            })
                        })
                        put("required", JSONArray().apply { put("function") })
                    })
                })
                put(JSONObject().apply {
                    put("name", "projection_control")
                    put("description", "启动或停止后排屏幕投屏控制界面")
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("action", JSONObject().apply {
                                put("type", "string")
                                put("description", "操作: start=启动, stop=停止")
                                put("enum", JSONArray().apply { put("start"); put("stop") })
                            })
                        })
                        put("required", JSONArray().apply { put("action") })
                    })
                })
                put(JSONObject().apply {
                    put("name", "vehicle_status")
                    put("description", "获取车辆当前状态")
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
                put(JSONObject().apply {
                    put("name", "rear_lyrics")
                    put("description", "启动或停止背屏歌词显示")
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("action", JSONObject().apply {
                                put("type", "string")
                                put("description", "操作: start=启动歌词显示, stop=停止歌词显示")
                                put("enum", JSONArray().apply { put("start"); put("stop") })
                            })
                        })
                        put("required", JSONArray().apply { put("action") })
                    })
                })
                put(JSONObject().apply {
                    put("name", "rear_desktop")
                    put("description", "打开背屏桌面")
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
                put(JSONObject().apply {
                    put("name", "rear_truth_dare")
                    put("description", "打开背屏真心话大冒险游戏")
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
                put(JSONObject().apply {
                    put("name", "rear_balance_ball")
                    put("description", "打开背屏平衡球游戏")
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
                put(JSONObject().apply {
                    put("name", "rear_charging_animation")
                    put("description", "预览或停止背屏充电动画")
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("action", JSONObject().apply {
                                put("type", "string")
                                put("description", "操作: start=预览充电动画, stop=停止充电动画")
                                put("enum", JSONArray().apply { put("start"); put("stop") })
                            })
                        })
                        put("required", JSONArray().apply { put("action") })
                    })
                })
            }
    }
        // MCP Server info for initialize handshake
        private val MCP_SERVER_INFO = JSONObject().apply {
            put("protocolVersion", "2025-03-26")
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject().apply {
                    put("listChanged", false)
                })
            })
            put("serverInfo", JSONObject().apply {
                put("name", "miroot-mcp-server")
                put("version", "1.0.0")
            })
        }


    private var serverSocket: ServerSocket? = null
    @Volatile
    var isRunning: Boolean = false
        private set
    private var serverThread: Thread? = null
    @Volatile
    var actualPort: Int = 0
        private set

    private val sseSessions = ConcurrentHashMap<String, SseSession>()

    private class SseSession(val sessionId: String, val out: OutputStream) {
        @Volatile
        var active: Boolean = true

        fun sendEvent(event: String, data: String) {
            try {
                val msg = "event: $event\r\ndata: $data\r\n\r\n"
                out.write(msg.toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (_: Exception) {
                active = false
            }
        }

        fun sendMessage(json: String) = sendEvent("message", json)

        fun close() {
            active = false
            try { out.close() } catch (_: Exception) {}
        }
    }

    fun start(): Boolean {
        if (isRunning) return true
        // 先尝试配置的端口，如果被占用则自动选空闲端口
        val ports = listOf(port, 8080, 8081, 9090, 42799)
        for (p in ports) {
            try {
                serverSocket = ServerSocket(p)
                // 如果配置端口和实际端口不同，记住实际端口
                if (p != port) {
                    // 通过 ServerSocket 的 localPort 获取实际端口
                }
                isRunning = true
                // 更新 mcpServerPort 为实际端口
                this.actualPort = serverSocket!!.localPort
                serverThread = Thread({ serverLoop() }, "CarControlHttpServer")
                serverThread!!.isDaemon = true
                serverThread!!.start()
                LogHelper.d(TAG, "Server started on port " + actualPort)
                return true
            } catch (_: Exception) {
                // 端口被占用，尝试下一个
                continue
            }
        }
        LogHelper.e(TAG, "Failed to start server: all ports in use")
        isRunning = false
        return false
    }

    fun stop() {
        isRunning = false
        sseSessions.values.forEach { it.close() }
        sseSessions.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        LogHelper.d(TAG, "Server stopped")
    }

    private fun serverLoop() {
        try {
            while (isRunning) {
                val client: Socket = try {
                    serverSocket?.accept() ?: break
                } catch (e: Exception) {
                    if (isRunning) LogHelper.w(TAG, "Accept error: " + e.message)
                    break
                }
                // Each connection on its own thread so SSE does not block POST
                Thread({ handleClient(client) }, "mcp-" + client.inetAddress.hostAddress).start()
            }
        } finally {
            stop()
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val sock = client
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            if (requestLine.isBlank()) return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val pathOnly = if (fullPath.contains("?")) fullPath.substring(0, fullPath.indexOf("?")) else fullPath
            val queryStr = if (fullPath.contains("?")) fullPath.substring(fullPath.indexOf("?") + 1) else ""

            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: break
                if (l.isBlank()) break
                val colon = l.indexOf(':')
                if (colon > 0) {
                    headers[l.substring(0, colon).trim().lowercase()] = l.substring(colon + 1).trim()
                }
            }
            val bodyLen = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (bodyLen > 0) {
                val buf = CharArray(bodyLen)
                var total = 0
                while (total < bodyLen) {
                    val n = reader.read(buf, total, bodyLen - total)
                    if (n < 0) break
                    total += n
                }
                String(buf, 0, total)
            } else "" 

            when {
                method == "OPTIONS" -> { sendOptionsResponse(sock) }
                method == "GET" && pathOnly == "/sse" -> { handleSseConnect(sock); return }
                method == "POST" && (pathOnly == "/message" || pathOnly == "/messages" || pathOnly == "/sse") -> {
                    val sessionId = parseQueryParam(queryStr, "sessionId") ?: ""
                    handleMcpMessage(sock, sessionId, body)
                }
                method == "GET" && pathOnly == "/api/v1/health" ->
                    sendJson(sock, 200, JSONObject().apply {
                        put("status", "ok"); put("service", "miroot-mcp-sse")
                    })
                method == "GET" && pathOnly == "/api/v1/vehicle/status" -> handleVehicleStatus(sock)
                method == "POST" && pathOnly == "/api/v1/vehicle/control" -> handleVehicleControl(sock, body)
                method == "POST" && pathOnly == "/api/v1/projection/start" -> handleProjectionStart(sock)
                method == "POST" && pathOnly == "/api/v1/projection/stop" -> handleProjectionStop(sock)
                else -> sendJson(sock, 404, JSONObject().put("error", "Not found"))
            }
            try { sock.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            LogHelper.w(TAG, "handleClient error: " + e.message)
        }
    }

    private fun parseQueryParam(query: String, name: String): String? {
        for (part in query.split("&")) {
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq) == name) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            }
        }
        return null
    }

    // ========== MCP SSE ==========

    private fun handleSseConnect(sock: Socket) {
        val sessionId = UUID.randomUUID().toString().take(8)
        try {
            val out = sock.getOutputStream()
            val session = SseSession(sessionId, out)
            sseSessions[sessionId] = session

            val httpHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Headers: x-api-key, content-type\r\n" +
                "\r\n"
            out.write(httpHeaders.toByteArray(Charsets.US_ASCII))
            out.flush()

            val endpointData = "/messages?sessionId=$sessionId"
            session.sendEvent("endpoint", endpointData)
            LogHelper.d(TAG, "SSE connected: $sessionId")

            val buf = ByteArray(1)
            while (session.active && isRunning) {
                try {
                    sock.soTimeout = 15000
                    val n = sock.getInputStream().read(buf)
                    if (n < 0) break
                } catch (_: java.net.SocketTimeoutException) {
                    try {
                        val hb = ": heartbeat\r\n\r\n"
                        out.write(hb.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } catch (_: Exception) { break }
                } catch (_: Exception) { break }
            }
        } catch (_: Exception) {
            LogHelper.w(TAG, "SSE connection error: $sessionId")
        } finally {
            sseSessions.remove(sessionId)
            try { sock.close() } catch (_: Exception) {}
            LogHelper.d(TAG, "SSE disconnected: $sessionId")
        }
    }

                private fun handleMcpMessage(sock: Socket, sessionId: String, body: String) {
        val session = if (sessionId.isNotEmpty()) sseSessions[sessionId] else null

        // Streamable HTTP mode: no SSE session needed, respond directly via HTTP
        if (session == null || !session.active) {
            // Streamable HTTP: no SSE session, respond via HTTP directly
            processMcpRequest(sock, body, null)
            return
        }
        // SSE mode: respond via SSE then HTTP 202
        try {
            val req = JSONObject(body)
            val method_ = req.optString("method", "")
            val id = req.opt("id")
            val isNotification = (id == null || id == JSONObject.NULL)

            if (method_ == "notifications/initialized") {
                LogHelper.d(TAG, "MCP initialized notification received")
                sendJson(sock, 202, JSONObject().apply { put("jsonrpc", "2.0") })
                return
            }

            fun respond(result: JSONObject?, error: JSONObject?) {
                val resp = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    if (!isNotification) put("id", id)
                    if (result != null) put("result", result)
                    if (error != null) put("error", error)
                }
                session.sendMessage(resp.toString())
                sendJson(sock, 202, JSONObject().apply { put("jsonrpc", "2.0") })
            }

            when (method_) {
                "initialize" -> {
                    val clientInfo = req.optJSONObject("params")?.optJSONObject("clientInfo")
                    val cn = clientInfo?.optString("name", "?") ?: "?"
                    val cv = clientInfo?.optString("version", "?") ?: "?"
                    LogHelper.d(TAG, "MCP initialize from: " + cn + " v" + cv)
                    respond(MCP_SERVER_INFO, null)
                }
                "ping" -> {
                    respond(JSONObject().apply { put("status", "ok") }, null)
                }
                "tools/list" -> {
                    val result = JSONObject().apply { put("tools", MCP_TOOLS) }
                    respond(result, null)
                }
                "tools/call" -> {
                    val params = req.optJSONObject("params")
                    val name = params?.optString("name", "") ?: ""
                    val args = params?.optJSONObject("arguments") ?: JSONObject()
                    val result = executeMcpTool(name, args)
                    respond(result, null)
                }
                else -> {
                    respond(null, JSONObject().apply {
                        put("code", -32601)
                        put("message", "Method not found: " + method_)
                    })
                }
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "MCP message error: " + e.message)
            sendJson(sock, 400, JSONObject().put("error", e.message ?: "Bad request"))
        }
    }

    private fun processMcpRequest(sock: Socket, body: String, sseSession: SseSession?) {
        try {
            val req = JSONObject(body)
            val method_ = req.optString("method", "")
            val id = req.opt("id")
            val isNotification = (id == null || id == JSONObject.NULL)

            if (method_ == "notifications/initialized") {
                LogHelper.d(TAG, "MCP initialized (Streamable)")
                sendJson(sock, 202, JSONObject().apply { put("jsonrpc", "2.0") })
                return
            }

            fun sendResponse(result: JSONObject?, error: JSONObject?) {
                val resp = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    if (!isNotification) put("id", id)
                    if (result != null) put("result", result)
                    if (error != null) put("error", error)
                }
                // In Streamable HTTP, also push via SSE if session is active
                if (sseSession?.active == true) {
                    sseSession.sendMessage(resp.toString())
                }
                val httpStatus = if (isNotification) 202 else 200
                sendJson(sock, httpStatus, resp)
            }

            when (method_) {
                "initialize" -> {
                    val clientInfo = req.optJSONObject("params")?.optJSONObject("clientInfo")
                    val cn = clientInfo?.optString("name", "?") ?: "?"
                    val cv = clientInfo?.optString("version", "?") ?: "?"
                    LogHelper.d(TAG, "MCP initialize (Streamable) from: " + cn + " v" + cv)
                    sendResponse(MCP_SERVER_INFO, null)
                }
                "ping" -> {
                    sendResponse(JSONObject().apply { put("status", "ok") }, null)
                }
                "tools/list" -> {
                    sendResponse(JSONObject().apply { put("tools", MCP_TOOLS) }, null)
                }
                "tools/call" -> {
                    val params = req.optJSONObject("params")
                    val name = params?.optString("name", "") ?: ""
                    val args = params?.optJSONObject("arguments") ?: JSONObject()
                    val result = executeMcpTool(name, args)
                    sendResponse(result, null)
                }
                else -> {
                    sendResponse(null, JSONObject().apply {
                        put("code", -32601)
                        put("message", "Method not found: " + method_)
                    })
                }
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "MCP process error: " + e.message)
            sendJson(sock, 400, JSONObject().put("error", e.message ?: "Bad request"))
        }
    }


private fun executeMcpTool(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "vehicle_control" -> {
                    val fn = args.optString("function", "").lowercase()
                    val dm = args.optInt("durationMinutes", 10)
                    val tp = args.optInt("temperature", 24)
                    val lv = args.optInt("level", 2)
                    if (fn.isEmpty()) {
                        return JSONObject().apply {
                            put("isError", true)
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", "Missing function parameter")
                                })
                            })
                        }
                    }
                    val r = when (fn) {
                        "unlock" -> VehicleControlService.unlock(context)
                        "lock" -> VehicleControlService.lock(context)
                        "findcar" -> VehicleControlService.findCar(context)
                        "opentrunk" -> VehicleControlService.openTrunk(context)
                        "startengine" -> VehicleControlService.startEngine(context, dm)
                        "stopengine" -> VehicleControlService.stopEngine(context)
                        "openwindow" -> VehicleControlService.openWindow(context)
                        "closewindow" -> VehicleControlService.closeWindow(context)
                        "ventilate" -> VehicleControlService.ventilate(context)
                        "openairconditioner" -> VehicleControlService.openAirConditioner(context, dm, tp)
                        "closeairconditioner" -> VehicleControlService.closeAirConditioner(context)
                        "openseatheating" -> VehicleControlService.openSeatHeating(context, dm, lv)
                        "closeseatheating" -> VehicleControlService.closeSeatHeating(context)
                        "navigatetocar" -> VehicleControlService.navigateToCar(context)
                        else -> VehicleControlService.ControlResult().apply { success = false; message = "Unknown: $fn" }
                    }
                    JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", if (r.success) "OK: " + (r.message ?: fn) else "Error: " + (r.message ?: "Unknown"))
                            })
                        })
                    }
                }
                "projection_control" -> {
                    val action = args.optString("action", "")
                    val i = Intent()
                    i.setClassName(context, "com.wmqc.miroot.car.CarControlProjectionService")
                    if (action == "start") {
                        i.action = CarControlIntents.ACTION_OPEN_CAR_CONTROL_PROJECTION
                        i.putExtra(CarControlIntents.EXTRA_CAR_PROJECTION_OP, CarControlIntents.VALUE_CAR_PROJECTION_OP_START)
                        context.startService(i)
                        JSONObject().apply {
                            put("content", JSONArray().apply {
                                put(JSONObject().apply { put("type", "text"); put("text", "Projection started") })
                            })
                        }
                    } else {
                        i.action = CarControlIntents.ACTION_STOP_CAR_CONTROL_PROJECTION
                        context.startService(i)
                        JSONObject().apply {
                            put("content", JSONArray().apply {
                                put(JSONObject().apply { put("type", "text"); put("text", "Projection stopped") })
                            })
                        }
                    }
                }
                "vehicle_status" -> {
                    val info = VehicleStatusService.getVehicleStatus(context)
                    val basicInfo = VehicleStatusService.getVehicleBasicInfo(context)
                    
                    val statusText = StringBuilder()
                    statusText.append("【车辆信息】").append("\n")
                    if (basicInfo != null) {
                        statusText.append("车牌: ").append(basicInfo.plateNo).append("\n")
                        statusText.append("型号: ").append(basicInfo.modelName).append("\n")
                        statusText.append("系列: ").append(basicInfo.seriesName).append("\n")
                        statusText.append("颜色: ").append(basicInfo.colorName).append("\n")
                    }
                    statusText.append("\n【车辆状态】").append("\n")
                    statusText.append("发动机: ").append(VehicleStatusService.translateEngineStatus(info?.engineStatus ?: "未知")).append("\n")
                    statusText.append("门锁: ").append(VehicleStatusService.translateDoorLockStatus(info?.doorLockStatusDriver ?: "未知")).append("\n")
                    statusText.append("总里程: ").append(info?.odometer ?: "未知").append(" km\n")
                    statusText.append("剩余续航: ").append(info?.distanceToEmpty ?: "未知").append(" km\n")
                    statusText.append("油量: ").append(info?.fuelLevel ?: "未知").append(" L\n")
                    statusText.append("油量百分比: ").append(info?.fuelLevelStatus ?: "未知").append("\n")
                    statusText.append("电瓶电压: ").append(info?.voltage ?: "未知").append(" V\n")
                    statusText.append("平均油耗: ").append(info?.aveFuelConsumption ?: "未知").append(" L/100km\n")
                    statusText.append("平均时速: ").append(info?.avgSpeed ?: "未知").append(" km/h\n")
                    statusText.append("车内温度: ").append(info?.interiorTemp ?: "未知").append(" ℃\n")
                    statusText.append("车外温度: ").append(info?.exteriorTemp ?: "未知").append(" ℃\n")
                    statusText.append("更新时间: ").append(info?.updateDateTime ?: "未知")
                    
                    JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", statusText.toString())
                            })
                        })
                    }
                }
                "rear_lyrics" -> {
                    val action = args.optString("action", "start")
                    val intent = Intent(context, MusicProjectionService::class.java).apply {
                        this.action = "com.wmqc.miroot.lyrics.ACTION_OPEN_MUSIC_PROJECTION"
                        putExtra("com.wmqc.miroot.lyrics.EXTRA_MUSIC_PROJECTION_OP",
                            if (action == "stop") "stop" else "start")
                    }
                    context.startService(intent)
                    val msg = if (action == "stop") "背屏歌词已停止" else "背屏歌词已启动"
                    JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", msg) })
                        })
                    }
                }
                "rear_desktop" -> {
                    com.wmqc.miroot.rear.desktop.RearDesktopLaunchHelper.requestOpenDesktop(context)
                    JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", "背屏桌面已打开") })
                        })
                    }
                }
                "rear_truth_dare" -> {
                    com.wmqc.miroot.rear.truthdare.RearTruthDareWheelLaunchHelper.requestOpenTruthDareWheel(context)
                    JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", "背屏真心话大冒险已启动") })
                        })
                    }
                }
                "rear_balance_ball" -> {
                    com.wmqc.miroot.rear.balance.RearBalanceGameLaunchHelper.requestOpenBalanceGame(context)
                    JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", "背屏平衡球已启动") })
                        })
                    }
                }
                "rear_charging_animation" -> {
                    val action = args.optString("action", "start")
                    val intent = Intent(context, com.wmqc.miroot.charging.ChargingService::class.java).apply {
                        this.action = "com.wmqc.miroot.charging.PREVIEW_CHARGING_ANIMATION"
                    }
                    context.startService(intent)
                    // Small delay to let ChargingService register its receiver
                    try { Thread.sleep(200) } catch (_: Exception) {}
                    if (action == "stop") {
                        context.sendBroadcast(
                            Intent("com.wmqc.miroot.charging.ACTION_FINISH_CHARGING_ANIMATION")
                                .setPackage(context.packageName))
                        JSONObject().apply {
                            put("content", JSONArray().apply {
                                put(JSONObject().apply { put("type", "text"); put("text", "充电动画已停止") })
                            })
                        }
                    } else {
                        context.sendBroadcast(
                            Intent("com.wmqc.miroot.charging.PREVIEW_CHARGING_ANIMATION")
                                .setPackage(context.packageName))
                        JSONObject().apply {
                            put("content", JSONArray().apply {
                                put(JSONObject().apply { put("type", "text"); put("text", "充电动画预览已启动") })
                            })
                        }
                    }
                }
                else -> JSONObject().apply {
                    put("isError", true)
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text"); put("text", "Tool not found: $name")
                        })
                    })
                }
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "executeMcpTool error: " + e.message)
            JSONObject().apply {
                put("isError", true)
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text"); put("text", "Error: " + (e.message ?: "Unknown"))
                    })
                })
            }
        }
    }

    // ========== REST API ==========

    private fun sendOptionsResponse(sock: Socket) {
        try {
            val sb = StringBuilder()
            sb.append("HTTP/1.1 204 No Content\r\n")
            sb.append("Access-Control-Allow-Origin: *\r\n")
            sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            sb.append("Access-Control-Allow-Headers: x-api-key, content-type, authorization\r\n")
            sb.append("Access-Control-Max-Age: 86400\r\n")
            sb.append("Content-Length: 0\r\n")
            sb.append("Connection: close\r\n\r\n")
            val out = sock.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            out.flush()
        } catch (_: Exception) {}
    }

    private fun sendJson(sock: Socket, status: Int, json: JSONObject) {
        sendResponse(sock, status, json.toString())
    }

    private fun sendResponse(sock: Socket, status: Int, body: String) {
        try {
            val st = when (status) {
                200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"
                401 -> "Unauthorized"; 404 -> "Not Found"; 500 -> "Internal Server Error"
                else -> "Unknown"
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(status).append(" ").append(st).append("\r\n")
            sb.append("Content-Type: application/json; charset=utf-8\r\n")
            sb.append("Content-Length: ").append(bytes.size).append("\r\n")
            sb.append("Access-Control-Allow-Origin: *\r\n")
            sb.append("Access-Control-Allow-Headers: x-api-key, content-type\r\n")
            sb.append("Connection: close\r\n\r\n")
            val out = sock.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {}
    }

    private fun handleVehicleStatus(sock: Socket) {
        try {
            val info = VehicleStatusService.getVehicleStatus(context)
            sendJson(sock, 200, JSONObject().apply {
                put("success", true)
                put("engine", info?.engineStatus ?: "")
                put("odometer", info?.odometer ?: "")
                put("fuel", info?.fuelLevel ?: "")
            })
        } catch (e: Exception) {
            LogHelper.w(TAG, "status error: " + e.message)
            sendJson(sock, 500, JSONObject().apply {
                put("success", false); put("message", e.message ?: "Unknown")
            })
        }
    }

    private fun handleVehicleControl(sock: Socket, body: String) {
        try {
            val j = JSONObject(body)
            val fn = j.optString("function", "").lowercase()
            val dm = j.optInt("durationMinutes", 0)
            val tp = j.optInt("temperature", 0)
            val lv = j.optInt("level", 0)
            if (fn.isEmpty()) {
                sendJson(sock, 400, JSONObject().apply {
                    put("success", false); put("message", "Missing function")
                })
                return
            }
            val r = when (fn) {
                "unlock" -> VehicleControlService.unlock(context)
                "lock" -> VehicleControlService.lock(context)
                "findcar", "find", "horn" -> VehicleControlService.findCar(context)
                "opentrunk", "trunk" -> VehicleControlService.openTrunk(context)
                "startengine" -> VehicleControlService.startEngine(context, if (dm > 0) dm else 10)
                "stopengine" -> VehicleControlService.stopEngine(context)
                "openwindow" -> VehicleControlService.openWindow(context)
                "closewindow" -> VehicleControlService.closeWindow(context)
                "ventilate" -> VehicleControlService.ventilate(context)
                "openairconditioner", "openac", "ac_on" -> VehicleControlService.openAirConditioner(context, if (dm > 0) dm else 10, if (tp > 0) tp else 24)
                "closeairconditioner", "closeac", "ac_off" -> VehicleControlService.closeAirConditioner(context)
                "openseatheating", "seatheating" -> VehicleControlService.openSeatHeating(context, if (dm > 0) dm else 10, if (lv > 0) lv else 2)
                "closeseatheating" -> VehicleControlService.closeSeatHeating(context)
                "navigate", "navigatetocar" -> VehicleControlService.navigateToCar(context)
                else -> VehicleControlService.ControlResult().apply { success = false; message = "Unknown: " + fn }
            }
            sendJson(sock, 200, JSONObject().apply {
                put("success", r.success); put("message", r.message ?: "")
            })
        } catch (e: Exception) {
            LogHelper.w(TAG, "control error: " + e.message)
            sendJson(sock, 500, JSONObject().apply {
                put("success", false); put("message", e.message ?: "Unknown")
            })
        }
    }

    private fun handleProjectionStart(sock: Socket) {
        try {
            val i = Intent()
            i.setClassName(context, "com.wmqc.miroot.car.CarControlProjectionService")
            i.action = CarControlIntents.ACTION_OPEN_CAR_CONTROL_PROJECTION
            i.putExtra(CarControlIntents.EXTRA_CAR_PROJECTION_OP, CarControlIntents.VALUE_CAR_PROJECTION_OP_START)
            context.startService(i)
            sendJson(sock, 200, JSONObject().apply {
                put("success", true); put("message", "Projection started")
            })
        } catch (e: Exception) {
            sendJson(sock, 500, JSONObject().apply {
                put("success", false); put("message", e.message ?: "Unknown")
            })
        }
    }

    private fun handleProjectionStop(sock: Socket) {
        try {
            val i = Intent()
            i.setClassName(context, "com.wmqc.miroot.car.CarControlProjectionService")
            i.action = CarControlIntents.ACTION_STOP_CAR_CONTROL_PROJECTION
            context.startService(i)
            sendJson(sock, 200, JSONObject().apply {
                put("success", true); put("message", "Projection stopped")
            })
        } catch (e: Exception) {
            sendJson(sock, 500, JSONObject().apply {
                put("success", false); put("message", e.message ?: "Unknown")
            })
        }
    }
}
