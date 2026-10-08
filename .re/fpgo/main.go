package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"
)

func post(url string, body []byte) ([]byte, error) {
	req, err := http.NewRequest("POST", url, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("X-Apple-ET", "32")
	req.Header.Set("X-Apple-ProtocolVersion", "1")
	cli := &http.Client{Timeout: 15 * time.Second}
	resp, err := cli.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != 200 {
		return b, fmt.Errorf("status %d", resp.StatusCode)
	}
	return b, nil
}

func main() {
	host := "192.168.1.3:7000"
	if len(os.Args) > 1 {
		host = os.Args[1]
	}
	url := "http://" + host + "/fp-setup"

	session, err := newFPSAPSession(rand.Reader)
	if err != nil {
		fmt.Println("session error:", err)
		os.Exit(1)
	}
	m1 := session.message1()
	fmt.Println("m1:", hex.EncodeToString(m1))

	m2, err := post(url, m1)
	if err != nil {
		fmt.Printf("fp-setup m1 failed: %v (body=%x)\n", err, m2)
		os.Exit(2)
	}
	fmt.Println("m2:", hex.EncodeToString(m2))

	m3, err := session.exchangeM3(m2)
	if err != nil {
		fmt.Println("m3 error:", err)
		os.Exit(3)
	}
	fmt.Println("m3:", hex.EncodeToString(m3))

	m4, err := post(url, m3)
	if err != nil {
		fmt.Printf("fp-setup m3 failed: %v (body=%x)\n", err, m4)
		os.Exit(4)
	}
	if err := session.confirmM4(m4); err != nil {
		fmt.Println("m4 verify failed:", err)
		os.Exit(5)
	}
	fmt.Println("m4:", hex.EncodeToString(m4))
	fmt.Println("m4 OK")

	var rawKey [16]byte
	var iv [16]byte
	if _, err := rand.Read(rawKey[:]); err != nil {
		panic(err)
	}
	if _, err := rand.Read(iv[:]); err != nil {
		panic(err)
	}
	ekey, err := session.wrapKey(rawKey, rand.Reader)
	if err != nil {
		fmt.Println("wrap error:", err)
		os.Exit(6)
	}
	fmt.Println("rawkey:", hex.EncodeToString(rawKey[:]))
	fmt.Println("eiv:", hex.EncodeToString(iv[:]))
	fmt.Println("ekey:", hex.EncodeToString(ekey[:]))
	fmt.Println("ekeylen:", len(ekey))
}
