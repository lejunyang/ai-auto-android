package dev.aiauto.android.ui.navigation

/**
 * 功能用途：底部四 tab 的切换策略。抽为独立可测试单元，避免 tab 切换逻辑散落在
 * 各页面回调中导致返回栈被污染。
 *
 * 背景（P0 缺陷根因）：早期实现中底部导航用 popUpTo(startDestination)+saveState/restoreState，
 * 而页内跳 tab（如助理页"查看全部"跳技能页）用裸 navigate(tabRoute)，把 tab 目的地压成
 * 普通返回栈条目，污染了 saveState/restoreState 的存档 key，导致进入技能页后
 * "助理" tab 永久失效。
 *
 * 修复原则：四个 tab 是同级互斥的根目的地，**任何**入口切 tab 都必须走同一套
 * NavOptions（popUpTo 图起点 + saveState + launchSingleTop + restoreState），
 * 不允许裸 navigate 到 tab route。
 */

import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

/** 四个主 tab 的根路由。与 AppTab 保持一致，供导航策略与测试共用。 */
object TabRoutes {
    const val ASSISTANT = "assistant"
    const val SKILLS = "skills"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    /** 底部导航可见的根路由集合，用于判定是否显示底栏。 */
    val ALL = setOf(ASSISTANT, SKILLS, HISTORY, SETTINGS)

    /** 判断给定路由是否为 tab 根路由。 */
    fun isTabRoute(route: String?): Boolean = route in ALL
}

/**
 * 构造 tab 切换的统一 NavOptions。
 *
 * 语义：
 * - popUpTo(startDestinationRoute) + saveState：弹出到图起点并保存被弹出目的地的状态，
 *   使四个 tab 互斥（栈中同时只有一个 tab）。
 * - launchSingleTop：连点同一个 tab 不重复入栈。
 * - restoreState：恢复该 tab 之前保存的状态（滚动位置等）。
 *
 * @param startDestinationRoute 导航图起点路由，通常是 TabRoutes.ASSISTANT。
 */
fun NavOptionsBuilder.applyTabSwitchOptions(startDestinationRoute: String) {
    popUpTo(startDestinationRoute) {
        saveState = true
    }
    launchSingleTop = true
    restoreState = true
}

/**
 * 切换到指定 tab。这是**唯一**允许导航到 tab 根路由的入口：
 * 底部导航点击、页内跳 tab（查看全部、去设置）都必须调用它。
 *
 * @param tabRoute 目标 tab 根路由，必须是 TabRoutes.ALL 之一。
 * @param startDestinationRoute 导航图起点路由。
 * @throws IllegalArgumentException 当 tabRoute 不是 tab 根路由时抛出，
 *   防止误把子页面路由当 tab 切换导致返回栈异常。
 */
fun NavController.switchTab(
    tabRoute: String,
    startDestinationRoute: String = TabRoutes.ASSISTANT,
) {
    require(TabRoutes.isTabRoute(tabRoute)) {
        "switchTab only accepts tab root routes, got: $tabRoute"
    }
    navigate(tabRoute) {
        applyTabSwitchOptions(startDestinationRoute)
    }
}
