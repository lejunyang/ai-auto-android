package dev.aiauto.fixture

/**
 * 功能用途：提供无网络、无敏感数据的本地开关页面，供设备动作、Bridge、录制和回放冒烟测试使用。
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

        toggle.contentDescription = getString(R.string.local_toggle_description)
        updateStatus(toggle.isChecked, statusText)

        toggle.setOnCheckedChangeListener { _, isChecked ->
            updateStatus(isChecked, statusText)
        }
    }

    private fun updateStatus(isChecked: Boolean, statusText: TextView) {
        if (isChecked) {
            statusText.text = getString(R.string.status_on)
        } else {
            statusText.text = getString(R.string.status_off)
        }
    }
}
