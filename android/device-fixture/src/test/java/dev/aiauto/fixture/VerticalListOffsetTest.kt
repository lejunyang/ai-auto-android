package dev.aiauto.fixture

/**
 * 测试用途：验证固定行高列表的真实纵向逻辑偏移计算，防止设备状态回退为固定零值。
 */

import org.junit.Assert.assertEquals
import org.junit.Test

class VerticalListOffsetTest {
    @Test
    fun topOfFirstItemIsZero() {
        assertEquals(
            0,
            VerticalListOffset.calculate(
                firstVisiblePosition = 0,
                firstChildTop = 12,
                listPaddingTop = 12,
                itemExtent = 96,
            ),
        )
    }

    @Test
    fun partialFirstItemProducesRealOffset() {
        assertEquals(
            24,
            VerticalListOffset.calculate(
                firstVisiblePosition = 0,
                firstChildTop = -12,
                listPaddingTop = 12,
                itemExtent = 96,
            ),
        )
    }

    @Test
    fun laterVisibleItemIncludesCompletedRows() {
        assertEquals(
            216,
            VerticalListOffset.calculate(
                firstVisiblePosition = 2,
                firstChildTop = -12,
                listPaddingTop = 12,
                itemExtent = 96,
            ),
        )
    }
}
