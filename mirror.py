"""Mirror this PC's screen and sound, or play a video file, URL or torrent, on an Apple TV 3
(AirPlay 1), a Mac open to AirPlay from anyone on the network, or a Google Cast device such as a
Xiaomi TV box, from a web dashboard.

The TV plays an HLS stream or a file that this script serves, so no FairPlay or PIN pairing is needed.
Mirroring an Apple TV 3 instead uses AirPlay's own screen mirroring, through airmirror (doubletake's
sender, see airmirror/), which skips the ~2 s its player keeps buffered. Elsewhere, and on an Apple TV 3
if airmirror isn't built, mirroring lags ~3 s, more on Cast.
Run: python mirror.py, then open http://localhost:8000
The openplay Android app can also drive it from a phone on the same network, with the pairing code the
dashboard shows.
"""
import asyncio
import base64
import functools
import hashlib
import hmac
import http.client
import json
import math
import mimetypes
import os
import plistlib
import queue
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import uuid
import warnings
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import libtorrent as lt
import pyatv
import pychromecast
import soundcard as sc
import zeroconf
from pyatv.auth.hap_pairing import NO_CREDENTIALS
from pyatv.const import Protocol
from pyatv.exceptions import AuthenticationError, ConnectionLostError
from pyatv.protocols.airplay.auth import extract_credentials
from pyatv.protocols.raop.protocols import StreamContext, TimingServer
from pyatv.protocols.raop.protocols.airplayv2 import AirPlayV2
from pyatv.support.http import decode_bplist_from_body, http_connect
from pyatv.support.rtsp import RtspSession
from pychromecast.error import PyChromecastError

HERE = Path(__file__).parent
WINDOWS = sys.platform == "win32"
# Windows needs a recent build for ddagrab: BtbN's win64-gpl ffmpeg.exe placed next to this script.
FFMPEG = str(HERE / "ffmpeg.exe") if WINDOWS else "ffmpeg"
# Built with: cd airmirror && go build
AIRMIRROR = HERE / "airmirror" / ("airmirror.exe" if WINDOWS else "airmirror")
AIRMIRROR_RATE = 44100  # the PCM airmirror takes: 16-bit stereo at this rate
SETTINGS_FILE = HERE / "settings.json"
HISTORY_FILE = HERE / "history.json"
REMOTE_FILE = HERE / "remote.json"  # the pairing code phones use
UPLOADS = HERE / "subtitles"  # subtitle files added from the dashboard, a folder per video
# Torrents are kept here after playing, so playing one again resumes instead of starting over.
DOWNLOADS = HERE / "downloads"
PORT = 8000
RATE = 48000
CHUNK = 1 << 18  # bytes per read when serving a video file
DEFAULTS = {"device": "", "display": 0, "height": 1080, "fps": 30, "bitrate": 6,
            "segment": 0.5, "audio": True, "speaker": "", "source": "",
            "subtitle": "",  # language last picked on a Cast device, picked again next time
            "episode": 0}  # which of a torrent's videos plays, in episodes() order
LIMITS = {"display": (0, 8), "height": (360, 1080), "fps": (10, 30), "bitrate": (1, 20), "segment": (0.5, 2)}
HLS = "application/vnd.apple.mpegurl"
SEGMENT = 4  # seconds of video in each HLS segment of a converted video
SKIP = 3  # segments past what's converted a TV may seek to and wait for, before ffmpeg restarts there instead
HLS_TYPES = {".m3u8": HLS, ".ts": "video/mp2t", ".m4s": "video/iso.segment", ".mp4": "video/mp4", ".vtt": "text/vtt"}
CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O or 1/I to mix up
SUBTITLE_FILES = {".srt", ".ass", ".ssa", ".vtt"}
VIDEO_FILES = {".mkv", ".mp4", ".m4v", ".avi", ".mov", ".webm", ".ts", ".m2ts", ".wmv", ".mpg"}
TEXT_SUBTITLES = {"subrip", "ass", "ssa", "mov_text", "webvtt", "text"}  # not pictures (PGS, VobSub): no text to send
# ponytail: common languages only; others still play, but TVs can't match them to their own language
LANGUAGES = [  # code HLS players match against, name, other spellings found in videos and file names
    ("en", "English", "eng"), ("tr", "Turkish", "tur", "turkce", "türkçe"), ("de", "German", "ger", "deu"),
    ("fr", "French", "fre", "fra"), ("es", "Spanish", "spa"), ("it", "Italian", "ita"), ("pt", "Portuguese", "por"),
    ("ru", "Russian", "rus"), ("ar", "Arabic", "ara"), ("nl", "Dutch", "dut", "nld"), ("pl", "Polish", "pol"),
    ("ja", "Japanese", "jpn"), ("ko", "Korean", "kor"), ("zh", "Chinese", "chi", "zho"),
]


def clean(new):
    """Keep known settings only, coerced to their default's type and clamped to sane ranges."""
    s = {k: type(DEFAULTS[k])(v) for k, v in new.items() if k in DEFAULTS}
    for k, (lo, hi) in LIMITS.items():
        if k in s:
            s[k] = max(lo, min(hi, s[k]))
    return s


def episodes(files):
    """Which of a torrent's files, (path, size) each, can be picked: its videos in episode order ("2" before
    "10"), leaving out samples and extras under a tenth the size of the biggest. Or the biggest file if no video."""
    videos = [i for i, (path, _) in enumerate(files) if Path(path).suffix.lower() in VIDEO_FILES]
    biggest = max((files[i][1] for i in videos), default=0)
    natural = lambda i: [int(t) if t.isdecimal() else t.lower() for t in re.split(r"(\d+)", files[i][0])]
    return (sorted((i for i in videos if files[i][1] >= biggest / 10), key=natural)
            or [max(range(len(files)), key=lambda i: files[i][1])])


def uploads(source, episode):
    """Folder of the subtitle files added for a video (each episode of a torrent gets its own)."""
    return UPLOADS / hashlib.sha1(f"{source}#{episode}".encode()).hexdigest()[:16]


def downloads_size():
    """Bytes in DOWNLOADS. Partly downloaded torrent files count in full."""
    try:
        return sum(f.stat().st_size for f in DOWNLOADS.rglob("*") if f.is_file())
    except OSError:  # a file deleted while adding up
        return 0


def local_ip(target):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect((target, 7000))
        return s.getsockname()[0]


def lan_ip():
    """This PC's address on the network (the one its default route leaves from)."""
    try:
        return local_ip("10.255.255.255")
    except OSError:  # no network
        return "127.0.0.1"


def pairing_code():
    """A code like "K7QF-M2XA-P9RD" that pairs a phone: 60 bits, far too many to guess over the network."""
    code = "".join(secrets.choice(CODE_LETTERS) for _ in range(12))
    return "-".join(code[i:i + 4] for i in range(0, 12, 4))


def paired(authorization, code):
    """Whether an Authorization header ("Bearer <code>") carries the pairing code. Case and dashes don't matter."""
    letters = lambda text: re.sub(r"[^A-Z0-9]", "", text.upper())
    return bool(code) and hmac.compare_digest(letters(authorization.removeprefix("Bearer ")), letters(code))


def advertise(zc):
    """Announce this server on mDNS as _openplay._tcp, where the phone app looks for it."""
    # ponytail: the address is the one at startup; restart after moving to another network
    info = zeroconf.ServiceInfo("_openplay._tcp.local.", f"{socket.gethostname()[:40]}._openplay._tcp.local.",
                                port=PORT, parsed_addresses=[lan_ip()])
    try:
        zc.register_service(info, allow_name_change=True)
    except (OSError, zeroconf.Error):
        pass  # the app can still be given the address by hand


def served(tv, path):
    """URL the TV fetches something from this server at."""
    return f"http://{local_ip(tv.address)}:{PORT}{path}"


