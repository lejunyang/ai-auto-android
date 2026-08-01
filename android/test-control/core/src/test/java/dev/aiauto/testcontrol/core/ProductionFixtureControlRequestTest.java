package dev.aiauto.testcontrol.core;

/**
 * 测试用途：验证 N47 控制请求拒绝重复键、未知字段、转义、尾随内容和秘密回显。
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class ProductionFixtureControlRequestTest {
    private static final String RUN_ID = "019fbdf0-0000-7000-8000-000000000047";
    private static final String TOKEN =
        FixedProductionFixtureAuthorization.TEST_TOKEN;

    @Test
    public void acceptsOnlyCanonicalFixedRequest() {
        ProductionFixtureControlRequest request =
            ProductionFixtureControlRequest.parse(
                "{\"command\":\"setup\",\"runId\":\"" + RUN_ID
                    + "\",\"token\":\"" + TOKEN + "\"}"
            );

        assertEquals("setup", request.command());
        assertEquals(RUN_ID, request.runId());
        assertEquals(TOKEN, request.token());
        assertFalse(request.toString().contains(TOKEN));
    }

    @Test
    public void rejectsDuplicateUnknownEscapedTrailingAndOversizedRequests() {
        for (String invalid : new String[] {
            "{\"command\":\"setup\",\"command\":\"stop\",\"runId\":\"" + RUN_ID
                + "\",\"token\":\"" + TOKEN + "\"}",
            "{\"command\":\"setup\",\"runId\":\"" + RUN_ID
                + "\",\"token\":\"" + TOKEN + "\",\"extra\":true}",
            "{\"command\":\"set\\u0075p\",\"runId\":\"" + RUN_ID
                + "\",\"token\":\"" + TOKEN + "\"}",
            "{\"command\":\"setup\",\"runId\":\"" + RUN_ID
                + "\",\"token\":\"" + TOKEN + "\"}{}",
            "{\"token\":\"" + TOKEN + "\",\"runId\":\"" + RUN_ID
                + "\",\"command\":\"setup\"}",
            "x".repeat(513),
        }) {
            TestControlException failure = assertThrows(
                TestControlException.class,
                () -> ProductionFixtureControlRequest.parse(invalid)
            );
            assertEquals(TestControlError.CONTROL_PROTOCOL_INVALID, failure.error());
            assertFalse(failure.getMessage().contains(TOKEN));
        }
    }
}
