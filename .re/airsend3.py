import socket, plistlib, struct, time, threading, random, sys, os

ATV = "192.168.1.3"
PORT = 7100
MAC = "62:54:8B:31:27:49"
TIMING = 7010
H264 = r"C:\Users\Kaan\Desktop\openplay\.re\test.h264"
LOG = open(r"C:\Users\Kaan\Desktop\openplay\.re\airsend3.log", "w", buffering=1)
def log(*a):
    m = " ".join(str(x) for x in a); print(m); LOG.write(m + "\n"); LOG.flush()

T0 = time.time()
def ntp_now():
    # NTP fixed point since 1900
    t = time.time() + 2208988800
    s = int(t); f = int((t - s) * (1 << 32))
    return (s << 32) | f

def pkt(ptype, payload):
    h = bytearray(128)
    struct.pack_into("<I", h, 0, len(payload))
    struct.pack_into("<H", h, 4, ptype)
    struct.pack_into("<H", h, 6, 30 if ptype == 2 else 6)
    struct.pack_into("<Q", h, 8, ntp_now())
    for i, f in enumerate([474.0, 720.0, 604.0, 0.0, 790.0, 1200.0]):
        struct.pack_into("<f", h, 40 + 4 * i, f)
    return bytes(h) + payload

def ntp_server(stop):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try: s.bind(("0.0.0.0", TIMING))
    except Exception as e: log("[ntp] bind fail", e); return
    s.settimeout(1.0); log("[ntp] UDP", TIMING)
    while not stop.is_set():
        try: d, addr = s.recvfrom(1024)
        except socket.timeout: continue
        except Exception: break
        now = time.time() + 2208988800; sec = int(now); frac = int((now - sec) * (1 << 32))
        tx = struct.pack(">II", sec, frac); resp = bytearray(48)
        resp[0] = 0x24; resp[1] = 1; resp[12:16] = b"AIRP"
        if len(d) >= 48: resp[16:24] = d[40:48]
        resp[24:32] = tx; resp[32:40] = tx
        s.sendto(bytes(resp), addr); log("[ntp] <-", addr)
    s.close()

def read_http(sock, timeout=8):
    sock.settimeout(timeout); buf = b""
    try:
        while b"\r\n\r\n" not in buf:
            c = sock.recv(65536)
            if not c: break
            buf += c
    except socket.timeout:
        return b""
    head, rest = buf.split(b"\r\n\r\n", 1)
    hdrs = {}
    for l in head.decode("latin-1", "ignore").split("\r\n")[1:]:
        if ":" in l:
            k, v = l.split(":", 1); hdrs[k.strip().lower()] = v.strip()
    cl = int(hdrs.get("content-length", "0") or 0)
    while len(rest) < cl:
        c = sock.recv(65536)
        if not c: break
        rest += c
    return head

def parse_h264(path):
    import re
    data = open(path, "rb").read()
    pat = re.compile(rb"\x00\x00\x00\x01|\x00\x00\x01")
    ms = list(pat.finditer(data))
    nals = []
    for k, m in enumerate(ms):
        start = m.end()
        end = ms[k + 1].start() if k + 1 < len(ms) else len(data)
        if end > start:
            nals.append(data[start:end])
    return nals

def main():
    stop = threading.Event()
    threading.Thread(target=ntp_server, args=(stop,), daemon=True).start()

    nals = parse_h264(H264)
    sps = next((n for n in nals if n and (n[0] & 0x1f) == 7), None)
    pps = next((n for n in nals if n and (n[0] & 0x1f) == 8), None)
    log("nal units:", len(nals), "sps:", len(sps) if sps else 0, "pps:", len(pps) if pps else 0)
    avcc = b"\x01" + sps[1:4] + b"\xff\xe1" + struct.pack(">H", len(sps)) + sps + \
           b"\x01" + struct.pack(">H", len(pps)) + pps

    s = socket.create_connection((ATV, PORT), 6)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    req = ("GET /stream.xml HTTP/1.1\r\nUser-Agent: Mirroring360/1.1.9.2\r\n"
           "X-Apple-Device-ID: 0x%s\r\nX-Apple-Client-Name: airrepl\r\n"
           "X-Apple-ProtocolVersion: 1\r\nContent-Length: 0\r\n\r\n" % MAC)
    s.sendall(req.encode())
    log("GET /stream.xml ->", read_http(s)[:60])

    body = plistlib.dumps({
        "deviceID": MAC, "sessionID": random.randint(1, 2**31), "version": "150.33",
        "latencyMs": 50,
        "fpsInfo": ["SubS", "B4En", "EnDp", "IdEn", "IdDp", "EQDp", "QueF", "Sent"],
        "timestampInfo": ["SubSu", "BePxT", "AfPxt", "BefEn", "EmEnc", "QueFr", "SndFr"],
    }, fmt=plistlib.FMT_BINARY)
    post = ("POST /stream HTTP/1.1\r\nUser-Agent: Mirroring360/1.1.9.2\r\n"
            "X-Apple-Device-ID: 0x%s\r\nX-Apple-Client-Name: airrepl\r\n"
            "X-Apple-ProtocolVersion: 1\r\nContent-Type: application/x-apple-binary-plist\r\n"
            "Content-Length: %d\r\n\r\n" % (MAC, len(body)))
    s.sendall(post.encode() + body)
    log("POST /stream sent (%d bytes)" % len(body))

    # codec data, then video
    s.sendall(pkt(1, avcc)); log("sent codec data", len(avcc))
    vcl = [n for n in nals if n and (n[0] & 0x1f) in (1, 5)]
    log("video NALs:", len(vcl))
    t0 = time.time()
    for k in range(30):   # ~30s of video
        for n in vcl:
            payload = struct.pack(">I", len(n)) + n
            s.sendall(pkt(0, payload))
            if (k * len(vcl) + vcl.index(n)) % 30 == 0:
                s.sendall(pkt(2, b""))
            time.sleep(1.0 / 30)
        log("loop", k, "done at %.1fs" % (time.time() - t0))
    time.sleep(2)
    stop.set(); s.close(); log("done")

main()
