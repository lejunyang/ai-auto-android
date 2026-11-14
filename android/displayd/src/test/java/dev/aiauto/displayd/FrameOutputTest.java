package dev.aiauto.displayd;

import static org.junit.Assert.*;
import org.junit.Test;

/** 测试用途：验证编码流在扩容前拒绝超限，确保单字节和批量写入都遵守同一帧预算。 */
public class FrameOutputTest {
    @Test public void exactBudgetThenSingleByteIsRejected() {
        FrameOutput output = new FrameOutput();
        output.write(new byte[Policy.MAX_FRAME_BYTES], 0, Policy.MAX_FRAME_BYTES);
        try { output.write(1); fail(); } catch (FrameOutput.TooLarge expected) { /* 预期拒绝超限。 */ }
        assertEquals(Policy.MAX_FRAME_BYTES, output.size());
    }
    @Test public void bulkOverflowIsRejectedBeforeAllocation() {
        FrameOutput output = new FrameOutput();
        try { output.write(new byte[1], 0, Policy.MAX_FRAME_BYTES + 1); fail(); }
        catch (FrameOutput.TooLarge expected) { /* 预算先于数组访问校验。 */ }
        assertEquals(0, output.size());
    }
    @Test public void responseLineBudgetIsEnforced() throws Exception {
        try { Protocol.line(Protocol.fields("value", "x".repeat(Policy.MAX_MESSAGE_BYTES))); fail(); }
        catch (Protocol.Failure error) { assertEquals("MESSAGE_TOO_LARGE", error.code); }
    }
}
