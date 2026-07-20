package dev.aiauto.android.ui.recording

/**
 * 测试用途：验证 RecordingUi 的功能契约、失败语义及自动化安全边界。
 */

import dev.aiauto.android.automation.recording.AutomationScript
import dev.aiauto.android.automation.recording.RecordedAction
import dev.aiauto.android.automation.recording.RecordedStep
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingUiTest {
    @Test
    fun `required secret refs are ordered and deduplicated BitsUT`() {
        val script = scriptWithSecrets(
            "account.password",
            "verification.code",
            "account.password",
        )

        assertEquals(
            listOf("account.password", "verification.code"),
            requiredSecretRefs(script),
        )
    }

    @Test
    fun `replay readiness requires every secret to be non blank BitsUT`() {
        val refs = listOf("account.password", "verification.code")

        assertFalse(
            hasRequiredSecrets(
                requiredRefs = refs,
                secretValues = mapOf(
                    "account.password" to "present",
                    "verification.code" to " ",
                ),
            ),
        )
        assertTrue(
            hasRequiredSecrets(
                requiredRefs = refs,
                secretValues = mapOf(
                    "account.password" to "present",
                    "verification.code" to "123456",
                ),
            ),
        )
    }

    private fun scriptWithSecrets(vararg aliases: String) = AutomationScript(
        id = "script",
        name = "Secret form",
        targetPackages = listOf("com.example"),
        createdAt = "2026-07-18T00:00:00Z",
        steps = aliases.mapIndexed { index, alias ->
            RecordedStep(
                id = "step-$index",
                recordedAtMs = index.toLong(),
                action = RecordedAction(
                    type = "ui.setText",
                    params = buildJsonObject {
                        put("secretRef", JsonPrimitive(alias))
                    },
                ),
            )
        },
    )
}
