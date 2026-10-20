package upstreamproxy

import (
	"testing"
)

func TestDestAndMesh(t *testing.T) {
	// IPv4 header dest 8.8.8.8
	v4 := make([]byte, 20)
	v4[0] = 0x45
	copy(v4[16:20], []byte{8, 8, 8, 8})
	addr, ok := DestAddr(v4)
	if !ok || addr.String() != "8.8.8.8" {
		t.Fatalf("%v %v", addr, ok)
	}
	if IsMeshDest(v4) {
		t.Fatal("public dest")
	}

	mesh := make([]byte, 20)
	mesh[0] = 0x45
	copy(mesh[16:20], []byte{100, 64, 0, 1})
	if !IsMeshDest(mesh) {
		t.Fatal("mesh dest")
	}
}

func TestTransportTCP(t *testing.T) {
	pkt := make([]byte, 24)
	pkt[0] = 0x45
	pkt[9] = protoTCP
	pkt[20], pkt[21] = 0x04, 0xd2 // 1234
	pkt[22], pkt[23] = 0x01, 0xbb // 443
	proto, src, dst, ok := Transport(pkt)
	if !ok || proto != protoTCP || src != 1234 || dst != 443 {
		t.Fatalf("%v %v %v %v", proto, src, dst, ok)
	}
}
