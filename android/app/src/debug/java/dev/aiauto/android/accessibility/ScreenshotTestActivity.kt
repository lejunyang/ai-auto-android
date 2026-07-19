package dev.aiauto.android.accessibility

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

import kotlinx.coroutines.runBlocking

import dev.aiauto.android.accessibility.model.AccessibilityResult

class ScreenshotTestActivity : Activity() {
    private lateinit var userTouchTarget: Button
    private lateinit var statusView: TextView

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
                        text = "Run attributed automation click"
                        setOnClickListener { runAutomationClickVerification(this) }
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
                        setOnClickListener {
                            postDelayed(
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
                        }
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

    private fun runAutomationClickVerification(button: Button) {
        setStatus("AUTOMATION_CLICK_RUNNING")
        button.postDelayed(
            {
                val service = ScreenshotTestAccessibilityService.connectedService
                if (service == null) {
                    setStatus("SERVICE_NOT_CONNECTED")
                    return@postDelayed
                }
                service.resetUserTouchSignal()
                val performed = service.performAttributedClick(AUTOMATION_TARGET_DESCRIPTION)
                statusView.postDelayed(
                    {
                        setStatus(
                            if (performed && service.userTouchNotificationCount() == 0) {
                                "AUTOMATION_CLICK_PASS"
                            } else {
                                "AUTOMATION_CLICK_FAIL"
                            },
                        )
                    },
                    EVENT_SETTLE_MS,
                )
            },
            EVENT_SETTLE_MS,
        )
    }

    private fun armUserTouchVerification(button: Button) {
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

    private fun setStatus(status: String) {
        statusView.text = status
        statusView.contentDescription = status
    }

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
