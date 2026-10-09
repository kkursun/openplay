package airplay

import "io"

// NewPipeCapture streams Annex-B H.264 read from r (for example an ffmpeg
// stdout pipe) instead of a GStreamer capture. The caller owns the encoder
// process; Stop only closes r.
func NewPipeCapture(r io.ReadCloser) *ScreenCapture {
	return &ScreenCapture{stdout: r, streamOnly: true}
}

// NewPipeAudioCapture encodes 44.1 kHz stereo s16le PCM read from r with the
// codec the session negotiated. Don't call Stop on it: there is no process to wait for.
func NewPipeAudioCapture(r io.Reader, codec AudioCodec) (*AudioCapture, error) {
	ac := &AudioCapture{pcmPipe: io.NopCloser(r), waitCh: make(chan struct{}), codec: codec}
	if codec == AudioCodecAACELD {
		var err error
		if ac.eld, err = newELDEncoder(); err != nil {
			return nil, err
		}
	}
	return ac, nil
}
