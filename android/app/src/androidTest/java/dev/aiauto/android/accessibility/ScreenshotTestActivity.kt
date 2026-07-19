package dev.aiauto.android.accessibility

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class ScreenshotTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "Non-sensitive screenshot test surface"
                textSize = 24f
                contentDescription = "Screenshot test surface"
            },
        )
    }
}
