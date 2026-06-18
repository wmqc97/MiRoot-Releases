import socket

host = "192.168.1.130"
ports = [8080, 8081, 9090, 42799, 5000, 39815]

for port in ports:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(2)
        s.connect((host, port))
        # Try HTTP GET on /api/v1/health
        req = f"GET /api/v1/health HTTP/1.1\r\nHost: {host}:{port}\r\nConnection: close\r\n\r\n".encode()
        s.sendall(req)
        resp = s.recv(4096)
        status = "unknown"
        if resp:
            # Try to extract HTTP status
            try:
                status_line = resp.split(b"\r\n")[0].decode("utf-8", errors="replace")
                status = status_line
            except:
                status = f"{len(resp)} bytes received"
        else:
            status = "connected but no response"
        print(f"Port {port}: OPEN - {status}")
        s.close()
    except socket.timeout:
        print(f"Port {port}: timeout")
    except ConnectionRefusedError:
        print(f"Port {port}: closed")
    except ConnectionResetError:
        print(f"Port {port}: reset")
    except Exception as e:
        print(f"Port {port}: {type(e).__name__}")
