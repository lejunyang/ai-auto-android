package dev.aiauto.android.accessibility

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class ScreenshotTestActivity : Activity() {
    private lateinit var userTouchTarget: Button

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
                        text = "Automation target"
                        contentDescription = AUTOMATION_TARGET_DESCRIPTION
                    },
                )
                addView(
                    Button(context).apply {
                        text = "User touch target"
                        contentDescription = USER_TARGET_DESCRIPTION
                        userTouchTarget = this
                    },
                )
            },
        )
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
    }
}
