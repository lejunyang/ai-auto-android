package dev.aiauto.testcontrol.core;

/**
 * 测试用途：验证配对秘密只可消费一次，并在关闭后不可读取且默认诊断永不暴露内容。
 */

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class OneTimeTestSecretTest {
    @Test
    public void secretCanBeConsumedExactlyOnce() {
        OneTimeTestSecret secret = new OneTimeTestSecret("123456".toCharArray());

        char[] consumed = secret.consume();

        assertArrayEquals("123456".toCharArray(), consumed);
        assertFalse(secret.available());
        assertThrows(IllegalStateException.class, secret::consume);
        Arrays.fill(consumed, '\0');
    }

    @Test
    public void closeDestroysPendingSecretAndDiagnosticsStayRedacted() {
        OneTimeTestSecret secret = new OneTimeTestSecret("654321".toCharArray());
        assertFalse(secret.toString().contains("654321"));

        secret.close();

        assertFalse(secret.available());
        assertThrows(IllegalStateException.class, secret::consume);
        assertTrue(secret.toString().contains("<redacted>"));
    }
}
