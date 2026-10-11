"""Self-check: reads test clips like the ones TVs choke on and checks what gets converted, how a converting
video's playlist is completed, and how subtitles are found and cut up. Run: python test_media.py"""
import asyncio
import re
import subprocess
import tempfile
from pathlib import Path

from mirror import (DEFAULTS, FFMPEG, AirPlayTV, CastTV, convert_subtitle, cues_between, episodes, full_playlist,
                    hevc_codec, language, paired, pairing_code, parse_probe, plan, probe, start_media_ffmpeg)

# The phone app's pairing code: typed loosely, checked exactly.
code = pairing_code()
assert re.fullmatch(r"[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){2}", code), code
assert paired("Bearer " + code, code) and paired("Bearer " + code.lower().replace("-", ""), code)
assert not paired("Bearer " + code[:-1], code) and not paired("", code) and not paired("Bearer ", "")
assert pairing_code() != code

# ffmpeg's playlist two segments into a conversion: the TV is shown the whole video, to its last second.
GROWING = ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n"
           "#EXT-X-PLAYLIST-TYPE:EVENT\n#EXTINF:4.004000,\nlive0.ts\n#EXTINF:3.962000,\nlive1.ts\n")
whole = full_playlist(GROWING, 1513.45)
assert "PLAYLIST-TYPE:VOD" in whole and whole.endswith("#EXT-X-ENDLIST\n"), whole
assert abs(sum(map(float, re.findall(r"EXTINF:([\d.]+)", whole))) - 1513.45) < 0.01, whole
assert re.findall(r"^live(\d+)\.ts$", whole, flags=re.M) == [str(n) for n in range(379)], whole  # 2 written, 377 to come
assert full_playlist(GROWING + "#EXT-X-ENDLIST\n", 1513.45) == GROWING + "#EXT-X-ENDLIST\n"  # already finished
assert full_playlist(GROWING, 0) == GROWING  # length unknown
assert parse_probe("Input #0, hls, from 'x':\n  Duration: N/A, start: 1.4, bitrate: N/A")["duration"] == 0

# A season pack's episodes in order, without its sample and subtitles; a torrent with no video, its biggest file.
PACK = [("Show/Show.S01E10.mkv", 900), ("Show/Sample/sample.mkv", 20), ("Show/Show.S01E2.mkv", 800),
        ("Show/Show.S01E1.mkv", 850), ("Show/Subs/Show.S01E1.srt", 1)]
assert episodes(PACK) == [3, 2, 0], episodes(PACK)
assert episodes([("Movie/movie.nfo", 5), ("Movie/Movie.iso", 50)]) == [1]

# name: (ffmpeg output options, AirPlay's (copy video, copy audio), Cast's)
CLIPS = {
    "plain.mp4": (["-c:v", "libx264", "-c:a", "aac"], (True, True), (True, True)),
    "hi10.mkv": (["-c:v", "libx264", "-pix_fmt", "yuv420p10le", "-c:a", "ac3"], (False, False), (False, False)),
    "hevc.mkv": (["-c:v", "libx265", "-c:a", "aac"], (False, True), (True, True)),
    "sixty.mp4": (["-r", "60", "-c:v", "libx264", "-c:a", "aac"], (False, True), (True, True)),
}
SRT = "1\n00:00:00,500 --> 00:00:02,000\nŞimdi ığdır'a gidiyoruz\n\n2\n00:00:04,000 --> 00:00:05,000\nİkinci\n"

