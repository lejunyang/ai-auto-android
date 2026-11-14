package dev.aiauto.displayd;

import java.io.ByteArrayOutputStream;

/** 功能用途：在 PNG 编码写入之前检查预算，防止先分配无界帧再事后拒绝。 */
final class FrameOutput extends ByteArrayOutputStream {
    static final class TooLarge extends RuntimeException {}

    private void reserve(int length) {
        if (length < 0 || (long) count + length > Policy.MAX_FRAME_BYTES) throw new TooLarge();
    }

    @Override public synchronized void write(int value) { reserve(1); super.write(value); }
    @Override public synchronized void write(byte[] bytes, int offset, int length) {
        reserve(length); super.write(bytes, offset, length);
    }
}
