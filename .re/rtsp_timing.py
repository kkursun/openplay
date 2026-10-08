import socket, time, threading, struct

TV = "192.168.1.3"
RTSP = 7000
TIMING = 7010
CONTROL = 7011

def ntp8():
    t = time.time() + 2208988800
    s = int(t); f = int((t - s) * (1 << 32))
    return struct.pack(">II", s, f)

udp = {}
for port in (TIMING, CONTROL):
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    u.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    u.bind(("0.0.0.0", port)); u.settimeout(0.5)
    udp[port] = u
print("UDP bound 7010/7011")

stop = False
count = [0]
def responder():
    while not stop:
        try:
            d, a = udp[TIMING].recvfrom(2048)
        except socket.timeout:
            continue
        count[0] += 1
        if count[0] <= 3:
            print("TIMING req <- %s : %s" % (a, d.hex()))
        # reply: same header, echo origin ts, append our receive/transmit
        resp = bytearray(d)
        if len(resp) >= 24:
            origin = d[20:28] if len(d) >= 28 else b"\x00" * 8
            now = ntp8()
            resp = bytearray(d[:20]) + bytearray(origin) + bytearray(now)
        try:
            udp[TIMING].sendto(bytes(resp), a)
        except Exception as e:
            print("send err", e)
        if count[0] == 3:
            print("TIMING reply sent, example:", bytes(resp).hex())
threading.Thread(target=responder, daemon=True).start()

s = socket.create_connection((TV, RTSP), 6)
local = s.getsockname()[0]
s.settimeout(25)
cseq = [1]
def send(method, url, hdrs, body=b""):
    req = "%s %s RTSP/1.0\r\nCSeq: %d\r\nUser-Agent: AirPlay/409.16\r\n" % (method, url, cseq[0]); cseq[0] += 1
    for k, v in hdrs.items(): req += "%s: %s\r\n" % (k, v)
    if body: req += "Content-Length: %d\r\n" % len(body)
    s.sendall((req + "\r\n").encode() + body)
    buf = b""
    try:
        while b"\r\n\r\n" not in buf:
            c = s.recv(65536)
            if not c: break
            buf += c
    except socket.timeout:
        print("[%s] TIMEOUT" % method); return ""
    print("[%s] %s" % (method, buf.decode("latin-1").replace("\r\n", " | ")[:400]))
    return buf.decode("latin-1").split("\r\n")[0]

xh = {"X-Apple-Device-ID": "0x62:54:8B:31:27:49", "X-Apple-Client-Name": "airrepl",
      "X-Apple-ProtocolVersion": "1", "X-Apple-Session-ID": "1"}
sdp = ("v=0\r\no=AirTunes 62:54:8B:31:27:49 0 IN IP4 %s\r\ns=AirTunes\r\nc=IN IP4 %s\r\nt=0 0\r\n"
       "m=audio 0 RTP/AVP 96\r\na=rtpmap:96 mpeg4-generic/44100/2\r\na=fmtp:96 mode=AAC-eld; constantDuration=480\r\n"
       "m=video 0 RTP/AVP 97\r\na=rtpmap:97 H264\r\na=fmtp:97\r\n" % (local, local)).encode()
send("OPTIONS", "*", {})
send("ANNOUNCE", "rtsp://%s/1" % TV, dict(xh, **{"Content-Type": "application/sdp"}), sdp)
send("SETUP", "rtsp://%s/1" % TV,
     dict(xh, **{"Transport": "RTP/AVP/UDP;unicast;mode=screen;interleaved=0-1;timing_port=%d;control_port=%d;redundant=2" % (TIMING, CONTROL)}))
send("RECORD", "rtsp://%s/1" % TV, dict(xh, **{"Range": "npt=0-"}))
time.sleep(4)
stop = True
print("timing packets received:", count[0])
s.close()
