package core

// How fast a way out carries an upload, measured rather than assumed.
//
// From a server in Iran, the line throttles upload per foreign exit IP: most
// exits carry 10-20 KB/s up while downloading at megabytes, and a few in the
// same city carry the full rate (measured 2026-10-31 from 46.29.34.53: ch-017
// 16 KB/s, ch-019 3 MB/s). Which exit is which does not show in a ping or a
// download, so server + exit asks each candidate to carry a small upload and
// keeps the first that does.

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"io"
	"net"
	"net/http"
	"time"
)

// uploadTarget swallows a POST and answers once it has all of it. Cloudflare's
// own speed test endpoint, by name: the request goes through the way, so the
// lookup is the far end's and not this line's.
const uploadTarget = "https://speed.cloudflare.com/__up"

// UploadRate sends size bytes through w and returns how fast they went, in
// bytes a second, timed to the far end's answer - which only comes once every
// byte has arrived, so buffers along the way cannot flatter a slow exit. A
// throttled exit does not fail, it crawls: past timeout it is an error.
func UploadRate(w Way, size int, timeout time.Duration) (float64, error) {
	body := make([]byte, size)
	_, _ = rand.Read(body)

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	client := &http.Client{Transport: &http.Transport{
		DialContext: func(_ context.Context, _, addr string) (net.Conn, error) {
			conn, spare, err := w.Open(addr, timeout)
			if err != nil {
				return nil, err
			}
			if len(spare) > 0 {
				conn.Close()
				return nil, errors.New("the exit spoke before it was asked to")
			}
			return conn, nil
		},
		DisableKeepAlives: true,
	}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, uploadTarget, bytes.NewReader(body))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/octet-stream")

	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return 0, errors.New("the upload test answered " + resp.Status)
	}
	took := time.Since(start)
	if took <= 0 {
		took = time.Millisecond
	}
	return float64(size) / took.Seconds(), nil
}
