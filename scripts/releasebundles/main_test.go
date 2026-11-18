package main

// 测试用途：用本地伪清单与伪归档验证供应链拒绝、原子发布、精简离线和归档可复现性。

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func fixtureConfig(t *testing.T, extra string, mode os.FileMode) (config, map[string][]byte) {
	t.Helper()
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "osdk.toml"), []byte("android-platform-tools = \"37.0.1\"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	archives := map[string][]byte{}
	manifest := map[string]platformArchive{}
	for _, system := range []string{"darwin", "linux", "windows"} {
		var b bytes.Buffer
		w := zip.NewWriter(&b)
		for _, name := range allowedFiles(system) {
			h := &zip.FileHeader{Name: name, Method: zip.Deflate}
			h.SetMode(0o755)
			f, err := w.CreateHeader(h)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := io.WriteString(f, "fixture "+name); err != nil {
				t.Fatal(err)
			}
		}
		if extra != "" {
			h := &zip.FileHeader{Name: extra, Method: zip.Deflate}
			h.SetMode(mode)
			f, err := w.CreateHeader(h)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := io.WriteString(f, "../escape"); err != nil {
				t.Fatal(err)
			}
		}
		if err := w.Close(); err != nil {
			t.Fatal(err)
		}
		name := archiveName(system, "37.0.1")
		data := b.Bytes()
		archives[name] = data
		h1 := sha1.Sum(data)
		h256 := sha256.Sum256(data)
		manifest[system] = platformArchive{Version: "37.0.1", Archive: name, Size: int64(len(data)), SHA1: hex.EncodeToString(h1[:]), SHA256: hex.EncodeToString(h256[:])}
	}
	manifestPath := filepath.Join(root, "manifest.json")
	writeManifest(t, manifestPath, manifest)
	c := config{Root: root, Output: filepath.Join(root, "dist"), Version: "test", Bundles: "full,slim", AcceptLicense: "android-sdk-license", Manifest: manifestPath, CacheDir: filepath.Join(root, "cache"), Epoch: 1700000000}
	c.Fetch = func(_ context.Context, url, dest string) error {
		if !strings.HasPrefix(url, officialBase) {
			t.Fatalf("unexpected URL %s", url)
		}
		data, ok := archives[strings.TrimPrefix(url, officialBase)]
		if !ok {
			return fmt.Errorf("missing fixture")
		}
		return os.WriteFile(dest, data, 0o600)
	}
	c.Build = func(_ context.Context, dir string) (buildInputs, error) {
		in := buildInputs{CLI: map[string]string{}, Helper: filepath.Join(dir, "helper.apk"), App: filepath.Join(dir, "app.apk")}
		for _, target := range targets {
			p := filepath.Join(dir, strings.ReplaceAll(target, "/", "-"))
			if err := os.WriteFile(p, []byte("cli "+target), 0o755); err != nil {
				return in, err
			}
			in.CLI[target] = p
		}
		for _, p := range []string{in.Helper, in.App} {
			if err := os.WriteFile(p, []byte("apk fixture"), 0o644); err != nil {
				return in, err
			}
		}
		return in, nil
	}
	return c, archives
}