def x11_monitor(index):
    """x11grab input for one monitor (geometry from xrandr), or the whole X screen as a fallback."""
    display = os.environ.get("DISPLAY", ":0")
    try:
        listing = subprocess.run(["xrandr", "--listactivemonitors"], capture_output=True, text=True).stdout
        # Lines look like " 0: +*DP-1 1920/527x1080/296+0+0  DP-1"
        w, h, x, y = re.findall(r"(\d+)/\d+x(\d+)/\d+\+(\d+)\+(\d+)", listing)[index]
        return ["-video_size", f"{w}x{h}", "-i", f"{display}+{x},{y}"]
    except (OSError, IndexError):
        return ["-i", display]


def fit(height, width=None):
    """Scale filter that shrinks the picture to fit height and width (16:9 by default), never enlarging it."""
    return (f"scale='min({width or height * 16 // 9},iw)':'min({height},ih)'"
            ":force_original_aspect_ratio=decrease:force_divisible_by=2")


def h264(s, gop):
    """Encoder options for anything this script converts to H.264."""
    rate = f"{s['bitrate']}M"
    if WINDOWS:
        # ponytail: AMD-only encoder; use the Linux libx264 line below on other GPUs
        # forced_idr: keyframes forced by start_media_ffmpeg() must be IDR ones, which segments start with
        encoder = ["-c:v", "h264_amf", "-quality", "speed", "-rc", "cbr", "-b:v", rate, "-forced_idr", "1"]
    else:
        # ponytail: CPU encoder; h264_vaapi would move it to the GPU if CPU use is a problem
        encoder = ["-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
                   "-b:v", rate, "-maxrate", rate, "-bufsize", rate]
    # Encoders pick level 4.2 on their own; the Apple TV 3 decoder is only rated up to 4.0.
    return [*encoder, "-pix_fmt", "yuv420p", "-level", "4.0", "-g", str(gop), "-bf", "0"]


def playlist(out):
    # Forward slashes: ffmpeg only looks for "/" when putting fMP4's init.mp4 next to the playlist.
    return (out / "live.m3u8").as_posix()


def run_ffmpeg(out, args, stdin=None):
    log = open(out / "ffmpeg.log", "w")
    proc = subprocess.Popen([FFMPEG, "-nostdin", "-loglevel", "warning", *args], stdin=stdin, stderr=log)
    log.close()  # the child keeps its own handle
    return proc


def screen(s):
    """ffmpeg input options capturing the screen, and the filter that gets its frames into memory."""
    if WINDOWS:
        # Desktop Duplication capture, straight from the GPU.
        return ["-f", "lavfi", "-i", f"ddagrab=output_idx={s['display']}:framerate={s['fps']}"], "hwdownload,format=bgra,"
    # ponytail: X11 only; Wayland needs PipeWire portal capture, which ffmpeg can't do yet
    return ["-f", "x11grab", "-framerate", str(s["fps"]), *x11_monitor(s["display"])], ""


def start_ffmpeg(out, s, tv):
    """Capture the screen (and sound, fed through stdin) into a live HLS stream."""
    fps, segment = s["fps"], s["segment"]
    capture, download = screen(s)
    audio_in = ["-probesize", "32", "-analyzeduration", "0",
                "-f", "f32le", "-ar", str(RATE), "-ac", "2", "-i", "pipe:0"] if s["audio"] else []
    audio_out = ["-c:a", "aac", "-b:a", "160k"] if s["audio"] else []
    return run_ffmpeg(out, [
        *capture,
        # System audio, fed by pump_audio() through stdin. No probing: it would buffer seconds of audio.
        *audio_in,
        "-vf", download + fit(s["height"]),
        *h264(s, math.ceil(fps * segment)),
        *audio_out,
        "-f", "hls", "-hls_time", str(segment), "-hls_segment_type", tv.segments,
        "-hls_list_size", str(max(2, math.ceil(tv.playlist_seconds / segment))),
        "-hls_flags", "delete_segments+temp_file", playlist(out),
    ], stdin=subprocess.PIPE)


def start_airmirror(out, s, tv):
    """Mirror the screen (and sound, fed through stdin) with AirPlay screen mirroring.
    airmirror starts the ffmpeg after "--" once the TV has accepted the session."""
    capture, download = screen(s)
    # {w}x{h}: the TV's screen size, which airmirror fills in. Encoding bigger made the TV scale it itself.
    encoder = [FFMPEG, "-nostdin", "-loglevel", "warning", *capture, "-vf", download + fit("{h}", "{w}"),
               *h264(s, s["fps"] * 2), "-f", "h264", "-"]
    audio = ["-audio"] if s["audio"] else []
    log = open(out / "ffmpeg.log", "w")  # airmirror's own messages land here too
    proc = subprocess.Popen([str(AIRMIRROR), "-target", tv.address, "-max-height", str(s["height"]), *audio,
                             "--", *encoder],
                            stdin=subprocess.PIPE, stderr=log)
    log.close()
    return proc


def stop_process(proc):
    """Close stdin first, which airmirror takes as the cue to end the TV's session, then make sure."""
    if proc.stdin:
        try:
            proc.stdin.close()
            proc.wait(3)
        except (OSError, ValueError, subprocess.TimeoutExpired):
            pass
    proc.kill()
    proc.wait()


def start_media_ffmpeg(out, source, info, copy_video, copy_audio, subtitles, tv, s, start=0):
    """Turn a video into HLS, copying whatever the TV can play as it is, and its subtitle
    tracks (ffmpeg's numbers for them) into sub0.vtt, sub1.vtt, ...
    From segment start on, if given (the TV skipped ahead): the segments come out as converting from the
    beginning makes them, but with the playlist in seek.m3u8 and subtitles in subN-<start>.vtt, so the
    ones converted before stay as they are."""
    at = ["-output_ts_offset", str(start * SEGMENT)] if start else []
    fps = min(info["fps"] or 30, 30)
    # hvc1: the HEVC tag Apple players insist on, and the one hevc_codec() names.
    video = ["-c:v", "copy", *(["-tag:v", "hvc1"] if info["video"] == "hevc" else [])] if copy_video else [
        # ponytail: no HDR tone mapping, so HDR sources that need converting come out washed out
        "-vf", fit(s["height"]) + (",fps=30" if info["fps"] > 30 else ""), *h264(s, round(fps * 2)),
        # A keyframe every SEGMENT seconds on the dot, so every segment is that long and full_playlist() can
        # list them ahead. (GOPs alone drift: segments of 2 or 3 GOPs turn up as they slip off the grid.)
        "-force_key_frames", f"expr:gte(t,n_forced*{SEGMENT})"]
    audio = ["-c:a", "copy"] if copy_audio else ["-c:a", "aac", "-b:a", "192k", "-ac", "2"]
    # Written as ffmpeg reads along, so subtitles of a torrent still downloading keep coming.
    extract = [arg for n, i in enumerate(subtitles)
               for arg in ("-map", f"0:s:{i}", "-c:s", "webvtt", "-flush_packets", "1", *at,
                           str(out / (f"sub{n}-{start}.vtt" if start else f"sub{n}.vtt")))]
    names = ["-master_pl_name", "master.m3u8", playlist(out)]
    if start:
        ext = "m4s" if tv.segments == "fmp4" else "ts"
        names = ["-start_number", str(start), "-hls_segment_filename", (out / f"live%d.{ext}").as_posix(),
                 # frag_discont: fMP4 timestamps carry on from the offset, rather than starting over at 0
                 *(["-hls_segment_options", "movflags=+frag_discont"] if ext == "m4s" else []),
                 (out / "seek.m3u8").as_posix()]
    return run_ffmpeg(out, [
        *(["-ss", str(start * SEGMENT)] if start else []),
        # V (not v) skips cover art. Only the first audio track.
        "-i", source, "-map", "0:V:0?", "-map", "0:a:0?", *video, *audio, *at,
        # EVENT: segments are only added, so the TV can seek to anything converted so far.
        "-f", "hls", "-hls_time", str(SEGMENT), "-hls_segment_type", tv.segments,
        "-hls_playlist_type", "event", "-hls_flags", "temp_file", *names,
        *extract,
    ])


