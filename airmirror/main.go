// Command airmirror mirrors an H.264 stream to an AirPlay receiver (Apple TV)
// with doubletake's sender, vendored in ./airplay.
//
//	airmirror -target 192.168.1.3 [-audio] -- ffmpeg <capture and encoder args> -f h264 -
//
// The command after "--" must write Annex-B H.264 to stdout. It is started once
// the receiver has accepted the session, so no stale frames queue up during setup.
// {w} and {h} in it become the receiver's screen size (at most -max-height tall),
// the size to encode at, as Apple's own senders do.
// With -audio, stdin carries 44.1 kHz stereo s16le PCM.
//
// With no command, stdin carries both, as packets: a type byte ('v' for H.264,
// 'a' for PCM), a big-endian uint32 length, then that many bytes. Send them
// once "mirroring" is logged; "receiver canvas WxH" before it is the size to
// encode at. The Android app feeds it this way.
//
// Closing stdin ends the session cleanly. Exit status 0 means the session ended
// normally, including from the TV's remote.
package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"

	"openplay/airmirror/airplay"
)

func main() {
	target := flag.String("target", "", "receiver IP address")
	port := flag.Int("port", 7000, "receiver AirPlay port")
	code := flag.String("code", "", "PIN or password, if the receiver asks for one")
	audio := flag.Bool("audio", false, "send the PCM read from stdin as the mirror's sound")
	maxHeight := flag.Int("max-height", 0, "cap for {h}; 0 = the receiver's screen height")
	debug := flag.Bool("debug", false, "verbose protocol logging")
	flag.Parse()
	if *target == "" {
		log.Fatal("usage: airmirror -target IP [-audio] [-- encoder [args...]]")
	}
	airplay.SetDebugMode(*debug)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	if err := run(ctx, stop, *target, *port, *code, *audio, *maxHeight, flag.Args()); err != nil {
		log.Fatal(err)
	}
}

func run(ctx context.Context, stop func(), target string, port int, code string, audio bool, maxHeight int, encoder []string) error {
	var video io.ReadCloser // H.264
	var pcm io.Reader = os.Stdin
	if len(encoder) == 0 {
		video, pcm = demux(os.Stdin, audio, stop)
	} else if !audio {
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

	var enc *exec.Cmd
	// Gone before we exit, so its broken-pipe errors don't bury ours in the shared log.
	defer func() {
		if enc != nil && enc.Process != nil {
			enc.Process.Kill()
			enc.Wait()
		}
	}()
	cfg := airplay.StreamConfig{VideoCodec: airplay.VideoCodecH264, NoAudio: !audio}
	session, err := client.SetupMirrorWithVideoPreparation(ctx, cfg, func(w, h int) error {
		log.Printf("receiver canvas %dx%d", w, h)
		if len(encoder) == 0 {
			return nil
		}
		if maxHeight > 0 && h > maxHeight {
			w, h = w*maxHeight/h, maxHeight
		}
		size := strings.NewReplacer("{w}", strconv.Itoa(w), "{h}", strconv.Itoa(h))
		args := make([]string, len(encoder))
		for i, a := range encoder {
			args[i] = size.Replace(a)
		}
		enc = exec.CommandContext(ctx, args[0], args[1:]...)
		enc.Stderr = os.Stderr
		var err error
		if video, err = enc.StdoutPipe(); err != nil {
			return err
		}
		return enc.Start()
	})
	if err != nil {
		return fmt.Errorf("mirror setup: %w", err)
	}
	defer session.Close()
	if audio && session.HasAudio() {
		pcm, err := airplay.NewPipeAudioCapture(pcm, session.AudioCodec())
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
	err = session.StreamFrames(ctx, airplay.NewPipeCapture(video), 0)
	// The receiver closing the data channel (Menu on the remote) or us stopping is a normal end.
	if ctx.Err() != nil || errors.Is(err, io.EOF) {
		return nil
	}
	return fmt.Errorf("stream: %w", err)
}

// demux splits the packets on r into H.264 and PCM, and ends the session when r closes.
func demux(r io.Reader, audio bool, stop func()) (io.ReadCloser, io.Reader) {
	vr, vw := io.Pipe()
	ar, aw := io.Pipe()
	go func() {
		br := bufio.NewReader(r)
		var head [5]byte
		var err error
		for {
			if _, err = io.ReadFull(br, head[:]); err != nil {
				break
			}
			var w io.Writer = vw
			if head[0] == 'a' {
				w = aw
				if !audio {
					w = io.Discard
				}
			}
			if _, err = io.CopyN(w, br, int64(binary.BigEndian.Uint32(head[1:]))); err != nil {
				break
			}
		}
		stop()
		vw.CloseWithError(err)
		aw.CloseWithError(err)
	}()
	return vr, ar
}