func writeManifest(t *testing.T, name string, m map[string]platformArchive) {
	t.Helper()
	b, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(name, b, 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestReleaseBundlesOfflineAndReproducible(t *testing.T) {
	c, _ := fixtureConfig(t, "platform-tools/unneeded", 0o644)
	if err := run(context.Background(), c); err != nil {
		t.Fatal(err)
	}
	first := inspectRelease(t, c.Output, 9)
	c.Output = filepath.Join(c.Root, "second")
	c.Fetch = func(context.Context, string, string) error { t.Fatal("verified cache must avoid network"); return nil }
	if err := run(context.Background(), c); err != nil {
		t.Fatal(err)
	}
	second := inspectRelease(t, c.Output, 9)
	for name, data := range first {
		if !bytes.Equal(data, second[name]) {
			t.Errorf("non-reproducible artifact: %s", name)
		}
	}
}

func TestReleaseRejectsInvalidInputsWithoutPartialOutput(t *testing.T) {
	for _, kind := range []string{"sha1", "sha256", "size", "pin", "license", "build", "download"} {
		t.Run(kind, func(t *testing.T) {
			c, _ := fixtureConfig(t, "", 0)
			b, _ := os.ReadFile(c.Manifest)
			var m map[string]platformArchive
			if err := json.Unmarshal(b, &m); err != nil {
				t.Fatal(err)
			}
			p := m["linux"]
			switch kind {
			case "sha1":
				p.SHA1 = strings.Repeat("0", 40)
			case "sha256":
				p.SHA256 = strings.Repeat("0", 64)
			case "size":
				p.Size++
			case "pin":
				p.Version = "other"
			case "license":
				c.AcceptLicense = ""
				c.Fetch = func(context.Context, string, string) error {
					t.Fatal("license gate must run before network")
					return nil
				}
			case "build":
				c.Build = func(context.Context, string) (buildInputs, error) { return buildInputs{}, fmt.Errorf("build failed") }
			case "download":
				c.Fetch = func(_ context.Context, _ string, dest string) error {
					_ = os.WriteFile(dest, []byte("partial"), 0o600)
					return fmt.Errorf("interrupted")
				}
			}
			m["linux"] = p
			writeManifest(t, c.Manifest, m)
			if err := run(context.Background(), c); err == nil {
				t.Fatal("want rejection")
			}
			assertNoPartial(t, c)
			if kind == "sha1" || kind == "sha256" || kind == "size" {
				if _, err := os.Stat(filepath.Join(c.CacheDir, p.Archive)); !os.IsNotExist(err) {
					t.Fatalf("bad cache retained: %v", err)
				}
			}
		})
	}
}

func TestReleaseRejectsArchiveEscapeAndSymlinks(t *testing.T) {
	for _, entry := range []struct {
		name string
		mode os.FileMode
	}{
		{"../escape", 0o644}, {"/absolute", 0o644}, {"C:/escape", 0o644}, {"platform-tools/../escape", 0o644}, {"platform-tools\\adb", 0o644}, {"platform-tools/link", os.ModeSymlink | 0o777}, {"platform-tools/fifo", os.ModeNamedPipe | 0o644}, {"platform-tools/NOTICE.txt", 0o644},
	} {
		t.Run(entry.name, func(t *testing.T) {
			c, _ := fixtureConfig(t, entry.name, entry.mode)
			if err := run(context.Background(), c); err == nil {
				t.Fatal("unsafe archive accepted")
			}
			assertNoPartial(t, c)
		})
	}
}

func TestSlimNeverFetchesAndNeedsNoLicense(t *testing.T) {
	c, _ := fixtureConfig(t, "", 0)
	c.Bundles = "slim"
	c.AcceptLicense = ""
	c.Manifest = "does-not-exist"
	c.Fetch = func(context.Context, string, string) error { t.Fatal("slim must not access network"); return nil }
	if err := run(context.Background(), c); err != nil {
		t.Fatal(err)
	}
	inspectRelease(t, c.Output, 5)
	if _, err := os.Stat(c.CacheDir); !os.IsNotExist(err) {
		t.Fatal("slim touched download cache")
	}
}

func TestReleasePreservesExistingOutput(t *testing.T) {
	c, _ := fixtureConfig(t, "", 0)
	if err := os.Mkdir(c.Output, 0o755); err != nil {
		t.Fatal(err)
	}
	old := filepath.Join(c.Output, "keep")
	if err := os.WriteFile(old, []byte("previous release"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := run(context.Background(), c); err == nil {
		t.Fatal("must refuse to overwrite existing directory")
	}
	if b, err := os.ReadFile(old); err != nil || string(b) != "previous release" {
		t.Fatal("old output changed")
	}
	entries, _ := os.ReadDir(c.Root)
	for _, entry := range entries {
		if strings.Contains(entry.Name(), ".tmp-") {
			t.Fatal("staging leaked")
		}
	}
}

func assertNoPartial(t *testing.T, c config) {
	t.Helper()
	if _, err := os.Stat(c.Output); !os.IsNotExist(err) {
		t.Fatalf("partial output: %v", err)
	}
	entries, _ := os.ReadDir(filepath.Dir(c.Output))
	for _, entry := range entries {
		if strings.Contains(entry.Name(), ".tmp-") {
			t.Fatalf("staging leaked: %s", entry.Name())
		}
	}
	cache, _ := os.ReadDir(c.CacheDir)
	for _, entry := range cache {
		if strings.Contains(entry.Name(), ".part-") {
			t.Fatal("download partial leaked")
		}
	}
}

func inspectRelease(t *testing.T, dir string, count int) map[string][]byte {
	t.Helper()
	sums, err := os.ReadFile(filepath.Join(dir, "SHA256SUMS"))
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(sums)), "\n")
	if len(lines) != count {
		t.Fatalf("assets = %d, want %d", len(lines), count)
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != count+1 {
		t.Fatalf("unexpected release entries: %v", entries)
	}
	result := map[string][]byte{}
	for _, line := range lines {
		parts := strings.Split(line, "  ")
		if len(parts) != 2 {
			t.Fatalf("invalid checksum %s", line)
		}
		name := parts[1]
		data, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil {
			t.Fatal(err)
		}
		h := sha256.Sum256(data)
		if hex.EncodeToString(h[:]) != parts[0] {
			t.Fatalf("checksum mismatch %s", name)
		}
		result[name] = data
		if strings.HasSuffix(name, ".apk") {
			continue
		}
		base := strings.TrimSuffix(strings.TrimSuffix(name, ".tar.gz"), ".zip")
		files := map[string][]byte{}
		check := func(n string, mode os.FileMode, r io.Reader) {
			if !strings.HasPrefix(n, base+"/") {
				t.Fatalf("wrong root %s", n)
			}
			if mode.IsDir() {
				if mode.Perm() != 0o755 {
					t.Fatalf("directory permissions %s: %v", n, mode)
				}
				return
			}
			data, err := io.ReadAll(r)
			if err != nil {
				t.Fatal(err)
			}
			rel := strings.TrimPrefix(n, base+"/")
			files[rel] = data
			expectedMode := os.FileMode(0o644)
			if rel == "aactl" || rel == "aactl.exe" || rel == "platform-tools/adb" || rel == "platform-tools/adb.exe" {
				expectedMode = 0o755
			}
			if mode.Perm() != expectedMode {
				t.Fatalf("file permissions %s: %v", n, mode)
			}
		}
		if strings.HasSuffix(name, ".zip") {
			z, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
			if err != nil {
				t.Fatal(err)
			}
			for _, f := range z.File {
				if f.Modified.Unix() != 1700000000 {
					t.Errorf("zip timestamp = %v", f.Modified)
				}
				r, err := f.Open()
				if err != nil {
					t.Fatal(err)
				}
				check(f.Name, f.Mode(), r)
				_ = r.Close()
			}
		} else {
			g, err := gzip.NewReader(bytes.NewReader(data))
			if err != nil {
				t.Fatal(err)
			}
			tr := tar.NewReader(g)
			for {
				h, err := tr.Next()
				if err == io.EOF {
					break
				}
				if err != nil {
					t.Fatal(err)
				}
				if h.ModTime.Unix() != 1700000000 || h.Uid != 0 || h.Gid != 0 {
					t.Fatalf("tar metadata: %+v", h)
				}
				mode := os.FileMode(h.Mode)
				if h.Typeflag == tar.TypeDir {
					mode |= os.ModeDir
				}
				check(h.Name, mode, tr)
			}
			_ = g.Close()
		}
		cli := "aactl"
		if strings.Contains(name, "windows") {
			cli += ".exe"
		}
		for _, n := range []string{cli, "aactl-displayd.apk", "README.txt"} {
			if len(files[n]) == 0 {
				t.Fatalf("missing %s in %s", n, name)
			}
		}
		wantFiles := 3
		if !strings.Contains(name, "_slim.") {
			wantFiles += 2
			if strings.Contains(name, "windows") {
				wantFiles += 2
			}
			if len(files["platform-tools/NOTICE.txt"]) == 0 {
				t.Fatal("missing official notice")
			}
		}
		if len(files) != wantFiles {
			t.Fatalf("unexpected files %s: %v", name, files)
		}
	}
	return result
}
