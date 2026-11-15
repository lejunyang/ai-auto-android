package dev.aiauto.fixture

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/** 测试用途：验证高对比相机验收色及带行填充、交错 UV 和非零起点的采样，防止错误象限判据。 */
class CameraSampleTest {
    @Test fun classifiesYuvColors() {
        assertEquals("red", CameraSample.color(76, 85, 255))
        assertEquals("green", CameraSample.color(150, 44, 21))
        assertEquals("blue", CameraSample.color(29, 255, 107))
        assertEquals("white", CameraSample.color(235, 128, 128))
        assertEquals("black", CameraSample.color(16, 128, 128))
        assertEquals("other", CameraSample.color(128, 128, 128))
    }
    @Test fun respectsPlaneStridesAndBufferPosition() {
        val buffer = ByteBuffer.wrap(ByteArray(32)).apply { position(3); put(3 + 8 + 4, 201.toByte()) }
        assertEquals(201, CameraSample.channel(buffer, 8, 2, 2, 1))
    }
}
