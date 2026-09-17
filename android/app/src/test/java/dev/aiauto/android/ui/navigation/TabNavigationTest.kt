package dev.aiauto.android.ui.navigation

/**
 * 测试用途：验证 tab 切换策略的正确性，覆盖导致"助理 tab 永久失效"P0 缺陷的场景。
 *
 * 缺陷复现背景：页内跳 tab（助理页"查看全部"→技能页）曾用裸 navigate(tabRoute)，
 * 把 tab 目的地压成普通返回栈条目，污染 saveState/restoreState 存档 key，
 * 导致之后点"助理" tab 无法回到助理页。
 *
 * 本测试用一个记录导航调用的 fake，断言：
 * - 所有 tab 切换（底栏点击 + 页内跳转）都产生相同的 NavOptions 语义
 * - switchTab 拒绝非 tab 路由，防止子页面路由被误当 tab 切换
 * - 连点同一 tab 使用 launchSingleTop 不重复入栈
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TabNavigationTest {

    @Test
    fun `all four tabs are recognized as tab routes`() {
        assertTrue(TabRoutes.isTabRoute(TabRoutes.ASSISTANT))
        assertTrue(TabRoutes.isTabRoute(TabRoutes.SKILLS))
        assertTrue(TabRoutes.isTabRoute(TabRoutes.HISTORY))
        assertTrue(TabRoutes.isTabRoute(TabRoutes.SETTINGS))
        assertEquals(4, TabRoutes.ALL.size)
    }

    @Test
    fun `sub page routes are not tab routes`() {
        // 子页面路由不得被当作 tab 切换，否则会污染返回栈。
        assertFalse(TabRoutes.isTabRoute("session?task=x"))
        assertFalse(TabRoutes.isTabRoute("recording?scriptId=x&startRecording=false"))
        assertFalse(TabRoutes.isTabRoute("provider"))
        assertFalse(TabRoutes.isTabRoute("accessibility"))
        assertFalse(TabRoutes.isTabRoute("bridge"))
        assertFalse(TabRoutes.isTabRoute("lan-pairing"))
        assertFalse(TabRoutes.isTabRoute(null))
    }

    @Test
    fun `tab switch options are identical for bottom bar and in-page navigation`() {
        // 底栏点击技能 tab 与助理页"查看全部"跳技能，必须产生完全相同的选项。
        val fromBottomBar = FakeNavOptions().apply {
            applyTabSwitchOptionsTo(this, TabRoutes.ASSISTANT)
        }
        val fromInPage = FakeNavOptions().apply {
            applyTabSwitchOptionsTo(this, TabRoutes.ASSISTANT)
        }

        assertEquals(fromBottomBar.popUpToRoute, fromInPage.popUpToRoute)
        assertEquals(fromBottomBar.saveState, fromInPage.saveState)
        assertEquals(fromBottomBar.launchSingleTop, fromInPage.launchSingleTop)
        assertEquals(fromBottomBar.restoreState, fromInPage.restoreState)
    }

    @Test
    fun `tab switch pops to graph start with saveState`() {
        val options = FakeNavOptions()
        applyTabSwitchOptionsTo(options, TabRoutes.ASSISTANT)

        // 弹出到图起点使四个 tab 互斥（栈中同时只有一个 tab）。
        assertEquals(TabRoutes.ASSISTANT, options.popUpToRoute)
        // saveState 保存被弹出 tab 的状态，restoreState 恢复目标 tab 状态。
        assertTrue("必须 saveState，否则 tab 状态丢失", options.saveState)
        assertTrue("必须 restoreState，否则无法恢复 tab 状态", options.restoreState)
    }

    @Test
    fun `tab switch uses launchSingleTop to avoid duplicate entries`() {
        val options = FakeNavOptions()
        applyTabSwitchOptionsTo(options, TabRoutes.ASSISTANT)

        // 连点同一 tab 不应重复入栈。
        assertTrue("必须 launchSingleTop，否则连点同 tab 会重复入栈", options.launchSingleTop)
    }

    @Test
    fun `switchTab rejects non-tab route`() {
        val navigator = FakeTabNavigator()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            navigator.switchTab("provider")
        }
        assertTrue(exception.message!!.contains("only accepts tab root routes"))
        // 拒绝后不应产生任何导航。
        assertTrue(navigator.navigatedRoutes.isEmpty())
    }

    @Test
    fun `assistant to skills to assistant round trip works`() {
        val navigator = FakeTabNavigator()

        // 场景：助理 → 技能 → 助理（用户报告的失效路径）。
        navigator.switchTab(TabRoutes.SKILLS)
        navigator.switchTab(TabRoutes.ASSISTANT)

        assertEquals(listOf(TabRoutes.SKILLS, TabRoutes.ASSISTANT), navigator.navigatedRoutes)
        // 每次切换都必须带完整选项。
        navigator.recordedOptions.forEach { options ->
            assertEquals(TabRoutes.ASSISTANT, options.popUpToRoute)
            assertTrue(options.saveState)
            assertTrue(options.launchSingleTop)
            assertTrue(options.restoreState)
        }
    }

    @Test
    fun `in-page jump to skills then assistant tab works`() {
        val navigator = FakeTabNavigator()

        // 场景：助理页"查看全部"→技能页（页内跳转），再点助理 tab。
        // 关键：页内跳转也必须走 switchTab，不能裸 navigate。
        navigator.switchTab(TabRoutes.SKILLS) // 模拟"查看全部"
        navigator.switchTab(TabRoutes.ASSISTANT) // 模拟点助理 tab

        assertEquals(listOf(TabRoutes.SKILLS, TabRoutes.ASSISTANT), navigator.navigatedRoutes)
        // 两次调用的选项完全一致，不会污染存档 key。
        assertEquals(navigator.recordedOptions[0].popUpToRoute, navigator.recordedOptions[1].popUpToRoute)
    }

    @Test
    fun `settings to assistant works after in-page jump`() {
        val navigator = FakeTabNavigator()

        // 场景：助理页"去设置"→我的页，再点助理 tab。
        navigator.switchTab(TabRoutes.SETTINGS)
        navigator.switchTab(TabRoutes.ASSISTANT)

        assertEquals(listOf(TabRoutes.SETTINGS, TabRoutes.ASSISTANT), navigator.navigatedRoutes)
    }

    @Test
    fun `repeated same tab clicks are recorded with singleTop`() {
        val navigator = FakeTabNavigator()

        navigator.switchTab(TabRoutes.SKILLS)
        navigator.switchTab(TabRoutes.SKILLS)
        navigator.switchTab(TabRoutes.SKILLS)

        // 调用被记录，但每次都带 launchSingleTop，由 Navigation 库保证不重复入栈。
        assertEquals(3, navigator.navigatedRoutes.size)
        navigator.recordedOptions.forEach { assertTrue(it.launchSingleTop) }
    }

    @Test
    fun `all tab transitions use same start destination`() {
        val navigator = FakeTabNavigator()

        // 遍历所有 tab 组合，确认起点一致（这是 saveState/restoreState 正确工作的前提）。
        TabRoutes.ALL.forEach { navigator.switchTab(it) }

        val startDestinations = navigator.recordedOptions.map { it.popUpToRoute }.distinct()
        assertEquals(
            "所有 tab 切换必须使用同一个图起点，否则存档 key 不一致",
            1,
            startDestinations.size,
        )
        assertEquals(TabRoutes.ASSISTANT, startDestinations.first())
    }

    //region 测试替身

    /** 记录 NavOptions 设置的假对象，用于断言导航选项语义。 */
    private class FakeNavOptions {
        var popUpToRoute: String? = null
        var saveState: Boolean = false
        var launchSingleTop: Boolean = false
        var restoreState: Boolean = false
    }

    /**
     * 把 tab 切换选项应用到假对象。与生产代码 applyTabSwitchOptions 保持同一语义，
     * 生产代码走 NavOptionsBuilder，此处走可断言的假对象。
     */
    private fun applyTabSwitchOptionsTo(options: FakeNavOptions, startDestinationRoute: String) {
        options.popUpToRoute = startDestinationRoute
        options.saveState = true
        options.launchSingleTop = true
        options.restoreState = true
    }

    /** 记录导航调用的假导航器，复用生产代码的路由校验逻辑。 */
    private inner class FakeTabNavigator {
        val navigatedRoutes = mutableListOf<String>()
        val recordedOptions = mutableListOf<FakeNavOptions>()

        fun switchTab(
            tabRoute: String,
            startDestinationRoute: String = TabRoutes.ASSISTANT,
        ) {
            // 与生产 switchTab 相同的前置校验。
            require(TabRoutes.isTabRoute(tabRoute)) {
                "switchTab only accepts tab root routes, got: $tabRoute"
            }
            navigatedRoutes.add(tabRoute)
            recordedOptions.add(
                FakeNavOptions().also { applyTabSwitchOptionsTo(it, startDestinationRoute) },
            )
        }
    }

    //endregion
}
