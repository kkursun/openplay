package airplay

import (
	"io"
	"sync"
	"time"
)

// NewPipeCapture streams Annex-B H.264 read from r (for example an ffmpeg
// stdout pipe) instead of a GStreamer capture. The caller owns the encoder
// process; Stop only closes r.
func NewPipeCapture(r io.ReadCloser) *ScreenCapture {
	return &ScreenCapture{stdout: r, streamOnly: true}
}

// NewPipeAudioCapture encodes 44.1 kHz stereo s16le PCM read from r with the
// codec the session negotiated. Don't call Stop on it: there is no process to wait for.
func NewPipeAudioCapture(r io.Reader, codec AudioCodec) (*AudioCapture, error) {
	ac := &AudioCapture{pcmPipe: io.NopCloser(newFreshPCM(r)), waitCh: make(chan struct{}), codec: codec}
	if codec == AudioCodecAACELD {
		var err error
		if ac.eld, err = newELDEncoder(); err != nil {
			return nil, err
		}
	}
	return ac, nil
}

// freshPCM reads PCM in the background and drops the oldest whole sample
// frames when more has been waiting than needed. Untimestamped audio is paced
// at real time from the first frame read, so anything left queued (a producer
// running ahead, a stall the pacer could not catch up from) would make the
// sound lag the video for the rest of the session.
type freshPCM struct {
	mu      sync.Mutex
	ready   *sync.Cond
	buf     []byte
	err     error
	low     int // least queued since the last trim: what never got used
	dropped int
	peak    int
}

const (
	// ponytail: fixed jitter target; above it the queue is trimmed every pcmTrimEvery
	pcmQueueTarget = 20 * audioSampleRate / 1000 * audioBytesPerSampleFrame
	pcmTrimEvery   = time.Second
)

func newFreshPCM(r io.Reader) *freshPCM {
	p := &freshPCM{}
	p.ready = sync.NewCond(&p.mu)
	go p.fill(r)
	return p
}

func (p *freshPCM) fill(r io.Reader) {
	chunk := make([]byte, 32<<10)
	lastTrim := time.Now()
	for {
		n, err := r.Read(chunk)
		p.mu.Lock()
		p.buf = append(p.buf, chunk[:n]...)
		p.peak = max(p.peak, len(p.buf))
		if time.Since(lastTrim) >= pcmTrimEvery {
			if extra := p.low - pcmQueueTarget; extra > 0 {
				drop := extra / audioBytesPerSampleFrame * audioBytesPerSampleFrame
				p.buf = p.buf[drop:]
				p.dropped += drop
			}
			dbg("[AUDIO] PCM queue peak %v, dropped %v", pcmDuration(p.peak), pcmDuration(p.dropped))
			p.low, p.peak, p.dropped, lastTrim = len(p.buf), 0, 0, time.Now()
		}
		if err != nil {
			p.err = err
		}
		p.ready.Broadcast()
		p.mu.Unlock()
		if err != nil {
			return
		}
	}
}

func (p *freshPCM) Read(b []byte) (int, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for len(p.buf) == 0 && p.err == nil {
		p.ready.Wait()
	}
	if len(p.buf) == 0 {
		return 0, p.err
	}
	n := copy(b, p.buf)
	p.buf = p.buf[n:]
	p.low = min(p.low, len(p.buf))
	return n, nil
}

func pcmDuration(bytes int) time.Duration {
	return time.Duration(bytes/audioBytesPerSampleFrame) * time.Second / audioSampleRate
}
