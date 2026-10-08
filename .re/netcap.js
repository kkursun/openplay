// Native-only socket capture. No Java bridge. Loaded directly (no frida-compile).
var sockFds = {};
var nSent = 0;
var MAXLINES = 4000;

function emit(o) {
  if (nSent < MAXLINES) { nSent++; send(o); }
}

function sockaddrStr(ptr) {
  try {
    var fam = ptr.readU16();
    if (fam === 2) {
      var port = (ptr.add(2).readU8() << 8) | ptr.add(3).readU8();
      var ip = ptr.add(4).readU8() + "." + ptr.add(5).readU8() + "." + ptr.add(6).readU8() + "." + ptr.add(7).readU8();
      return ip + ":" + port;
    }
    return "fam=" + fam;
  } catch (e) { return "?"; }
}

function bytesHex(ptr, len, max) {
  try {
    var n = Math.min(len, max >> 1);
    if (n <= 0) return "";
    var u8 = new Uint8Array(ptr.readByteArray(n));
    var h = [];
    for (var i = 0; i < n; i++) h.push(("0" + u8[i].toString(16)).slice(-2));
    return h.join(" ");
  } catch (e) { return "<err>"; }
}

function bytesAscii(ptr, len, max) {
  try {
    var n = Math.min(len, max);
    if (n <= 0) return "";
    var u8 = new Uint8Array(ptr.readByteArray(n));
    var s = "";
    for (var i = 0; i < n; i++) { var c = u8[i]; s += (c >= 32 && c < 127) ? String.fromCharCode(c) : "."; }
    return s;
  } catch (e) { return "<err>"; }
}

function libcExport(name) {
  var m = Process.getModuleByName("libc.so");
  return m ? m.findExportByName(name) : null;
}

// Track socket fds
var pSocket = libcExport("socket");
if (pSocket) Interceptor.attach(pSocket, {
  onLeave: function (ret) { if (!ret.isNull()) sockFds[ret.toInt32()] = true; }
});
var pClose = libcExport("close");
if (pClose) Interceptor.attach(pClose, {
  onEnter: function (a) { delete sockFds[a[0].toInt32()]; }
});

function hookSend(name, hasAddr) {
  var p = libcExport(name);
  if (!p) { emit({ tag: "miss", fn: name }); return; }
  Interceptor.attach(p, {
    onEnter: function (a) {
      var fd = a[0].toInt32();
      if (!sockFds[fd]) return;
      var buf = a[1], len = a[2].toInt32();
      var dst = hasAddr ? sockaddrStr(a[4]) : "";
      emit({ tag: "TX", fn: name, fd: fd, len: len, dst: dst,
             hex: bytesHex(buf, len, 32768), asc: bytesAscii(buf, len, 64) });
    }
  });
  emit({ tag: "on", fn: name });
}

function hookRecv(name, hasAddr) {
  var p = libcExport(name);
  if (!p) { emit({ tag: "miss", fn: name }); return; }
  Interceptor.attach(p, {
    onEnter: function (a) { this.fd = a[0].toInt32(); this.buf = a[1]; this.want = a[2].toInt32(); this.addr = hasAddr ? a[4] : null; },
    onLeave: function (ret) {
      var fd = this.fd;
      if (!sockFds[fd]) return;
      var len = ret.toInt32();
      if (len <= 0) return;
      emit({ tag: "RX", fn: name, fd: fd, len: len, src: this.addr ? sockaddrStr(this.addr) : "",
             hex: bytesHex(this.buf, len, 32768), asc: bytesAscii(this.buf, len, 64) });
    }
  });
  emit({ tag: "on", fn: name });
}

// socket-specific
hookSend("send", false);
hookSend("sendto", true);
hookRecv("recv", false);
hookRecv("recvfrom", true);
// generic (Java socket IO goes through write/read on the fd)
hookSend("write", false);
hookRecv("read", false);

// connect -> record destination + emit
var pConnect = libcExport("connect");
if (pConnect) Interceptor.attach(pConnect, {
  onEnter: function (a) { emit({ tag: "CONNECT", fd: a[0].toInt32(), dst: sockaddrStr(a[1]) }); }
});

// AES keys (native OpenSSL in the two app libs) - modules may load later, so poll
var aesHooked = {};
function hookAes() {
  ["libraopplay.so", "libm360tx.so"].forEach(function (mn) {
    var mod = null;
    try { mod = Process.getModuleByName(mn); } catch (e) { return; }
    if (!mod) return;
    ["AES_set_encrypt_key", "AES_set_decrypt_key"].forEach(function (fn) {
      var id = fn + "@" + mn;
      if (aesHooked[id]) return;
      var p = mod.findExportByName(fn);
      if (!p) return;
      aesHooked[id] = true;
      Interceptor.attach(p, {
        onEnter: function (a) {
          emit({ tag: "AES", lib: mn, fn: fn, bits: a[1].toInt32(), key: bytesHex(a[0], Math.min(a[1].toInt32() / 8, 32), 64) });
        }
      });
      emit({ tag: "on", fn: id });
    });
  });
}
hookAes();
setInterval(hookAes, 1000);

emit({ tag: "READY" });

