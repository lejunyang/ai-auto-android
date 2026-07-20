package dev.aiauto.android.automation.recording

// 设备测试用途：在真实 Android 运行时验证 AndroidScriptEnvironmentProviderDevice 的设备能力、权限前提与生命周期边界。

import android.os.Build

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import dev.aiauto.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidScriptEnvironmentProviderDeviceTest {
    @Test
    fun applicationContextCapturesDisplayEnvironment() {
        val applicationContext =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

        val environment = AndroidScriptEnvironmentProvider(applicationContext).capture()

        assertEquals(Build.VERSION.SDK_INT, environment.apiLevel)
        assertEquals(BuildConfig.VERSION_NAME, environment.appVersion)
        assertTrue(requireNotNull(environment.logicalWidth) > 0)
        assertTrue(requireNotNull(environment.logicalHeight) > 0)
        assertTrue(requireNotNull(environment.densityDpi) > 0)
        assertTrue(requireNotNull(environment.rotation) in setOf(0, 90, 180, 270))
    }
}
