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

func TestIsDNSAndMeshDNS(t *testing.T) {
	udp53 := make([]byte, 24)
	udp53[0] = 0x45
	udp53[9] = protoUDP
	copy(udp53[16:20], []byte{8, 8, 8, 8})
	udp53[22], udp53[23] = 0, 53
	if !IsDNS(udp53) || IsMeshDNS(udp53) || IsIPv6(udp53) {
		t.Fatalf("public dns: dns=%v mesh=%v v6=%v", IsDNS(udp53), IsMeshDNS(udp53), IsIPv6(udp53))
	}

	magic := make([]byte, 24)
	magic[0] = 0x45
	magic[9] = protoUDP
	copy(magic[16:20], []byte{100, 100, 100, 100})
	magic[22], magic[23] = 0, 53
	if !IsDNS(magic) || !IsMeshDNS(magic) {
		t.Fatal("MagicDNS 100.100.100.100:53 must stay on mesh")
	}

	https := make([]byte, 24)
	https[0] = 0x45
	https[9] = protoTCP
	https[22], https[23] = 0x01, 0xbb
	if IsDNS(https) {
		t.Fatal("443 is not DNS")
	}

	v6 := make([]byte, 44)
	v6[0] = 0x60
	v6[6] = protoUDP
	v6[42], v6[43] = 0, 53
	if !IsIPv6(v6) || !IsDNS(v6) {
		t.Fatal("ipv6 udp/53")
	}

	// fd7a:115c:a1e0:: is mesh; MagicDNS IPv6 stays on the mesh.
	mesh6 := make([]byte, 44)
	mesh6[0] = 0x60
	mesh6[6] = protoUDP
	copy(mesh6[24:40], []byte{0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})
	mesh6[42], mesh6[43] = 0, 53
	if !IsIPv6(mesh6) || !IsDNS(mesh6) || !IsMeshDNS(mesh6) {
		t.Fatal("mesh ipv6 dns must stay on mesh")
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
