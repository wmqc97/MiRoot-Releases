import socket, ssl, json, select, time

host = "192.168.1.130"
port = 8080

# Step 1: Connect to SSE
print("=== Step 1: Connect to /sse ===")
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(10)
s.connect((host, port))

http_req = (
    "GET /sse HTTP/1.1\r\n"
    f"Host: {host}:{port}\r\n"
    "Connection: keep-alive\r\n"
    "Accept: text/event-stream\r\n"
    "\r\n"
).encode()
s.sendall(http_req)

# Read HTTP response headers
resp = b""
while b"\r\n\r\n" not in resp:
    chunk = s.recv(1024)
    if not chunk:
        break
    resp += chunk

headers, body = resp.split(b"\r\n\r\n", 1)
print(f"HTTP Response: {headers.decode('utf-8', errors='replace')[:200]}")
if body:
    print(f"Body start: {body[:200]}")

# Step 2: Read SSE events for a bit
print("\n=== Step 2: Read SSE events ===")
s.settimeout(3)
try:
    # Read endpoint event
    event_data = body
    while b"endpoint" not in event_data and b"message" not in event_data:
        chunk = s.recv(4096)
        if not chunk:
            break
        event_data += chunk
    
    print(f"SSE data received: {event_data.decode('utf-8', errors='replace')[:500]}")
    
    # Extract sessionId
    if b"sessionId=" in event_data:
        # Parse sessionId from the endpoint event
        text = event_data.decode("utf-8")
        for line in text.split("\n"):
            if "sessionId=" in line:
                parts = line.split("sessionId=")
                if len(parts) > 1:
                    session_id = parts[1].strip()
                    print(f"\nExtracted sessionId: {session_id}")
except socket.timeout:
    print("Timeout waiting for SSE events")

s.close()

print("\n=== Step 3: Direct /message POST with full HTTP ===")
# Try sending POST with content-length and proper formatting
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(10)
s.connect((host, port))

body = json.dumps({"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}})
http_req = (
    "POST /message HTTP/1.1\r\n"
    f"Host: {host}:{port}\r\n"
    "Content-Type: application/json\r\n"
    f"Content-Length: {len(body)}\r\n"
    "Connection: close\r\n"
    "\r\n"
    f"{body}"
).encode()

print(f"Sending: {body[:100]}")
s.sendall(http_req)

resp = b""
s.settimeout(5)
try:
    while True:
        chunk = s.recv(4096)
        if not chunk:
            break
        resp += chunk
except socket.timeout:
    pass

if resp:
    print(f"Response ({len(resp)} bytes):")
    print(resp.decode("utf-8", errors="replace")[:500])
else:
    print("No response received")
s.close()
