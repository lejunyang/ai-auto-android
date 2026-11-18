// Package main 构建完整与精简发布包，以固定清单、白名单提取和原子目录发布约束供应链。
package main

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"context"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"
)

const officialBase = "https://dl.google.com/android/repository/"

var targets = []string{"darwin/arm64", "darwin/amd64", "windows/amd64", "linux/amd64"}
var versionPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]*$`)
var pinPattern = regexp.MustCompile(`(?m)^android-platform-tools\s*=\s*"([0-9.]+)"\s*$`)

type platformArchive struct {
	Version string `json:"version"`
	Archive string `json:"archive"`
	Size    int64  `json:"size"`
	SHA1    string `json:"sha1"`
	SHA256  string `json:"sha256"`
}

type config struct {
	Root, Output, Version, Bundles, AcceptLicense, Manifest, CacheDir, Go, Gradlew string
	Epoch                                                                          int64
	Fetch                                                                          func(context.Context, string, string) error
	Build                                                                          func(context.Context, string) (buildInputs, error)
}

type buildInputs struct {
	CLI         map[string]string
	Helper, App string
}
type entry struct {
	name      string
	data      []byte
	mode      int64
	directory bool
}

func main() {
	root := flag.String("root", ".", "repository root")
	goCmd := flag.String("go", envOr("GO", "go"), "Go executable (explicit path supported)")
	gradlew := flag.String("gradlew", envOr("ANDROID_GRADLEW", "./android/gradlew"), "Gradle wrapper")
	flag.Parse()
	c := config{Root: *root, Output: envOr("DIST_DIR", "dist"), Version: envOr("VERSION", "dev"), Bundles: envOr("BUNDLES", "full,slim"), AcceptLicense: os.Getenv("AACTL_ACCEPT_ANDROID_SDK_LICENSE"), CacheDir: os.Getenv("PLATFORM_TOOLS_CACHE"), Go: *goCmd, Gradlew: *gradlew}
	var err error
	c.Root, err = filepath.Abs(c.Root)
	if err == nil {
		c.Epoch, err = sourceEpoch(c.Root)
	}
	if err == nil {
		err = run(context.Background(), c)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "release:", err)
		os.Exit(1)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func sourceEpoch(root string) (int64, error) {
	value := os.Getenv("SOURCE_DATE_EPOCH")
	if value == "" {
		cmd := exec.Command(envOr("GIT", "git"), "show", "-s", "--format=%ct", "HEAD")
		cmd.Dir = root
		b, err := cmd.Output()
		if err != nil {
			return 0, fmt.Errorf("读取提交时间（可显式设置 SOURCE_DATE_EPOCH）: %w", err)
		}
		value = strings.TrimSpace(string(b))
	}
	n, err := strconv.ParseInt(value, 10, 64)
	// ZIP 的 DOS 时间支持 1980–2107；统一限制让两种容器都有可比较的时间。
	if err != nil || n < 315532800 || n > 4354819198 {
		return 0, fmt.Errorf("SOURCE_DATE_EPOCH 超出 ZIP 支持范围: %q", value)
	}
	return n, nil
}

func run(ctx context.Context, c config) error {
	if !versionPattern.MatchString(c.Version) {
		return fmt.Errorf("VERSION 格式无效")
	}
	full, slim := false, false
	for _, b := range strings.Split(c.Bundles, ",") {
		switch b {
		case "full":
			if full {
				return fmt.Errorf("BUNDLES 重复 full")
			}
			full = true
		case "slim":
			if slim {
				return fmt.Errorf("BUNDLES 重复 slim")
			}
			slim = true
		default:
			return fmt.Errorf("BUNDLES 仅支持 full、slim 或 full,slim")
		}
	}
	if full && c.AcceptLicense != "android-sdk-license" {
		return fmt.Errorf("完整包获取和打包官方 Platform-Tools 前，必须显式设置 AACTL_ACCEPT_ANDROID_SDK_LICENSE=android-sdk-license；请先阅读 Android SDK 许可。仅精简包可使用 BUNDLES=slim，无需此同意且不下载 Platform-Tools")
	}
	if c.Output == "" {
		return fmt.Errorf("DIST_DIR 不能为空")
	}
	if !filepath.IsAbs(c.Output) {
		c.Output = filepath.Join(c.Root, c.Output)
	}
	output, err := filepath.Abs(c.Output)
	if err != nil {
		return err
	}
	c.Output = output
	root, err := filepath.Abs(c.Root)
	if err != nil {
		return err
	}
	c.Root = root
	rel, err := filepath.Rel(c.Output, c.Root)
	if err == nil && (rel == "." || rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))) {
		return fmt.Errorf("DIST_DIR 不得是仓库或其父目录")
	}
	if _, err := os.Lstat(c.Output); err == nil {
		return fmt.Errorf("DIST_DIR 已存在，保留原目录；请指定新的专属输出目录: %s", c.Output)
	} else if !os.IsNotExist(err) {
		return err
	}
	if c.Epoch < 315532800 || c.Epoch > 4354819198 {
		return fmt.Errorf("归档时间超出 ZIP 支持范围")
	}
	if err := os.MkdirAll(filepath.Dir(c.Output), 0o755); err != nil {
		return err
	}
	stage, err := os.MkdirTemp(filepath.Dir(c.Output), "."+filepath.Base(c.Output)+".tmp-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stage)
	work := filepath.Join(stage, ".work")
	if err := os.Mkdir(work, 0o755); err != nil {
		return err
	}
	tools := map[string][]entry{}
	toolVersion := "未附带"
	if full {
		manifest, err := loadManifest(c)
		if err != nil {
			return err
		}
		if c.CacheDir == "" {
			d, err := os.UserCacheDir()
			if err != nil {
				return err
			}
			c.CacheDir = filepath.Join(d, "aactl", "platform-tools")
		}
		if err := os.MkdirAll(c.CacheDir, 0o755); err != nil {
			return err
		}
		if c.Fetch == nil {
			c.Fetch = downloadOfficial
		}
		// 三个系统全部校验通过才开始提取，任一失败不会产出可发布归档。
		for _, system := range []string{"darwin", "linux", "windows"} {
			if err := ensureArchive(ctx, c, manifest[system]); err != nil {
				return fmt.Errorf("%s: %w", system, err)
			}
		}
		for _, system := range []string{"darwin", "linux", "windows"} {
			p := manifest[system]
			files, err := readTools(filepath.Join(c.CacheDir, p.Archive), system)
			if err != nil {
				return fmt.Errorf("%s: %w", system, err)
			}
			tools[system] = files
			toolVersion = p.Version
		}
	}
	if c.Build == nil {
		c.Build = c.build
	}
	in, err := c.Build(ctx, work)
	if err != nil {
		return err
	}
	helper, err := readRegular(in.Helper)
	if err != nil {
		return err
	}
	assets := []string{}
	for _, target := range targets {
		parts := strings.Split(target, "/")
		system, arch := parts[0], parts[1]
		cli, err := readRegular(in.CLI[target])
		if err != nil {
			return err
		}
		binary := "aactl"
		if system == "windows" {
			binary += ".exe"
		}
		for _, bundle := range []string{"full", "slim"} {
			if bundle == "full" && !full || bundle == "slim" && !slim {
				continue
			}
			base := fmt.Sprintf("aactl_%s_%s_%s", c.Version, system, arch)
			if bundle == "slim" {
				base += "_slim"
			}
			files := []entry{{name: binary, data: cli, mode: 0o755}, {name: "aactl-displayd.apk", data: helper, mode: 0o644}, {name: "README.txt", data: []byte(bundleReadme(c.Version, target, bundle, toolVersion, binary)), mode: 0o644}}
			if bundle == "full" {
				files = append(files, tools[system]...)
			}
			ext := ".tar.gz"
			if system == "windows" {
				ext = ".zip"
			}
			name := base + ext
			if err := writeBundle(filepath.Join(stage, name), base, files, c.Epoch, system == "windows"); err != nil {
				return err
			}
			assets = append(assets, name)
			fmt.Println("已生成", name)
		}
	}
	app, err := readRegular(in.App)
	if err != nil {
		return err
	}
	appName := "ai-auto-android_" + c.Version + "_debug.apk"
	if err := os.WriteFile(filepath.Join(stage, appName), app, 0o644); err != nil {
		return err
	}
	assets = append(assets, appName)
	sort.Strings(assets)
	var sums strings.Builder
	for _, name := range assets {
		b, err := os.ReadFile(filepath.Join(stage, name))
		if err != nil {
			return err
		}
		h := sha256.Sum256(b)
		fmt.Fprintf(&sums, "%x  %s\n", h, name)
	}
	if err := os.WriteFile(filepath.Join(stage, "SHA256SUMS"), []byte(sums.String()), 0o644); err != nil {
		return err
	}
	if err := os.RemoveAll(work); err != nil {
		return err
	}
	if err := os.Rename(stage, c.Output); err != nil {
		return err
	}
	fmt.Println("发布产物目录：", c.Output)
	return nil
}

func archiveName(system, version string) string {
	suffix := system
	if system == "windows" {
		suffix = "win"
	}
	return "platform-tools_r" + version + "-" + suffix + ".zip"
}

func loadManifest(c config) (map[string]platformArchive, error) {
	name := c.Manifest
	if name == "" {
		name = filepath.Join(c.Root, "scripts", "platform-tools.lock.json")
	}
	b, err := os.ReadFile(name)
	if err != nil {
		return nil, err
	}
	var m map[string]platformArchive
	d := json.NewDecoder(strings.NewReader(string(b)))
	d.DisallowUnknownFields()
	if err := d.Decode(&m); err != nil {
		return nil, err
	}
	pins, err := os.ReadFile(filepath.Join(c.Root, "osdk.toml"))
	if err != nil {
		return nil, err
	}
	match := pinPattern.FindSubmatch(pins)
	if len(match) != 2 || len(m) != 3 {
		return nil, fmt.Errorf("Platform-Tools pin 或固定清单结构无效")
	}
	for _, system := range []string{"darwin", "linux", "windows"} {
		p := m[system]
		if p.Version != string(match[1]) || p.Archive != archiveName(system, p.Version) || p.Size <= 0 || p.Size > 128<<20 || !validHash(p.SHA1, 20) || !validHash(p.SHA256, 32) {
			return nil, fmt.Errorf("%s 固定清单与 osdk.toml 不一致或校验字段无效", system)
		}
	}
	return m, nil
}

func validHash(s string, size int) bool {
	b, err := hex.DecodeString(s)
	return err == nil && len(b) == size && strings.ToLower(s) == s
}

func ensureArchive(ctx context.Context, c config, p platformArchive) error {
	dest := filepath.Join(c.CacheDir, p.Archive)
	if _, err := os.Lstat(dest); err == nil {
		if err := verifyArchive(dest, p); err != nil {
			_ = os.Remove(dest)
			return err
		}
		return nil
	} else if !os.IsNotExist(err) {
		return err
	}
	f, err := os.CreateTemp(c.CacheDir, ".part-")
	if err != nil {
		return err
	}
	temp := f.Name()
	if err := f.Close(); err != nil {
		_ = os.Remove(temp)
		return err
	}
	defer os.Remove(temp)
	if err := c.Fetch(ctx, officialBase+p.Archive, temp); err != nil {
		return err
	}
	if err := verifyArchive(temp, p); err != nil {
		return err
	}
	return os.Rename(temp, dest)
}

func verifyArchive(name string, p platformArchive) error {
	info, err := os.Lstat(name)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || info.Size() != p.Size {
		return fmt.Errorf("官方归档字节数或文件类型不符: %s，预期 %d", p.Archive, p.Size)
	}
	f, err := os.Open(name)
	if err != nil {
		return err
	}
	defer f.Close()
	h1, h256 := sha1.New(), sha256.New()
	if _, err := io.Copy(io.MultiWriter(h1, h256), f); err != nil {
		return err
	}
	if hex.EncodeToString(h1.Sum(nil)) != p.SHA1 || hex.EncodeToString(h256.Sum(nil)) != p.SHA256 {
		return fmt.Errorf("官方归档 SHA-1/SHA-256 不符: %s", p.Archive)
	}
	return nil
}

func downloadOfficial(ctx context.Context, address, dest string) error {
	allowed := func(address string) bool {
		u, err := url.Parse(address)
		return err == nil && u.Scheme == "https" && u.Host == "dl.google.com" && u.User == nil && strings.HasPrefix(u.Path, "/android/repository/") && !strings.Contains(u.Path, "..") && u.RawQuery == ""
	}
	if !allowed(address) {
		return fmt.Errorf("拒绝非官方二进制来源")
	}
	client := &http.Client{Timeout: 3 * time.Minute, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= 5 || !allowed(req.URL.String()) {
			return fmt.Errorf("拒绝离开官方目录的重定向")
		}
		return nil
	}}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return err
	}
	response, err := client.Do(req)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("官方归档 HTTP %d", response.StatusCode)
	}
	f, err := os.OpenFile(dest, os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	n, copyErr := io.Copy(f, io.LimitReader(response.Body, (128<<20)+1))
	closeErr := f.Close()
	if copyErr != nil {
		return copyErr
	}
	if n > 128<<20 {
		return fmt.Errorf("官方归档超过下载上限")
	}
	return closeErr
}

func allowedFiles(system string) []string {
	if system == "windows" {
		return []string{"platform-tools/adb.exe", "platform-tools/AdbWinApi.dll", "platform-tools/AdbWinUsbApi.dll", "platform-tools/NOTICE.txt"}
	}
	return []string{"platform-tools/adb", "platform-tools/NOTICE.txt"}
}

func readTools(name, system string) ([]entry, error) {
	z, err := zip.OpenReader(name)
	if err != nil {
		return nil, err
	}
	defer z.Close()
	if len(z.File) > 512 {
		return nil, fmt.Errorf("官方归档条目过多")
	}
	wanted := map[string]bool{}
	for _, n := range allowedFiles(system) {
		wanted[n] = true
	}
	seen := map[string]bool{}
	files := []entry{}
	for _, f := range z.File {
		// 全部条目先校验路径和类型，包括不在提取白名单中的条目；从不调用通用解压。
		n := f.Name
		clean := strings.TrimSuffix(n, "/")
		if clean == "" || path.IsAbs(n) || strings.ContainsAny(n, "\\:\x00") || path.Clean(clean) != clean || strings.Contains(clean, "../") || clean == ".." || seen[clean] {
			return nil, fmt.Errorf("拒绝不安全或重复归档路径: %q", n)
		}
		seen[clean] = true
		mode := f.Mode()
		if mode&os.ModeSymlink != 0 || !mode.IsRegular() && !mode.IsDir() {
			return nil, fmt.Errorf("拒绝非常规归档条目: %q", n)
		}
		if !wanted[n] {
			continue
		}
		if !mode.IsRegular() || f.UncompressedSize64 == 0 || f.UncompressedSize64 > 64<<20 {
			return nil, fmt.Errorf("白名单文件类型/大小无效: %s", n)
		}
		r, err := f.Open()
		if err != nil {
			return nil, err
		}
		data, err := io.ReadAll(io.LimitReader(r, (64<<20)+1))
		closeErr := r.Close()
		if err != nil {
			return nil, err
		}
		if closeErr != nil {
			return nil, closeErr
		}
		if len(data) > 64<<20 {
			return nil, fmt.Errorf("提取文件过大")
		}
		perm := int64(0o644)
		if strings.HasSuffix(n, "/adb") || strings.HasSuffix(n, "/adb.exe") {
			perm = 0o755
		}
		files = append(files, entry{name: n, data: data, mode: perm})
	}
	if len(files) != len(wanted) {
		return nil, fmt.Errorf("官方归档缺少 ADB 运行文件或 NOTICE.txt")
	}
	return files, nil
}

func readRegular(name string) ([]byte, error) {
	info, err := os.Lstat(name)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, fmt.Errorf("构建产物不是常规文件: %s", name)
	}
	return os.ReadFile(name)
}

func writeBundle(name, base string, files []entry, epoch int64, isZip bool) (result error) {
	all := []entry{{name: base + "/", mode: 0o755, directory: true}}
	hasTools := false
	for _, f := range files {
		if strings.HasPrefix(f.name, "platform-tools/") {
			hasTools = true
		}
		f.name = base + "/" + f.name
		all = append(all, f)
	}
	if hasTools {
		all = append(all, entry{name: base + "/platform-tools/", mode: 0o755, directory: true})
	}
	sort.Slice(all, func(i, j int) bool { return all[i].name < all[j].name })
	f, err := os.OpenFile(name, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	defer func() { result = errors.Join(result, f.Close()) }()
	timestamp := time.Unix(epoch, 0).UTC()
	if isZip {
		w := zip.NewWriter(f)
		for _, e := range all {
			mode := os.FileMode(e.mode)
			if e.directory {
				mode |= os.ModeDir
			}
			h := &zip.FileHeader{Name: e.name, Method: zip.Deflate, Modified: timestamp}
			h.SetMode(mode)
			if e.directory {
				h.Method = zip.Store
			}
			r, err := w.CreateHeader(h)
			if err != nil {
				return err
			}
			if _, err := r.Write(e.data); err != nil {
				return err
			}
		}
		return w.Close()
	}
	g := gzip.NewWriter(f)
	g.Header.ModTime = timestamp
	g.Header.OS = 255
	w := tar.NewWriter(g)
	for _, e := range all {
		h := &tar.Header{Name: e.name, Mode: e.mode, ModTime: timestamp, Size: int64(len(e.data)), Typeflag: tar.TypeReg, Format: tar.FormatPAX}
		if e.directory {
			h.Typeflag = tar.TypeDir
		}
		if err := w.WriteHeader(h); err != nil {
			return err
		}
		if _, err := w.Write(e.data); err != nil {
			return err
		}
	}
	if err := w.Close(); err != nil {
		return err
	}
	return g.Close()
}

func (c config) build(ctx context.Context, dir string) (buildInputs, error) {
	in := buildInputs{CLI: map[string]string{}, Helper: filepath.Join(c.Root, "android", "displayd", "build", "outputs", "apk", "debug", "displayd-debug.apk"), App: filepath.Join(c.Root, "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk")}
	for _, target := range targets {
		parts := strings.Split(target, "/")
		name := strings.ReplaceAll(target, "/", "-")
		if parts[0] == "windows" {
			name += ".exe"
		}
		output := filepath.Join(dir, name)
		cmd := exec.CommandContext(ctx, c.Go, "build", "-trimpath", "-ldflags", "-s -w -X github.com/lejunyang/ai-auto-android/internal/cli.Version="+c.Version, "-o", output, "./cmd/aactl")
		cmd.Dir = c.Root
		cmd.Env = replaceEnv(os.Environ(), map[string]string{"CGO_ENABLED": "0", "GOOS": parts[0], "GOARCH": parts[1]})
		if c.Bundles == "slim" {
			cmd.Env = replaceEnv(cmd.Env, map[string]string{"GOPROXY": "off", "GOSUMDB": "off", "GOTOOLCHAIN": "local"})
		}
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Run(); err != nil {
			return in, fmt.Errorf("构建 %s: %w", target, err)
		}
		in.CLI[target] = output
	}
	args := []string{"-p", filepath.Join(c.Root, "android"), "--no-daemon", ":app:assembleDebug", ":displayd:assembleDebug"}
	if c.Bundles == "slim" {
		args = append(args, "--offline")
	}
	wrapper := c.Gradlew
	if !filepath.IsAbs(wrapper) {
		wrapper = filepath.Join(c.Root, wrapper)
	}
	var cmd *exec.Cmd
	if runtime.GOOS == "windows" && strings.HasSuffix(strings.ToLower(wrapper), ".bat") {
		// 固定 wrapper 参数，不拼接 shell 命令；Windows wrapper 由系统 cmd 显式启动。
		cmd = exec.CommandContext(ctx, filepath.Join(os.Getenv("SystemRoot"), "System32", "cmd.exe"), append([]string{"/d", "/c", wrapper}, args...)...)
	} else {
		cmd = exec.CommandContext(ctx, wrapper, args...)
	}
	cmd.Dir = c.Root
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		return in, fmt.Errorf("构建 debug APK: %w", err)
	}
	return in, nil
}

func replaceEnv(current []string, values map[string]string) []string {
	out := []string{}
	for _, e := range current {
		key, _, _ := strings.Cut(e, "=")
		if _, exists := values[strings.ToUpper(key)]; !exists {
			out = append(out, e)
		}
	}
	for k, v := range values {
		out = append(out, k+"="+v)
	}
	return out
}

func bundleReadme(version, target, bundle, adbVersion, binary string) string {
	mode := "完整包（推荐），附官方 Platform-Tools " + adbVersion + " 的 ADB 及原始 platform-tools/NOTICE.txt。"
	if bundle == "slim" {
		mode = "精简包，不含 ADB。请自备官方 Android SDK Platform-Tools（https://developer.android.com/tools/releases/platform-tools），设置 AACTL_ADB_PATH 为其 adb 可执行文件，或将它加入 PATH。"
	}
	prefix := "./"
	if strings.HasSuffix(binary, ".exe") {
		prefix = ".\\"
	}
	return fmt.Sprintf(`AI Auto Android / aactl %s (%s)
