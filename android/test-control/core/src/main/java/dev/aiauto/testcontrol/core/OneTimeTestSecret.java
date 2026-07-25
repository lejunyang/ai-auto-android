package dev.aiauto.testcontrol.core;

/**
 * 功能用途：以可擦除字符数组保存一次性测试秘密，消费或关闭后禁止恢复和重复读取。
 */

import java.util.Arrays;

public final class OneTimeTestSecret implements AutoCloseable {
    private char[] pending;

    public OneTimeTestSecret(char[] value) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("One-time secret must not be empty");
        }
        pending = value.clone();
    }

    public synchronized char[] consume() {
        if (pending == null) {
            throw new IllegalStateException("One-time test secret is no longer available");
        }
        char[] result = pending.clone();
        Arrays.fill(pending, '\0');
        pending = null;
        return result;
    }

    public synchronized boolean available() {
        return pending != null;
    }

    @Override
    public synchronized void close() {
        if (pending != null) {
            Arrays.fill(pending, '\0');
            pending = null;
        }
    }

    @Override
    public synchronized String toString() {
        return "OneTimeTestSecret[available=" + available() + ", value=<redacted>]";
    }
}
