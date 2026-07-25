package lan

// 功能用途：本文件原子消费 invitation ID 与 nonce，并按 TTL 有界清理重放状态。

import (
	"sync"
	"time"
)

// ReplayGuard 在进程内至少保留 invitation TTL 范围的已消费标识。
type ReplayGuard struct {
	mu          sync.Mutex
	invitations map[string]time.Time
	nonces      map[string]time.Time
}

// NewReplayGuard 创建独立 LAN 重放集合，不与 loopback token store 共享。
func NewReplayGuard() *ReplayGuard {
	return &ReplayGuard{
		invitations: make(map[string]time.Time),
		nonces:      make(map[string]time.Time),
	}
}

// Consume 原子检查并消费 invitation ID 与 nonce，失败不会只写入其中一项。
func (guard *ReplayGuard) Consume(
	invitationID string,
	nonce string,
	expiresAt time.Time,
	now time.Time,
) error {
	if guard == nil {
		return fail(CodeInvitationReplayed, "replay guard is unavailable")
	}
	guard.mu.Lock()
	defer guard.mu.Unlock()
	guard.pruneLocked(now)
	if expiry, consumed := guard.invitations[invitationID]; consumed && expiry.After(now) {
		return fail(CodeInvitationReplayed, "invitation ID was already consumed")
	}
	if expiry, consumed := guard.nonces[nonce]; consumed && expiry.After(now) {
		return fail(CodeNonceReplayed, "invitation nonce was already consumed")
	}
	guard.invitations[invitationID] = expiresAt
	guard.nonces[nonce] = expiresAt
	return nil
}

func (guard *ReplayGuard) pruneLocked(now time.Time) {
	for invitationID, expiry := range guard.invitations {
		if !expiry.After(now) {
			delete(guard.invitations, invitationID)
		}
	}
	for nonce, expiry := range guard.nonces {
		if !expiry.After(now) {
			delete(guard.nonces, nonce)
		}
	}
}
