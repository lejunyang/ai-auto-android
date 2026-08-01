package dev.aiauto.fixture

/**
 * 测试用途：用 UI Automator 逐步验证原生动作、三页 Back 栈和系统导航，提供可重复矩阵入口。
 */

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            if (scenario == "vertical") {
                resetInApp()
                runVerticalScrollScenario()
            }
            if (scenario == "all" || scenario == "system") {
                runSystemScenario(iteration)
            }
            require(scenario in setOf("all", "core", "vertical", "system")) {
                "fixtureScenario must be all, core, vertical, or system"
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

        runVerticalScrollScenario()

        awaitStateAfterReveal(R.id.horizontal_swipe_offset, "HORIZONTAL_OFFSET:0")
        val horizontal = revealScrollableTarget(R.id.horizontal_swipe_target, HORIZONTAL_TARGET_HEIGHT_DP)
        assertEquals("Fixture horizontal swipe target", horizontal.contentDescription)
        swipeHorizontallyWithin(horizontal.visibleBounds)
        assertPositiveOffset(R.id.horizontal_swipe_offset, "HORIZONTAL_OFFSET")
        awaitStateAfterReveal(R.id.horizontal_swipe_state, "HORIZONTAL_SWIPE:1")

        revealObjectBelow(R.id.dialog_open_target).also {
            assertEquals("Fixture dialog open target", it.contentDescription)
            it.click()
        }
        requireObject(R.id.dialog_state, "DIALOG:OPEN")
        requireObject(R.id.dialog_dismiss_target).also {
            assertEquals("Fixture dialog dismiss target", it.contentDescription)
            it.click()
        }
        awaitState(R.id.dialog_state, "DIALOG:DISMISSED")

        revealObjectBelow(R.id.open_second_page_target).also {
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
        awaitStateAfterReveal(R.id.vertical_scroll_state, "VERTICAL_SCROLL:0")
        awaitStateAfterReveal(R.id.vertical_scroll_offset, "VERTICAL_OFFSET:0")
        awaitStateAfterReveal(R.id.horizontal_swipe_state, "HORIZONTAL_SWIPE:0")
        awaitStateAfterReveal(R.id.horizontal_swipe_offset, "HORIZONTAL_OFFSET:0")
        awaitStateAfterReveal(R.id.dialog_state, "DIALOG:READY")
    }

    private fun runVerticalScrollScenario() {
        awaitStateAfterReveal(R.id.vertical_scroll_offset, "VERTICAL_OFFSET:0")
        val vertical = revealScrollableTarget(R.id.vertical_scroll_target, VERTICAL_TARGET_HEIGHT_DP)
        assertEquals("Fixture vertical scroll target", vertical.contentDescription)
        swipeVerticallyWithin(vertical.visibleBounds)
        assertPositiveOffset(R.id.vertical_scroll_offset, "VERTICAL_OFFSET")
        awaitStateAfterReveal(R.id.vertical_scroll_state, "VERTICAL_SCROLL:1")
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
        awaitStateAfterReveal(R.id.home_return_state, "SYSTEM_RETURN:HOME")

        markExternalNavigation(
            R.id.mark_recents_target,
            "Fixture mark Recents navigation target",
            "RECENTS",
        )
        assertTrue(
            "Recents global action must be accepted by UiAutomation",
            instrumentation.uiAutomation.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_RECENTS,
            ),
        )
        val recentsPackage = awaitForeignForegroundPackage()
        assertNotEquals(packageName, recentsPackage)
        assertTrue(
            "Recents must be hosted by the observed launcher or a system package: $recentsPackage",
            recentsPackage == launcherPackage || isSystemPackage(recentsPackage),
        )
        device.waitForIdle()
        assertEquals(recentsPackage, observedUniqueApplicationPackage())
        launchThroughMainLauncherIntent()
        awaitStateAfterReveal(R.id.recents_return_state, "SYSTEM_RETURN:RECENTS")

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
        awaitStateAfterReveal(R.id.settings_return_state, "SYSTEM_RETURN:SETTINGS")
        awaitStateAfterReveal(R.id.reset_state, "RESET:DONE")
        assertTrue("Iteration must stay non-negative", iteration >= 0)
    }

    private fun resetInApp() {
        revealObjectBelow(R.id.reset_target).also {
            assertEquals("Fixture reset target", it.contentDescription)
            it.click()
        }
        awaitStateAfterReveal(R.id.reset_state, "RESET:DONE")
        awaitStateAfterReveal(R.id.page_state, "PAGE:MAIN")
        assertFalse(
            "Fixture reset must release text input focus before system navigation",
            requireObject(R.id.text_input).isFocused,
        )
    }

    private fun markExternalNavigation(targetId: Int, description: String, kind: String) {
        assertFixtureForeground()
        awaitStateAfterReveal(R.id.system_pending_state, "SYSTEM_PENDING:NONE")
        revealObjectBelow(targetId).also {
            assertEquals(description, it.contentDescription)
            it.click()
        }
        awaitStateAfterReveal(R.id.system_pending_state, "SYSTEM_PENDING:$kind")
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

    private fun revealScrollableTarget(targetId: Int, expectedHeightDp: Int): UiObject2 {
        val minimumHeight = dpToPixels(expectedHeightDp) - BOUNDS_TOLERANCE_PX
        val target = requireObject(targetId)
        val screen = Rect(0, 0, device.displayWidth, device.displayHeight)
        check(
            target.visibleBounds.height() >= minimumHeight &&
                isFullyVisibleWithin(target.visibleBounds, screen),
        ) {
            "Target ${resourceName(targetId)} is not fully visible: " +
                "target=${target.visibleBounds}, screen=$screen"
        }
        return target
    }

    private fun isFullyVisibleWithin(target: Rect, container: Rect): Boolean =
        !target.isEmpty &&
            target.left >= container.left &&
            target.top >= container.top &&
            target.right <= container.right &&
            target.bottom <= container.bottom

    private fun swipeVerticallyWithin(bounds: Rect) {
        check(bounds.height() >= MIN_GESTURE_SIZE_PX)
        val inset = bounds.height() / GESTURE_INSET_DIVISOR
        assertTrue(
            "Vertical swipe injection failed inside the latest ListView bounds",
            device.swipe(
                bounds.centerX(),
                bounds.bottom - inset,
                bounds.centerX(),
                bounds.top + inset,
                GESTURE_STEPS,
            ),
        )
    }

    private fun swipeHorizontallyWithin(bounds: Rect) {
        check(bounds.width() >= MIN_GESTURE_SIZE_PX)
        val inset = bounds.width() / GESTURE_INSET_DIVISOR
        assertTrue(
            "Horizontal swipe injection failed inside the latest target bounds",
            device.swipe(
                bounds.right - inset,
                bounds.centerY(),
                bounds.left + inset,
                bounds.centerY(),
                GESTURE_STEPS,
            ),
        )
    }

    private fun dpToPixels(dp: Int): Int =
        (dp * targetContext.resources.displayMetrics.density).toInt()

    private fun assertPositiveOffset(id: Int, prefix: String) {
        val selector = By.res(packageName, resourceName(id))
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            val observed = device.findObject(selector)
            val value = observed?.text
                ?.removePrefix("$prefix:")
                ?.toIntOrNull()
            if (value != null && value > 0) {
                return
            }
            Thread.sleep(100)
        }
        throw AssertionError(
            "Expected positive $prefix, observed ${device.findObject(selector)?.text}",
        )
    }

    private fun awaitStateAfterReveal(
        id: Int,
        expected: String,
    ): UiObject2 {
        val selector = By.res(packageName, resourceName(id))
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            val observed = device.findObject(selector)
            if (observed?.text == expected) {
                return observed
            }
            Thread.sleep(100)
        }
        val observed = device.findObject(selector)?.text
        val pending = device.findObject(
            By.res(packageName, resourceName(R.id.system_pending_state)),
        )?.text
        val page = device.findObject(
            By.res(packageName, resourceName(R.id.page_state)),
        )?.text
        throw AssertionError(
            "Missing node ${resourceName(id)} with text=$expected; " +
                "observed=$observed, pending=$pending, page=$page, " +
                "foreground=${device.currentPackageName}",
        )
    }

    private fun revealObjectBelow(id: Int): UiObject2 =
        requireObject(id)

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
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            val observed = observedUniqueApplicationPackage()
            if (!observed.isNullOrBlank() && observed != packageName) {
                device.waitForIdle()
                val stable = observedUniqueApplicationPackage()
                if (!stable.isNullOrBlank() && stable != packageName) {
                    return stable
                }
            }
            Thread.sleep(100)
        }
        throw AssertionError(
            "No non-fixture foreground package was observed; " +
                "current=${device.currentPackageName}, " +
                "uniqueApplication=${observedUniqueApplicationPackage()}, " +
                "applicationWindows=${observedApplicationPackages()}, " +
                "fixtureRootVisible=${device.hasObject(By.pkg(packageName).depth(0))}",
        )
    }

    private fun observedUniqueApplicationPackage(): String? =
        observedApplicationPackages().singleOrNull()

    private fun observedApplicationPackages(): Set<String> =
        instrumentation.uiAutomation.windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root?.packageName?.toString() }
            .toSortedSet()

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
        const val VERTICAL_TARGET_HEIGHT_DP = 120
        const val HORIZONTAL_TARGET_HEIGHT_DP = 72
        const val BOUNDS_TOLERANCE_PX = 2
        const val MIN_GESTURE_SIZE_PX = 24
        const val GESTURE_INSET_DIVISOR = 5
        const val GESTURE_STEPS = 20
    }
}
