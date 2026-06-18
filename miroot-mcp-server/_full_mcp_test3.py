import socket, json, threading, time

host = "192.168.1.130"
port = 8080
api_key = "acde49c3-b2c0-41"
session_id = None

# SSE listener keeps reading events while we send messages
def sse_listener(sock):
    global session_id
    buf = b""
    while True:
        try:
            data = sock.recv(4096)
            if not data:
                break
            buf += data
            text = buf.decode("utf-8", errors="replace")
            # Check for events in the buffer
            for line in text.split("\n"):
                if "sessionId=" in line:
                    sid = line.split("sessionId=")[1].strip()
                    if session_id is None:
                        session_id = sid
                        print(f"[SSE] Got sessionId: {sid}")
                if "message" in line and "event:" in text:
                    print(f"[SSE] Event: {line[:100]}")
        except socket.timeout:
            continue
        except:
            break

# Step 1: Open SSE connection
print("=== Opening SSE connection ===")
sse = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sse.settimeout(30)
sse.connect((host, port))
sse.sendall(f"GET /sse HTTP/1.1\r\nHost: {host}:{port}\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n".encode())

# Read HTTP headers + endpoint event
data = b""
while session_id is None:
    chunk = sse.recv(4096)
    if not chunk:
        break
    data += chunk
    text = data.decode("utf-8", errors="replace")
    for line in text.split("\n"):
        if "sessionId=" in line:
            session_id = line.split("sessionId=")[1].strip()

print(f"Session ID: {session_id}")

# Start SSE listener thread
listener = threading.Thread(target=sse_listener, args=(sse,), daemon=True)
listener.start()

# Step 2: Send initialize
print(f"\n=== Initialize ===")
def send_mcp(method, params={}, rid=1):
    body = json.dumps({"jsonrpc":"2.0","id":rid,"method":method,"params":params})
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(10)
    s.connect((host, port))
    req = (
        f"POST /message?sessionId={session_id} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Content-Type: application/json\r\n"
        f"Content-Length: {len(body)}\r\n"
        f"X-API-Key: {api_key}\r\n"
        "Connection: close\r\n\r\n"
        f"{body}"
    )
    s.sendall(req.encode())
    resp = b""
    try:
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            resp += chunk
    except:
        pass
    s.close()
    return resp

# Initialize
resp = send_mcp("initialize", {
    "protocolVersion": "2025-03-26",
    "capabilities": {},
    "clientInfo": {"name": "test", "version": "1.0"}
}, 1)
if resp:
    r = resp.decode("utf-8", errors="replace")
    print(f"HTTP: {r[:200]}")
else:
    print("No HTTP response for initialize")

time.sleep(1)

# tools/list
print(f"\n=== tools/list ===")
resp = send_mcp("tools/list", {}, 2)
if resp:
    r = resp.decode("utf-8", errors="replace")
    print(f"HTTP ({len(resp)} bytes): {r[:400]}")
    # Extract JSON
    if "\r\n\r\n" in r:
        _, body = r.split("\r\n\r\n", 1)
        try:
            j = json.loads(body)
            if "result" in j and "tools" in j["result"]:
                tools = j["result"]["tools"]
                print(f"Tools count: {len(tools)}")
                for t in tools:
                    print(f"  - {t['name']}: {t['description'][:40]}")
        except:
            print(f"Body: {body[:200]}")
else:
    print("No HTTP response for tools/list")

time.sleep(1)

# Wait for SSE events
print(f"\n=== SSE events received ===")
time.sleep(2)

sse.close()
print("\nDone")
