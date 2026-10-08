import socket, time, threading, struct, sys

TV = "192.168.1.3"; RTSP = 7000; TIMING = 7010; CONTROL = 7011
REPLY_TYPE = int(sys.argv[1], 16) if len(sys.argv) > 1 else 7

def ntp8():
    t = time.time() + 2208988800; s = int(t); f = int((t - s) * (1 << 32))
    return struct.pack(">II", s, f)

tu = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
tu.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
tu.bind(("0.0.0.0", TIMING)); tu.settimeout(0.3)
stop = [False]
def resp():
    while not stop[0]:
        try: d, a = tu.recvfrom(2048)
        except socket.timeout: continue
        if len(d) >= 28:
            hdr = bytearray(d[:4]); hdr[2] = REPLY_TYPE
            r = bytes(hdr) + bytes(d[20:28]) + ntp8() + ntp8()
        else:
            r = bytes(d)
        try: tu.sendto(r, a)
        except Exception: pass
threading.Thread(target=resp, daemon=True).start()

XH = {"X-Apple-Device-ID": "0x62:54:8B:31:27:49", "X-Apple-Client-Name": "airrepl",
      "X-Apple-ProtocolVersion": "1", "X-Apple-Session-ID": "1"}
def sdp(ip):
    return ("v=0\r\no=AirTunes 62:54:8B:31:27:49 0 IN IP4 %s\r\ns=AirTunes\r\nc=IN IP4 %s\r\nt=0 0\r\n"
            "m=audio 0 RTP/AVP 96\r\na=rtpmap:96 mpeg4-generic/44100/2\r\na=fmtp:96 mode=AAC-eld; constantDuration=480\r\n"
            "m=video 0 RTP/AVP 97\r\na=rtpmap:97 H264\r\na=fmtp:97\r\n" % (ip, ip)).encode()

def trial(name, transport):
    s = socket.create_connection((TV, RTSP), 6); ip = s.getsockname()[0]; s.settimeout(20)
    c = [1]
    def send(m, hdrs, body=b""):
        req = "%s rtsp://%s/1 RTSP/1.0\r\nCSeq: %d\r\nUser-Agent: AirPlay/409.16\r\n" % (m, TV, c[0]); c[0] += 1
        for k, v in hdrs.items(): req += "%s: %s\r\n" % (k, v)
        if body: req += "Content-Length: %d\r\n" % len(body)
        s.sendall((req + "\r\n").encode() + body)
        buf = b""
        try:
            while b"\r\n\r\n" not in buf:
                x = s.recv(65536)
                if not x: break
                buf += x
        except socket.timeout: return "TIMEOUT", ""
        return buf.decode("latin-1").split("\r\n")[0], buf.decode("latin-1")
    send("OPTIONS", {})
    send("ANNOUNCE", dict(XH, **{"Content-Type": "application/sdp"}), sdp(ip))
    st, txt = send("SETUP", dict(XH, **{"Transport": transport}))
    print("%-34s -> %-32s %s" % (name, st, txt.replace("\r\n", " | ")[:160] if "200" in st else ""))
    s.close(); time.sleep(0.4)

print("REPLY_TYPE=0x%02x" % REPLY_TYPE)
trial("UDP screen", "RTP/AVP/UDP;unicast;mode=screen;control_port=7011;timing_port=7010")
trial("UDP screen+ilv+red", "RTP/AVP/UDP;unicast;mode=screen;interleaved=0-1;timing_port=7010;control_port=7011;redundant=2")
trial("TCP ilv", "RTP/AVP/TCP;interleaved=0-1")
stop[0] = True
