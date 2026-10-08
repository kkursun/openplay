import socket, plistlib, struct, subprocess, uuid, random, threading, time, re, sys

TV = "192.168.1.3"; RTSP = 7000; TIMING = 7010; UA = "AirPlay/935.7.1"
MAC = "62:54:8B:31:27:49"

out = subprocess.run([r"C:\Users\Kaan\Desktop\openplay\.re\fpgo\fpcli.exe", "%s:%d" % (TV, RTSP)],
                     capture_output=True, text=True, cwd=r"C:\Users\Kaan\Desktop\openplay\.re\fpgo", timeout=90)
assert "m4 OK" in out.stdout, "fairplay failed"
ekey = bytes.fromhex(re.search(r"^ekey: (\w+)$", out.stdout, re.M).group(1))
eiv = bytes.fromhex(re.search(r"^eiv: (\w+)$", out.stdout, re.M).group(1))
print("ekey %d / eiv %d" % (len(ekey), len(eiv)))

stop = [False]; tc = [0]
tu = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
tu.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
tu.bind(("0.0.0.0", TIMING)); tu.settimeout(0.3)
def resp():
    while not stop[0]:
        try: d, a = tu.recvfrom(2048)
        except socket.timeout: continue
        if len(d) < 32 or d[0] != 0x80 or d[1] != 0xd2: continue
        tc[0] += 1
        rep = bytearray(d[:32]); rep[0] = 0x80; rep[1] = 0xd3
        t = time.time() + 2208988800; s = int(t); f = int((t - s) * (1 << 32)); now = (s << 32) | f
        rep[8:16] = d[24:32]; struct.pack_into(">Q", rep, 16, now); struct.pack_into(">Q", rep, 24, now)
        try: tu.sendto(bytes(rep), a)
        except Exception: pass
threading.Thread(target=resp, daemon=True).start()

s = socket.create_connection((TV, RTSP), 6); s.settimeout(30)
n = [1]
def req(m, u, ct, b=b"", extra=None):
    h = "%s %s RTSP/1.0\r\nCSeq: %d\r\nUser-Agent: %s\r\n" % (m, u, n[0], UA); n[0] += 1
    if ct: h += "Content-Type: %s\r\n" % ct
    for k, v in (extra or {}).items(): h += "%s: %s\r\n" % (k, v)
    if b: h += "Content-Length: %d\r\n" % len(b)
    s.sendall((h + "\r\n").encode() + b)
    buf = b""
    try:
        while b"\r\n\r\n" not in buf:
            x = s.recv(65536)
            if not x: break
            buf += x
    except socket.timeout:
        return "TIMEOUT", {}, b""
    head, rest = buf.split(b"\r\n\r\n", 1)
    text = head.decode("latin-1"); hdrs = {}
    for l in text.split("\r\n")[1:]:
        if ":" in l:
            k, v = l.split(":", 1); hdrs[k.strip().lower()] = v.strip()
    cl = int(hdrs.get("content-length", "0") or 0)
    while len(rest) < cl:
        x = s.recv(65536)
        if not x: break
        rest += x
    return text.split("\r\n")[0], hdrs, rest[:cl]

req("OPTIONS", "*", None)
SESSION = str(uuid.uuid4())
plist = {"deviceID": MAC, "macAddress": MAC, "sessionUUID": SESSION,
         "sourceVersion": "935.7.1", "isScreenMirroringSession": True,
         "timingProtocol": "NTP", "timingPort": TIMING, "osBuildVersion": "13F69",
         "model": "Linux", "name": "airrepl", "updateSessionRequest": False,
         "combinedGetInfoWithControlSetup": True, "ekey": ekey, "eiv": eiv}
uri = "rtsp://%s:%d/%d" % (TV, RTSP, random.randint(1, 2**31))
st, hdrs, body = req("SETUP", uri, "application/x-apple-binary-plist",
                     plistlib.dumps(plist, fmt=plistlib.FMT_BINARY))
print("SETUP:", st, hdrs)
if body:
    try:
        obj = plistlib.loads(body)
        print("response plist keys:", sorted(obj.keys()))
        for k, v in obj.items():
            if k in ("streams", "timingPeerInfo", "timingPeerList", "info"):
                print("  %s = %r" % (k, v))
    except Exception as e:
        print("body not plist:", e, body[:120])

# video stream SETUP then RECORD
vid = random.randint(1, 2**31)
vuri = "rtsp://%s:%d/%d" % (TV, RTSP, vid)
vstream = {"type": 110, "streamConnectionID": vid, "usingScreen": True,
           "latencyMin": 0, "latencyMax": 88200}
vplist = {"streams": [vstream]}
st2, h2, b2 = req("SETUP", vuri, "application/x-apple-binary-plist", plistlib.dumps(vplist, fmt=plistlib.FMT_BINARY))
print("video SETUP:", st2, h2)
if b2:
    try: print("  ", plistlib.loads(b2))
    except Exception: print("  raw", b2[:120])
st3, h3, b3 = req("RECORD", uri, None, b"",
                  {"Session": SESSION, "Range": "npt=0-", "RTP-Info": "seq=0;rtptime=0"})
print("RECORD(session uri + Session hdr):", st3, h3)
if b3:
    try: print("  ", plistlib.loads(b3))
    except Exception: print("  raw", b3[:160])
st4, h4, _ = req("RECORD", vid and vuri, None, b"", {"Session": SESSION, "Range": "npt=0-", "RTP-Info": "seq=0;rtptime=0"})
print("RECORD(video uri + Session hdr):", st4)

time.sleep(4)
stop[0] = True
print("timing probes:", tc[0])
s.close()
