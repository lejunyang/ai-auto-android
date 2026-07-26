package dev.aiauto.android.automation.session

/**
 * 测试用途：验证 AutomationRiskPolicy 的功能契约、失败语义及自动化安全边界。
 */

import dev.aiauto.android.provider.ProviderAction
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRiskPolicyTest {
    private val policy = AutomationRiskPolicy(setOf(TARGET_PACKAGE))

    @Test
    fun `evaluateTask allows normal task for configured package BitsUT`() {
        assertTrue(
            policy.evaluateTask("Open the inbox", TARGET_PACKAGE) is RiskDecision.Allow,
        )
    }

    @Test
    fun `evaluateTask blocks unconfigured and sensitive targets BitsUT`() {
        assertTrue(
            policy.evaluateTask("Open inbox", "com.other.app") is RiskDecision.Block,
        )
        assertTrue(
            policy.evaluateTask(
                "Open account",
                "com.example.wallet",
            ) is RiskDecision.Block,
        )
    }

    @Test
    fun `evaluateTask blocks payment install and permission intent BitsUT`() {
        assertTrue(
            policy.evaluateTask(
                "Transfer money to a contact",
                TARGET_PACKAGE,
            ) is RiskDecision.Block,
        )
        assertTrue(
            policy.evaluateTask(
                "Install the downloaded package",
                TARGET_PACKAGE,
            ) is RiskDecision.Block,
        )
        assertTrue(
            policy.evaluateTask(
                "\u6388\u4e88\u76f8\u673a\u6743\u9650",
                TARGET_PACKAGE,
            ) is RiskDecision.Block,
        )
    }

    @Test
    fun `evaluateAction blocks target package escape and unknown action BitsUT`() {
        val escaped = ProviderAction(
            type = "app.launch",
            params = buildJsonObject { put("packageName", "com.other.app") },
        )

        assertTrue(policy.evaluateAction(escaped, TARGET_PACKAGE) is RiskDecision.Block)
        assertTrue(
            policy.evaluateAction(
                ProviderAction("shell.exec", buildJsonObject {}),
                TARGET_PACKAGE,
            ) is RiskDecision.Block,
        )
    }

    @Test
    fun `evaluateAction requires explicit package for semantic targets BitsUT`() {
        val targetWithoutPackage = ProviderAction(
            type = "ui.click",
            params = buildJsonObject {
                put(
                    "target",
                    buildJsonObject {
                        put(
                            "fingerprint",
                            buildJsonObject {
                                put("text", JsonPrimitive("Open details"))
                            },
                        )
                    },
                )
            },
        )

        assertTrue(
            policy.evaluateAction(
                targetWithoutPackage,
                TARGET_PACKAGE,
            ) is RiskDecision.Block,
        )
    }

    @Test
    fun `evaluateAction blocks global navigation that leaves target package BitsUT`() {
        assertTrue(
            policy.evaluateAction(
                ProviderAction("ui.home", buildJsonObject {}),
                TARGET_PACKAGE,
            ) is RiskDecision.Block,
        )
        assertTrue(
            policy.evaluateAction(
                ProviderAction("ui.recents", buildJsonObject {}),
                TARGET_PACKAGE,
            ) is RiskDecision.Block,
        )
    }

    @Test
    fun `evaluateAction confirms risky semantic and evidenced visual tap but blocks bare coordinates BitsUT`() {
        assertTrue(
            policy.evaluateAction(
                semanticClick("Send message"),
                TARGET_PACKAGE,
            ) is RiskDecision.RequireConfirmation,
        )
        assertTrue(
            policy.evaluateAction(
                semanticClick("Delete item"),
                TARGET_PACKAGE,
            ) is RiskDecision.RequireConfirmation,
        )
        assertTrue(
            policy.evaluateAction(
                ProviderAction(
                    "ui.tap",
                    buildJsonObject {
                        put("x", 10)
                        put("y", 20)
                    },
                ),
                TARGET_PACKAGE,
            ) is RiskDecision.Block,
        )
        assertTrue(
            policy.evaluateAction(
                visualTap(),
                TARGET_PACKAGE,
            ) is RiskDecision.RequireConfirmation,
        )
        assertTrue(
            policy.evaluateAction(
                visualTap().copy(type = "ui.swipe"),
                TARGET_PACKAGE,
            ) is RiskDecision.RequireConfirmation,
        )
    }

    @Test
    fun `evaluateAction allows semantic navigation without risky target BitsUT`() {
        assertTrue(
            policy.evaluateAction(
                semanticClick("Open details"),
                TARGET_PACKAGE,
            ) is RiskDecision.Allow,
        )
        assertTrue(
            policy.evaluateAction(
                ProviderAction("ui.back", buildJsonObject {}),
                TARGET_PACKAGE,
            ) is RiskDecision.Allow,
        )
    }

    private fun semanticClick(value: String) = ProviderAction(
        type = "ui.click",
        params = buildJsonObject {
            put(
                "target",
                buildJsonObject {
                    put("packageName", TARGET_PACKAGE)
                    put(
                        "fingerprint",
                        buildJsonObject {
                            put("text", JsonPrimitive(value))
                        },
                    )
                },
            )
        },
    )

    private fun visualTap() = ProviderAction(
        type = "ui.tap",
        params = buildJsonObject {
            put(
                "visualTarget",
                buildJsonObject {
                    put("packageName", TARGET_PACKAGE)
                    put("observationId", "123e4567-e89b-42d3-a456-426614174044")
                    put("imageSha256", "a".repeat(64))
                    put("candidateId", "123e4567-e89b-42d3-a456-426614174045")
                    put("source", "model")
                    put("confidence", 0.93)
                    put("point", buildJsonObject {
                        put("x", 0.5)
                        put("y", 0.75)
                    })
                    put("bounds", buildJsonObject {
                        put("left", 0.4)
                        put("top", 0.7)
                        put("right", 0.6)
                        put("bottom", 0.8)
                    })
                },
            )
        },
    )

    private companion object {
        const val TARGET_PACKAGE = "com.example.app"
    }
}
