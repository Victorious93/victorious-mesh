// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package meshshare

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const tok = "0123456789abcdefXYZ"

func setup(t *testing.T) (string, http.Handler) {
	t.Helper()
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "notes.txt"), []byte("hello mesh"), 0o600)
	os.WriteFile(filepath.Join(dir, "a b<c>.bin"), []byte{1, 2, 3}, 0o600)
	os.WriteFile(filepath.Join(dir, ".hidden"), []byte("nope"), 0o600)
	os.Mkdir(filepath.Join(dir, "sub"), 0o700)
	outside := filepath.Join(t.TempDir(), "secret.txt")
	os.WriteFile(outside, []byte("secret"), 0o600)
	os.Symlink(outside, filepath.Join(dir, "link.txt"))
	return dir, Handler(dir, tok)
}

func do(h http.Handler, method, path, pass string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, nil)
	if pass != "" {
		r.SetBasicAuth("mesh", pass)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestAuthRequired(t *testing.T) {
	_, h := setup(t)
	for _, p := range []string{"", "wrong-password-xxxxxxxx"} {
		w := do(h, "GET", "/", p)
		if w.Code != http.StatusUnauthorized || !strings.Contains(w.Header().Get("WWW-Authenticate"), "Basic") {
			t.Fatalf("pass %q: code %d", p, w.Code)
		}
	}
	if w := do(Handler(t.TempDir(), ""), "GET", "/", "x"); w.Code != http.StatusUnauthorized {
		t.Fatal("empty token must never authenticate")
	}
}

func TestReadOnly(t *testing.T) {
	_, h := setup(t)
	for _, m := range []string{"POST", "PUT", "DELETE", "PATCH"} {
		if w := do(h, m, "/f/notes.txt", tok); w.Code != http.StatusMethodNotAllowed {
			t.Fatalf("%s: %d", m, w.Code)
		}
	}
}

func TestIndexListsOnlyPlainFilesEscaped(t *testing.T) {
	_, h := setup(t)
	w := do(h, "GET", "/", tok)
	body := w.Body.String()
	if w.Code != 200 || !strings.Contains(body, "notes.txt") {
		t.Fatalf("code %d body %s", w.Code, body)
	}
	if strings.Contains(body, ".hidden") || strings.Contains(body, "link.txt") || strings.Contains(body, ">sub<") {
		t.Fatal("hidden, symlink, or folder listed")
	}
	if strings.Contains(body, "<c>") || !strings.Contains(body, "a b&lt;c&gt;.bin") {
		t.Fatal("name not HTML-escaped")
	}
	if strings.Contains(strings.ToLower(body), "#fff") || !strings.Contains(body, "#0B0F1A") {
		t.Fatal("index must use the dark neon theme")
	}
	if w.Header().Get("X-Content-Type-Options") != "nosniff" {
		t.Fatal("missing nosniff")
	}
}

func TestIndexJSON(t *testing.T) {
	_, h := setup(t)
	w := do(h, "GET", "/index.json", tok)
	var got struct{ Files []File }
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if len(got.Files) != 2 || got.Files[1].Name != "notes.txt" || got.Files[1].Size != 10 {
		t.Fatalf("%+v", got.Files)
	}
}

func TestDownload(t *testing.T) {
	_, h := setup(t)
	w := do(h, "GET", "/f/notes.txt", tok)
	b, _ := io.ReadAll(w.Body)
	if w.Code != 200 || string(b) != "hello mesh" {
		t.Fatalf("%d %q", w.Code, b)
	}
	if !strings.HasPrefix(w.Header().Get("Content-Disposition"), "attachment") {
		t.Fatal("must download as attachment")
	}
	if w := do(h, "GET", "/f/a%20b%3Cc%3E.bin", tok); w.Code != 200 {
		t.Fatalf("escaped name: %d", w.Code)
	}
}

func TestNoEscapeFromFolder(t *testing.T) {
	_, h := setup(t)
	for _, p := range []string{"/f/../secret.txt", "/f/link.txt", "/f/.hidden", "/f/sub", "/f/", "/f/%2e%2e%2fsecret.txt", "/etc/passwd"} {
		if w := do(h, "GET", p, tok); w.Code == 200 {
			t.Fatalf("%s served", p)
		}
	}
}

func TestCleanNameAndToken(t *testing.T) {
	for _, bad := range []string{"", ".", "..", ".x", "a/b", `a\b`, "a\x00b"} {
		if _, err := CleanName(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
	if _, err := CleanName("photo 1.jpg"); err != nil {
		t.Fatal(err)
	}
	if ValidToken("short") == nil || ValidToken("has space 0123456789") == nil || ValidToken(tok) != nil {
		t.Fatal("token rules")
	}
}
