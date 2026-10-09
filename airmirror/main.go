// Command airmirror mirrors an H.264 stream to an AirPlay receiver (Apple TV)
// with doubletake's sender, vendored in ./airplay.
//
//	airmirror -target 192.168.1.3 [-audio] -- ffmpeg <capture and encoder args> -f h264 -
//
// The command after "--" must write Annex-B H.264 to stdout. It is started once
// the receiver has accepted the session, so no stale frames queue up during setup.
// With -audio, stdin carries 44.1 kHz stereo s16le PCM. Closing stdin ends the
// session cleanly. Exit status 0 means the session ended normally, including
// from the TV's remote.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"os/signal"

	"openplay/airmirror/airplay"
)

func main() {
	target := flag.String("target", "", "receiver IP address")
	port := flag.Int("port", 7000, "receiver AirPlay port")
	code := flag.String("code", "", "PIN or password, if the receiver asks for one")
	audio := flag.Bool("audio", false, "send the PCM read from stdin as the mirror's sound")
	debug := flag.Bool("debug", false, "verbose protocol logging")
	flag.Parse()
	if *target == "" || flag.NArg() == 0 {
		log.Fatal("usage: airmirror -target IP [-audio] -- encoder [args...]")
	}
	airplay.SetDebugMode(*debug)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	if err := run(ctx, stop, *target, *port, *code, *audio, flag.Args()); err != nil {
		log.Fatal(err)
	}
}

func run(ctx context.Context, stop func(), target string, port int, code string, audio bool, encoder []string) error {
	if !audio {
		go func() { io.Copy(io.Discard, os.Stdin); stop() }()
	}
	client := airplay.NewAirPlayClient(target, port)
	if err := client.Connect(ctx); err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	defer client.Close()
	info, err := client.GetInfo()
	if err != nil {
		return fmt.Errorf("get info: %w", err)
	}
	log.Printf("receiver: %s (%s, sourceVersion %s)", info.Name, info.Model, info.SourceVersion)
	if err := client.Pair(ctx, code); err != nil {
		return fmt.Errorf("pair: %w (if the TV shows a code, pass it with -code)", err)
	}
	// FairPlay must run on this same connection: the receiver unwraps ekey with
	// the state the handshake left on it.
	if err := client.FairPlaySetup(ctx); err != nil && !errors.Is(err, airplay.ErrFairPlayUnsupported) {
		return fmt.Errorf("fairplay: %w", err)
	}

	enc := exec.CommandContext(ctx, encoder[0], encoder[1:]...)
	enc.Stderr = os.Stderr
	stdout, err := enc.StdoutPipe()
	if err != nil {
		return fmt.Errorf("encoder pipe: %w", err)
	}
	cfg := airplay.StreamConfig{VideoCodec: airplay.VideoCodecH264, NoAudio: !audio}
	session, err := client.SetupMirrorWithVideoPreparation(ctx, cfg, func(w, h int) error {
		log.Printf("receiver canvas %dx%d", w, h)
		return enc.Start()
	})
	if err != nil {
		return fmt.Errorf("mirror setup: %w", err)
	}
	defer session.Close()
	if audio && session.HasAudio() {
		pcm, err := airplay.NewPipeAudioCapture(os.Stdin, session.AudioCodec())
		if err != nil {
			return fmt.Errorf("audio: %w", err)
		}
		go func() {
			err := session.StreamAudio(ctx, pcm, session.AudioStream())
			if err != nil && ctx.Err() == nil && !errors.Is(err, io.ErrUnexpectedEOF) && !errors.Is(err, io.EOF) {
				log.Printf("audio ended: %v", err)
			}
			stop() // stdin closed, or the audio failed
		}()
	}
	log.Printf("mirroring (data port %d)", session.DataPort)
	err = session.StreamFrames(ctx, airplay.NewPipeCapture(stdout), 0)
	// The receiver closing the data channel (Menu on the remote) or us stopping is a normal end.
	if ctx.Err() != nil || errors.Is(err, io.EOF) {
		return nil
	}
	return fmt.Errorf("stream: %w", err)
}
