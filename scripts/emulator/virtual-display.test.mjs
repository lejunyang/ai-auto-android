// 测试用途：验证端到端脚本的真机拒绝与 PNG 黑帧判据，避免验收脚本本身误报成功。
// 功能用途：验证脚本的设备守卫与有界像素解码，供 Node 测试运行器独立执行。
import test from "node:test";
import assert from "node:assert/strict";
import { deflateSync } from "node:zlib";
import { pngStats } from "./virtual-display-png.mjs";
import { requireTestSerial, primaryTopActivity, topActivityOnDisplay, assertEmptyOwnedDisplay } from "./verify-virtual-display.mjs";

function pngFixture(value) {
  // CLI 已校验 PNG CRC；此处只测试像素判据，构造两行 RGBA 扫描线。
  const chunk = (type, bytes) => { const header = Buffer.alloc(8); header.writeUInt32BE(bytes.length); header.write(type, 4); return Buffer.concat([header, bytes, Buffer.alloc(4)]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(2); ihdr.writeUInt32BE(2, 4); ihdr[8] = 8; ihdr[9] = 6;
  const row = Buffer.from([0, value, value, value, 255, value, value, value, 255]);
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr), chunk("IDAT", deflateSync(Buffer.concat([row, row]))), chunk("IEND", Buffer.alloc(0))]);
}
test("refuses real devices and other emulator serials before any operation", () => {
  for (const serial of ["SERIAL", "192.168.1.2:5555", "emulator-5556", "", undefined]) assert.throws(() => requireTestSerial(serial));
  assert.doesNotThrow(() => requireTestSerial("emulator-5554"));
});
test("counts black and nonblack pixels distinctly", () => {
  assert.equal(pngStats(pngFixture(0)).nonBlack, 0);
  assert.equal(pngStats(pngFixture(255)).nonBlack, 4);
});
test("rejects truncated or oversized PNG input", () => {
  assert.throws(() => pngStats(pngFixture(255).subarray(0, 35)));
  const huge = pngFixture(255); huge.writeUInt32BE(2049, 16); assert.throws(() => pngStats(huge));
});
test("primary activity is selected by display ID rather than focus or dump order", () => {
  const virtual = "Display #2 (activities from top to bottom):\n    topResumedActivity=ActivityRecord{1f8c854 u0 dev.aiauto.fixture/.MainActivity t14}\n";
  const primary = "Display #0 (activities from top to bottom):\n      topResumedActivity=ActivityRecord{1ee1678 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t13}\n";
  for (const dump of [virtual + primary, primary + virtual]) assert.equal(primaryTopActivity(dump), "com.google.android.apps.nexuslauncher/.NexusLauncherActivity");
  assert.throws(() => primaryTopActivity(virtual));
});

test("empty virtual display never borrows primary or global activity", () => {
  const dump = "Display #0 (activities from top to bottom):\n topResumedActivity=ActivityRecord{a u0 primary.app/.Main t1}\nDisplay #2 (activities from top to bottom):\n mLastResumedActivity=ActivityRecord{a u0 primary.app/.Main t1}\n";
  assert.equal(topActivityOnDisplay(dump, 2), null);
  assert.equal(topActivityOnDisplay(dump, 0), "primary.app/.Main");
  assert.equal(topActivityOnDisplay(dump, 3), null);
});
test("empty acceptance requires dedicated code, ownership and no foreground", () => {
  const empty = { ok: false, error: { code: "DISPLAY_EMPTY" } };
  assert.doesNotThrow(() => assertEmptyOwnedDisplay(empty, true, null));
  assert.throws(() => assertEmptyOwnedDisplay({ ok: false, error: { code: "DISPLAY_NOT_FOUND" } }, true, null));
  assert.throws(() => assertEmptyOwnedDisplay(empty, false, null));
  assert.throws(() => assertEmptyOwnedDisplay(empty, true, "fixture/.MainActivity"));
  assert.throws(() => assertEmptyOwnedDisplay({ ok: true }, true, null));
});
