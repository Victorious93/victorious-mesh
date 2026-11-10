// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

// Package meshshare serves one flat folder of files, read-only, to other
// mesh devices. Every request needs the share password (HTTP Basic auth,
// any user name). No uploads, no sub-folders, no hidden files. No Android
// imports; plain Go tests.
package meshshare

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// MinTokenLen is the shortest share password accepted.
const MinTokenLen = 16

var ErrBadName = errors.New("bad file name")

// ValidToken reports whether token is long enough and has no spaces.
func ValidToken(token string) error {
	if len(token) < MinTokenLen {
		return fmt.Errorf("share password must be at least %d characters", MinTokenLen)
	}
	if strings.ContainsAny(token, " \t\r\n:") {
		return fmt.Errorf("share password must not contain spaces or ':'")
	}
	return nil
}

// CleanName accepts a plain file name in the shared folder only.
func CleanName(name string) (string, error) {
	if name == "" || name == "." || name == ".." || strings.HasPrefix(name, ".") {
		return "", ErrBadName
	}
	if strings.ContainsAny(name, "/\\\x00") || filepath.Base(name) != name {
		return "", ErrBadName
	}
	return name, nil
}

// File is one listed entry.
type File struct {
	Name     string    `json:"name"`
	Size     int64     `json:"size"`
	Modified time.Time `json:"modified"`
}

// List returns the regular, non-hidden files in dir, sorted by name.
// Symlinks are skipped so nothing outside dir can be reached.
func List(dir string) ([]File, error) {
	ents, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	var out []File
	for _, e := range ents {
		if _, err := CleanName(e.Name()); err != nil {
			continue
		}
		if !e.Type().IsRegular() {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, File{Name: e.Name(), Size: info.Size(), Modified: info.ModTime().UTC()})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}

// Handler serves dir read-only behind the share password.
func Handler(dir, token string) http.Handler {
	want := []byte(token)
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		files, err := List(dir)
		if err != nil {
			http.Error(w, "share folder unavailable", http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		writeIndex(w, files)
	})
	mux.HandleFunc("/index.json", func(w http.ResponseWriter, r *http.Request) {
		files, err := List(dir)
		if err != nil {
			http.Error(w, "share folder unavailable", http.StatusServiceUnavailable)
			return
		}
		if files == nil {
			files = []File{}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"files": files})
	})
	mux.HandleFunc("/f/", func(w http.ResponseWriter, r *http.Request) {
		name, err := CleanName(strings.TrimPrefix(r.URL.Path, "/f/"))
		if err != nil {
			http.NotFound(w, r)
			return
		}
		p := filepath.Join(dir, name)
		info, err := os.Lstat(p)
		if err != nil || !info.Mode().IsRegular() {
			http.NotFound(w, r)
			return
		}
		f, err := os.Open(p)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		defer f.Close()
		w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+url.PathEscape(name))
		w.Header().Set("Content-Type", "application/octet-stream")
		http.ServeContent(w, r, name, info.ModTime(), f)
	})
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Cache-Control", "no-store")
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			http.Error(w, "read-only share", http.StatusMethodNotAllowed)
			return
		}
		_, pass, ok := r.BasicAuth()
		if !ok || len(want) == 0 || subtle.ConstantTimeCompare([]byte(pass), want) != 1 {
			w.Header().Set("WWW-Authenticate", `Basic realm="Victorious Mesh share", charset="UTF-8"`)
			http.Error(w, "share password required", http.StatusUnauthorized)
			return
		}
		mux.ServeHTTP(w, r)
	})
}

func writeIndex(w http.ResponseWriter, files []File) {
	var b strings.Builder
	b.WriteString(`<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">`)
	b.WriteString(`<title>Victorious Mesh share</title><style>`)
	b.WriteString(`body{background:#0B0F1A;color:#E6F7FF;font-family:sans-serif;margin:0;padding:16px}`)
	b.WriteString(`h1{color:#00E5FF;font-size:20px}a{color:#00E5FF}li{margin:8px 0}.s{color:#FF2BD6;font-size:12px}`)
	b.WriteString(`ul{list-style:none;padding:0;border:1px solid #FF2BD6;border-radius:12px;padding:12px}`)
	b.WriteString(`</style></head><body><h1>Victorious Mesh share</h1>`)
	if len(files) == 0 {
		b.WriteString(`<p>No files shared yet.</p>`)
	} else {
		b.WriteString(`<ul>`)
		for _, f := range files {
			fmt.Fprintf(&b, `<li><a href="/f/%s">%s</a> <span class="s">%s</span></li>`,
				html.EscapeString(url.PathEscape(f.Name)), html.EscapeString(f.Name), html.EscapeString(humanSize(f.Size)))
		}
		b.WriteString(`</ul>`)
	}
	b.WriteString(`</body></html>`)
	_, _ = w.Write([]byte(b.String()))
}

func humanSize(n int64) string {
	switch {
	case n >= 1<<30:
		return fmt.Sprintf("%.1f GB", float64(n)/(1<<30))
	case n >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(n)/(1<<20))
	case n >= 1<<10:
		return fmt.Sprintf("%.1f KB", float64(n)/(1<<10))
	}
	return fmt.Sprintf("%d B", n)
}
