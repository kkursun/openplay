# Provenance

This package is doubletake's `internal/airplay` (https://github.com/omarroth/doubletake,
commit 5e2f1cbe3e1070bcefec8e549c7baf884727bc00), licensed under the GNU LGPL v3.0 (see LICENSE).
Test files were left out.

Changes:
- capture.go: the Linux-only `Pdeathsig` process attribute moved to procattr_linux.go
  (procattr_other.go returns none), so the package builds on Windows.
- audio.go: `audioPacingRebaseThreshold` raised from 2 ms to 50 ms. Windows timers wake up
  late often enough that every late frame pushed the audio schedule back for good.
- pipe_capture.go (new): `NewPipeCapture` and `NewPipeAudioCapture` take H.264 and PCM
  from pipes instead of GStreamer; the PCM queue is trimmed so no backlog becomes delay.
