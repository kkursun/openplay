import socket, plistlib, struct, subprocess, uuid, random, threading, time, re, sys, hashlib
from Crypto.Cipher import AES
from Crypto.Util import Counter

TV = "192.168.1.3"; RTSP = 7000; TIMING = 7010; CONTROL = 7011
UA = "AirPlay/935.7.1"; MAC = "62:54:8B:31:27:49"
H264 = r"C:\Users\Kaan\Desktop\openplay\.re\test.h264"
EPOCH = 2208988800

# ---- FairPlay ------------------------------------------------------------
out = subprocess.run([r"C:\Users\Kaan\Desktop\openplay\.re\fpgo\fpcli.exe", "%s:%d" % (TV, RTSP)],
                     capture_output=True, text=True, cwd=r"C:\Users\Kaan\Desktop\openplay\.re\fpgo", timeout=90)
assert "m4 OK" in out.stdout, "fairplay failed:\n" + out.stdout
ekey = bytes.fromhex(re.search(r"^ekey: (\w+)$", out.stdout, re.M).group(1))
eiv = bytes.fromhex(re.search(r"^eiv: (\w+)$", out.stdout, re.M).group(1))
shk = bytes.fromhex(re.search(r"^rawkey: (\w+)$", out.stdout, re.M).group(1))
print("FairPlay OK: ekey=%d eiv=%d shk=%d" % (len(ekey), len(eiv), len(shk)))

T_START = time.monotonic()
BIAS = 0.075   # sender-side playout lead

def _fixed(d):
    sec = int(d); frac = int((d - sec) * (1 << 32))
    return (sec << 32) | frac

def ntp_epoch():
    # timing response: boot-relative seconds + NTP epoch
    return _fixed(time.monotonic() - T_START + EPOCH)

def ntp_boot():
    # video frame header: boot-relative seconds, NO epoch, plus playout bias
    return _fixed(time.monotonic() - T_START + BIAS)

# ---- timing responder ----------------------------------------------------
stop = [False]; tc = [0]
tu = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); tu.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
tu.bind(("0.0.0.0", TIMING)); tu.settimeout(0.3)
cu = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); cu.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
cu.bind(("0.0.0.0", CONTROL)); cu.settimeout(0.3)
def timing_loop():
    while not stop[0]:
        try: d, a = tu.recvfrom(2048)
        except socket.timeout: continue
        if len(d) < 32 or d[0] != 0x80 or d[1] != 0xd2: continue
        tc[0] += 1
        rep = bytearray(d[:32]); rep[0] = 0x80; rep[1] = 0xd3
        now = ntp_epoch(); rep[8:16] = d[24:32]
        struct.pack_into(">Q", rep, 16, now); struct.pack_into(">Q", rep, 24, now)
        try: tu.sendto(bytes(rep), a)
        except Exception: pass
threading.Thread(target=timing_loop, daemon=True).start()

# ---- H.264 -> avcC + AVCC frames ----------------------------------------
def parse_h264(path):
    data = open(path, "rb").read()
    pat = re.compile(rb"\x00\x00\x00\x01|\x00\x00\x01")
    ms = list(pat.finditer(data)); nals = []
    for k, m in enumerate(ms):
        s = m.end(); e = ms[k + 1].start() if k + 1 < len(ms) else len(data)
        if e > s: nals.append(data[s:e])
    return nals

nals = parse_h264(H264)
sps = next(n for n in nals if (n[0] & 0x1f) == 7)
pps = next(n for n in nals if (n[0] & 0x1f) == 8)
avcc = b"\x01" + sps[1:4] + b"\xff\xe1" + struct.pack(">H", len(sps)) + sps + b"\x01" + struct.pack(">H", len(pps)) + pps
frames = []
for n in nals:
    t = n[0] & 0x1f
    if t in (1, 5):
        frames.append((struct.pack(">I", len(n)) + n, t == 5))
print("H.264: sps=%d pps=%d avcC=%d frames=%d" % (len(sps), len(pps), len(avcc), len(frames)))
# probe width/height from ffprobe: 1280x720
W, H = 1280.0, 720.0

# ---- RTSP session --------------------------------------------------------
SESSION = str(uuid.uuid4())
aid = random.randint(1, 2**31); vid = random.randint(1, 2**31)
AURI = "rtsp://%s:%d/%d" % (TV, RTSP, aid); VURI = "rtsp://%s:%d/%d" % (TV, RTSP, vid)
s = socket.create_connection((TV, RTSP), 6); s.settimeout(30)
n = [1]
def req(m, u, ct, b=b"", extra=None):
    h = "%s %s RTSP/1.0\r\nCSeq: %d\r\nUser-Agent: %s\r\n" % (m, u, n[0], UA); n[0] += 1
    if ct: h += "Content-Type: %s\r\n" % ct
    for k, v in (extra or {}).items(): h += "%s: %s\r\n" % (k, v)
    if b: h += "Content-Length: %d\r\n" % len(b)
    s.sendall((h + "\r\n").encode() + b)
    buf = b""
    while b"\r\n\r\n" not in buf:
        x = s.recv(65536)
        if not x: break
        buf += x
    head, rest = buf.split(b"\r\n\r\n", 1); hd = {}
    for l in head.decode("latin-1").split("\r\n")[1:]:
        if ":" in l:
            k, v = l.split(":", 1); hd[k.strip().lower()] = v.strip()
    cl = int(hd.get("content-length", "0") or 0)
    while len(rest) < cl:
        x = s.recv(65536)
        if not x: break
        rest += x
    return head.decode("latin-1").split("\r\n")[0], hd, rest[:cl]

