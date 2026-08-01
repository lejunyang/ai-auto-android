package dev.aiauto.android.ui.recording

/**
 * 测试用途：验证截图点选租约不会进入可恢复编辑状态，且旋转和离开均释放授权。
 */

import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEditorObservationTest {
    @Test
    fun `authorized observation expires closed and releases once BitsUT`() {
        var releases = 0
        val lease = AuthorizedObservationLease(
            observationId = "short-lived",
            imageSha256 = IMAGE_SHA256,
            expiresAtMs = 1_100,
            onRelease = { releases += 1 },
        )
        val holder = RecordingObservationHolder(clock = { 1_101 })
        holder.attach(AuthorizedObservationView(lease) {})

        val result = holder.selectNormalized(0.25, 0.75)

        assertEquals(ObservationSelection.Unavailable, result)
        assertTrue(lease.isReleased)
        assertEquals(1, releases)
        holder.detach()
        assertEquals(1, releases)
    }

    @Test
    fun `rotation releases image lease while retained script keeps only visual metadata BitsUT`() {
        val viewModel = RecordingEditorViewModel(initialScript = script())
        viewModel.selectStep(STEP_ID)
        var releases = 0
        val oldHolder = RecordingObservationHolder(clock = { 1_000 })
        val lease = AuthorizedObservationLease(
            observationId = "rotation-image",
            imageSha256 = IMAGE_SHA256,
            expiresAtMs = 2_000,
            onRelease = { releases += 1 },
        )
        oldHolder.attach(AuthorizedObservationView(lease) {})

        val selection = oldHolder.selectNormalized(0.2, 0.8)
        assertTrue(selection is ObservationSelection.Selected)
        selection as ObservationSelection.Selected
        assertEquals("rotation-image", selection.observationId)
        assertEquals(IMAGE_SHA256, selection.imageSha256)
        viewModel.applyObservationSelection(selection)
        oldHolder.detach()

        val recreatedHolder = RecordingObservationHolder(clock = { 1_000 })
        assertEquals(1, releases)
        assertNull(recreatedHolder.currentObservationId)
        assertEquals("0.2", viewModel.uiState.value.stepForm?.coordinateX)
        assertEquals("0.8", viewModel.uiState.value.stepForm?.coordinateY)
        val target = requireNotNull(viewModel.uiState.value.script.steps.single().visualTarget)
        assertEquals("rotation-image", target.observationId)
        assertEquals(IMAGE_SHA256, target.imageSha256)
        assertNull(target.screenshotBase64)
        assertNull(target.deviceLocalPath)
    }

    @Test
    fun `bounds selection canonicalizes reverse drag with the same authorized metadata BitsUT`() {
        val holder = RecordingObservationHolder(clock = { 1_000 })
        holder.attach(
            AuthorizedObservationView(
                AuthorizedObservationLease(
                    observationId = "bounds-image",
                    imageSha256 = IMAGE_SHA256,
                    expiresAtMs = 2_000,
                    onRelease = {},
                ),
            ) {},
        )

        val selection = holder.selectNormalizedBounds(
            startX = 0.8,
            startY = 0.75,
            endX = 0.2,
            endY = 0.25,
        )

        assertEquals(
            ObservationSelection.BoundsSelected(
                bounds = dev.aiauto.android.automation.recording.NormalizedBounds(
                    left = 0.2,
                    top = 0.25,
                    right = 0.8,
                    bottom = 0.75,
                ),
                observationId = "bounds-image",
                imageSha256 = IMAGE_SHA256,
            ),
            selection,
        )
    }

    private fun script() = AutomationScript(
        id = "10000000-0000-4000-8000-000000000001",
        name = "Observation test",
        targetPackages = listOf("com.example"),
        createdAt = "2026-07-26T00:00:00Z",
        steps = listOf(
            RecordedStep(
                id = STEP_ID,
                action = RecordedAction(
                    "ui.click",
                    buildJsonObject {
                        putJsonObject("target") {}
                    },
                ),
            ),
        ),
    )

    private companion object {
        const val STEP_ID = "20000000-0000-4000-8000-000000000001"
        const val IMAGE_SHA256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
