# AirPlay replication — state of play (updated)

## SOLVED: the FairPlay key management (the "hard part")

Ported **omarroth/doubletake**'s self-contained FairPlay core to a compiled Go helper:
- `.re/fpgo/` (fairplay_sap.go, fpsap.go, fairplay_crypto.go, fairplay_md5.go,
  fairplay_message.go, fpsap_tables.go + main.go + stubs.go) -> `fpgo/fpcli.exe`
- Validated with doubletake's own golden vectors: **all crypto tests PASS**
  (TestFairPlayPrimitiveVectors, TestFairPlaySAPHashCorpus, TestFairPlayMessageVectors,
  TestFairPlayKeyWrapRoundTrip, TestFairPlayKeyUnwrapVector, TestFairPlayAlternateMACVector).
  The only failure is a source-file-scanning test that needs the upstream repo layout.

Against the real AppleTV3,2 (`192.168.1.3`):
```
m1 -> m2 (128-byte challenge)
m3 -> m4   m4 OK          <- receiver confirmed our SAP response
ekey = 72 bytes, eiv = 16 bytes, shk = 16 bytes (raw key)
```
So the Apple TV 3 *does* support `POST /fp-setup` and the AirPlay-2 FairPlay SAP exchange.

## SOLVED: full mirroring RTSP session

`.re/airplay_mirror.py` runs the whole flow and every request is accepted:
```
OPTIONS            -> 200
control SETUP      -> 200 {eventPort, timingPort}      (binary plist body)
  (connect event channel; send NTP probes to receiver timingPort)
audio SETUP        -> 200 {controlPort, dataPort}
video SETUP        -> 200 {type:110, dataPort:NNNNN}
RECORD             -> 200 OK
data channel       -> TCP to TV's video dataPort
```
Critical finding: the SETUP plist carries `ekey`/`eiv` as root keys, and **`et` must be
omitted** — including `et` (or `et=32`) makes the TV answer
`RTSP/1.0 466 Key Management Error`; without it the SETUP is 200.

Timing: the receiver probes the sender's UDP timing port (from the plist `timingPort`)
with `80 d2 ...` 32-byte packets; the reply must be `80 d3` with the request's transmit
timestamp echoed into bytes 8-16 and our now in bytes 16-32. Note the two clock domains:
- timing response = boot-relative seconds **+ 2208988800 (NTP epoch)**
- video frame header timestamp = the same boot-relative seconds **without** the epoch
  (plus a small playout bias). This is what makes the receiver schedule frames correctly.

## REMAINING: the TV does not render

The data channel opens, the codec packet (header[4]=0x01, [6]=0x16, avcC payload) and
AES-128-CTR-encrypted AVCC video frames are written, but nothing appears on the TV.
Tried both AES-128-CTR (key/iv = SHA512("AirPlayStreamKey/IV<id>"+shk)[:16]) and plaintext.
After many rapid sessions the TV now aborts the data connection immediately after the
codec packet (`WinError 10053`), i.e. it is closing the stream rather than tolerating it.

Suspects (unresolved):
1. The video stream descriptor (`{"type":110,"streamConnectionID":..,"usingScreen":true,
   "latencyMin":0,"latencyMax":88200}`) may be missing required fields.
2. The codec packet layout / `header[6]` option byte (0x16) or the avcC framing.
3. The frame header geometry fields ([16]/[40]/[56] width/height floats) or the
   128-byte header details beyond [0:16].
4. Whether the Apple TV 3 expects the ekey wrapped differently for this legacy path.
5. A receiver cooldown/reset is needed between attempts.

## Evidence artifacts in .re/
- `fpgo/`           FairPlay Go core + fpcli.exe (validated)
- `airplay_mirror.py` full session + streaming sender
- `rtsp_fp.py`, `rtsp_fp2.py`, `rtsp_fp3.py`, `rtsp_fp4.py` progressive session probes
- `rtsp_timing*.py`, `rtsp_variants*.py` transport/timing probes
- `mirror.go`, `client.go` (doubletake reference)
- `tvstream2.bin`   the app's real 107-packet stream to the TV
- `libs/`           extracted x86_64 libraopplay.so / libm360tx.so
