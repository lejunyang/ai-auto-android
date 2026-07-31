package dev.aiauto.fixture

/**
 * 功能用途：根据列表可见行位置计算稳定的纵向逻辑偏移，防止设备后置状态退化为固定零值。
 */

object VerticalListOffset {
    fun calculate(
        firstVisiblePosition: Int,
        firstChildTop: Int,
        listPaddingTop: Int,
        itemExtent: Int,
    ): Int {
        require(firstVisiblePosition >= 0)
        require(itemExtent > 0)
        return (
            firstVisiblePosition * itemExtent +
                listPaddingTop -
                firstChildTop
        ).coerceAtLeast(0)
    }
}
