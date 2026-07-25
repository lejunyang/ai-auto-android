package dev.aiauto.android.bridge.lan

/**
 * 测试用途：验证重放状态跨进程实例持久化、并发原子 claim 及原始 invitation 信息不落盘。
 */

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LanReplayStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `invitation id and nonce conflicts are distinguished after process recreation`() {
        val stateFile = temporaryFolder.newFolder("replay").resolve("claims.json")
        val now = Instant.parse("2026-07-25T10:00:30Z")
        val expiresAt = Instant.parse("2026-07-25T10:01:30Z")
        val first = key("invitation-a", "nonce-a", expiresAt)

        assertEquals(
            LanReplayClaim.CLAIMED,
            FileLanReplayStore(stateFile).claim(first, now),
        )
        assertEquals(
            LanReplayClaim.INVITATION_REPLAYED,
            FileLanReplayStore(stateFile).claim(
                key("invitation-a", "nonce-b", expiresAt),
                now,
            ),
        )
        assertEquals(
            LanReplayClaim.NONCE_REPLAYED,
            FileLanReplayStore(stateFile).claim(
                key("invitation-b", "nonce-a", expiresAt),
                now,
            ),
        )
    }

    @Test
    fun `concurrent claims allow exactly one first consumer`() {
        val stateFile = temporaryFolder.newFolder("concurrent").resolve("claims.json")
        val now = Instant.parse("2026-07-25T10:00:30Z")
        val key = key("invitation-a", "nonce-a", now.plusSeconds(60))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                executor.submit<LanReplayClaim> {
                    ready.countDown()
                    start.await()
                    FileLanReplayStore(stateFile).claim(key, now)
                }
            }
            ready.await()
            start.countDown()
            val results = futures.map { it.get() }

            assertEquals(1, results.count { it == LanReplayClaim.CLAIMED })
            assertEquals(1, results.count { it == LanReplayClaim.INVITATION_REPLAYED })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `expired claims are pruned and state contains digests only`() {
        val stateFile = temporaryFolder.newFolder("privacy").resolve("claims.json")
        val beforeExpiry = Instant.parse("2026-07-25T10:00:30Z")
        val expired = key(
            rawInvitationId = RAW_INVITATION_ID,
            rawNonce = RAW_NONCE,
            expiresAt = beforeExpiry.plusSeconds(1),
        )
        val store = FileLanReplayStore(stateFile)
        assertEquals(LanReplayClaim.CLAIMED, store.claim(expired, beforeExpiry))

        val persisted = stateFile.readText()
        assertFalse(persisted.contains(RAW_INVITATION_ID))
        assertFalse(persisted.contains(RAW_NONCE))
        assertTrue(persisted.contains(expired.invitationIdDigest))
        assertTrue(persisted.contains(expired.nonceDigest))

        val afterExpiry = beforeExpiry.plusSeconds(2)
        assertEquals(
            LanReplayClaim.CLAIMED,
            store.claim(
                expired.copy(expiresAt = afterExpiry.plusSeconds(60)),
                afterExpiry,
            ),
        )
    }

    private companion object {
        const val RAW_INVITATION_ID = "647d42e0-47c3-4ac8-9e7f-8f8bcf716c41"
        const val RAW_NONCE = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

        fun key(
            rawInvitationId: String,
            rawNonce: String,
            expiresAt: Instant,
        ): LanReplayKey {
            val invitationDigest = LanCrypto.sha256(rawInvitationId.encodeToByteArray())
            val nonceDigest = LanCrypto.sha256(rawNonce.encodeToByteArray())
            return try {
                LanReplayKey(
                    invitationIdDigest = invitationDigest.toHex(),
                    nonceDigest = nonceDigest.toHex(),
                    expiresAt = expiresAt,
                )
            } finally {
                invitationDigest.fill(0)
                nonceDigest.fill(0)
            }
        }
    }
}
