package dev.aiauto.fixture

/**
 * 功能用途：仅在 debug 测试包内复位进程状态和任务栈，避免 instrumentation 使用 shell 清理应用。
 */

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class TestResetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FixtureProcessState.current.reset()

        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_RESET_AND_LAUNCH -> {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
            MODE_RESET_AND_FINISH -> finishAndRemoveTask()
            else -> finishAndRemoveTask()
        }
        finish()
    }

    companion object {
        const val EXTRA_MODE = "fixture_reset_mode"
        const val MODE_RESET_AND_LAUNCH = "reset_and_launch"
        const val MODE_RESET_AND_FINISH = "reset_and_finish"
    }
}