with tempfile.TemporaryDirectory() as tmp:
    tmp = Path(tmp)
    clip = ["-f", "lavfi", "-i", "testsrc2=size=320x180:duration=1", "-f", "lavfi", "-i", "sine=duration=1"]
    for name, (options, airplay, cast) in CLIPS.items():
        subprocess.run([FFMPEG, "-v", "error", *clip, *options, str(tmp / name)], check=True)
        info = asyncio.run(probe(str(tmp / name)))
        assert ("mp4" if name.endswith(".mp4") else "matroska") in info["container"], (name, info)
        assert 0.9 < info["duration"] < 1.2, (name, info)
        assert plan(info, AirPlayTV) == airplay, (name, info)
        assert plan(info, CastTV) == cast, (name, info)

    # The codec name Cast needs for copied HEVC: Main profile (1), compatibility flags 6, main tier.
    subprocess.run([FFMPEG, "-v", "error", "-i", str(tmp / "hevc.mkv"), "-c", "copy", "-tag:v", "hvc1",
                    "-movflags", "frag_keyframe+empty_moov", str(tmp / "init.mp4")], check=True)
    assert hevc_codec((tmp / "init.mp4").read_bytes()).startswith("hvc1.1.6.L"), hevc_codec((tmp / "init.mp4").read_bytes())

    # A Turkish subtitle file saved the old Windows way, converted to UTF-8 WebVTT.
    (tmp / "tr.srt").write_bytes(SRT.encode("cp1254"))
    assert convert_subtitle(tmp / "tr.srt", tmp / "tr.vtt")
    vtt = (tmp / "tr.vtt").read_text(encoding="utf-8")
    assert "Şimdi ığdır'a gidiyoruz" in vtt, vtt
    assert "Şimdi" in cues_between(vtt, 0, 4) and "İkinci" not in cues_between(vtt, 0, 4)
    assert "İkinci" in cues_between(vtt, 4.5, 8) and cues_between(vtt, 6, 8) == ""

    # Subtitle tracks inside a video, found with their languages.
    subprocess.run([FFMPEG, "-v", "error", *clip, "-i", str(tmp / "tr.vtt"), "-i", str(tmp / "tr.vtt"),
                    "-map", "0", "-map", "1", "-map", "2", "-map", "3", "-c:v", "libx264", "-c:a", "aac", "-c:s", "srt",
                    "-metadata:s:s:0", "language=eng", "-metadata:s:s:1", "language=tur",
                    "-disposition:s:1", "forced", str(tmp / "subs.mkv")], check=True)
    info = asyncio.run(probe(str(tmp / "subs.mkv")))
    assert info["subtitles"] == [("eng", "subrip", False), ("tur", "subrip", True)], info["subtitles"]
    assert language("tur") == ("tr", "Turkish") and language("Movie.2020.en") == ("en", "English")
    assert language("2_Turkish") == ("tr", "Turkish") and language("Director's cut") == ("", "")

    # A TV seeking past what's converted: ffmpeg starts at segment 2 (8 s), timed as if it had come from the start.
    (tmp / "late.srt").write_text("1\n00:00:09,000 --> 00:00:10,000\nLate\n")
    subprocess.run([FFMPEG, "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:duration=12", "-f", "lavfi",
                    "-i", "sine=duration=12", "-i", str(tmp / "late.srt"), "-map", "0", "-map", "1", "-map", "2",
                    "-c:v", "libx264", "-c:a", "aac", "-c:s", "srt", str(tmp / "long.mkv")], check=True)
    info = asyncio.run(probe(str(tmp / "long.mkv")))
    for tv, first, segment in ((AirPlayTV, 9.4, "live2.ts"), (CastTV, 8, "both.mp4")):  # MPEG-TS starts at 1.4 s
        out = tmp / tv.segments
        out.mkdir()
        assert start_media_ffmpeg(out, [str(tmp / "long.mkv")], info, False, False, [0], tv, DEFAULTS, 2).wait() == 0
        if tv is CastTV:
            (out / segment).write_bytes((out / "init.mp4").read_bytes() + (out / "live2.m4s").read_bytes())
        text = subprocess.run([FFMPEG, "-i", str(out / segment)], capture_output=True, text=True).stderr
        assert abs(float(re.search(r"start: ([\d.]+)", text)[1]) - first) < 0.1, (tv, text)
        assert "Late" in cues_between((out / "sub0-2.vtt").read_text(encoding="utf-8"), 8, 12)

    # A web video's picture and sound in separate files, as YouTube serves them: probed and converted together,
    # both from where the TV skipped to.
    subprocess.run([FFMPEG, "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:duration=12",
                    "-c:v", "libx264", str(tmp / "picture.mp4")], check=True)
    subprocess.run([FFMPEG, "-v", "error", "-f", "lavfi", "-i", "sine=duration=12", "-c:a", "aac",
                    str(tmp / "sound.m4a")], check=True)
    apart = [str(tmp / "picture.mp4"), str(tmp / "sound.m4a")]
    info = asyncio.run(probe(*apart))
    assert (info["video"], info["audio"]) == ("h264", "aac") and plan(info, AirPlayTV) == (True, True), info
    out = tmp / "apart"
    out.mkdir()
    assert start_media_ffmpeg(out, apart, info, True, True, [], AirPlayTV, DEFAULTS, 2).wait() == 0
    text = subprocess.run([FFMPEG, "-i", str(out / "live2.ts")], capture_output=True, text=True).stderr
    assert "Video: h264" in text and "Audio: aac" in text, text

    (tmp / "junk.mp4").write_text("not a video")
    try:
        asyncio.run(probe(str(tmp / "junk.mp4")))
        raise AssertionError("junk probed")
    except RuntimeError:
        pass
print("ok")
