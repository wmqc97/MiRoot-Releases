path = r"F:\Android\App\MiRoot2.1\app\src\main\java\com\wmqc\miroot\car\CarControlHttpServer.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# Find and replace the handleMcpMessage function
old_start = "private fun handleMcpMessage(sock: Socket, sessionId: String, body: String) {"
sidx = content.find(old_start)
if sidx < 0:
    print("ERROR: handleMcpMessage not found")
    exit(1)

# Find end of function
i = sidx + len(old_start)
bc = 0
started = False
while i < len(content):
    c = content[i]
    if not started:
        if c == "{":
            started = True
            bc = 1
    else:
        if c == "{": bc += 1
        elif c == "}":
            bc -= 1
            if bc == 0: break
    i += 1
eidx = i + 1

new_func = """    private fun handleMcpMessage(sock: Socket, sessionId: String, body: String) {
        val session = if (sessionId.isNotEmpty()) sseSessions[sessionId] else null

        // Streamable HTTP mode: no SSE session needed, respond directly via HTTP
        if (session == null || !session.active) {
            // If sessionId was provided but invalid, return 404
            if (sessionId.isNotEmpty()) {
                sendJson(sock, 404, JSONObject().put("error", "Session not found"))
                return
            }
            // Streamable HTTP: process without SSE, respond via HTTP directly
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

"""

content = content[:sidx] + new_func + content[eidx:]

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("handleMcpMessage rewritten with Streamable HTTP support")
print("processMcpRequest added for no-session mode")
