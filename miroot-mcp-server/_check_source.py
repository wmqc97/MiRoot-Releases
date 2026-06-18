path = r"F:\Android\App\MiRoot2.1\app\src\main\java\com\wmqc\miroot\car\CarControlHttpServer.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# Check for the POST /sse route
if '"/sse"' in content and 'method == "POST"' in content:
    idx = content.find("method == \"POST\"")
    ctx = content[idx:idx+500]
    print("POST routes section:")
    print(ctx[:500])
    print()
    if '"/sse"' in ctx:
        print("POST /sse is IN the route list")
    else:
        print("POST /sse is NOT in the route list")
else:
    print("Could not find POST routes section")
    # Search for the pathOnly check
    idx = content.find('"/message"')
    if idx >= 0:
        print(content[idx-50:idx+300])
