# openplay for Android

One app, two ways to get something onto the TV:

- **This phone** – the phone does the work itself, no PC needed. It mirrors its screen (and the sound
  of apps that allow it) or plays a video file on the phone, a web URL, or a torrent (magnet link or
  `.torrent`, played while it downloads) on an Apple TV 3 (AirPlay 1) or a Google Cast device such as
  a Chromecast or a Xiaomi TV box. Subtitle files from the torrent, or added in the app, come along.
- **PC remote** – drives `mirror.py` running on a PC: everything its dashboard does, from the phone.
  Pair once with the code in the dashboard's *Phone* tab; the app finds the PC on the network by
  itself (or type the address the dashboard shows).

Mirroring an Apple TV 3 from an arm64 phone (nearly all of them) uses AirPlay's own screen mirroring,
through airmirror (the repository's `airmirror/`, built into the app), so it's close to live. Other
phones and TVs get an HLS stream, a few seconds behind.

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

## Install

Download the APK for your phone from the repository's
[Releases](https://github.com/kkursun/openplay/releases) (arm64-v8a fits nearly every recent phone)
and open it, allowing installs from unknown sources when asked. Android 10 or later.

Mirroring asks for screen sharing (and the microphone permission, which Android requires for
capturing other apps' sound); the notification while streaming has a Stop button.

## Build

Needs JDK 17+, the Android SDK (platform 37) and Go (it builds `../airmirror` into the app):

    ./gradlew assembleRelease     # APKs per processor type in app/build/outputs/apk/release/
    ./gradlew testDebugUnitTest   # some tests check the muxer's output with ffmpeg, if installed

Releases are made by GitHub Actions, which tests, builds and attaches the APKs: run the *Android
release* workflow from the Actions tab with a version (it makes the tag and the release), publish a
release on GitHub, or push a tag like `v1.1`. Versions with a dash, like `1.1-beta`, are pre-releases.

Android only updates an app with an APK signed by the same key. Without one of your own, APKs are
signed with the building machine's debug key, which differs from machine to machine (GitHub's
included), so updating means uninstalling first. To sign every release alike, make a key:

    keytool -genkeypair -alias openplay -keyalg RSA -validity 10000 -storetype PKCS12 -keystore openplay.p12

and add it to the repository's Actions secrets as `OPENPLAY_KEYSTORE_BASE64` (`base64 -w0 openplay.p12`)
and `OPENPLAY_KEY_PASSWORD`. Locally, set `OPENPLAY_KEYSTORE` to the file and `OPENPLAY_KEY_PASSWORD`.
