// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package wgquick

// wg-quick style configuration parsing for WireGuard tunnel mode.
// Written from scratch for Victorious Mesh (BSD-3-Clause). Secrets parsed
// here (PrivateKey, PresharedKey) must never be logged; String/summary
// helpers deliberately omit them.

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

// DefaultMTU matches wg-quick's conservative mobile default.
const DefaultMTU = 1280

type wgKey [32]byte

func (k wgKey) hex() string { return hex.EncodeToString(k[:]) }

func parseWGKey(s string) (wgKey, error) {
	var k wgKey
	b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(s))
	if err != nil || len(b) != 32 {
		return k, errors.New("key must be 32 bytes, base64-encoded")
	}
	copy(k[:], b)
	return k, nil
}

type Peer struct {
	PublicKey           wgKey
	PresharedKey        *wgKey
	AllowedIPs          []netip.Prefix
	Endpoint            string // host:port as written; resolved at start
	PersistentKeepalive int
}

type Config struct {
	PrivateKey    wgKey
	Addresses     []netip.Prefix
	DNSServers    []netip.Addr
	SearchDomains []string
	MTU           int
	ListenPort    int
	Peers         []Peer
}

// wgIgnoredInterfaceKeys are wg-quick keys that only make sense for the
// Linux wg-quick script. They are accepted and ignored.
var wgIgnoredInterfaceKeys = map[string]bool{
	"table": true, "preup": true, "postup": true, "predown": true,
	"postdown": true, "saveconfig": true, "fwmark": true,
}

