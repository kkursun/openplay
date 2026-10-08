# NOTICE — FairPlay core provenance

The Go files in this directory are **copies of source from
[`omarroth/doubletake`](https://github.com/omarroth/doubletake)**
(`internal/airplay/`), used unmodified apart from the package clause
(`package airplay` -> `package main`), plus a small `main.go` CLi wrapper and
`stubs.go` test shims.

Upstream files copied:
- `fairplay_sap.go`
- `fpsap.go`
- `fairplay_crypto.go`
- `fairplay_md5.go`
- `fairplay_message.go`
- `fpsap_tables.go`

doubletake is licensed **LGPL-3.0-or-later**. These files therefore remain under
that licence (the upstream repo carries `LICENSE` (LGPL-3.0) and `COPYING.GPL`).

Purpose: a standalone CLI (`fpcli`) that performs the FairPlay SAP handshake and
emits the 72-byte `ekey` / 16-byte `eiv` for the AirPlay mirroring sender in
`../airplay_mirror.py`. It is used here for interoperability research.

Build: `go build -o fpcli.exe .` (Go 1.22+; stdlib only).
Validate: `go test -count=1 ./...` runs doubletake's own golden vectors.
