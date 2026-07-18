package bridge

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

type Session struct {
	Device     string    `json:"device"`
	LocalPort  int       `json:"localPort"`
	RemotePort int       `json:"remotePort"`
	Token      string    `json:"token"`
	ExpiresAt  time.Time `json:"expiresAt"`
	OpenedAt   time.Time `json:"openedAt"`
	HostName   string    `json:"hostName"`
}

type SessionStore interface {
	Save(session Session) error
	Load(device string) (Session, error)
	Delete(device string) error
}

type FileSessionStore struct {
	directory string
}

func DefaultSessionStore() (*FileSessionStore, error) {
	configDirectory, err := os.UserConfigDir()
	if err != nil {
		return nil, apperr.Wrap(
			apperr.CodeInternal,
			"The local configuration directory could not be resolved.",
			false,
			err,
		)
	}
	return NewFileSessionStore(filepath.Join(configDirectory, "aactl", "bridge")), nil
}

func NewFileSessionStore(directory string) *FileSessionStore {
	return &FileSessionStore{directory: directory}
}

func (s *FileSessionStore) Save(session Session) error {
	if err := validateSession(session); err != nil {
		return err
	}
	if err := os.MkdirAll(s.directory, 0o700); err != nil {
		return storeError("create", err)
	}
	if err := os.Chmod(s.directory, 0o700); err != nil {
		return storeError("secure", err)
	}
	payload, err := json.Marshal(session)
	if err != nil {
		return storeError("encode", err)
	}
	defer func() {
		for index := range payload {
			payload[index] = 0
		}
	}()

	temporary, err := os.CreateTemp(s.directory, ".session-*")
	if err != nil {
		return storeError("create", err)
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0o600); err != nil {
		_ = temporary.Close()
		return storeError("secure", err)
	}
	if _, err := temporary.Write(payload); err != nil {
		_ = temporary.Close()
		return storeError("write", err)
	}
	if err := temporary.Sync(); err != nil {
		_ = temporary.Close()
		return storeError("sync", err)
	}
	if err := temporary.Close(); err != nil {
		return storeError("close", err)
	}
	path := s.path(session.Device)
	if err := os.Rename(temporaryPath, path); err != nil {
		return storeError("replace", err)
	}
	if err := os.Chmod(path, 0o600); err != nil {
		return storeError("secure", err)
	}
	return nil
}

func (s *FileSessionStore) Load(device string) (Session, error) {
	path := s.path(device)
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return Session{}, apperr.New(
			apperr.CodeAuthRequired,
			"No local bridge session exists. Run bridge open first.",
			false,
			map[string]any{"device": device},
		)
	}
	if err != nil {
		return Session{}, storeError("inspect", err)
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return Session{}, storeError("inspect", errors.New("session path is not a regular file"))
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return Session{}, apperr.New(
			apperr.CodePermissionDenied,
			"The local bridge session file has unsafe permissions.",
			false,
			nil,
		)
	}
	payload, err := os.ReadFile(path)
	if err != nil {
		return Session{}, storeError("read", err)
	}
	var session Session
	decoderError := json.Unmarshal(payload, &session)
	for index := range payload {
		payload[index] = 0
	}
	if decoderError != nil {
		return Session{}, storeError("decode", decoderError)
	}
	if session.Device != device {
		return Session{}, storeError("validate", errors.New("session device mismatch"))
	}
	if err := validateSession(session); err != nil {
		return Session{}, err
	}
	return session, nil
}

func (s *FileSessionStore) Delete(device string) error {
	err := os.Remove(s.path(device))
	if err == nil || errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return storeError("delete", err)
}

func (s *FileSessionStore) path(device string) string {
	sum := sha256.Sum256([]byte(device))
	return filepath.Join(s.directory, hex.EncodeToString(sum[:])+".json")
}

func validateSession(session Session) error {
	if session.Device == "" ||
		session.LocalPort < 1 ||
		session.LocalPort > 65535 ||
		session.RemotePort < 1 ||
		session.RemotePort > 65535 ||
		!validToken(session.Token) ||
		session.ExpiresAt.IsZero() {
		return apperr.New(
			apperr.CodeProtocol,
			"The local bridge session metadata is invalid.",
			false,
			nil,
		)
	}
	return nil
}

func storeError(operation string, err error) error {
	return apperr.Wrap(
		apperr.CodeInternal,
		fmt.Sprintf("Could not %s the local bridge session.", operation),
		false,
		err,
	)
}
