package dev.aiauto.fixture

/**
 * 功能用途：集中维护 Fixture 的短生命周期动作结果，确保测试可复位且不落盘保存输入数据。
 */
class FixtureState {
    var inputValue: String = ""
        private set
    var clickCount: Int = 0
        private set
    var longPressCount: Int = 0
        private set
    var verticalScrollCount: Int = 0
        private set
    var horizontalSwipeCount: Int = 0
        private set
    var dialogDismissed: Boolean = false
        private set
    var hasReturnedFromSystem: Boolean = false
        private set
    var lastExternalNavigation: String = NONE
        private set
    var pendingExternalNavigation: String = NONE
        private set

    fun recordInput(value: String) {
        inputValue = value
    }

    fun recordClick() {
        clickCount += 1
    }

    fun recordLongPress() {
        longPressCount += 1
    }

    fun recordVerticalScroll() {
        if (verticalScrollCount == 0) {
            verticalScrollCount = 1
        }
    }

    fun recordHorizontalSwipe() {
        if (horizontalSwipeCount == 0) {
            horizontalSwipeCount = 1
        }
    }

    fun recordDialogOpened() {
        dialogDismissed = false
    }

    fun recordDialogDismissed() {
        dialogDismissed = true
    }

    fun recordExternalNavigation(kind: String) {
        pendingExternalNavigation = kind
        hasReturnedFromSystem = false
    }

    fun recordForegroundReturn() {
        if (pendingExternalNavigation == NONE) {
            return
        }
        lastExternalNavigation = pendingExternalNavigation
        pendingExternalNavigation = NONE
        hasReturnedFromSystem = true
    }

    fun reset() {
        inputValue = ""
        clickCount = 0
        longPressCount = 0
        verticalScrollCount = 0
        horizontalSwipeCount = 0
        dialogDismissed = false
        hasReturnedFromSystem = false
        lastExternalNavigation = NONE
        pendingExternalNavigation = NONE
    }

    companion object {
        const val NONE = "NONE"
    }
}

/**
 * 功能用途：向同一进程内的三个页面与 debug 复位入口共享唯一、可整体清空的测试状态。
 */
object FixtureProcessState {
    val current = FixtureState()
}
