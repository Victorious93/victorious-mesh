package meshproxy

import "testing"

func TestValidateIPAcceptsMeshOnly(t *testing.T) {
	good := []string{"100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1"}
	for _, ip := range good {
		if err := ValidateIP(ip); err != nil {
			t.Fatalf("%s: %v", ip, err)
		}
	}
	bad := []string{
		"",
		"not-an-ip",
		"0.0.0.0",
		"::",
		"127.0.0.1",
		"::1",
		"192.168.1.1",
		"10.0.0.1",
		"172.16.0.1",
		"8.8.8.8",
		"169.254.1.1",
		"224.0.0.1",
		"fe80::1",
	}
	for _, ip := range bad {
		if err := ValidateIP(ip); err == nil {
			t.Fatalf("expected reject for %s", ip)
		}
	}
}

func TestValidatePort(t *testing.T) {
	if err := ValidatePort(1080); err != nil {
		t.Fatal(err)
	}
	if err := ValidatePort(1023); err == nil {
		t.Fatal("expected reject for 1023")
	}
	if err := ValidatePort(0); err == nil {
		t.Fatal("expected reject for 0")
	}
	if err := ValidatePort(65536); err == nil {
		t.Fatal("expected reject for 65536")
	}
}

func TestMatchesSelf(t *testing.T) {
	self := []string{"100.64.1.2/32", "fd7a:115c:a1e0::2/128"}
	if !MatchesSelf("100.64.1.2", self) {
		t.Fatal("v4 self")
	}
	if !MatchesSelf("fd7a:115c:a1e0::2", self) {
		t.Fatal("v6 self")
	}
	if MatchesSelf("100.64.0.1", self) {
		t.Fatal("other mesh IP is not this node")
	}
	if MatchesSelf("0.0.0.0", self) {
		t.Fatal("unspecified")
	}
}

func TestValidateAuth(t *testing.T) {
	if err := ValidateAuth("", ""); err != nil {
		t.Fatal(err)
	}
	if err := ValidateAuth("u", "p"); err != nil {
		t.Fatal(err)
	}
	if err := ValidateAuth("u", ""); err == nil {
		t.Fatal("expected reject for username only")
	}
	if err := ValidateAuth("", "p"); err == nil {
		t.Fatal("expected reject for password only")
	}
}

func TestValidateBind(t *testing.T) {
	if err := ValidateBind("100.64.0.1", 1080); err != nil {
		t.Fatal(err)
	}
	if err := ValidateBind("192.168.1.1", 1080); err == nil {
		t.Fatal("expected reject LAN bind")
	}
	if err := ValidateBind("100.64.0.1", 80); err == nil {
		t.Fatal("expected reject privileged port")
	}
}
