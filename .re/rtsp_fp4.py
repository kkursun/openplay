import socket, plistlib, struct, subprocess, uuid, random, threading, time, re, sys

TV = "192.168.1.3"; RTSP = 7000; TIMING = 7010; CONTROL = 7011; UA = "AirPlay/935.7.1"
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
cu = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
cu.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
cu.bind(("0.0.0.0", CONTROL)); cu.settimeout(0.3)
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
    hd = {}
    for l in head.decode("latin-1").split("\r\n")[1:]:
        if ":" in l:
            k, v = l.split(":", 1); hd[k.strip().lower()] = v.strip()
    cl = int(hd.get("content-length", "0") or 0)
    while len(rest) < cl:
        x = s.recv(65536)
        if not x: break
        rest += x
    print("   [%s] %s %s" % (m, head.decode("latin-1").split("\r\n")[0], hd if cl else ""))
    if rest[:cl]:
        try: print("        ", plistlib.loads(rest[:cl]))
        except Exception: print("         raw", rest[:cl][:100])
    return head.decode("latin-1").split("\r\n")[0], hd, rest[:cl]

SESSION = str(uuid.uuid4())
aid = random.randint(1, 2**31)
vid = random.randint(1, 2**31)
AURI = "rtsp://%s:%d/%d" % (TV, RTSP, aid)
VURI = "rtsp://%s:%d/%d" % (TV, RTSP, vid)

req("OPTIONS", "*", None)
session = {"deviceID": MAC, "macAddress": MAC, "sessionUUID": SESSION,
           "sourceVersion": "935.7.1", "isScreenMirroringSession": True,
           "timingProtocol": "NTP", "timingPort": TIMING, "osBuildVersion": "13F69",
           "model": "Linux", "name": "airrepl", "ekey": ekey, "eiv": eiv}
_, _, ctl = req("SETUP", AURI, "application/x-apple-binary-plist",
    plistlib.dumps(dict(session, updateSessionRequest=False, combinedGetInfoWithControlSetup=True), fmt=plistlib.FMT_BINARY))
ctlobj = plistlib.loads(ctl) if ctl else {}
event_port = ctlobj.get("eventPort"); rx_timing = ctlobj.get("timingPort")
print("eventPort=%s receiver timingPort=%s" % (event_port, rx_timing))

# connect event channel
if event_port:
    try:
        ev = socket.create_connection((TV, event_port), 5); ev.settimeout(0.5)
        print("event channel connected:", event_port)
    except Exception as e:
        print("event channel failed:", e)

# probe receiver's timing port
if rx_timing:
    for seq in range(1, 4):
        p = bytearray(32); p[0], p[1] = 0x80, 0xd2
        struct.pack_into(">H", p, 2, seq)
        t = time.time() + 2208988800; s2 = int(t); f = int((t - s2) * (1 << 32))
        struct.pack_into(">Q", p, 24, (s2 << 32) | f)
        try: tu.sendto(bytes(p), (TV, rx_timing))
        except Exception as e: print("probe fail", e)
    print("sent timing probes to receiver")

audio = {"type": 96, "streamConnectionID": aid, "ct": 2, "spf": 352, "sr": 44100,
         "audioFormat": 0x40000, "audioMode": "default", "usingScreen": True,
         "latencyMin": 0, "latencyMax": 88200, "controlPort": CONTROL}
req("SETUP", AURI, "application/x-apple-binary-plist",
    plistlib.dumps({"streams": [audio]}, fmt=plistlib.FMT_BINARY))

video = {"type": 110, "streamConnectionID": vid, "usingScreen": True, "latencyMin": 0, "latencyMax": 88200}
req("SETUP", VURI, "application/x-apple-binary-plist",
    plistlib.dumps({"streams": [video]}, fmt=plistlib.FMT_BINARY))

req("RECORD", AURI, None, b"", {"Session": SESSION, "Range": "npt=0-", "RTP-Info": "seq=0;rtptime=0"})
time.sleep(3)
stop[0] = True
print("timing probes:", tc[0])
s.close()