项目：https://github.com/lejunyang/ai-auto-android
包类型：%s
helper：同一源码提交的 displayd debug APK（协议 v2）；只作 app_process CLASSPATH，不安装到设备。

解压即用的三步：
1. 校验同一 Release 的 SHA256SUMS，将整个归档解压到独立目录，并在此目录打开终端。
2. 在设备上启用 USB 调试并连接数据线；由用户在设备端核对并同意 ADB RSA 授权。
3. 运行 %s%s doctor --json，再运行 %s%s devices list --json；设备命令显式带 --device SERIAL。
   虚拟显示：%s%s display start --device SERIAL --json（自动读取同目录 aactl-displayd.apk）。

Android App 单独下载：同一 Release 的 ai-auto-android_%s_debug.apk（调试签名，仅开发验证）。
发布页：https://github.com/lejunyang/ai-auto-android/releases
本包不再单独提供裸 CLI 发布资产；精简包已覆盖该用途。
旧版升级：保留旧 CLI，先用旧 CLI 执行 display stop --device SERIAL --json 停止 v1 helper，
再整体替换 CLI/helper，启动后核对 protocolVersion:2。新版拒绝与 v1 helper 握手。
电脑已有另一版本 ADB 服务时，交替使用会互相重启服务、打断连接。
可用 AACTL_ADB_PATH 指向同一个官方 ADB，或改用精简包。doctor 的 adbPath/adbSource 显示实际选择。
官方二进制受 Android SDK 许可条款约束，再分发风险由发布者自行评估，本文不是法律意见。
`, version, target, mode, prefix, binary, prefix, binary, prefix, binary, version)
}