req("OPTIONS", "*", None)
sess = {"deviceID": MAC, "macAddress": MAC, "sessionUUID": SESSION, "sourceVersion": "935.7.1",
        "isScreenMirroringSession": True, "timingProtocol": "NTP", "timingPort": TIMING,
        "osBuildVersion": "13F69", "model": "Linux", "name": "airrepl", "ekey": ekey, "eiv": eiv}
st, _, ctl = req("SETUP", AURI, "application/x-apple-binary-plist",
                 plistlib.dumps(dict(sess, updateSessionRequest=False, combinedGetInfoWithControlSetup=True), fmt=plistlib.FMT_BINARY))
c = plistlib.loads(ctl) if ctl else {}
print("control SETUP:", st, c)
ev = None
if c.get("eventPort"):
    ev = socket.create_connection((TV, c["eventPort"]), 5); ev.settimeout(0.5)
if c.get("timingPort"):
    for seq in range(1, 4):
        p = bytearray(32); p[0], p[1] = 0x80, 0xd2; struct.pack_into(">H", p, 2, seq)
        struct.pack_into(">Q", p, 24, ntp_epoch()); tu.sendto(bytes(p), (TV, c["timingPort"]))
audio = {"type": 96, "streamConnectionID": aid, "ct": 2, "spf": 352, "sr": 44100,
         "audioFormat": 0x40000, "audioMode": "default", "usingScreen": True,
         "latencyMin": 0, "latencyMax": 88200, "controlPort": CONTROL}
st, _, ab = req("SETUP", AURI, "application/x-apple-binary-plist", plistlib.dumps({"streams": [audio]}, fmt=plistlib.FMT_BINARY))
print("audio SETUP:", st, plistlib.loads(ab) if ab else None)
video = {"type": 110, "streamConnectionID": vid, "usingScreen": True, "latencyMin": 0, "latencyMax": 88200}
st, _, vb = req("SETUP", VURI, "application/x-apple-binary-plist", plistlib.dumps({"streams": [video]}, fmt=plistlib.FMT_BINARY))
vobj = plistlib.loads(vb) if vb else {}
dport = vobj.get("streams", [{}])[0].get("dataPort")
print("video SETUP:", st, vobj, "dataPort=", dport)
st, hd, _ = req("RECORD", AURI, None, b"", {"Session": SESSION, "Range": "npt=0-", "RTP-Info": "seq=0;rtptime=0"})
print("RECORD:", st)
assert "200" in st, "RECORD failed"

# ---- media: connect data channel, encrypt, send --------------------------
ck = hashlib.sha512(("AirPlayStreamKey%d" % vid).encode() + shk).digest()[:16]
civ = hashlib.sha512(("AirPlayStreamIV%d" % vid).encode() + shk).digest()[:16]
print("cipher key=%s iv=%s" % (ck.hex(), civ.hex()))
ctr = Counter.new(128, initial_value=int.from_bytes(civ, "big"))
enc = AES.new(ck, AES.MODE_CTR, counter=ctr)

d = socket.create_connection((TV, dport), 6); d.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
print("data channel -> %s:%d" % (TV, dport))

def hdr(plen, ptype, sub, ts, w, h, option=(0x00, 0x00)):
    b = bytearray(128)
    struct.pack_into("<I", b, 0, plen); b[4] = ptype; b[5] = sub; b[6] = option[0]; b[7] = option[1]
    struct.pack_into("<Q", b, 8, ts)
    for off in (16, 40, 56):
        struct.pack_into("<f", b, off, float(w)); struct.pack_into("<f", b, off + 4, float(h))
    return bytes(b)

d.sendall(hdr(len(avcc), 0x01, 0x00, ntp_boot(), W, H, (0x16, 0x01)) + avcc)
print("sent codec packet (%d bytes)" % len(avcc))
ENCRYPT = not ("plain" in sys.argv)
print("frame mode:", "AES-128-CTR" if ENCRYPT else "PLAINTEXT")
try:
    t0 = time.time()
    for loop in range(60):
        for au, isidr in frames:
            ts = ntp_boot()
            payload = enc.encrypt(au) if ENCRYPT else au
            d.sendall(hdr(len(payload), 0x00, 0x10 if isidr else 0x00, ts, W, H) + payload)
            time.sleep(1.0 / 30)
        if loop % 5 == 0:
            print("loop %d  t=%.1fs  timing=%d" % (loop, time.time() - t0, tc[0]))
except Exception as e:
    print("DATA SEND FAILED after streaming:", e)
time.sleep(2)
stop[0] = True
d.close(); s.close()
print("done; timing packets:", tc[0])
