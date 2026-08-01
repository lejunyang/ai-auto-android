package dev.aiauto.android.ui.bridge.lan

/**
 * 测试用途：验证生产配对编排只在完整人工确认后连接，并在失败、停止或销毁时清理会话。
 */

import java.io.File
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import dev.aiauto.android.bridge.lan.LanConnectRequest
import dev.aiauto.android.bridge.lan.LanLocalInterface
import dev.aiauto.android.bridge.lan.LanProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPairingViewModelTest {
    private val input = StrictLanInvitationInputPort {
        Instant.parse("2026-07-25T10:00:30Z")
    }
    private val localInterface = LanLocalInterface(
        id = "android-network-42",
        name = "wlan0",
        kind = "wifi",
    )

    @Test
    fun `manual invitation requires candidate interface and fingerprint before connection`() {
        val connector = FakeSessionLauncher()
        val viewModel = viewModel(connector)

        viewModel.submitManualInvitation(validPayload)
        assertEquals(LanPairingPhase.REVIEW, viewModel.uiState.value.pairing.phase)
        assertEquals(0, connector.attempts.get())

        val invitation = requireNotNull(viewModel.uiState.value.pairing.invitation)
        val candidate = invitation.candidates.first()
        viewModel.selectCandidate(candidate.host, candidate.interfaceId)
        viewModel.selectLocalInterface(localInterface)
        assertEquals(0, connector.attempts.get())

        viewModel.confirmFingerprint("WRONG-FINGERPRINT")
        viewModel.connect()
        assertEquals(0, connector.attempts.get())

        viewModel.confirmFingerprint(invitation.desktopFingerprint)
        viewModel.connect()

        assertEquals(1, connector.attempts.get())
        assertEquals(LanPairingPhase.CONNECTED, viewModel.uiState.value.pairing.phase)
        assertTrue(viewModel.uiState.value.pairing.stopAvailable)
        assertFalse(viewModel.uiState.value.toString().contains(validPayload))
        viewModel.close()
    }

    @Test
    fun `stable connection error clears confirmation and does not expose exception text`() {
        val connector = FakeSessionLauncher(
            failure = LanProtocolException(
                "LAN_ADDRESS_UNREACHABLE",
                "secret endpoint details must not reach state",
            ),
        )
        val viewModel = viewModel(connector)
        confirm(viewModel)

        viewModel.connect()

        assertEquals(LanPairingPhase.FAILED, viewModel.uiState.value.pairing.phase)
        assertEquals("LAN_ADDRESS_UNREACHABLE", viewModel.uiState.value.pairing.errorCode)
        assertFalse(viewModel.uiState.value.toString().contains("secret endpoint"))
        assertFalse(viewModel.uiState.value.pairing.fingerprintConfirmed)
        viewModel.close()
    }

    @Test
    fun `scanner capability and result never connect before explicit confirmation`() {
        val connector = FakeSessionLauncher()
        val viewModel = viewModel(connector)

        viewModel.updateScannerCapability(
            hardware = CameraHardware.PRESENT,
            providerAvailable = true,
        )
        assertEquals(
            ScannerAvailability.READY,
            viewModel.uiState.value.pairing.scannerAvailability,
        )

        viewModel.onQrScanResult(LanQrScanResult.Success(validPayload))

        assertEquals(LanPairingPhase.REVIEW, viewModel.uiState.value.pairing.phase)
        assertEquals(0, connector.attempts.get())
        assertFalse(viewModel.uiState.value.toString().contains(validPayload))
        assertFalse(viewModel.uiState.value.pairing.canRequestConnection)
        viewModel.close()
    }

    @Test
    fun `cancelled scanner keeps manual path and no camera is explicit`() {
        val connector = FakeSessionLauncher()
        val viewModel = viewModel(connector)
        viewModel.updateScannerCapability(
            hardware = CameraHardware.ABSENT,
            providerAvailable = false,
        )
        assertEquals(
            ScannerAvailability.NO_CAMERA,
            viewModel.uiState.value.pairing.scannerAvailability,
        )

        viewModel.onQrScanResult(LanQrScanResult.Cancelled)

        assertTrue(viewModel.uiState.value.pairing.manualEntryAvailable)
        assertEquals(0, connector.attempts.get())
        assertEquals(LanPairingPhase.INPUT, viewModel.uiState.value.pairing.phase)
        viewModel.close()
    }

    @Test
    fun `explicit stop and close destroy active session and pending input`() {
        val connector = FakeSessionLauncher()
        val viewModel = viewModel(connector)
        confirm(viewModel)
        viewModel.connect()
        val session = requireNotNull(connector.session)

        viewModel.stop()
        assertTrue(session.closed)
        assertEquals(LanPairingPhase.STOPPED, viewModel.uiState.value.pairing.phase)

        viewModel.submitManualInvitation(validPayload)
        val pendingState = confirmedState(viewModel.uiState.value.pairing)
        viewModel.close()
        assertFailure("LAN_INVITATION_DESTROYED") {
            input.takeConnectRequest(
                state = pendingState,
                requestedCapabilities = LanPairingViewModel.REQUIRED_CAPABILITIES,
            )
        }
    }

    @Test
    fun `stop interrupts an in flight launcher and rejects a late session`() {
        val launcher = BlockingSessionLauncher()
        val executor = Executors.newSingleThreadExecutor()
        val viewModel = LanPairingViewModel(
            invitationInput = input,
            sessionLauncher = launcher,
            localInterfaces = { listOf(localInterface) },
            networkSelector = { it == localInterface },
            executor = executor,
            closeExecutor = executor::shutdownNow,
        )
        try {
            confirm(viewModel)
            viewModel.connect()
            assertTrue(launcher.entered.await(1, TimeUnit.SECONDS))

            viewModel.stop()

            assertTrue(launcher.closed)
            launcher.release.countDown()
            assertTrue(launcher.returned.await(1, TimeUnit.SECONDS))
            assertTrue(launcher.sessionClosed.await(1, TimeUnit.SECONDS))
            assertTrue(launcher.session.closed)
            assertEquals(LanPairingPhase.STOPPED, viewModel.uiState.value.pairing.phase)
        } finally {
            launcher.release.countDown()
            viewModel.close()
        }
    }

    private fun viewModel(connector: FakeSessionLauncher) = LanPairingViewModel(
        invitationInput = input,
        sessionLauncher = connector,
        localInterfaces = { listOf(localInterface) },
        networkSelector = { it == localInterface },
        executor = Executor(Runnable::run),
        closeExecutor = {},
    )

    private fun confirm(viewModel: LanPairingViewModel) {
        viewModel.submitManualInvitation(validPayload)
        val invitation = requireNotNull(viewModel.uiState.value.pairing.invitation)
        val candidate = invitation.candidates.first()
        viewModel.selectCandidate(candidate.host, candidate.interfaceId)
        viewModel.selectLocalInterface(localInterface)
        viewModel.confirmFingerprint(invitation.desktopFingerprint)
    }

    private fun confirmedState(state: LanPairingUiState): LanPairingUiState = state.copy(
        phase = LanPairingPhase.REVIEW,
        selectedCandidate = state.invitation?.candidates?.first(),
        selectedLocalInterface = localInterface,
        fingerprintConfirmed = true,
    )

    private fun assertFailure(expectedCode: String, action: () -> Unit) {
        try {
            action()
            throw AssertionError("expected $expectedCode")
        } catch (error: LanProtocolException) {
            assertEquals(expectedCode, error.code)
        }
    }

    private class FakeSessionLauncher(
        private val failure: LanProtocolException? = null,
    ) : LanPairingSessionLauncher {
        val attempts = AtomicInteger()
        var session: FakeSession? = null

        override fun connect(request: LanConnectRequest): LanPairingSession {
            attempts.incrementAndGet()
            failure?.also {
                request.invitation.close()
                throw it
            }
            return FakeSession(
                expiresAt = request.invitation.expiresAt,
                onClose = request.invitation::close,
            ).also { session = it }
        }
    }

    private class FakeSession(
        override val expiresAt: Instant,
        private val onClose: () -> Unit,
    ) : LanPairingSession {
        var closed = false

        override fun close() {
            if (!closed) {
                closed = true
                onClose()
            }
        }
    }

    private class BlockingSessionLauncher : LanPairingSessionLauncher {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val sessionClosed = CountDownLatch(1)
        val session = FakeSession(
            expiresAt = Instant.parse("2026-07-25T10:01:30Z"),
            onClose = sessionClosed::countDown,
        )

        @Volatile
        var closed = false

        override fun connect(request: LanConnectRequest): LanPairingSession {
            entered.countDown()
            release.await()
            request.invitation.close()
            returned.countDown()
            return session
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val validPayload: String by lazy {
            val start = requireNotNull(System.getProperty("user.dir")).let(::File)
            val fixture = generateSequence(start, File::getParentFile)
                .map { directory ->
                    File(directory, "protocol/fixtures/lan-invitation-v1-valid.json")
                }
                .firstOrNull(File::isFile)
            requireNotNull(fixture) { "Unable to locate LAN invitation fixture" }.readText()
        }
    }
}
