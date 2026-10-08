import socket, time

TV = "192.168.1.3"
RTSP = 7000

VARIANTS = [
    ("TCP interleaved", "RTP/AVP/TCP;interleaved=0-1"),
    ("UDP plain", "RTP/AVP/UDP;unicast;control_port=7011;timing_port=7010"),
    ("UDP mode=screen", "RTP/AVP/UDP;unicast;mode=screen;control_port=7011;timing_port=7010"),
    ("UDP screen+red", "RTP/AVP/UDP;unicast;mode=screen;control_port=7011;timing_port=7010;redundant=2"),
    ("UDP screen+ilv", "RTP/AVP/UDP;unicast;mode=screen;interleaved=0-1;timing_port=7010;control_port=7011;redundant=2"),
    ("UDP mode=record", "RTP/AVP/UDP;unicast;mode=record;control_port=7011;timing_port=7010"),
]

def one(name, transport, timeout=15):
    try:
        s = socket.create_connection((TV, RTSP), 6)
    except Exception as e:
        print("%-20s CONNECT FAIL %s" % (name, e)); return
    s.settimeout(timeout)
    local = s.getsockname()[0]
    sdp = ("v=0\r\no=AirTunes 62:54:8B:31:27:49 0 IN IP4 %s\r\ns=AirTunes\r\n"
           "c=IN IP4 %s\r\nt=0 0\r\nm=audio 0 RTP/AVP 96\r\n"
           "a=rtpmap:96 mpeg4-generic/44100/2\r\na=fmtp:96 mode=AAC-eld; constantDuration=480\r\n"
           "m=video 0 RTP/AVP 97\r\na=rtpmap:97 H264\r\na=fmtp:97\r\n" % (local, local)).encode()
    def send(method, url, hdrs, body=b""):
        req = "%s %s RTSP/1.0\r\nCSeq: %d\r\nUser-Agent: AirPlay/409.16\r\n" % (method, url, cseq[0])
        cseq[0] += 1
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
            return "TIMEOUT"
        return buf.decode("latin-1").split("\r\n")[0]
    cseq = [1]
    send("OPTIONS", "*", {})
    send("ANNOUNCE", "rtsp://%s/1" % TV, {"Content-Type": "application/sdp"}, sdp)
    r = send("SETUP", "rtsp://%s/1" % TV, {"Transport": transport})
    r2 = send("RECORD", "rtsp://%s/1" % TV, {"Range": "npt=0-"})
    print("%-20s SETUP: %-28s RECORD: %s" % (name, r, r2))
    s.close(); time.sleep(1)

for name, t in VARIANTS:
    one(name, t)
