import socket, json

host = "192.168.1.130"
port = 8081
api_key = "acde49c3-b2c0-41"

# Test all paths on port 8081
print(f"=== Checking {host}:{port} ===")
paths_status = {}

for method, p, body in [
    ("GET", "/sse", ""),
    ("POST", "/sse", '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'),
    ("POST", "/messages", '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'),
    ("POST", "/message", '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'),
    ("GET", "/api/v1/health", ""),
]:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(5)
    try:
        s.connect((host, port))
    except:
        print(f"  {method} {p}: CONNECTION REFUSED")
        continue
    
    req = f"{method} {p} HTTP/1.1\r\nHost: {host}:{port}\r\n"
    if body:
        req += f"Content-Type: application/json\r\nContent-Length: {len(body)}\r\nX-API-Key: {api_key}\r\n"
    req += "Connection: close\r\n\r\n"
    if body:
        req += body
    s.sendall(req.encode())
    
    resp = b""
    s.settimeout(3)
    try:
        while True:
            chunk = s.recv(4096)
            if not chunk: break
            resp += chunk
    except: pass
    s.close()
    
    status = "NO RESPONSE"
    body_text = ""
    if resp:
        r = resp.decode("utf-8", errors="replace")
        status = r.split("\r\n")[0] if "\r\n" in r else r[:60]
        if "\r\n\r\n" in r:
            body_text = r.split("\r\n\r\n", 1)[1][:200]
    print(f"  {method} {p}: {status}")
    if body_text:
        print(f"    Body: {body_text}")

# Test SSE flow with POST
print(f"\n=== Full SSE flow via port {port} ===")
sse = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sse.settimeout(15)
sse.connect((host, port))
sse.sendall(f"GET /sse HTTP/1.1\r\nHost: {host}:{port}\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n".encode())

data = b""
while b"sessionId=" not in data:
    chunk = sse.recv(4096)
    if not chunk: break
    data += chunk

text = data.decode("utf-8", errors="replace")
session_id = ""
for line in text.split("\n"):
    if "sessionId=" in line:
        session_id = line.split("sessionId=")[1].strip()
print(f"  Session: {session_id}")

# POST tools/list via /sse with sessionId
if session_id:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(10)
    s.connect((host, port))
    body = json.dumps({"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}})
    req = (
        f"POST /sse?sessionId={session_id} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Content-Type: application/json\r\n"
        f"Content-Length: {len(body)}\r\n"
        f"X-API-Key: {api_key}\r\n"
        "Connection: close\r\n\r\n"
        f"{body}"
    )
    s.sendall(req.encode())
    resp = b""
    s.settimeout(3)
    try:
        while True:
            chunk = s.recv(4096)
            if not chunk: break
            resp += chunk
    except: pass
    s.close()
    
    if resp:
        r = resp.decode("utf-8", errors="replace")
        status = r.split("\r\n")[0] if "\r\n" in r else r[:60]
        print(f"  POST /sse?sessionId=xxx: {status}")
        if "\r\n\r\n" in r:
            print(f"  Body: {r.split(chr(13)+chr(10)+chr(13)+chr(10),1)[1][:300]}")
    else:
        print(f"  POST /sse?sessionId=xxx: NO RESPONSE")

sse.close()
