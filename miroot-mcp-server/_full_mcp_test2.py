import socket, json, time

host = "192.168.1.130"
port = 8080
api_key = "acde49c3-b2c0-41"

# Open SSE connection
print("=== Connecting to SSE ===")
sse = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sse.settimeout(15)
sse.connect((host, port))
sse.sendall(f"GET /sse HTTP/1.1\r\nHost: {host}:{port}\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n".encode())

# Read until we get sessionId
data = b""
while b"sessionId=" not in data:
    data += sse.recv(4096)

text = data.decode("utf-8", errors="replace")
session_id = ""
for line in text.split("\n"):
    if "sessionId=" in line:
        session_id = line.split("sessionId=")[1].strip()

print(f"Session: {session_id}")

# Initialize - try sending request
init = json.dumps({
    "jsonrpc":"2.0","id":1,"method":"initialize",
    "params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}
})

print(f"\n=== Initialize ({len(init)} bytes) ===")
s2 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s2.settimeout(15)
s2.connect((host, port))
req = (
    f"POST /message?sessionId={session_id} HTTP/1.1\r\n"
    f"Host: {host}:{port}\r\n"
    "Content-Type: application/json\r\n"
    f"Content-Length: {len(init)}\r\n"
    f"X-API-Key: {api_key}\r\n"
    "Connection: close\r\n\r\n"
    f"{init}"
)
s2.sendall(req.encode())

# Read HTTP response
resp = b""
try:
    while True:
        chunk = s2.recv(4096)
        if not chunk:
            break
        resp += chunk
except socket.timeout:
    print("(read timeout)")

if resp:
    r = resp.decode("utf-8", errors="replace")
    print(f"HTTP response ({len(resp)} bytes):")
    print(r[:400])
else:
    print("No HTTP response on POST socket!")

# Read SSE events
print(f"\n=== SSE events ===")
sse.settimeout(5)
try:
    while True:
        chunk = sse.recv(4096)
        if not chunk:
            break
        print(f"SSE: {chunk.decode('utf-8', errors='replace')[:300]}")
except socket.timeout:
    print("(SSE timeout)")

# Try tools/list
print(f"\n=== tools/list ===")
s3 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s3.settimeout(15)
s3.connect((host, port))
list_req = json.dumps({"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}})
req = (
    f"POST /message?sessionId={session_id} HTTP/1.1\r\n"
    f"Host: {host}:{port}\r\n"
    "Content-Type: application/json\r\n"
    f"Content-Length: {len(list_req)}\r\n"
    f"X-API-Key: {api_key}\r\n"
    "Connection: close\r\n\r\n"
    f"{list_req}"
)
s3.sendall(req.encode())

resp = b""
try:
    while True:
        chunk = s3.recv(4096)
        if not chunk:
            break
        resp += chunk
except socket.timeout:
    pass

if resp:
    r = resp.decode("utf-8", errors="replace")
    print(f"HTTP response ({len(resp)} bytes):")
    print(r[:400])
else:
    print("No HTTP response!")

# Read SSE for tools/list
print(f"\n=== SSE after tools/list ===")
sse.settimeout(5)
try:
    while True:
        chunk = sse.recv(4096)
        if not chunk:
            break
        print(f"SSE: {chunk.decode('utf-8', errors='replace')[:500]}")
except socket.timeout:
    print("(SSE timeout)")

sse.close()
s2.close()
s3.close()
