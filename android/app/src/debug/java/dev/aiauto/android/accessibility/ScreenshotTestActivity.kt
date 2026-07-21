package dev.aiauto.android.accessibility

/**
 * 功能用途：提供 ScreenshotTestActivity 的设备验收入口，仅用于 debug 变体且不进入 release 制品。
 */

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

import dev.aiauto.android.accessibility.model.AccessibilityResult

class ScreenshotTestActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var userTouchTarget: Button
    private lateinit var statusView: TextView
    private var sessionStopHarness: SessionStopVerificationHarness? = null
    @Volatile
    private var currentStatus = "READY"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(context).apply {
                        text = "Non-sensitive screenshot test surface"
                        textSize = 24f
                        contentDescription = "Screenshot test surface"
                    },
                )
                addView(
                    Button(context).apply {
                        text = "Run screenshot verification"
                        setOnClickListener { runScreenshotVerification() }
                    },
                )
                addView(
                    Button(context).apply {
                        text = "Run session stop verification"
                        setOnClickListener { startSessionStopVerification() }
                    },
                )
                addView(
                    Button(context).apply {
                        text = "Arm user touch verification"
                        setOnClickListener { armUserTouchVerification(this) }
                    },
                )
                addView(
                    Button(context).apply {
                        text = "Automation target"
                        contentDescription = AUTOMATION_TARGET_DESCRIPTION
                    },
                )
                addView(
                    Button(context).apply {
                        text = "User touch target"
                        contentDescription = USER_TARGET_DESCRIPTION
                        userTouchTarget = this
                        setOnClickListener { verifyUserTouchResult() }
                    },
                )
                addView(
                    TextView(context).apply {
                        text = "READY"
                        contentDescription = "READY"
                        statusView = this
                    },
                )
            },
        )
    }

    override fun onDestroy() {
        sessionStopHarness?.close()
        sessionStopHarness = null
        activityScope.cancel()
        super.onDestroy()
    }

    private fun runScreenshotVerification() {
        val service = ScreenshotTestAccessibilityService.connectedService
        if (service == null) {
            setStatus("SERVICE_NOT_CONNECTED")
            return
        }
        setStatus("SCREENSHOT_RUNNING")
        Thread {
            val result = runBlocking {
                AccessibilityScreenshotController(
                    platform = AndroidScreenshotPlatform(
                        service = service,
                        callbackExecutor = service.mainExecutor,
                    ),
                    snapshot = service::snapshot,
                    isAuthorized = { it == packageName },
                ).capture(packageName)
            }
            val status = when (result) {
                is AccessibilityResult.Failure ->
                    "SCREENSHOT_FAIL_${result.error.code.name}"

                is AccessibilityResult.Success -> result.value.use { screenshot ->
                    if (
                        screenshot.pngBytes.size <= ScreenshotImagePolicy().maxPngBytes &&
                        maxOf(screenshot.width, screenshot.height) <=
                        ScreenshotImagePolicy().maxLongEdgePx
                    ) {
                        "SCREENSHOT_PASS"
                    } else {
                        "SCREENSHOT_FAIL_BUDGET"
                    }
                }
            }
            runOnUiThread { setStatus(status) }
        }.start()
    }

    fun startSessionStopVerification() {
        val service = ScreenshotTestAccessibilityService.connectedService
        if (service == null) {
            setStatus("SERVICE_NOT_CONNECTED")
            return
        }
        sessionStopHarness?.close()
        val harness = SessionStopVerificationHarness(packageName)
        sessionStopHarness = harness
        setStatus("SESSION_STARTING")
        activityScope.launch {
            delay(EVENT_SETTLE_MS)
            if (sessionStopHarness !== harness) {
                return@launch
            }
            service.resetUserTouchSignal()
            if (!harness.start()) {
                setStatus("SESSION_START_FAIL")
                harness.close()
                return@launch
            }
            setStatus("SESSION_PLANNING")
            val performed = service.performAttributedClick(AUTOMATION_TARGET_DESCRIPTION)
            delay(EVENT_SETTLE_MS)
            if (sessionStopHarness !== harness) {
                return@launch
            }
            if (
                harness.recordAutomationClick(
                    performed = performed,
                    userTouchNotifications = service.userTouchNotificationCount(),
                )
            ) {
                setStatus("AUTOMATION_CLICK_PASS TAP_USER_TARGET")
            } else {
                setStatus("AUTOMATION_CLICK_FAIL")
                harness.close()
            }
        }
    }

    private fun armUserTouchVerification(button: Button) {
        sessionStopHarness?.close()
        sessionStopHarness = null
        setStatus("USER_TOUCH_ARMING")
        button.postDelayed(
            {
                val service = ScreenshotTestAccessibilityService.connectedService
                if (service == null) {
                    setStatus("SERVICE_NOT_CONNECTED")
                    return@postDelayed
                }
                service.resetUserTouchSignal()
                if (service.armClickExpectation(AUTOMATION_TARGET_DESCRIPTION)) {
                    setStatus("TAP_USER_TARGET")
                } else {
                    setStatus("USER_TOUCH_ARM_FAIL")
                }
            },
            EVENT_SETTLE_MS,
        )
    }

    private fun verifyUserTouchResult() {
        val harness = sessionStopHarness
        if (harness == null) {
            userTouchTarget.postDelayed(
                {
                    setStatus(
                        if (
                            ScreenshotTestAccessibilityService
                                .connectedService
                                ?.userTouchNotificationCount() == 1
                        ) {
                            "USER_TOUCH_PASS"
                        } else {
                            "USER_TOUCH_FAIL"
                        },
                    )
                },
                EVENT_SETTLE_MS,
            )
            return
        }
        activityScope.launch {
            setStatus(harness.verifyStopped())
        }
    }

    private fun setStatus(status: String) {
        currentStatus = status
        statusView.text = status
        statusView.contentDescription = status
    }

    fun currentVerificationStatus(): String = currentStatus

    fun userTouchTargetCenter(): Pair<Float, Float> {
        val location = IntArray(2)
        userTouchTarget.getLocationOnScreen(location)
        return Pair(
            location[0] + userTouchTarget.width / 2f,
            location[1] + userTouchTarget.height / 2f,
        )
    }

    companion object {
        const val AUTOMATION_TARGET_DESCRIPTION = "Automation target"
        const val USER_TARGET_DESCRIPTION = "User touch target"
        const val EVENT_SETTLE_MS = 500L
    }
}