def language(text):
    """(code, name) of the first language named in text, like "tur", "Movie.en" or "2_English"."""
    for word in re.findall(r"[^\W\d_]+", text.lower()):
        for code, name, *spellings in LANGUAGES:
            if word in (code, name.lower(), *spellings):
                return code, name
    return "", ""


def convert_subtitle(path, dest):
    """A subtitle file to UTF-8 WebVTT. Files that aren't UTF-8 are taken as Windows-1254, the old Turkish
    code page, which also reads English and Western European text right."""
    # ponytail: old Cyrillic, Greek or Arabic code pages come out garbled; guessing them needs a detector
    data = path.read_bytes()
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError:
        text = data.decode("cp1254", errors="replace")
    utf8 = dest.with_suffix(path.suffix.lower())
    utf8.write_text(text, encoding="utf-8", newline="")
    return subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-y", "-i", str(utf8), str(dest)]).returncode == 0


def seconds(timestamp):
    """WebVTT "01:02:03.500" or "02:03.500" in seconds."""
    return sum(float(part) * 60 ** i for i, part in enumerate(reversed(timestamp.split(":"))))


def cues_between(vtt, start, end):
    """The cues of a WebVTT file shown between start and end seconds."""
    blocks = vtt.split("\n\n")
    if not vtt.endswith("\n"):  # ffmpeg is halfway through writing the last cue
        blocks.pop()
    cues = []
    for block in blocks:
        if (m := re.search(r"(\S+) --> (\S+)", block)) and seconds(m[1]) < end and seconds(m[2]) > start:
            cues.append(block[m.start():])
    return "\n\n".join(cues)


def hevc_codec(init):
    """RFC 6381 name of the HEVC video in an fMP4 init segment ("hvc1.2.4.L120.B0"), from its hvcC box."""
    box = init[init.index(b"hvcC") + 4:]
    profile, compat, constraints, level = box[1], int.from_bytes(box[2:6], "big"), box[6:12], box[12]
    name = f"hvc1.{['', 'A', 'B', 'C'][profile >> 6]}{profile & 31}.{int(f'{compat:032b}'[::-1], 2):X}"
    name += f".{'H' if profile & 32 else 'L'}{level}"  # high or main tier
    return name + "".join(f".{b:X}" for b in constraints.rstrip(b"\x00"))


def master_playlist(out, subtitles):
    """ffmpeg's master playlist, with the subtitles added as WebVTT renditions. Apple TVs show the one
    in their own language and offer the rest in their menu; Cast devices switch when told to."""
    body = (out / "master.m3u8").read_text()
    init = (out / "init.mp4").read_bytes() if (out / "init.mp4").exists() else b""
    if "CODECS=" not in body and b"hvcC" in init:  # ffmpeg can't name HEVC; Cast players then assume H.264
        codecs = hevc_codec(init) + (",mp4a.40.2" if b"mp4a" in init else "")
        body = re.sub(r"(#EXT-X-STREAM-INF:.*)", rf'\g<1>,CODECS="{codecs}"', body)
    media = "".join(f'#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="{sub["name"]}",'
                    + (f'LANGUAGE="{sub["lang"]}",' if sub["lang"] else "")
                    + f'AUTOSELECT=YES,URI="sub{n}.m3u8"\n' for n, sub in enumerate(subtitles))
    if not media:
        return body
    return re.sub(r"#EXT-X-STREAM-INF:(.*)", lambda m: f'{media}#EXT-X-STREAM-INF:{m[1]},SUBTITLES="subs"', body)


def full_playlist(text, total):
    """ffmpeg's playlist so far (it only lists segments already written) as a finished one covering the
    video's total seconds, with the segments still to come listed too. The TV then shows the real length and
    can seek anywhere; Handler.hls holds its requests for segments ffmpeg hasn't written yet."""
    names = re.findall(r"^live(\d+)\.(\w+)$", text, flags=re.M)
    left = total - sum(float(x) for x in re.findall(r"#EXTINF:([\d.]+)", text))
    if "#EXT-X-ENDLIST" in text or not names or left < 1:
        return text
    tail, n, ext = "", int(names[-1][0]), names[-1][1]
    while left >= 1:  # a last bit under a second may never be written, and isn't missed
        n, length = n + 1, min(SEGMENT, left)
        tail += f"#EXTINF:{length:.6f},\nlive{n}.{ext}\n"
        left -= length
    return text.replace("PLAYLIST-TYPE:EVENT", "PLAYLIST-TYPE:VOD") + tail + "#EXT-X-ENDLIST\n"


def video_playlist(out, total):
    """live.m3u8 as the TV gets it: whole, if the video's length (total seconds) is known."""
    text = (out / "live.m3u8").read_text()
    return full_playlist(text, total) if total else text


def wait_segment(ffmpeg, out, n, timeout=300):
    """Block until ffmpeg (a function giving the one running, which a seek may replace) has written
    video segment n, or stopped, or timeout seconds passed."""
    until = time.monotonic() + timeout
    while not any((out / f"live{n}.{ext}").exists() for ext in ("ts", "m4s")):
        if (proc := ffmpeg()) is None or proc.poll() is not None or time.monotonic() > until:
            return
        time.sleep(0.1)


def subtitle_part(out, name, segments, total):
    """subN.m3u8, a subtitle playlist with the same segments as the video's, or subN_M.vtt, subtitle N's
    cues during video segment M."""
    video = video_playlist(out, total)
    if m := re.fullmatch(r"sub(\d+)\.m3u8", name):
        return re.sub(r"#EXT-X-MAP:.*\n", "", re.sub(r"^live(\d+)\.\w+$", rf"sub{m[1]}_\1.vtt", video, flags=re.M))
    n, segment = map(int, re.fullmatch(r"sub(\d+)_(\d+)\.vtt", name).groups())
    lengths = [float(x) for x in re.findall(r"#EXTINF:([\d.]+)", video)]
    start = sum(lengths[:segment])
    # ffmpeg's MPEG-TS segments start at 1.4 s; X-TIMESTAMP-MAP lines the cues up with that.
    offset = 126000 if segments == "mpegts" else 0
    # Conversions started midway write their own subN-<start>.vtt; cues two of them share go out once.
    files = [out / f"sub{n}.vtt", *out.glob(f"sub{n}-*.vtt")]
    cues = [cue for f in files
            for cue in cues_between(f.read_text(encoding="utf-8"), start, start + lengths[segment]).split("\n\n")]
    return (f"WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:{offset},LOCAL:00:00:00.000\n\n"
            + "\n\n".join(dict.fromkeys(filter(None, cues))))


