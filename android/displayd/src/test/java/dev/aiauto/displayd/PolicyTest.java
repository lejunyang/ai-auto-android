package dev.aiauto.displayd;

import static org.junit.Assert.*;
import org.junit.Test;

/** 测试用途：验证 shell 身份、尺寸数量帧上限与锁定策略，防止主屏回退和凭据锁定后唤醒。 */
public class PolicyTest {
    private interface Check { void run() throws Exception; }
    private void rejects(String code, Check check) throws Exception {
        try { check.run(); fail("expected " + code); }
        catch (Protocol.Failure error) { assertEquals(code, error.code); }
    }
    @Test public void acceptsOnlyRootAndShell() {
        assertTrue(Policy.acceptsUid(0)); assertTrue(Policy.acceptsUid(2000));
        for (int uid : new int[]{-1, 1000, 10000, 2001, Integer.MAX_VALUE}) assertFalse(Policy.acceptsUid(uid));
    }
    @Test public void acceptsDimensionBoundaries() throws Exception {
        Policy.dimensions(128, 128, 72); Policy.dimensions(2048, 2048, 640);
    }
    @Test public void rejectsWidthBounds() throws Exception {
        for (int value : new int[]{0, 127, 2049, Integer.MAX_VALUE})
            rejects("INVALID_ARGUMENT", () -> Policy.dimensions(value, 720, 240));
    }
    @Test public void rejectsHeightBounds() throws Exception {
        for (int value : new int[]{-1, 127, 2049})
            rejects("INVALID_ARGUMENT", () -> Policy.dimensions(720, value, 240));
    }
    @Test public void rejectsDpiBounds() throws Exception {
        for (int value : new int[]{0, 71, 641})
            rejects("INVALID_ARGUMENT", () -> Policy.dimensions(720, 1280, value));
    }
    @Test public void enforcesDisplayCount() throws Exception {
        Policy.capacity(0); Policy.capacity(Policy.MAX_DISPLAYS - 1);
        rejects("DISPLAY_LIMIT", () -> Policy.capacity(Policy.MAX_DISPLAYS));
    }
    @Test public void enforcesFrameBudget() throws Exception {
        Policy.frameSize(1); Policy.frameSize(Policy.MAX_FRAME_BYTES);
        rejects("FRAME_TOO_LARGE", () -> Policy.frameSize((long) Policy.MAX_FRAME_BYTES + 1));
        rejects("FRAME_TOO_LARGE", () -> Policy.frameSize(Long.MAX_VALUE));
    }
    @Test public void neverKeepsAwakeWhenLockedOrEmpty() {
        assertFalse(Policy.shouldKeepAwake(true, 1)); assertFalse(Policy.shouldKeepAwake(false, 0));
        assertTrue(Policy.shouldKeepAwake(false, 1));
    }
    @Test public void captureRejectsCredentialLock() throws Exception {
        Policy.captureAllowed(false);
        rejects("DEVICE_LOCKED", () -> Policy.captureAllowed(true));
    }
    @Test public void captureRejectsSleepingGroupEvenWhenDisplayReportsOn() throws Exception {
        rejects("DISPLAY_ASLEEP", () -> Policy.captureAllowed(false, false, 2));
    }
    @Test public void captureRejectsOffDisplayWhenGroupQueryIsUnavailable() throws Exception {
        rejects("DISPLAY_ASLEEP", () -> Policy.captureAllowed(false, null, 1));
        rejects("DISPLAY_ASLEEP", () -> Policy.captureAllowed(false, null, 3));
    }
    @Test public void credentialLockTakesPriorityOverSleepingGroup() throws Exception {
        rejects("DEVICE_LOCKED", () -> Policy.captureAllowed(true, false, 1));
    }
    @Test public void api33UnknownGroupIsNotPretendedAwake() throws Exception {
        Policy.captureAllowed(false, null, 2);
    }
    @Test public void rejectedConnectionCloseFailureDoesNotEscape() throws Exception {
        Policy.closeRejected(() -> { throw new java.io.IOException("peer close failed"); });
    }
    @Test public void distinguishesCredentialLockFromSwipeKeyguard() {
        assertFalse(Policy.credentialLocked(false, false));
        assertFalse(Policy.credentialLocked(false, true));
        assertFalse(Policy.credentialLocked(true, false));
        assertTrue(Policy.credentialLocked(true, true));
    }
    @Test public void unsupportedApiFails() throws Exception {
        rejects("UNSUPPORTED_API", () -> Policy.flags(32));
    }
    @Test public void fixedFlagsRespectApiAndSecureBoundary() throws Exception {
        // 禁止抢占顶层焦点只在API34+可用；API33不伪装缓解，所有版本继续排除安全内容。
        assertEquals(15819, Policy.flags(33));
        for (int api : new int[]{34, 35, 36}) {
            assertEquals(130507, Policy.flags(api));
            assertEquals(1 << 16, Policy.flags(api) & (1 << 16));
        }
        assertEquals(0, Policy.flags(33) & (1 << 16));
        for (int api : new int[]{33, 34, 35, 36}) assertEquals(0, Policy.flags(api) & (1 << 2));
    }
    @Test public void focusPoliciesHaveFixedFlagsAndHonestIsolation() throws Exception {
        // 纯策略测试覆盖API33降级与所有版本禁止SECURE，调用方不能选择任意标志。
        for (int api : new int[]{33, 34, 35, 36}) {
            for (String focus : new String[]{"isolated", "steal"}) {
                int expected = api == 33 ? 15819 : focus.equals("isolated") ? 130507 : 64971;
                assertEquals(expected, Policy.flags(api, focus));
                assertEquals(0, Policy.flags(api, focus) & (1 << 2));
                assertEquals(api >= 34 && focus.equals("isolated"), Policy.focusIsolated(api, focus));
            }
        }
        rejects("INVALID_ARGUMENT", () -> Policy.flags(35, "raw"));
    }
}
