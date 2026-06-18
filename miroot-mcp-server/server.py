# ============================================================
# MiRoot MCP Server v2.0 ? ?? MiClaw ????
# ???? MiClaw (AI ???) MCP ????????????????
# ============================================================
# ???python server.py
# ???https://github.com/GoldenglowSusie/MiRoot
# ============================================================

import json
import queue
import logging
import threading
from flask import Flask, request, Response, jsonify

from config import Config
from tools import get_tool_schemas, call_tool, get_tool_count

app = Flask(__name__)

logging.basicConfig(
    level=logging.DEBUG if Config.DEBUG else logging.INFO,
    format="[MCP] %(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger("miroot-mcp")

_client_queues: dict[int, queue.Queue] = {}
_client_lock = threading.Lock()

log.info("MiRoot MCP Server starting (mock=%s, auth=%s, tools=%d)",
         Config.MOCK_MODE, Config.AUTH_ENABLED, get_tool_count())


def _get_client_queue(client_id: int):
    with _client_lock:
        return _client_queues.get(client_id)


def _add_client(client_id: int, q: queue.Queue):
    with _client_lock:
        _client_queues[client_id] = q


def _remove_client(client_id: int):
    with _client_lock:
        _client_queues.pop(client_id, None)


def make_rpc_response(request_id, result=None, error=None):
    resp = {"jsonrpc": "2.0", "id": request_id}
    if error:
        resp["error"] = error
    else:
        resp["result"] = result
    return resp


def make_rpc_error(code: int, message: str, data=None):
    err = {"code": code, "message": message}
    if data:
        err["data"] = data
    return err


def handle_mcp_method(method: str, params: dict, request_id):
    if method == "initialize":
        client_info = params.get("clientInfo", {})
        log.info("Handshake from client: %s v%s",
                 client_info.get("name", "?"), client_info.get("version", "?"))
        return make_rpc_response(request_id, result={
            "protocolVersion": Config.MCP_PROTOCOL_VERSION,
            "capabilities": {
                "tools": {"listChanged": False},
            },
            "serverInfo": {
                "name": Config.SERVER_NAME,
                "version": Config.SERVER_VERSION,
            },
        })

    if method == "notifications/initialized":
        log.info("Client initialized")
        return None

    if method == "tools/list":
        return make_rpc_response(request_id, result={
            "tools": get_tool_schemas(),
        })

    if method == "tools/call":
        name = params.get("name", "")
        arguments = params.get("arguments", {})
        log.info("Tool call: %s args=%s", name, json.dumps(arguments, ensure_ascii=False))

        try:
            tool_result = call_tool(name, arguments)
            if isinstance(tool_result, dict) and "content" in tool_result:
                result = tool_result
            else:
                result = {
                    "content": [
                        {"type": "text", "text": json.dumps(tool_result, ensure_ascii=False)}
                    ]
                }
            return make_rpc_response(request_id, result=result)
        except ValueError as e:
            return make_rpc_response(request_id, error=make_rpc_error(-32601, str(e)))
        except Exception as e:
            log.exception("Tool call failed: %s", name)
            return make_rpc_response(request_id, error=make_rpc_error(-32603, f"Internal error: {e}"))

    if method == "ping":
        return make_rpc_response(request_id, result={"status": "ok"})

    return make_rpc_response(request_id, error=make_rpc_error(-32601, f"Method not found: {method}"))


@app.route("/sse")
def sse_endpoint():
    q: queue.Queue = queue.Queue()
    client_id = id(q)
    _add_client(client_id, q)
    log.info("SSE client connected: id=%d", client_id)

    def event_stream():
        try:
            messages_url = "/messages?sessionId=%d" % client_id
            yield "event: endpoint\ndata: %s\n\n" % messages_url
            log.info("SSE sent endpoint: %s", messages_url)

            while True:
                try:
                    msg = q.get(timeout=Config.SSE_KEEPALIVE_TIMEOUT)
                    yield "event: message\ndata: %s\n\n" % json.dumps(msg)
                except queue.Empty:
                    yield ": keepalive\n\n"
        except GeneratorExit:
            log.info("SSE client disconnected: id=%d", client_id)
        finally:
            _remove_client(client_id)

    return Response(
        event_stream(),
        mimetype="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@app.route("/messages", methods=["POST"])
def messages_endpoint():
    data = request.get_json(silent=True)
    if not data:
        return jsonify(make_rpc_response(None, error=make_rpc_error(-32700, "Parse error: invalid JSON"))), 400

    method = data.get("method", "")
    params = data.get("params", {})
    request_id = data.get("id")
    session_id = request.args.get("sessionId")

    log.debug("Request: method=%s id=%s session=%s", method, request_id, session_id)

    response = handle_mcp_method(method, params, request_id)

    if response is None:
        return jsonify({"jsonrpc": "2.0"}), 202

    if session_id:
        try:
            client_q = _get_client_queue(int(session_id))
            if client_q is not None:
                client_q.put(response)
        except (ValueError, TypeError):
            pass

    return jsonify(response)


@app.route("/health")
def health():
    return jsonify({
        "status": "ok",
        "service": Config.SERVER_NAME,
        "version": Config.SERVER_VERSION,
        "clients": len(_client_queues),
        "tools": get_tool_count(),
        "mockMode": Config.MOCK_MODE,
        "authEnabled": Config.AUTH_ENABLED,
    })


@app.route("/oauth/authorize")
def oauth_authorize():
    return jsonify({"status": "unavailable", "message": "OAuth not yet implemented"}), 501


@app.route("/oauth/token", methods=["POST"])
def oauth_token():
    return jsonify({"status": "unavailable", "message": "OAuth not yet implemented"}), 501


def main():
    mode_str = "MOCK" if Config.MOCK_MODE else "LIVE"
    auth_str = "ON (API_KEY)" if Config.AUTH_ENABLED else "OFF"
    print(f"""
+====================================================+
|  MiRoot MCP v{Config.SERVER_VERSION:<8} ? MiClaw ??       |
|  ?? AI ??? ? ?????????                 |
+====================================================+
|  SSE:    http://localhost:{Config.PORT:<5}/sse              |
|  MSG:    http://localhost:{Config.PORT:<5}/messages         |
|  Health: http://localhost:{Config.PORT:<5}/health           |
|----------------------------------------------------|
|  Mode:   {mode_str:<32}|
|  Auth:   {auth_str:<32}|
|  Tools:  {get_tool_count():<32}|
+====================================================+
    """)
    app.run(host=Config.HOST, port=Config.PORT, debug=Config.DEBUG, threaded=True)


if __name__ == "__main__":
    main()
