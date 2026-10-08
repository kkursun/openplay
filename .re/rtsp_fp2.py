import socket, plistlib, struct, subprocess, uuid, random, threading, time, re, sys

TV = "192.168.1.3"; RTSP = 7000; TIMING = 7010; UA = "AirPlay/935.7.1"
MAC = "62:54:8B:31:27:49"

out = subprocess.run([r"C:\Users\Kaan\Desktop\openplay\.re\fpgo\fpcli.exe", "%s:%d" % (TV, RTSP)],
                     capture_output=True, text=True, cwd=r"C:\Users\Kaan\Desktop\openplay\.re\fpgo", timeout=90)
if "m4 OK" not in out.stdout:
    print("FAIRPLAY FAILED"); sys.exit(1)
ekey = bytes.fromhex(re.search(r"^ekey: (\w+)$", out.stdout, re.M).group(1))
eiv = bytes.fromhex(re.search(r"^eiv: (\w+)$", out.stdout, re.M).group(1))
print("got ekey %d / eiv %d" % (len(ekey), len(eiv)))

stop = [False]
def make_timing():
    tu = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    tu.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    tu.bind(("0.0.0.0", TIMING)); tu.settimeout(0.3)
    c = [0]
    def r():
        while not stop[0]:
            try: d, a = tu.recvfrom(2048)
            except socket.timeout: continue
            if len(d) < 32 or d[0] != 0x80 or d[1] != 0xd2: continue
            c[0] += 1
            rep = bytearray(d[:32]); rep[0] = 0x80; rep[1] = 0xd3
            t = time.time() + 2208988800; s = int(t); f = int((t - s) * (1 << 32))
            now = (s << 32) | f
            rep[8:16] = d[24:32]; struct.pack_into(">Q", rep, 16, now); struct.pack_into(">Q", rep, 24, now)
            try: tu.sendto(bytes(rep), a)
            except Exception: pass
    threading.Thread(target=r, daemon=True).start()
    return c
tc = make_timing()

def base(extra):
    p = {"deviceID": MAC, "macAddress": MAC, "sessionUUID": str(uuid.uuid4()),
         "sourceVersion": "935.7.1", "isScreenMirroringSession": True,
         "timingProtocol": "NTP", "timingPort": TIMING, "osBuildVersion": "13F69",
         "model": "Linux", "name": "airrepl", "updateSessionRequest": False,
         "combinedGetInfoWithControlSetup": True}
    p.update(extra)
    return p

def trial(name, plist, hdrs=None):
    s = socket.create_connection((TV, RTSP), 6); s.settimeout(25)
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
            return "TIMEOUT", ""
        return buf.decode("latin-1").split("\r\n")[0], buf.decode("latin-1")
    req("OPTIONS", "*", None)
    body = plistlib.dumps(plist, fmt=plistlib.FMT_BINARY)
    uri = "rtsp://%s:%d/%d" % (TV, RTSP, random.randint(1, 2**31))
    st, _ = req("SETUP", uri, "application/x-apple-binary-plist", body, hdrs)
    print("%-28s -> %s" % (name, st))
    s.close(); time.sleep(1.0)
    return st

trial("no keys", base({}))
trial("ekey/eiv, no et", base({"ekey": ekey, "eiv": eiv}))
trial("ekey/eiv + et=32", base({"ekey": ekey, "eiv": eiv, "et": 32}))
print("timing probes seen:", tc[0])
stop[0] = True
