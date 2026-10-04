# openplay for Android

One app, two ways to get something onto the TV:

- **This phone** – the phone does the work itself, no PC needed. It mirrors its screen (and the sound
  of apps that allow it) or plays a video file on the phone, a web URL, or a torrent (magnet link or
  `.torrent`, played while it downloads) on an Apple TV 3 (AirPlay 1) or a Google Cast device such as
  a Chromecast or a Xiaomi TV box. Subtitle files from the torrent, or added in the app, come along.
- **PC remote** – drives `mirror.py` running on a PC: everything its dashboard does, from the phone.
  Pair once with the code in the dashboard's *Phone* tab; the app finds the PC on the network by
  itself (or type the address the dashboard shows).

Links and videos shared to openplay from other apps (or magnet links opened with it) land in the
Play box.

## What the phone can't do that the PC can

The phone has no ffmpeg, so it never re-encodes video:

- Videos the TV can play as they are (MP4s, or H.264 in MKV and other containers, which the phone
  repackages into HLS on the fly) play from the phone. Sound the TV can't play (AC-3, DTS, MP3 on
  Cast…) is converted to AAC when the phone has a decoder for it.
- Anything needing its picture converted (HEVC on an Apple TV, 10-bit H.264, 4K on an Apple TV, HEVC
  in MKV on Cast…) has to be played from the PC: the app says so and points to the PC tab.
- Subtitle tracks inside a video aren't extracted; subtitle files (`.srt`, `.ass`, `.ssa`, `.vtt`) are.
- AirPlay 2 devices that require pairing (newer Apple TVs, Macs) only work from the PC.

## Build

Needs JDK 17+ and the Android SDK (platform 37):

    ./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
    ./gradlew testDebugUnitTest   # some tests check the muxer's output with ffmpeg, if installed

The release APK is signed with the building machine's debug key, for installing by hand (allow
installs from unknown sources). An APK built on another machine has another signature, so Android
won't update one with the other: uninstall first.

Android 10 or later. Mirroring asks for screen sharing (and the microphone permission, which
Android requires for capturing other apps' sound); the notification while streaming has a Stop
button.
