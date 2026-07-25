package dev.aiauto.fixture

/**
 * 测试用途：用 UI Automator 逐步验证原生动作、三页 Back 栈和系统导航，提供可重复矩阵入口。
 */

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeFixtureDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context = instrumentation.targetContext
    private val arguments: Bundle = InstrumentationRegistry.getArguments()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val packageName = targetContext.packageName

    @Before
    fun resetFixture() {
        startTestResetActivity(TestResetActivity.MODE_RESET_AND_LAUNCH)
        awaitState(R.id.page_state, "PAGE:MAIN")
    }

    @After
    fun clearFixture() {
        startTestResetActivity(TestResetActivity.MODE_RESET_AND_FINISH)
        assertTrue(device.wait(Until.gone(By.pkg(packageName).depth(0)), TIMEOUT))
    }

    @Test
    fun runSelectedFixtureScenario() {
        val repeat = arguments.getString("fixtureRepeat", "1").toIntOrNull() ?: 1
        require(repeat in 1..100) { "fixtureRepeat must be in 1..100" }
        val scenario = arguments.getString("fixtureScenario", "all").lowercase()

        repeat(repeat) { iteration ->
            if (scenario == "all" || scenario == "core") {
                runCoreScenario(iteration)
            }
            if (scenario == "all" || scenario == "system") {
                runSystemScenario(iteration)
            }
            require(scenario in setOf("all", "core", "system")) {
                "fixtureScenario must be all, core, or system"
            }
        }
    }

    private fun runCoreScenario(iteration: Int) {
        resetInApp()

        val input = requireObject(R.id.text_input)
        assertEquals("Fixture text input", input.contentDescription)
        input.click()
        input.text = "fixture-$iteration"
        device.pressBack()
        awaitState(R.id.input_state, "INPUT:fixture-$iteration")

        clickAndAwait(
            R.id.click_target,
            "Fixture click target",
            R.id.click_state,
            "CLICK:1",
        )
        requireObject(R.id.long_press_target).also {
            assertEquals("Fixture long press target", it.contentDescription)
            it.longClick()
        }
        awaitState(R.id.long_press_state, "LONG_PRESS:1")

        val vertical = requireObject(R.id.vertical_scroll_target)
        assertEquals("Fixture vertical scroll target", vertical.contentDescription)
        vertical.scroll(Direction.DOWN, 0.8f)
        awaitState(R.id.vertical_scroll_state, "VERTICAL_SCROLL:1")

        val horizontal = requireObject(R.id.horizontal_swipe_target)
        assertEquals("Fixture horizontal swipe target", horizontal.contentDescription)
        horizontal.swipe(Direction.LEFT, 0.8f)
        awaitState(R.id.horizontal_swipe_state, "HORIZONTAL_SWIPE:1")

        requireObject(R.id.dialog_open_target).also {
            assertEquals("Fixture dialog open target", it.contentDescription)
            it.click()
        }
        requireObject(R.id.dialog_state, "DIALOG:OPEN")
        requireObject(R.id.dialog_dismiss_target).also {
            assertEquals("Fixture dialog dismiss target", it.contentDescription)
            it.click()
        }
        awaitState(R.id.dialog_state, "DIALOG:DISMISSED")

        requireObject(R.id.open_second_page_target).also {
            assertEquals("Fixture open second page target", it.contentDescription)
            it.click()
        }
        awaitState(R.id.page_state, "PAGE:SECOND")
        requireObject(R.id.open_third_page_target).also {
            assertEquals("Fixture open third page target", it.contentDescription)
            it.click()
        }
        awaitState(R.id.page_state, "PAGE:THIRD")
        device.pressBack()
        awaitState(R.id.page_state, "PAGE:SECOND")
        device.pressBack()
        awaitState(R.id.page_state, "PAGE:MAIN")

        resetInApp()
        awaitState(R.id.input_state, "INPUT:")
        awaitState(R.id.click_state, "CLICK:0")
        awaitState(R.id.long_press_state, "LONG_PRESS:0")
        awaitState(R.id.vertical_scroll_state, "VERTICAL_SCROLL:0")
        awaitState(R.id.horizontal_swipe_state, "HORIZONTAL_SWIPE:0")
        awaitState(R.id.dialog_state, "DIALOG:READY")
    }

    private fun runSystemScenario(iteration: Int) {
        resetInApp()
        assertFixtureForeground()

        markExternalNavigation(
            R.id.mark_home_target,
            "Fixture mark Home navigation target",
            "HOME",
        )
        device.pressHome()
        val launcherPackage = awaitForeignForegroundPackage()
        assertTrue(
            "Home must resolve to an observed launcher: $launcherPackage",
            launcherPackage in observedLauncherPackages(),
        )
        launchThroughMainLauncherIntent()
        awaitState(R.id.home_return_state, "SYSTEM_RETURN:HOME")

        markExternalNavigation(
            R.id.mark_recents_target,
            "Fixture mark Recents navigation target",
            "RECENTS",
        )
        device.pressRecentApps()
        val recentsPackage = awaitForeignForegroundPackage()
        assertNotEquals(packageName, recentsPackage)
        assertTrue(
            "Recents must be hosted by the observed launcher or a system package: $recentsPackage",
            recentsPackage == launcherPackage || isSystemPackage(recentsPackage),
        )
        device.waitForIdle()
        assertEquals(recentsPackage, device.currentPackageName)
        launchThroughMainLauncherIntent()
        awaitState(R.id.recents_return_state, "SYSTEM_RETURN:RECENTS")

        markExternalNavigation(
            R.id.mark_settings_target,
            "Fixture mark Settings switch target",
            "SETTINGS",
        )
        val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val settingsPackage = settingsIntent.resolveActivity(targetContext.packageManager)
            ?.packageName
            ?: throw AssertionError("No activity resolves ACTION_SETTINGS")
        targetContext.startActivity(settingsIntent)
        assertTrue(device.wait(Until.hasObject(By.pkg(settingsPackage).depth(0)), TIMEOUT))
        assertEquals(settingsPackage, device.currentPackageName)
        launchThroughMainLauncherIntent()
        awaitState(R.id.settings_return_state, "SYSTEM_RETURN:SETTINGS")
        awaitState(R.id.reset_state, "RESET:DONE")
        assertTrue("Iteration must stay non-negative", iteration >= 0)
    }

    private fun resetInApp() {
        requireObject(R.id.reset_target).also {
            assertEquals("Fixture reset target", it.contentDescription)
            it.click()
        }
        awaitState(R.id.reset_state, "RESET:DONE")
        awaitState(R.id.page_state, "PAGE:MAIN")
    }

    private fun markExternalNavigation(targetId: Int, description: String, kind: String) {
        assertFixtureForeground()
        awaitState(R.id.system_pending_state, "SYSTEM_PENDING:NONE")
        requireObject(targetId).also {
            assertEquals(description, it.contentDescription)
            it.click()
        }
        awaitState(R.id.system_pending_state, "SYSTEM_PENDING:$kind")
    }

    private fun clickAndAwait(
        targetId: Int,
        description: String,
        stateId: Int,
        expected: String,
    ) {
        requireObject(targetId).also {
            assertEquals(description, it.contentDescription)
            it.click()
        }
        awaitState(stateId, expected)
    }

    private fun awaitState(id: Int, expected: String): UiObject2 =
        requireObject(id, expected).also {
            assertEquals(expected, it.text)
        }

    private fun requireObject(id: Int, text: String? = null): UiObject2 {
        val selector = By.res(packageName, resourceName(id)).let {
            if (text == null) it else it.text(text)
        }
        return device.wait(Until.findObject(selector), TIMEOUT)
            ?: throw AssertionError("Missing node ${resourceName(id)} with text=$text")
    }

    private fun launchThroughMainLauncherIntent() {
        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_MAIN, intent?.action)
        assertTrue(intent?.categories?.contains(Intent.CATEGORY_LAUNCHER) == true)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        targetContext.startActivity(intent)
        assertTrue(device.wait(Until.hasObject(By.pkg(packageName).depth(0)), TIMEOUT))
        assertEquals(packageName, device.currentPackageName)
        device.waitForIdle()
    }

    private fun startTestResetActivity(mode: String) {
        val intent = Intent(targetContext, TestResetActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(TestResetActivity.EXTRA_MODE, mode)
        }
        targetContext.startActivity(intent)
        device.waitForIdle()
    }

    private fun assertFixtureForeground() {
        assertTrue(device.wait(Until.hasObject(By.pkg(packageName).depth(0)), TIMEOUT))
        assertEquals(packageName, device.currentPackageName)
    }

    private fun awaitForeignForegroundPackage(): String {
        assertTrue(device.wait(Until.gone(By.pkg(packageName).depth(0)), TIMEOUT))
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            val observed = device.currentPackageName
            if (!observed.isNullOrBlank() && observed != packageName) {
                device.waitForIdle()
                val stable = device.currentPackageName
                if (!stable.isNullOrBlank() && stable != packageName) {
                    return stable
                }
            }
            Thread.sleep(100)
        }
        throw AssertionError("No non-fixture foreground package was observed")
    }

    private fun observedLauncherPackages(): Set<String> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return targetContext.packageManager
            .queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
    }

    @Suppress("DEPRECATION")
    private fun isSystemPackage(candidate: String): Boolean {
        val flags = targetContext.packageManager.getApplicationInfo(candidate, 0).flags
        return flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private fun resourceName(id: Int): String = targetContext.resources.getResourceEntryName(id)

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
