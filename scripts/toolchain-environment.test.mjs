// 脚本用途：验证外置工具根在 POSIX 与 Windows 路径下的包含关系、别名兼容和失败关闭。
import assert from "node:assert/strict";
import test from "node:test";

import {
  resolveToolchainEnvironment,
  ToolchainEnvironmentError,
} from "./toolchain-environment.mjs";

const variables = Object.freeze({
  sdkRoot: "ANDROID_SDK_ROOT",
  stateRoot: "AACTL_EMULATOR_STATE",
});

test("POSIX 工具根允许自身和规范化子路径", () => {
  assert.deepEqual(
    resolveToolchainEnvironment({
      AACTL_TOOLCHAIN_ROOT: "/opt/aiauto-tools",
      ANDROID_SDK_ROOT: "/opt/aiauto-tools/android-sdk",
      AACTL_EMULATOR_STATE: "/opt/aiauto-tools/state/../emulator-state",
    }, variables),
    {
      toolchainRoot: "/opt/aiauto-tools",
      sdkRoot: "/opt/aiauto-tools/android-sdk",
      stateRoot: "/opt/aiauto-tools/emulator-state",
    },
  );
});

test("Windows 工具根接受不同大小写和混合分隔符", () => {
  assert.deepEqual(
    resolveToolchainEnvironment({
      AACTL_TOOLCHAIN_ROOT: "D:\\AIAuto\\Tools",
      ANDROID_TOOLS_ROOT: "d:/aiauto/tools",
      ANDROID_SDK_ROOT: "D:/AIAuto/Tools/android-sdk",
      AACTL_EMULATOR_STATE: "d:\\aiauto\\tools\\emulator-state",
    }, variables),
    {
      toolchainRoot: "D:\\AIAuto\\Tools",
      sdkRoot: "D:\\AIAuto\\Tools\\android-sdk",
      stateRoot: "d:\\aiauto\\tools\\emulator-state",
    },
  );
});

test("现有 ANDROID_TOOLS_ROOT 可作为兼容根", () => {
  const result = resolveToolchainEnvironment({
    ANDROID_TOOLS_ROOT: "/Volumes/example/android-tools",
    ANDROID_SDK_ROOT: "/Volumes/example/android-tools/android-sdk",
    AACTL_EMULATOR_STATE: "/Volumes/example/android-tools/emulator-state",
  }, variables);
  assert.equal(result.toolchainRoot, "/Volumes/example/android-tools");
});

test("缺失根、别名冲突、相对路径和逃逸路径均失败关闭", () => {
  for (const environment of [
    {
      ANDROID_SDK_ROOT: "/opt/tools/android-sdk",
      AACTL_EMULATOR_STATE: "/opt/tools/state",
    },
    {
      AACTL_TOOLCHAIN_ROOT: "/opt/tools",
      ANDROID_TOOLS_ROOT: "/different/tools",
      ANDROID_SDK_ROOT: "/opt/tools/android-sdk",
      AACTL_EMULATOR_STATE: "/opt/tools/state",
    },
    {
      AACTL_TOOLCHAIN_ROOT: "/opt/tools",
      ANDROID_SDK_ROOT: "android-sdk",
      AACTL_EMULATOR_STATE: "/opt/tools/state",
    },
    {
      AACTL_TOOLCHAIN_ROOT: "/opt/tools",
      ANDROID_SDK_ROOT: "/opt/tools/../outside",
      AACTL_EMULATOR_STATE: "/opt/tools/state",
    },
    {
      AACTL_TOOLCHAIN_ROOT: "C:\\tools",
      ANDROID_SDK_ROOT: "/opt/tools/android-sdk",
      AACTL_EMULATOR_STATE: "C:\\tools\\state",
    },
  ]) {
    assert.throws(
      () => resolveToolchainEnvironment(environment, variables),
      (error) =>
        error instanceof ToolchainEnvironmentError
        && error.code === "ENVIRONMENT_INVALID",
    );
  }
});