func splitList(v string) []string {
	var out []string
	for _, p := range strings.Split(v, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func parsePrefixOrAddr(s string) (netip.Prefix, error) {
	if strings.Contains(s, "/") {
		return netip.ParsePrefix(s)
	}
	a, err := netip.ParseAddr(s)
	if err != nil {
		return netip.Prefix{}, err
	}
	return netip.PrefixFrom(a, a.BitLen()), nil
}

// Parse parses a wg-quick .conf file.
func Parse(text string) (*Config, error) {
	cfg := &Config{}
	section := ""
	var peer *Peer
	havePriv := false
	sc := bufio.NewScanner(strings.NewReader(text))
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	lineNo := 0
	finishPeer := func() error {
		if peer == nil {
			return nil
		}
		var zero wgKey
		if peer.PublicKey == zero {
			return errors.New("[Peer] is missing PublicKey")
		}
		cfg.Peers = append(cfg.Peers, *peer)
		peer = nil
		return nil
	}
	for sc.Scan() {
		lineNo++
		line := sc.Text()
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = line[:i]
		}
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			if err := finishPeer(); err != nil {
				return nil, err
			}
			section = strings.ToLower(strings.TrimSpace(line[1 : len(line)-1]))
			switch section {
			case "interface":
			case "peer":
				peer = &Peer{}
			default:
				return nil, fmt.Errorf("line %d: unknown section [%s]", lineNo, section)
			}
			continue
		}
		eq := strings.IndexByte(line, '=')
		if eq < 0 {
			return nil, fmt.Errorf("line %d: expected key = value", lineNo)
		}
		key := strings.ToLower(strings.TrimSpace(line[:eq]))
		val := strings.TrimSpace(line[eq+1:])
		switch section {
		case "interface":
			switch key {
			case "privatekey":
				k, err := parseWGKey(val)
				if err != nil {
					return nil, fmt.Errorf("line %d: PrivateKey: %v", lineNo, err)
				}
				cfg.PrivateKey, havePriv = k, true
			case "address":
				for _, s := range splitList(val) {
					p, err := parsePrefixOrAddr(s)
					if err != nil {
						return nil, fmt.Errorf("line %d: invalid Address %q", lineNo, s)
					}
					cfg.Addresses = append(cfg.Addresses, p)
				}
			case "dns":
				for _, s := range splitList(val) {
					if a, err := netip.ParseAddr(s); err == nil {
						cfg.DNSServers = append(cfg.DNSServers, a)
					} else {
						cfg.SearchDomains = append(cfg.SearchDomains, s)
					}
				}
			case "mtu":
				n, err := strconv.Atoi(val)
				if err != nil || n < 576 || n > 65535 {
					return nil, fmt.Errorf("line %d: invalid MTU %q", lineNo, val)
				}
				cfg.MTU = n
			case "listenport":
				n, err := strconv.Atoi(val)
				if err != nil || n < 0 || n > 65535 {
					return nil, fmt.Errorf("line %d: invalid ListenPort %q", lineNo, val)
				}
				cfg.ListenPort = n
			default:
				if !wgIgnoredInterfaceKeys[key] {
					return nil, fmt.Errorf("line %d: unknown [Interface] key %q", lineNo, key)
				}
			}
		case "peer":
			switch key {
			case "publickey":
				k, err := parseWGKey(val)
				if err != nil {
					return nil, fmt.Errorf("line %d: PublicKey: %v", lineNo, err)
				}
				peer.PublicKey = k
			case "presharedkey":
				k, err := parseWGKey(val)
				if err != nil {
					return nil, fmt.Errorf("line %d: PresharedKey: %v", lineNo, err)
				}
				peer.PresharedKey = &k
			case "allowedips":
				for _, s := range splitList(val) {
					p, err := parsePrefixOrAddr(s)
					if err != nil {
						return nil, fmt.Errorf("line %d: invalid AllowedIPs %q", lineNo, s)
					}
					peer.AllowedIPs = append(peer.AllowedIPs, p.Masked())
				}
			case "endpoint":
				host, port, err := net.SplitHostPort(val)
				if err != nil || host == "" {
					return nil, fmt.Errorf("line %d: invalid Endpoint %q", lineNo, val)
				}
				if n, err := strconv.Atoi(port); err != nil || n < 1 || n > 65535 {
					return nil, fmt.Errorf("line %d: invalid Endpoint port %q", lineNo, port)
				}
				peer.Endpoint = val
			case "persistentkeepalive":
				if strings.EqualFold(val, "off") {
					peer.PersistentKeepalive = 0
					break
				}
				n, err := strconv.Atoi(val)
				if err != nil || n < 0 || n > 65535 {
					return nil, fmt.Errorf("line %d: invalid PersistentKeepalive %q", lineNo, val)
				}
				peer.PersistentKeepalive = n
			default:
				return nil, fmt.Errorf("line %d: unknown [Peer] key %q", lineNo, key)
			}
		default:
			return nil, fmt.Errorf("line %d: key outside of a section", lineNo)
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	if err := finishPeer(); err != nil {
		return nil, err
	}
	if !havePriv {
		return nil, errors.New("[Interface] is missing PrivateKey")
	}
	if len(cfg.Addresses) == 0 {
		return nil, errors.New("[Interface] is missing Address")
	}
	if len(cfg.Peers) == 0 {
		return nil, errors.New("config has no [Peer]")
	}
	if cfg.MTU == 0 {
		cfg.MTU = DefaultMTU
	}
	return cfg, nil
}

// routes returns the deduplicated union of all peers' AllowedIPs.
func (c *Config) Routes() []netip.Prefix {
	seen := map[netip.Prefix]bool{}
	var out []netip.Prefix
	for _, p := range c.Peers {
		for _, r := range p.AllowedIPs {
			if !seen[r] {
				seen[r] = true
				out = append(out, r)
			}
		}
	}
	return out
}

// uapi renders the config in wireguard-go's IpcSet format. resolve turns a
// "host:port" endpoint into an address; it is injectable for tests.
func (c *Config) UAPI(resolve func(string) (netip.AddrPort, error)) (string, error) {
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\n", c.PrivateKey.hex())
	if c.ListenPort > 0 {
		fmt.Fprintf(&b, "listen_port=%d\n", c.ListenPort)
	}
	b.WriteString("replace_peers=true\n")
	for _, p := range c.Peers {
		fmt.Fprintf(&b, "public_key=%s\n", p.PublicKey.hex())
		if p.PresharedKey != nil {
			fmt.Fprintf(&b, "preshared_key=%s\n", p.PresharedKey.hex())
		}
		if p.Endpoint != "" {
			ap, err := resolve(p.Endpoint)
			if err != nil {
				return "", fmt.Errorf("resolving endpoint %q: %w", p.Endpoint, err)
			}
			fmt.Fprintf(&b, "endpoint=%s\n", ap.String())
		}
		fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", p.PersistentKeepalive)
		b.WriteString("replace_allowed_ips=true\n")
		for _, r := range p.AllowedIPs {
			fmt.Fprintf(&b, "allowed_ip=%s\n", r.String())
		}
	}
	return b.String(), nil
}

// ResolveEndpoint resolves host:port, preferring IPv4 like wg-quick does.
func ResolveEndpoint(hostport string) (netip.AddrPort, error) {
	if ap, err := netip.ParseAddrPort(hostport); err == nil {
		return ap, nil
	}
	host, portStr, err := net.SplitHostPort(hostport)
	if err != nil {
		return netip.AddrPort{}, err
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return netip.AddrPort{}, err
	}
	ips, err := net.LookupIP(host)
	if err != nil {
		return netip.AddrPort{}, err
	}
	var pick netip.Addr
	for _, ip := range ips {
		a, ok := netip.AddrFromSlice(ip)
		if !ok {
			continue
		}
		a = a.Unmap()
		if a.Is4() {
			pick = a
			break
		}
		if !pick.IsValid() {
			pick = a
		}
	}
	if !pick.IsValid() {
		return netip.AddrPort{}, fmt.Errorf("no addresses for %q", host)
	}
	return netip.AddrPortFrom(pick, uint16(port)), nil
}

// wgSummary is a secret-free description of a config for the UI.
type wgSummary struct {
	Addresses  []string `json:"addresses"`
	DNS        []string `json:"dns"`
	MTU        int      `json:"mtu"`
	PeerCount  int      `json:"peer_count"`
	Endpoints  []string `json:"endpoints"`
	AllowedIPs []string `json:"allowed_ips"`
}

func (c *Config) SummaryJSON() string {
	s := wgSummary{MTU: c.MTU, PeerCount: len(c.Peers), Addresses: []string{}, DNS: []string{}, Endpoints: []string{}, AllowedIPs: []string{}}
	for _, a := range c.Addresses {
		s.Addresses = append(s.Addresses, a.String())
	}
	for _, d := range c.DNSServers {
		s.DNS = append(s.DNS, d.String())
	}
	s.DNS = append(s.DNS, c.SearchDomains...)
	for _, p := range c.Peers {
		if p.Endpoint != "" {
			s.Endpoints = append(s.Endpoints, p.Endpoint)
		}
	}
	for _, r := range c.Routes() {
		s.AllowedIPs = append(s.AllowedIPs, r.String())
	}
	b, _ := json.Marshal(s)
	return string(b)
}
