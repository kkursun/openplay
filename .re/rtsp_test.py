import socket, time, hashlib, re, sys

TV = "192.168.1.3"
RTSP = 7000
TIMING = 7010
CONTROL = 7011

s = socket.create_connection((TV, RTSP), 6)
local = s.getsockname()[0]
s.settimeout(30)
print("connected to %s:%d from %s" % (TV, RTSP, local))

cseq = 0
def call(method, url, headers, body=b""):
    global cseq
    cseq += 1
    req = "%s %s RTSP/1.0\r\nCSeq: %d\r\nUser-Agent: AirPlay/409.16\r\n" % (method, url, cseq)
    for k, v in headers.items():
        req += "%s: %s\r\n" % (k, v)
    if body:
        req += "Content-Length: %d\r\n" % len(body)
    req += "\r\n"
    s.sendall(req.encode() + body)
    buf = b""
    try:
        while b"\r\n\r\n" not in buf:
            c = s.recv(65536)
            if not c: break
            buf += c
    except socket.timeout:
        print("-- %s -> TIMEOUT\n" % method); return b"", {}
    head, rest = buf.split(b"\r\n\r\n", 1)
    text = head.decode("latin-1")
    hdrs = {}
    for l in text.split("\r\n")[1:]:
        if ":" in l:
            k, v = l.split(":", 1); hdrs[k.strip().lower()] = v.strip()
    cl = int(hdrs.get("content-length", "0") or 0)
    while len(rest) < cl:
        c = s.recv(65536)
        if not c: break
        rest += c
    print("-- %s -> %s" % (method, text.split("\r\n")[0]))
    print("   headers:", {k: v for k, v in hdrs.items()})
    if rest[:cl]:
        print("   body:", rest[:cl][:200])
    return text.split("\r\n")[0], hdrs

sdp = ("v=0\r\no=AirTunes 62:54:8B:31:27:49 0 IN IP4 %s\r\ns=AirTunes\r\n"
       "c=IN IP4 %s\r\nt=0 0\r\nm=audio 0 RTP/AVP 96\r\n"
       "a=rtpmap:96 mpeg4-generic/44100/2\r\na=fmtp:96 mode=AAC-eld; constantDuration=480\r\n"
       "a=min-latency:11025\r\na=max-latency:88200\r\n"
       "m=video 0 RTP/AVP 97\r\na=rtpmap:97 H264\r\na=fmtp:97\r\n" % (local, local)).encode()

call("OPTIONS", "*", {})
call("ANNOUNCE", "rtsp://%s/%s" % (TV, "1"), {"Content-Type": "application/sdp"}, sdp)
call("SETUP", "rtsp://%s/%s" % (TV, "1"),
     {"Transport": "RTP/AVP/UDP;unicast;mode=screen;interleaved=0-1;timing_port=%d;control_port=%d;redundant=2" % (TIMING, CONTROL)})
call("RECORD", "rtsp://%s/%s" % (TV, "1"), {"Range": "npt=0-"})
time.sleep(1)
s.close()
print("done")
