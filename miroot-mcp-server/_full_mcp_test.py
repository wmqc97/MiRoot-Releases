import socket, json

host = "192.168.1.130"
port = 8080
api_key = "acde49c3-b2c0-41"

# Step 1: Connect to /sse and get sessionId
print("=== Step 1: GET /sse -> get sessionId ===")
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(10)
s.connect((host, port))
s.sendall(f"GET /sse HTTP/1.1\r\nHost: {host}:{port}\r\nAccept: text/event-stream\r\n\r\n".encode())

# Read headers + initial event
data = b""
while b"sessionId=" not in data:
    chunk = s.recv(4096)
    if not chunk:
        break
    data += chunk

text = data.decode("utf-8", errors="replace")
session_id = ""
for line in text.split("\n"):
    if "sessionId=" in line:
        session_id = line.split("sessionId=")[1].strip()
        break
print(f"Session ID: {session_id}")

# Step 2: Initialize
print(f"\n=== Step 2: initialize (session={session_id}) ===")
init_body = json.dumps({
    "jsonrpc": "2.0", "id": 1, "method": "initialize",
    "params": {
        "protocolVersion": "2025-03-26",
        "capabilities": {},
        "clientInfo": {"name": "test-client", "version": "1.0.0"}
    }
})

s2 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s2.settimeout(10)
s2.connect((host, port))
req = (
    f"POST /message?sessionId={session_id} HTTP/1.1\r\n"
    f"Host: {host}:{port}\r\n"
    "Content-Type: application/json\r\n"
    f"Content-Length: {len(init_body)}\r\n"
    f"X-API-Key: {api_key}\r\n"
    "Connection: close\r\n\r\n"
    f"{init_body}"
)
s2.sendall(req.encode())

resp = b""
s2.settimeout(5)
try:
    while True:
        chunk = s2.recv(4096)
        if not chunk:
            break
        resp += chunk
except:
    pass

if resp:
    resp_text = resp.decode("utf-8", errors="replace")
    print(f"Response ({len(resp)} bytes):")
    # Extract JSON body
    if "\r\n\r\n" in resp_text:
        headers, body = resp_text.split("\r\n\r\n", 1)
        print(f"HTTP: {headers[:100]}")
        print(f"Body: {body}")
    else:
        print(resp_text[:300])
else:
    print("No response")

# Step 3: Read SSE events (initialize response should come via SSE)
print(f"\n=== Step 3: Read SSE events for initialize response ===")
s.settimeout(5)
try:
    sse_data = s.recv(4096)
    print(sse_data.decode("utf-8", errors="replace")[:500])
except socket.timeout:
    print("No SSE events (timeout)")

# Step 4: tools/list via SSE-connected socket
print(f"\n=== Step 4: tools/list (via second socket) ===")
list_body = json.dumps({"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}})
s3 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s3.settimeout(10)
s3.connect((host, port))
req = (
    f"POST /message?sessionId={session_id} HTTP/1.1\r\n"
    f"Host: {host}:{port}\r\n"
    "Content-Type: application/json\r\n"
    f"Content-Length: {len(list_body)}\r\n"
    f"X-API-Key: {api_key}\r\n"
    "Connection: close\r\n\r\n"
    f"{list_body}"
)
s3.sendall(req.encode())

resp = b""
s3.settimeout(5)
try:
    while True:
        chunk = s3.recv(4096)
        if not chunk:
            break
        resp += chunk
except:
    pass

if resp:
    resp_text = resp.decode("utf-8", errors="replace")
    if "\r\n\r\n" in resp_text:
        h, b = resp_text.split("\r\n\r\n", 1)
        print(f"HTTP: {h[:100]}")
        try:
            j = json.loads(b)
            print(f"Tools: {json.dumps(j, indent=2, ensure_ascii=False)[:600]}")
        except:
            print(f"Body: {b[:300]}")

# Read SSE for the response
print(f"\n=== Step 4b: SSE events for tools/list ===")
s.settimeout(5)
try:
    sse_data = s.recv(4096)
    print(sse_data.decode("utf-8", errors="replace")[:500])
except socket.timeout:
    print("No SSE events (timeout)")

s.close()
s2.close()
s3.close()
