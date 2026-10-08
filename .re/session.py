import frida, sys, time, io, json

pid = int(sys.argv[1])
out = sys.argv[2] if len(sys.argv) > 2 else "session.log"
dur = float(sys.argv[3]) if len(sys.argv) > 3 else 120.0
bundle = sys.argv[4] if len(sys.argv) > 4 else r"C:\Users\Kaan\Desktop\openplay\.re\agent.bundle.js"

dev = frida.get_usb_device(timeout=10)
proc = dev.attach(pid)
code = open(bundle, "r", encoding="utf-8").read()
f = io.open(out, "w", encoding="utf-8")

def on_msg(m, d):
    f.write(json.dumps(m) + "\n")
    f.flush()

s = proc.create_script(code, runtime=(sys.argv[5] if len(sys.argv) > 5 else "qjs"))
s.on("message", on_msg)
s.load()
print("agent loaded; logging to %s for %.0fs" % (out, dur))
sys.stdout.flush()
t0 = time.time()
try:
    while time.time() - t0 < dur:
        time.sleep(0.5)
finally:
    try: s.unload()
    except Exception: pass
    proc.detach()
    f.close()
    print("session runner done")
