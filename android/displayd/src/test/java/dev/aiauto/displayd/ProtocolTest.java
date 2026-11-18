package dev.aiauto.displayd;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** 测试用途：验证严格请求语法与消息预算，防止调用方注入未知方法、字段或无界输入。 */
public class ProtocolTest {
    private Protocol.Request read(String text) throws Exception {
        return Protocol.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    private void rejects(String text, String code) throws Exception {
        try { read(text); fail("expected " + code); }
        catch (Protocol.Failure error) { assertEquals(code, error.code); }
    }
    @Test public void parsesCreate() throws Exception {
        var r = read("{\"id\":7,\"method\":\"display.create\",\"width\":720,\"height\":1280,\"dpi\":240}\n");
        assertEquals(7, r.id()); assertEquals(720, r.width()); assertEquals(1280, r.height());
        assertEquals(240, r.dpi());
        assertEquals("isolated", r.focus());
    }
    @Test public void parsesOnlyTheTwoFocusPolicies() throws Exception {
        for (String focus : new String[]{"isolated", "steal"}) {
            var r = read("{\"id\":1,\"method\":\"display.create\",\"width\":720,\"height\":1280,\"dpi\":240,\"focus\":\"" + focus + "\"}\n");
            assertEquals(720, r.width()); assertEquals(focus, r.focus());
        }
        for (String focus : new String[]{"", "ISOLATED", "other", "130507"})
            rejects("{\"id\":1,\"method\":\"display.create\",\"width\":720,\"height\":1280,\"dpi\":240,\"focus\":\"" + focus + "\"}\n", "INVALID_ARGUMENT");
        rejects("{\"id\":1,\"method\":\"display.create\",\"width\":720,\"height\":1280,\"dpi\":240,\"focus\":1}\n", "INVALID_REQUEST");
        rejects("{\"id\":1,\"method\":\"display.create\",\"width\":720,\"height\":1280,\"dpi\":240,\"flags\":130507}\n", "INVALID_REQUEST");
        rejects("{\"id\":1,\"method\":\"status\",\"focus\":\"steal\"}\n", "INVALID_REQUEST");
    }
    @Test public void parsesAllMethods() throws Exception {
        for (String method : new String[]{"hello", "display.list", "status", "shutdown"})
            assertEquals(method, read("{\"id\":0,\"method\":\"" + method + "\"}\n").method());
        for (String method : new String[]{"display.close", "display.capture"})
            assertEquals(4, read("{\"id\":1,\"method\":\"" + method + "\",\"displayId\":4}\n").displayId());
    }
    @Test public void rejectsUnknownMethod() throws Exception {
        rejects("{\"id\":1,\"method\":\"exec\"}\n", "UNKNOWN_METHOD");
    }
    @Test public void rejectsCallerFlags() throws Exception {
        rejects("{\"id\":1,\"method\":\"hello\",\"flags\":64971}\n", "INVALID_REQUEST");
    }
    @Test public void rejectsMissingAndWrongTypes() throws Exception {
        rejects("{\"method\":\"hello\"}\n", "INVALID_REQUEST");
        rejects("{\"id\":\"1\",\"method\":\"hello\"}\n", "INVALID_REQUEST");
        rejects("{\"id\":1,\"method\":\"display.create\"}\n", "INVALID_REQUEST");
    }
    @Test public void rejectsDuplicateAndNestedFields() throws Exception {
        rejects("{\"id\":1,\"id\":2,\"method\":\"hello\"}\n", "INVALID_REQUEST");
        rejects("{\"id\":1,\"method\":{}}\n", "INVALID_REQUEST");
    }
    @Test public void rejectsNonIntegralAndOverflowNumbers() throws Exception {
        for (String n : new String[]{"1.0", "1e2", "2147483648", "01", "-1"})
            rejects("{\"id\":" + n + ",\"method\":\"hello\"}\n", "INVALID_REQUEST");
    }
    @Test public void rejectsTruncationAndTrailingContent() throws Exception {
        rejects("{\"id\":1,\"method\":\"hello\"}", "INVALID_REQUEST");
        rejects("{\"id\":1,\"method\":\"hello\"}x\n", "INVALID_REQUEST");
    }
    @Test public void rejectsOversizeBeforeParsing() throws Exception {
        rejects(" ".repeat(Policy.MAX_MESSAGE_BYTES + 1) + "\n", "MESSAGE_TOO_LARGE");
    }
    @Test public void acceptsExactMessageBudget() throws Exception {
        String json = "{\"id\":1,\"method\":\"hello\"}";
        assertEquals("hello", read(json + " ".repeat(Policy.MAX_MESSAGE_BYTES - json.length()) + "\n").method());
    }
    @Test public void rejectsInvalidUtf8() throws Exception {
        try { Protocol.read(new ByteArrayInputStream(new byte[]{(byte) 0xff, 10})); fail(); }
        catch (Protocol.Failure e) { assertEquals("INVALID_REQUEST", e.code); }
    }
    @Test public void escapedMethodIsParsed() throws Exception {
        assertEquals("hello", read("{\"id\":1,\"method\":\"he\\u006clo\"}\r\n").method());
    }
    @Test public void rejectsInvalidJsonEscapes() throws Exception {
        rejects("{\"id\":1,\"method\":\"he\\u００６ｃlo\"}\n", "INVALID_REQUEST");
        rejects("{\"id\":1,\"method\":\"he\\x6clo\"}\n", "INVALID_REQUEST");
    }
    @Test public void validatesCreateRangeDuringParsing() throws Exception {
        rejects("{\"id\":1,\"method\":\"display.create\",\"width\":2049,\"height\":720,\"dpi\":240}\n",
                "INVALID_ARGUMENT");
    }
}