async def probe(source):
    """Container and codecs of a video, from what ffmpeg prints about its input
    (the Windows ffmpeg build this uses comes without ffprobe)."""
    proc = await asyncio.create_subprocess_exec(
        FFMPEG, "-hide_banner", "-nostdin", "-i", source,
        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    try:
        _, err = await proc.communicate()
    finally:
        if proc.returncode is None:  # cancelled by Stop
            proc.kill()
    return parse_probe(err.decode(errors="replace"))


def parse_probe(text):
    if not (m := re.search(r"Input #0, (.+?), from", text)):
        raise RuntimeError(f"ffmpeg can't read that: {(text.strip().splitlines() or ['no output'])[-1]}")
    info = {"container": m[1].split(","), "video": None, "audio": None, "height": 0, "fps": 0.0, "ten_bit": False,
            "duration": 0.0,  # seconds, 0 when unknown ("N/A" for live streams)
            "subtitles": []}  # (language, codec, forced) of each subtitle track, in ffmpeg's order
    if d := re.search(r"Duration: ([\d:.]+),", text):
        info["duration"] = seconds(d[1])
    # Lines look like "Stream #0:0[0x1](und): Video: h264 (High) (avc1 / 0x31637661), yuv420p(progressive),
    # 1920x1080 [SAR 1:1 DAR 16:9], 23.98 fps, ..." or "Stream #0:1(eng): Audio: ac3, 48000 Hz, 5.1(side), ..."
    # or "Stream #0:2(tur): Subtitle: subrip (srt) (forced)"
    for lang, kind, codec, rest in re.findall(r"Stream #0:\d+(?:\[\w+\])?(?:\((\w+)\))?: (Video|Audio|Subtitle): (\w+)(.*)", text):
        if kind == "Subtitle":
            info["subtitles"].append((lang, codec, "(forced)" in rest))
        elif kind == "Audio":
            info["audio"] = info["audio"] or codec
        elif not info["video"] and "(attached pic)" not in rest:  # skip cover art
            size, fps = re.search(r", \d+x(\d+)", rest), re.search(r"([\d.]+) fps", rest)
            info.update(video=codec, height=int(size[1]) if size else 0, fps=float(fps[1]) if fps else 0.0,
                        ten_bit=bool(re.search(r"p1[02](le|be)", rest)))
    return info


def plan(info, tv):
    """Whether the TV can play the video's picture and sound as they are."""
    v = info["video"]
    copy_video = v is None or (v in tv.video and info["height"] <= tv.max_height and info["fps"] <= tv.max_fps
                               and not (v == "h264" and info["ten_bit"]))  # no TV decodes 10-bit H.264
    return copy_video, info["audio"] in (None, *tv.audio)


def capture_audio(ffmpeg, wanted, chunks, rate):
    """Record the loopback of the output wanted() names ("" = system default) into chunks.
    Re-checked every second, so switching outputs mid-stream follows along."""
    # On Windows the buffer overflows once while ffmpeg starts up; dropping that stale audio is fine.
    warnings.filterwarnings("ignore", "data discontinuity")

    def pick():
        name = wanted()
        return next((x for x in sc.all_speakers() if x.name == name), None) or sc.default_speaker()

    while ffmpeg.poll() is None:
        source = pick()
        # Exact ids: Windows loopbacks reuse the speaker's id, PulseAudio monitors add ".monitor".
        # (Names aren't safe on Linux: a mic often shares its description with the speakers.)
        mic = sc.get_microphone(source.id if WINDOWS else source.id + ".monitor", include_loopback=True)
        with mic.recorder(samplerate=rate, channels=2) as rec:
            while ffmpeg.poll() is None and pick().id == source.id:
                until = time.monotonic() + 1
                while time.monotonic() < until:
                    chunks.put(rec.record(numframes=None))


def pump_audio(ffmpeg, wanted, rate=RATE, pcm16=False):
    """Feed audio to ffmpeg (float samples) or airmirror (pcm16) locked to the wall clock, like the
    screen capture.

    ffmpeg holds video back until audio for the same moment arrives, so an audio source that
    goes quiet (Linux recorders block while an output delivers nothing) would freeze the stream.
    Here gaps become silence and anything running ahead of the clock is dropped."""
    chunks = queue.SimpleQueue()
    threading.Thread(target=capture_audio, args=(ffmpeg, wanted, chunks, rate), daemon=True).start()
    start, written = time.monotonic(), 0
    try:
        while True:
            time.sleep(0.02)
            due = int((time.monotonic() - start) * rate)
            while not chunks.empty():
                data = chunks.get()
                if written + len(data) <= due + rate // 5:  # drop audio >200 ms ahead of the clock
                    ffmpeg.stdin.write((data.clip(-1, 1) * 32767).astype("<i2").tobytes() if pcm16 else data.tobytes())
                    written += len(data)
            if written < due - rate // 10:  # >100 ms behind: nothing arrived, fill with silence
                ffmpeg.stdin.write(bytes((4 if pcm16 else 8) * (due - written)))  # bytes in a stereo frame
                written = due
    except (OSError, ValueError):  # ffmpeg exited
        pass


def airplay(conn, method, path, body=None):
    """One AirPlay 1 request (the same calls pyatv makes for an unpaired Apple TV 3).
    Returns the status code and the decoded plist reply."""
    headers = {"User-Agent": "AirPlay/550.10", "X-Apple-ProtocolVersion": "1", "X-Apple-Stream-ID": "1"}
    if body is not None:
        headers["Content-Type"] = "application/x-apple-binary-plist"
        body = plistlib.dumps(body, fmt=plistlib.FMT_BINARY)
    conn.request(method, path, body, headers)
    resp = conn.getresponse()
    data = resp.read()
    try:
        return resp.status, plistlib.loads(data) if data else {}
    except plistlib.InvalidFileException:
        return resp.status, {}


# Both kinds of TV: play(url, content_type, live, own_hls) starts playback; alive() says True while
# playing, False while it looks stopped (TVs also say so while loading, so only a long spell counts),
# None once it's surely gone; close() stops it. Class attributes list what the player takes.

class AirPlayTV:
    """An AirPlay 1 device like the Apple TV 3, or an AirPlay 2 one (a Mac) that lets anyone on the
    network play: those want transient pairing, which needs no PIN but encrypts the connection."""
    # ponytail: H.264 level isn't checked (ffmpeg -i doesn't show it); 30 fps stands in for level 4.0
    # ponytail: Apple TV 3 limits for every AirPlay device; a Mac could take HEVC and 4K without converting
    video, audio, max_height, max_fps = {"h264"}, {"aac", "mp3"}, 1080, 30
    segments = "mpegts"
    # Measured on an Apple TV 3: a playlist shorter than the player's ~2 s buffer makes it start further back.
    playlist_seconds = 1.5
    picks_subtitles = False  # it shows the one in its own language; its remote's menu switches them

    def __init__(self, conf):
        self.conf, self.name, self.address = conf, conf.name, str(conf.address)
        self.model = conf.device_info.model_str
        self.service = conf.get_service(Protocol.AirPlay)
        self.credentials = extract_credentials(self.service)
        self.v2 = self.credentials != NO_CREDENTIALS
        self.loop = asyncio.get_running_loop()  # Mirror's (scan() makes these); pyatv's connections live on it
        self.conn = self.session = self.timing = None

    def on_loop(self, coro):
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result()

    def play(self, url, *_):  # the TV works out the rest itself
        if self.v2:
            code = self.on_loop(self.play_v2(url))
        else:
            # The TV keeps playing only while this connection stays open.
            self.conn = http.client.HTTPConnection(self.address, self.service.port, timeout=10)
            code, _ = airplay(self.conn, "POST", "/play", {
                "Content-Location": url, "Start-Position": 0.0, "X-Apple-Session-ID": str(uuid.uuid4())})
        if code >= 400:
            raise RuntimeError(f"{self.name} refused to play (HTTP {code}).")

    async def play_v2(self, url):
        """Pair-verify, then the calls an iPhone makes (pyatv's, pinned in requirements.txt)."""
        self.conn = await http_connect(self.address, self.service.port)
        _, self.timing = await self.loop.create_datagram_endpoint(TimingServer, local_addr=(self.conn.local_ip, 0))
        context = StreamContext()
        context.credentials = self.credentials
        self.session = AirPlayV2(context, RtspSession(self.conn))
        try:
            return (await self.session.play_url(self.timing.port, url)).code
        except AuthenticationError as e:
            # A Mac set to "Current User" (TXT act=2) only takes devices on its owner's Apple ID.
            raise RuntimeError(
                f"{self.name} only lets its owner's Apple devices AirPlay to it. On a Mac, set System Settings > "
                "General > AirDrop & Handoff > Allow AirPlay for: Anyone on the Same Network, password off. "
                "On an Apple TV: Settings > AirPlay and HomeKit > Allow Access: Anyone on the Same Network.") from e

    async def playback_info(self):
        resp = await self.conn.get("/playback-info")
        return decode_bplist_from_body(resp) if resp.body else {}

    def alive(self):
        try:
            info = self.on_loop(self.playback_info()) if self.v2 else airplay(self.conn, "GET", "/playback-info")[1]
        except (OSError, http.client.HTTPException, ConnectionLostError, RuntimeError):
            return None  # the TV dropped the connection: stopped from the remote
        if "error" in info:
            raise RuntimeError(f"Apple TV playback error: {info['error']}")
        # The TV leaves out "duration" while loading and during brief rebuffers, not just after Menu is pressed.
        return "duration" in info

    async def close_v2(self):
        if self.session:
            self.session.teardown()
        if self.timing:
            self.timing.close()
        if self.conn:
            self.conn.close()
        self.conn = self.session = self.timing = None

    def close(self):
        if self.v2:
            self.on_loop(self.close_v2())
        elif self.conn:
            self.conn.close()


class CastTV:
    """A Google Cast device (a Chromecast, or an Android TV box with Chromecast built-in like the
    Xiaomi TV Box), playing through Google's Default Media Receiver."""
    # ponytail: what a Xiaomi TV Box S plays; drop "hevc" for Chromecasts that lack it
    # (AAC only, so the master playlist's CODECS can name the audio without looking.)
    video, audio, max_height, max_fps = {"h264", "hevc", "vp8", "vp9"}, {"aac"}, 2160, 60
    segments = "fmp4"  # the receiver only takes HEVC in fMP4 segments
    # ponytail: works on a Xiaomi TV Box S but not tuned for delay; Cast players start ~3 segments
    # behind live, so a shorter playlist may cut lag if they still play smoothly
    playlist_seconds = 6
    picks_subtitles = True  # from the dashboard: the receiver has no subtitle menu of its own

    def __init__(self, info):
        self.info, self.name, self.address = info, info.friendly_name, info.host
        self.model = info.model_name or "Google Cast"
        self.cast = self.failed = None

    def play(self, url, content_type, live, own_hls):
        i = self.info
        self.cast = pychromecast.get_chromecast_from_host((i.host, i.port, i.uuid, i.model_name, i.friendly_name))
        self.cast.wait(timeout=10)
        self.failed = None

        def loaded(_, reply):
            if reply and reply.get("type") == "LOAD_FAILED":
                self.failed = reply.get("detailedErrorCode", "unknown")

        media = self.cast.media_controller
        media.play_media(
            url, content_type, stream_type="LIVE" if live else "BUFFERED",
            # Start growing (EVENT) playlists at the beginning, not at the live edge.
            current_time=None if live else 0,
            # The receiver assumes MPEG-TS segments unless told.
            media_info={"hlsSegmentFormat": "fmp4", "hlsVideoSegmentFormat": "fmp4"} if own_hls else None,
            callback_function=loaded)
        media.block_until_active(timeout=20)

    def alive(self):
        if self.failed:
            raise RuntimeError(f"{self.name} couldn't play it (Cast error {self.failed}).")
        app = self.cast.app_id  # None while reconnecting
        if app not in (None, pychromecast.APP_MEDIA_RECEIVER):
            return None  # Back or Home on the remote, or another app took over
        status = self.cast.media_controller.status
        if status.idle_reason == "ERROR":
            raise RuntimeError(f"{self.name} hit a playback error.")
        return app is not None and status.player_state != "IDLE"

    def subtitle(self, index):
        """Show the index-th subtitle rendition, or none."""
        media = self.cast.media_controller
        tracks = [t["trackId"] for t in media.status.subtitle_tracks or [] if t.get("type") == "TEXT"]
        try:
            if index is None:
                media.disable_subtitle()
            else:  # the receiver numbers renditions from 1, in playlist order, once it has read them
                media.enable_subtitle(tracks[index] if index < len(tracks) else index + 1)
        except PyChromecastError as e:
            raise RuntimeError(f"{self.name} didn't switch subtitles: {e}") from e

    def close(self):
        if not self.cast:
            return
        try:
            if self.cast.app_id == pychromecast.APP_MEDIA_RECEIVER:  # leave other apps alone
                self.cast.quit_app()
        except PyChromecastError:
            pass  # already gone
        self.cast.disconnect(timeout=2)
        self.cast = None


def find_casts(known):
    # Some boxes (a Xiaomi TV Box S on Wi-Fi) answer mDNS only now and then; known hosts are also asked directly.
    casts, browser = pychromecast.discovery.discover_chromecasts(timeout=6, known_hosts=known)
    browser.stop_discovery()
    return casts


class LocalFile:
    """A video on this PC, served as it is."""
    episodes, episode = [], 0

    def __init__(self, path):
        self.path = Path(path)
        self.size = self.path.stat().st_size

    def wait(self, start, end):
        pass  # all there already

    async def subtitle_files(self):
        """Subtitle files named like the video beside it, and any in a Subs folder next to it."""
        found = []
        for p in sorted(self.path.parent.iterdir()):
            if p.is_dir() and p.name.lower() in ("subs", "subtitles"):
                found += [f for f in sorted(p.rglob("*")) if f.suffix.lower() in SUBTITLE_FILES]
            elif p.suffix.lower() in SUBTITLE_FILES and p.name.lower().startswith(self.path.stem.lower()):
                found.append(p)
        return found

    def describe(self):
        return ""

    def problem(self):
        return None

    def close(self):
        pass


@functools.cache
def torrents():
    """The BitTorrent session, started on first use."""
    return lt.session({"listen_interfaces": "0.0.0.0:6881,[::]:6881"})


class Torrent:
    """One video of a torrent (an episode, for a season), downloaded in the order it's read."""

    def __init__(self, source):
        if source.startswith("magnet:"):
            params = lt.parse_magnet_uri(source)
        elif re.match(r"https?://", source):
            with urllib.request.urlopen(source, timeout=20) as r:
                params = lt.load_torrent_buffer(r.read())
        else:
            params = lt.load_torrent_file(source)
        params.save_path = str(DOWNLOADS)
        # A torrent played before is still in the session, paused, with its metadata and pieces known.
        self.handle = torrents().add_torrent(params)
        self.handle.unset_flags(lt.torrent_flags.auto_managed)  # else libtorrent resumes it once paused
        self.handle.set_flags(lt.torrent_flags.sequential_download)
        self.handle.clear_piece_deadlines()  # the last episode's
        self.handle.resume()
        self.index = None
        self.episodes, self.episode = [], 0  # names of the videos to pick from, and the one playing
        self.hot = -1  # first piece of the latest read
        self.closed = False

    async def ready(self, episode):
        while not self.handle.status().has_metadata:  # magnet links get it from peers first
            await asyncio.sleep(0.5)
        info = self.handle.torrent_file()
        files = info.files()
        every = [(files.file_path(i), files.file_size(i)) for i in range(files.num_files())]
        videos = episodes(every)
        index = videos[episode] if 0 <= episode < len(videos) else videos[0]
        # Names without the torrent's own folder.
        self.episodes = ["/".join(Path(every[i][0]).parts[1:]) or every[i][0] for i in videos]
        self.episode = videos.index(index)
        subtitles = [i for i, (path, _) in enumerate(every) if Path(path).suffix.lower() in SUBTITLE_FILES]
        if len(videos) > 1:  # a season's subtitles are named, or sit in folders named, after their episode
            stem = Path(every[index][0]).stem.lower()
            key = m[0] if (m := re.search(r"s\d+e\d+", stem)) else stem
            subtitles = [i for i in subtitles if key in every[i][0].lower()]
        # Subtitle files first (they're tiny), then the video; nothing else.
        self.handle.prioritize_files([4 if i == index else 7 if i in subtitles else 0 for i in range(len(every))])
        self.subtitles = [(DOWNLOADS / every[i][0], i, every[i][1]) for i in subtitles]
        self.path = DOWNLOADS / every[index][0]
        self.size, self.offset, self.piece = every[index][1], files.file_offset(index), info.piece_length()
        self.last = (self.offset + self.size - 1) // self.piece
        self.index = index

    def wait(self, start, end):
        """Block until bytes start..end-1 of the file are downloaded. Seeking TVs jump around,
        so each read makes its pieces, and the few after, the most urgent. (Deadlines on many
        pieces at once make libtorrent fetch them out of order, stalling playback.)"""
        first, last = (self.offset + start) // self.piece, (self.offset + end - 1) // self.piece
        if first != self.hot:
            self.hot = first
            for n, p in enumerate(range(first, min(self.last, last + 8) + 1)):
                self.handle.set_piece_deadline(p, 100 * n)
        while not self.closed:
            if all(self.handle.have_piece(p) for p in range(first, last + 1)):
                return
            time.sleep(0.1)
        raise OSError("torrent stopped")

    async def subtitle_files(self, timeout=10):
        """The torrent's subtitle files, once downloaded (or whichever are, after timeout seconds)."""
        until = time.monotonic() + timeout
        while True:
            progress = self.handle.file_progress()
            done = [path for path, i, size in self.subtitles if progress[i] == size]
            if len(done) == len(self.subtitles) or time.monotonic() > until:
                return done
            await asyncio.sleep(0.5)

    def describe(self):
        st = self.handle.status()
        if self.index is None:
            return f"Getting torrent info · {st.num_peers} peers"
        done = self.handle.file_progress()[self.index] / max(1, self.size)
        return f"{done:.0%} downloaded · {st.download_rate / 1e6:.1f} MB/s · {st.num_peers} peers"

    def problem(self):
        errc = self.handle.status().errc
        return f"Torrent error: {errc.message()}" if errc.value() else None

    def close(self):
        self.closed = True
        # Paused, not removed: playing it again (another episode, say) needn't fetch and check it again.
        # The download itself stays in DOWNLOADS.
        self.handle.pause()


def ffmpeg_log(out):
    try:
        return (out / "ffmpeg.log").read_text(errors="replace").strip()[-400:] or "no output"
    except OSError:
        return "no log"


def stream_problem(ffmpeg, out, segment):
    """Why the mirror stream stopped producing video, or None while it's healthy."""
    if ffmpeg.poll() is not None:
        return f"ffmpeg stopped: {ffmpeg_log(out)}"
    try:
        age = time.time() - (out / "live.m3u8").stat().st_mtime
    except OSError:  # mid-rename by ffmpeg
        return None
    if age > max(5, 4 * segment):
        return f"No new video for {age:.0f} s, capture or encoder stalled. ffmpeg says: {ffmpeg_log(out)}"
    return None


class Mirror:
    def __init__(self):
        try:
            saved = clean(json.loads(SETTINGS_FILE.read_text()))
        except (OSError, ValueError):
            saved = {}
        self.settings = {**DEFAULTS, **saved}
        try:
            self.history = json.loads(HISTORY_FILE.read_text())  # {"source", "episode", "name", "time"}, newest first
        except (OSError, ValueError):
            self.history = []
        try:
            self.code = json.loads(REMOTE_FILE.read_text())["code"]
        except (OSError, ValueError, KeyError, TypeError):
            self.new_code()
        self.devices = {}
        self.speakers = [x.name for x in sc.all_speakers()]
        self.status, self.error, self.since = "idle", "", 0.0
        self.client = None  # address of the device being streamed to
        self.running = None  # settings of the current job
        self.out = None
        self.ffmpeg = None
        self.convert = None  # starts converting the video being played from a segment on
        self.converting_from = 0  # the segment self.ffmpeg started at
        self.lock = threading.Lock()  # held while seek() swaps self.ffmpeg
        self.file = None  # LocalFile or Torrent served at /media/<token>
        self.token = ""
        self.tv = None  # the device of the current job
        self.subtitles = []  # {"name", "lang"} of each subtitle rendition, served from subN.vtt
        self.subtitle = -1  # the one a Cast device is showing, -1 for none
        self.total = 0.0  # seconds of video being converted into equal segments (0: not, or length unknown)
        self.task = None
        self.loop = asyncio.new_event_loop()
        threading.Thread(target=self.loop.run_forever, daemon=True).start()

    def call(self, coro):
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result()

    def new_code(self):
        """Pair phones afresh: ones paired with the old code need the new one."""
        self.code = pairing_code()
        REMOTE_FILE.write_text(json.dumps({"code": self.code}))

    def update(self, new):
        new = clean(new)
        if new.get("source", self.settings["source"]) != self.settings["source"]:
            new.setdefault("episode", 0)  # another video starts at its first episode
        self.settings.update(new)
        SETTINGS_FILE.write_text(json.dumps(self.settings, indent=2))

    def state(self):
        file, tv = self.file, self.tv
        pick_on_tv = self.subtitles and tv and not tv.picks_subtitles
        detail = [file.describe() if file else "",
                  "Subtitles: hold the center button on the Apple TV remote to pick" if pick_on_tv else ""]
        return {
            "status": self.status, "error": self.error, "computer": socket.gethostname(),
            "uptime": int(time.time() - self.since) if self.status == "live" else 0,
            "detail": " · ".join(x for x in detail if x),
            "subtitles": [sub["name"] for sub in self.subtitles] if tv and tv.picks_subtitles else [],
            "subtitle": self.subtitle,
            "episodes": file.episodes if file else [], "episode": file.episode if file else 0,
            "uploaded": [p.name for p in sorted(uploads(self.settings["source"], self.settings["episode"]).glob("*"))],
            "downloads": downloads_size(), "history": self.history,
            "settings": self.settings, "speakers": self.speakers, "running": self.running,
            "devices": [{"name": tv.name, "address": tv.address, "model": tv.model}
                        for tv in self.devices.values()],
        }

    async def scan(self):
        known = [tv.address for tv in self.devices.values() if isinstance(tv, CastTV)]
        found, casts = await asyncio.gather(
            pyatv.scan(self.loop, protocol=Protocol.AirPlay, timeout=3), asyncio.to_thread(find_casts, known))
        self.devices = {tv.name: tv for tv in [*map(AirPlayTV, found), *map(CastTV, casts)]}

    async def start(self, job):
        await self.stop()
        self.task = asyncio.create_task(self.run(dict(self.settings), job))

    async def stop(self):
        if self.task:
            self.task.cancel()
            await asyncio.gather(self.task, return_exceptions=True)
            self.task = None

    async def run(self, s, job):
        self.status, self.error, self.running = "starting", "", {**s, "job": job}
        self.out = Path(tempfile.mkdtemp())
        self.token = uuid.uuid4().hex
        tv = None
        try:
            tv = self.tv = self.devices.get(s["device"])
            if not tv:
                raise RuntimeError("Pick a device first (Scan if the list is empty).")
            url, content_type, own_hls, problem = await (self.mirror(s, tv) if job == "mirror"
                                                         else self.media(s, tv))
            self.client = tv.address
            if url:  # none when airmirror is already streaming
                await asyncio.to_thread(tv.play, url, content_type, job == "mirror", own_hls)
            self.status, self.since = "live", time.time()
            if job == "media":
                self.remember(s["source"], self.file.episode if self.file else 0,
                              self.file.path.name if self.file else s["source"])
            same = [n for n, sub in enumerate(self.subtitles) if sub["lang"] and sub["lang"] == s["subtitle"]]
            if same and tv.picks_subtitles:
                try:
                    await asyncio.to_thread(self.pick_subtitle, same[0], False)
                except RuntimeError:
                    pass  # a convenience; they can still be picked on the dashboard
            missing = 0
            while missing < 8:
                await asyncio.sleep(1)
                if trouble := problem():
                    raise RuntimeError(trouble)
                if url:
                    alive = await asyncio.to_thread(tv.alive)
                else:
                    alive = True if self.ffmpeg.poll() is None else None
                if alive is None:
                    break
                missing = 0 if alive else missing + 1
        except Exception as e:
            self.status, self.error = "error", str(e) or type(e).__name__
        finally:
            if self.status != "error":
                self.status = "idle"
            self.client = self.running = self.tv = None
            self.subtitles, self.subtitle, self.total = [], -1, 0.0
            if tv:
                await asyncio.to_thread(tv.close)
            with self.lock:
                if self.ffmpeg:
                    stop_process(self.ffmpeg)
                file, self.file, self.ffmpeg = self.file, None, None  # unpublish before closing
                self.convert, self.converting_from = None, 0
            if file:
                file.close()
            shutil.rmtree(self.out, ignore_errors=True)

    async def mirror(self, s, tv):
        # ponytail: Apple TV 3 only; doubletake also mirrors to Macs, untried here, so they keep HLS
        if isinstance(tv, AirPlayTV) and not tv.v2 and AIRMIRROR.exists():
            return await self.airmirror(s, tv)
        self.ffmpeg = ffmpeg = start_ffmpeg(self.out, s, tv)
        if s["audio"]:
            speaker = lambda: self.settings["speaker"]  # live, so the dashboard can switch it
            threading.Thread(target=pump_audio, args=(ffmpeg, speaker), daemon=True).start()
        await self.hls_ready()
        return served(tv, "/hls/live.m3u8"), HLS, True, lambda: stream_problem(ffmpeg, self.out, s["segment"])

    async def airmirror(self, s, tv):
        """Mirror with airmirror, which drives the TV itself: no URL for the TV to play."""
        self.ffmpeg = proc = start_airmirror(self.out, s, tv)
        for _ in range(100):  # pairing, FairPlay and SETUP take ~2 s
            if proc.poll() is not None:
                raise RuntimeError(f"Mirroring failed: {ffmpeg_log(self.out)}")
            if "mirroring (data port" in (self.out / "ffmpeg.log").read_text(errors="replace"):
                break
            await asyncio.sleep(0.2)
        else:
            raise RuntimeError(f"The TV didn't take the mirroring session: {ffmpeg_log(self.out)}")
        if s["audio"]:  # only now: airmirror doesn't read stdin before, and the backlog would lag
            speaker = lambda: self.settings["speaker"]
            threading.Thread(target=pump_audio, args=(proc, speaker, AIRMIRROR_RATE, True), daemon=True).start()

        def problem():  # exit status 0 is a normal end, such as Menu on the remote
            if proc.poll() not in (None, 0):
                return f"Mirroring stopped: {ffmpeg_log(self.out)}"
        return None, None, False, problem

    async def media(self, s, tv):
        source = s["source"].strip().strip('"')  # Windows' "Copy as path" adds quotes
        if source.startswith("magnet:") or source.lower().split("?")[0].endswith(".torrent"):
            self.file = await asyncio.to_thread(Torrent, source)
            await self.file.ready(s["episode"])
            # ffmpeg reads the torrent through this server, which waits for the pieces it asks for.
            source = f"http://127.0.0.1:{PORT}/media/{self.token}"
        elif os.path.isfile(source):
            self.file = LocalFile(source)
        elif not re.match(r"https?://", source):
            raise RuntimeError("Enter a video file's path, an http(s) URL, a magnet link or a .torrent.")

        def problem():
            with self.lock:  # one seek() killed isn't a problem
                if self.ffmpeg and self.ffmpeg.poll() not in (None, 0):
                    return f"ffmpeg stopped: {ffmpeg_log(self.out)}"
            return self.file and self.file.problem()

        def add_subtitle(name, lang):
            self.subtitles.append({"name": f"{len(self.subtitles) + 1}. {name}".replace('"', "'"), "lang": lang})

        info = await probe(source)
        # Subtitle tracks inside the video, then subtitle files beside it or in the torrent.
        tracks = [i for i, (_, codec, _) in enumerate(info["subtitles"]) if codec in TEXT_SUBTITLES]
        for i in tracks:
            lang, _, forced = info["subtitles"][i]
            code, name = language(lang)
            add_subtitle((name or lang or "Unknown") + (" (forced)" if forced else ""), code)
        found = await self.file.subtitle_files() if self.file else []
        for path in [*found, *sorted(uploads(s["source"], s["episode"]).glob("*"))]:
            if await asyncio.to_thread(convert_subtitle, path, self.out / f"sub{len(self.subtitles)}.vtt"):
                stem = self.file.path.stem if self.file else ""
                label = path.stem[len(stem):] if path.stem.lower().startswith(stem.lower()) else path.stem
                label = label.strip(" ._-") or "Subtitles"
                code, name = language(label)
                add_subtitle(name if name and len(label) <= 3 else label, code)  # "tr" reads better as Turkish
        copy_video, copy_audio = plan(info, tv)
        # A file is only served as it is if it's MP4, a URL may also be an HLS stream, and subtitles
        # only travel in this server's HLS.
        if (copy_video and copy_audio and not self.subtitles
                and ("mp4" in info["container"] or ("hls" in info["container"] and not self.file))):
            if self.file:
                return served(tv, f"/media/{self.token}{self.file.path.suffix}"), "video/mp4", False, problem
            return source, HLS if "hls" in info["container"] else "video/mp4", False, problem
        self.convert = functools.partial(start_media_ffmpeg, self.out, source, info, copy_video, copy_audio,
                                         tracks, tv, s)
        self.ffmpeg = self.convert()
        # ponytail: copied video is cut at the source's own keyframes, so its segments differ in length and
        # can't be listed ahead: the TV only knows the length converted so far (copying is quick, so that
        # soon catches up). Re-encoding it would fix that, at a cost in quality and GPU time.
        self.total = 0.0 if copy_video else info["duration"]
        await self.hls_ready()
        # Only subtitles need the master playlist. ffmpeg leaves its CODECS out for HEVC, and without them
        # a player may assume H.264.
        return served(tv, "/hls/master.m3u8" if self.subtitles else "/hls/live.m3u8"), HLS, True, problem

    def pick_subtitle(self, index, remember=True):
        """Show subtitle rendition index (-1: none) on the Cast device playing, and pick its language
        again next time."""
        tv = self.tv
        if not (tv and tv.picks_subtitles and -1 <= index < len(self.subtitles)):
            raise ValueError("No such subtitle")
        tv.subtitle(None if index < 0 else index)
        self.subtitle = index
        if remember:
            self.update({"subtitle": self.subtitles[index]["lang"] if index >= 0 else ""})

    def remember(self, source, episode, name):
        """Put a video first in the history, once per source (a torrent keeps its latest episode)."""
        self.history = [{"source": source, "episode": episode, "name": name, "time": int(time.time())},
                        *(h for h in self.history if h["source"] != source)][:30]
        HISTORY_FILE.write_text(json.dumps(self.history, indent=2))

    def upload_subtitle(self, name, data):
        """Keep a subtitle file (base64) for the video in the settings, to play with it from then on."""
        name = Path(name).name  # no folders
        if Path(name).suffix.lower() not in SUBTITLE_FILES:
            raise ValueError("Pick a .srt, .ass, .ssa or .vtt file.")
        if not self.settings["source"]:
            raise ValueError("Enter the video first.")
        folder = uploads(self.settings["source"], self.settings["episode"])
        folder.mkdir(parents=True, exist_ok=True)
        (folder / name).write_bytes(base64.b64decode(data, validate=True))

    async def clear_downloads(self):
        if self.running and self.running["job"] == "media":
            raise RuntimeError("Stop playing first.")
        for handle in torrents().get_torrents():
            torrents().remove_torrent(handle)
        for _ in range(20):  # libtorrent lets go of a removed torrent's files a moment later
            await asyncio.to_thread(shutil.rmtree, DOWNLOADS, ignore_errors=True)
            if not DOWNLOADS.exists():
                return
            await asyncio.sleep(0.5)
        raise RuntimeError(f"Some files in {DOWNLOADS} are in use and weren't deleted.")

    async def hls_ready(self):
        """Let a couple of segments build up (or all of a short video) so the player has something to buffer."""
        while True:
            try:
                text = (self.out / "live.m3u8").read_text()
                if text.count("#EXTINF") >= 2 or "#EXT-X-ENDLIST" in text:
                    return
            except OSError:  # not written yet
                pass
            if self.ffmpeg.poll() is not None:
                raise RuntimeError(f"ffmpeg stopped: {ffmpeg_log(self.out)}")
            await asyncio.sleep(0.2)

    def seek(self, n):
        """Restart ffmpeg at video segment n if the TV skipped to a part that isn't converted and won't be
        soon, rather than have it wait for ffmpeg to get there."""
        with self.lock:
            out, ffmpeg, first = self.out, self.ffmpeg, self.converting_from
            if not (self.convert and ffmpeg) or any((out / f"live{n}.{ext}").exists() for ext in ("ts", "m4s")):
                return
            try:  # the segment ffmpeg is on: the one after the last in its playlist
                text = (out / ("seek.m3u8" if first else "live.m3u8")).read_text()
                done = int(re.findall(r"^live(\d+)\.", text, flags=re.M)[-1]) + 1
            except (OSError, IndexError):  # none written yet
                done = first
            if first <= n <= done + SKIP:
                return
            ffmpeg.kill()
            ffmpeg.wait()
            (out / "seek.m3u8").unlink(missing_ok=True)  # the last restart's, not this one's
            self.ffmpeg, self.converting_from = self.convert(n), n


class Handler(BaseHTTPRequestHandler):
    def send(self, body, content_type="application/json", code=200, cors=False):
        if not isinstance(body, bytes):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if cors:  # Cast receivers fetch HLS from script
            self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def from_dashboard(self):
        # The server listens on the LAN for the TV, but the controls stay on this machine (and paired phones).
        # Checking Host also stops DNS-rebinding pages from reaching the API.
        host = self.headers.get("Host", "").rsplit(":", 1)[0]
        return self.client_address[0] == "127.0.0.1" and host in ("localhost", "127.0.0.1")

    def from_phone(self):
        # The phone app sends the pairing code. Web pages can't send that header to another site without a
        # CORS preflight, which this server never approves.
        return paired(self.headers.get("Authorization", ""), M.code)

    def state(self, local):
        st = M.state()
        if local:  # only the PC's own screen shows how to pair
            st["remote"] = {"code": M.code, "url": f"http://{lan_ip()}:{PORT}"}
        return st

    def do_GET(self):
        if self.path.startswith("/hls/"):
            return self.hls(self.path[len("/hls/"):])
        if self.path.startswith("/media/"):
            return self.media()
        local = self.from_dashboard()
        if not (local or self.from_phone()):
            return self.send_error(403)
        if self.path == "/" and local:
            self.send((HERE / "dashboard.html").read_bytes(), "text/html; charset=utf-8")
        elif self.path == "/api/state":
            self.send(self.state(local))
        else:
            self.send_error(404)

    def do_HEAD(self):
        if self.path.startswith("/media/"):
            return self.media(head=True)
        self.send_error(405)

    def hls(self, name):
        # Only the device being streamed to may fetch the stream, and only the stream's own files.
        if self.client_address[0] != M.client or not re.fullmatch(
                r"live(\d+\.(ts|m4s)|\.m3u8)|init\.mp4|master\.m3u8|sub\d+(_\d+\.vtt|\.m3u8)", name):
            return self.send_error(403)
        out, total = M.out, M.total
        try:
            # A full playlist lists segments ffmpeg hasn't written yet (a subtitle's cues wait for their video).
            if total and (m := re.fullmatch(r"(live|sub\d+_)(\d+)\.\w+", name)):
                if m[1] == "live":  # the TV seeking; subtitles follow the video
                    M.seek(int(m[2]))
                wait_segment(lambda: M.ffmpeg, out, int(m[2]))
            if name == "master.m3u8":
                body = master_playlist(out, M.subtitles).encode()
            elif name.startswith("sub"):
                body = subtitle_part(out, name, M.tv.segments, total).encode()
            elif name == "live.m3u8":
                body = video_playlist(out, total).encode()
            else:
                body = (out / name).read_bytes()
        except (OSError, IndexError, AttributeError):  # not written yet, or already gone; the player retries
            return self.send_error(404)
        # ffmpeg (4.4 through 2026 master) rounds sub-second target durations down to 0, which players reject.
        body = body.replace(b"TARGETDURATION:0", b"TARGETDURATION:1")
        # Players start growing playlists near their end, like live streams, unless told otherwise.
        body = body.replace(b"#EXT-X-PLAYLIST-TYPE:EVENT", b"#EXT-X-PLAYLIST-TYPE:EVENT\n#EXT-X-START:TIME-OFFSET=0")
        self.send(body, HLS_TYPES[Path(name).suffix], cors=True)

    def media(self, head=False):
        # The file being played, for the TV (or for ffmpeg on this machine), at a URL only they know.
        file, m = M.file, re.fullmatch(r"/media/([0-9a-f]+)(\.\w+)?", self.path)
        if not (file and m and m[1] == M.token and (self.client_address[0] == M.client or self.from_dashboard())):
            return self.send_error(403)
        start, end = 0, file.size
        r = re.fullmatch(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
        ranged = bool(r and (r[1] or r[2]))
        if ranged and r[1]:
            start, end = int(r[1]), min(file.size, int(r[2]) + 1) if r[2] else file.size
        elif ranged:  # "bytes=-N": the last N bytes
            start = max(0, file.size - int(r[2]))
        if ranged and start >= end:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{file.size}")
            self.send_header("Content-Length", "0")
            return self.end_headers()
        self.send_response(206 if ranged else 200)
        if ranged:
            self.send_header("Content-Range", f"bytes {start}-{end - 1}/{file.size}")
        self.send_header("Content-Type", mimetypes.guess_type(file.path.name)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(end - start))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        if head:
            return
        try:
            file.wait(start, min(end, start + CHUNK))  # a torrent's file only exists once a piece is in
            with open(file.path, "rb") as data:
                data.seek(start)
                while start < end:
                    n = min(CHUNK, end - start)
                    file.wait(start, start + n)
                    self.wfile.write(data.read(n))
                    start += n
        except OSError:  # the player hung up (it does on every seek), or playback stopped
            pass

    def do_POST(self):
        # Requiring JSON forces a CORS preflight, which this server never approves,
        # so other websites open in the browser can't drive the API.
        local = self.from_dashboard()
        if not (local or self.from_phone()) or self.headers.get("Content-Type") != "application/json":
            return self.send_error(403)
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
            if self.path == "/api/settings":
                M.update(body)
            elif self.path in ("/api/start", "/api/play"):
                M.update(body)
                M.call(M.start("mirror" if self.path == "/api/start" else "media"))
            elif self.path == "/api/subtitle":
                M.pick_subtitle(int(body["index"]))
            elif self.path == "/api/upload-subtitle":
                M.update(body)
                M.upload_subtitle(body["name"], body["data"])
            elif self.path == "/api/clear-subtitles":
                M.update(body)
                shutil.rmtree(uploads(M.settings["source"], M.settings["episode"]), ignore_errors=True)
            elif self.path == "/api/clear-downloads":
                M.call(M.clear_downloads())
            elif self.path == "/api/stop":
                M.call(M.stop())
            elif self.path == "/api/scan":
                M.call(M.scan())
            elif self.path == "/api/new-code" and local:
                M.new_code()
            else:
                return self.send_error(404)
        except (ValueError, TypeError, AttributeError, KeyError, RuntimeError) as e:
            return self.send({"error": str(e)}, code=400)
        self.send(self.state(local))

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    M = Mirror()
    server = ThreadingHTTPServer(("", PORT), Handler)
    asyncio.run_coroutine_threadsafe(M.scan(), M.loop)
    announcer = zeroconf.Zeroconf()
    threading.Thread(target=advertise, args=(announcer,), daemon=True).start()  # takes a couple of seconds
    print(f"Dashboard: http://localhost:{PORT}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        M.call(M.stop())
        announcer.close()
