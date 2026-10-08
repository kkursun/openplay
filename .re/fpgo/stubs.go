package main

import (
	"crypto/sha512"
	"encoding/hex"
)

func mustDecodeHexFP(s string) []byte {
	b, err := hex.DecodeString(s)
	if err != nil {
		panic(err)
	}
	return b
}

func deriveStreamMasterKey(rawKey, secret []byte, mixPairVerifySecret bool) []byte {
	if !mixPairVerifySecret || len(secret) == 0 {
		return rawKey
	}
	h := sha512.New()
	h.Write(rawKey)
	h.Write(secret)
	return h.Sum(nil)[:16]
}
