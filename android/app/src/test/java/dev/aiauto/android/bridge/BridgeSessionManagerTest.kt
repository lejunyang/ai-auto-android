package dev.aiauto.android.bridge

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgeSessionManagerTest {
    private val clock = MutableClock(Instant.parse("2026-07-18T00:00:00Z"))

    @Test
    fun `pairing code is one time and session token is random`() {
        val manager = BridgeSessionManager(clock = clock)
        val firstCode = manager.issuePairingCode()

        val firstSession = manager.open(firstCode.value, "desktop")

        assertEquals(43, firstSession.token.length)
        assertThrows(BridgeException::class.java) {
            manager.open(firstCode.value, "desktop")
        }.assertCode(BridgeErrorCode.AUTH_REQUIRED)

        val secondCode = manager.issuePairingCode()
        val secondSession = manager.open(secondCode.value, "desktop")
        assertNotEquals(firstSession.token, secondSession.token)
    }

    @Test
    fun `wrong and expired codes return stable authentication errors`() {
        val manager = BridgeSessionManager(clock = clock)
        val code = manager.issuePairingCode()

        assertThrows(BridgeException::class.java) {
            manager.open("999999", "desktop")
        }.assertCode(BridgeErrorCode.AUTH_INVALID)

        clock.advanceSeconds(BridgeLimits.PAIRING_CODE_TTL_SECONDS)
        assertThrows(BridgeException::class.java) {
            manager.open(code.value, "desktop")
        }.assertCode(BridgeErrorCode.AUTH_EXPIRED)
    }

    @Test
    fun `expired wrong and closed tokens reveal no protected state`() {
        val manager = BridgeSessionManager(clock = clock)
        val code = manager.issuePairingCode()
        val session = manager.open(code.value, "desktop")

        assertThrows(BridgeException::class.java) {
            manager.authenticate("different-token-that-is-at-least-32-bytes")
        }.assertCode(BridgeErrorCode.AUTH_INVALID)

        clock.advanceSeconds(BridgeLimits.SESSION_TOKEN_TTL_SECONDS)
        assertThrows(BridgeException::class.java) {
            manager.authenticate(session.token)
        }.assertCode(BridgeErrorCode.AUTH_EXPIRED)

        val replacementCode = manager.issuePairingCode()
        val replacement = manager.open(replacementCode.value, "desktop")
        manager.close(replacement.token)
        assertThrows(BridgeException::class.java) {
            manager.authenticate(replacement.token)
        }.assertCode(BridgeErrorCode.AUTH_INVALID)
    }

    @Test
    fun `replay cache rejects duplicate request ids inside the window`() {
        val cache = RequestReplayCache(clock)
        val requestId = "77777777-7777-4777-8777-777777777777"

        cache.record(requestId)
        assertThrows(BridgeException::class.java) {
            cache.record(requestId)
        }.assertCode(BridgeErrorCode.REQUEST_REPLAYED)

        clock.advanceSeconds(BridgeLimits.REPLAY_WINDOW_SECONDS + 1)
        cache.record(requestId)
    }

    @Test
    fun `replay cache rejects new work instead of evicting live request ids`() {
        val cache = RequestReplayCache(clock)
        repeat(BridgeLimits.MAX_REPLAY_ENTRIES) { index ->
            cache.record("request-$index")
        }

        assertThrows(BridgeException::class.java) {
            cache.record("one-too-many")
        }.assertCode(BridgeErrorCode.RATE_LIMITED)
        assertThrows(BridgeException::class.java) {
            cache.record("request-0")
        }.assertCode(BridgeErrorCode.REQUEST_REPLAYED)
    }

    private fun BridgeException.assertCode(expected: BridgeErrorCode) {
        assertEquals(expected, code)
        assertFalse(message.contains(Regex("\\d{6}")))
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }
}
