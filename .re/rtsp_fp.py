import socket, plistlib, struct, subprocess, uuid, random, threading, time, re, sys, os

TV = "192.168.1.3"
RTSP = 7000
TIMING = 7010
UA = "AirPlay/935.7.1"
MAC = "62:54:8B:31:27:49"

# 1) FairPlay handshake via the ported Go core
out = subprocess.run([r"C:\Users\Kaan\Desktop\openplay\.re\fpgo\fpcli.exe", "%s:%d" % (TV, RTSP)],
                     capture_output=True, text=True, cwd=r"C:\Users\Kaan\Desktop\openplay\.re\fpgo", timeout=90)
print(out.stdout.strip())
if "m4 OK" not in out.stdout:
    print("FAIRPLAY FAILED"); sys.exit(1)
ekey = bytes.fromhex(re.search(r"^ekey: (\w+)$", out.stdout, re.M).group(1))
eiv = bytes.fromhex(re.search(r"^eiv: (\w+)$", out.stdout, re.M).group(1))
print("ekey=%d bytes eiv=%d bytes" % (len(ekey), len(eiv)))

# 2) timing responder (0xd2 -> 0xd3, 32 bytes, NTP boot time)
tu = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
tu.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
tu.bind(("0.0.0.0", TIMING)); tu.settimeout(0.3)
stop = [False]; ntp_ct = [0]
EPOCH = 2208988800
def ntp_now():
    t = time.time() + EPOCH; s = int(t); f = int((t - s) * (1 << 32))
    return (s << 32) | f
def responder():
    while not stop[0]:
        try: d, a = tu.recvfrom(2048)
        except socket.timeout: continue
        if len(d) < 32 or d[0] != 0x80 or d[1] != 0xd2:
            continue
        ntp_ct[0] += 1
        r = bytearray(d[:32]); r[0] = 0x80; r[1] = 0xd3
        r[8:16] = d[24:32]; now = ntp_now()
        struct.pack_into(">Q", r, 16, now); struct.pack_into(">Q", r, 24, now)
        try: tu.sendto(bytes(r), a)
        except Exception: pass
threading.Thread(target=responder, daemon=True).start()

# 3) control SETUP with FairPlay plist
sid = random.randint(1, 2**31)
uri = "rtsp://%s:%d/%d" % (TV, RTSP, sid)
plist = {
    "deviceID": MAC, "macAddress": MAC, "sessionUUID": str(uuid.uuid4()),
    "sourceVersion": "935.7.1", "isScreenMirroringSession": True,
    "timingProtocol": "NTP", "timingPort": TIMING,
    "osBuildVersion": "13F69", "model": "Linux", "name": "airrepl",
    "updateSessionRequest": False, "combinedGetInfoWithControlSetup": True,
    "ekey": ekey, "eiv": eiv, "et": 32,
}
body = plistlib.dumps(plist, fmt=plistlib.FMT_BINARY)
print("SETUP plist %d bytes, uri=%s" % (len(body), uri))

s = socket.create_connection((TV, RTSP), 6); s.settimeout(30)
def req(method, u, ct, b=b"", extra=None):
    h = "%s %s RTSP/1.0\r\nCSeq: %d\r\nUser-Agent: %s\r\n" % (method, u, req.n, UA); req.n += 1
    if ct: h += "Content-Type: %s\r\n" % ct
    if extra:
        for k, v in extra.items(): h += "%s: %s\r\n" % (k, v)
    if b: h += "Content-Length: %d\r\n" % len(b)
    s.sendall((h + "\r\n").encode() + b)
    buf = b""
    try:
        while b"\r\n\r\n" not in buf:
            c = s.recv(65536)
            if not c: break
            buf += c
    except socket.timeout:
        print("[%s] TIMEOUT" % method); return b"", {}
    head, rest = buf.split(b"\r\n\r\n", 1)
    text = head.decode("latin-1"); hdrs = {}
    for l in text.split("\r\n")[1:]:
        if ":" in l:
            k, v = l.split(":", 1); hdrs[k.strip().lower()] = v.strip()
    cl = int(hdrs.get("content-length", "0") or 0)
    while len(rest) < cl:
        c = s.recv(65536)
        if not c: break
        rest += c
    print("[%s] %s" % (method, text.split("\r\n")[0]))
    print("   hdrs:", hdrs)
    if rest[:cl]:
        print("   body:", rest[:cl][:120])
    return rest[:cl], hdrs
req.n = 1

req("OPTIONS", "*", None)
req("SETUP", uri, "application/x-apple-binary-plist", body)
req("RECORD", uri, "text/parameters", b"", {"Range": "npt=0-"})
time.sleep(3)
stop[0] = True
print("timing packets:", ntp_ct[0])
s.close()
