package dev.aiauto.fixture

/**
 * 验收用途：提供无网络、无敏感数据的本地开关页面，供设备动作、Bridge、录制和回放冒烟测试使用。
 */

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.widget.ToggleButton

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toggle = findViewById<ToggleButton>(R.id.local_toggle)
        val statusText = findViewById<TextView>(R.id.status_text)

        updateStatus(toggle.isChecked, toggle, statusText)

        toggle.setOnCheckedChangeListener { _, isChecked ->
            updateStatus(isChecked, toggle, statusText)
        }
    }

    private fun updateStatus(isChecked: Boolean, toggle: ToggleButton, statusText: TextView) {
        if (isChecked) {
            statusText.text = getString(R.string.status_on)
            toggle.contentDescription = getString(R.string.toggle_accessibility_on)
        } else {
            statusText.text = getString(R.string.status_off)
            toggle.contentDescription = getString(R.string.toggle_accessibility_off)
        }
    }
}