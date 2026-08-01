// 脚本用途：统一校验跨平台外置工具根及其 SDK、缓存和运行状态子路径，避免机器专属常量。
import path from "node:path";

export class ToolchainEnvironmentError extends Error {
  constructor(code = "ENVIRONMENT_INVALID") {
    super(code);
    this.name = "ToolchainEnvironmentError";
    this.code = code;
  }
}

const fail = () => {
  throw new ToolchainEnvironmentError();
};

const windowsAbsolute = /^(?:[A-Za-z]:[\\/]|\\\\)/u;

const pathFlavor = (value) =>
  windowsAbsolute.test(value) ? path.win32 : path.posix;

const normalize = (flavor, value) => {
  const normalized = flavor.normalize(value);
  return flavor === path.win32 ? normalized.toLowerCase() : normalized;
};

const resolveRoot = (environment) => {
  const primary = environment.AACTL_TOOLCHAIN_ROOT;
  const compatible = environment.ANDROID_TOOLS_ROOT;
  if (
    primary !== undefined
    && (typeof primary !== "string" || primary === "")
    || compatible !== undefined
    && (typeof compatible !== "string" || compatible === "")
    || primary === undefined
    && compatible === undefined
  ) {
    fail();
  }
  const root = primary ?? compatible;
  const flavor = pathFlavor(root);
  if (!flavor.isAbsolute(root)) fail();
  if (
    primary !== undefined
    && compatible !== undefined
    && (
      pathFlavor(primary) !== flavor
      || pathFlavor(compatible) !== flavor
      || normalize(flavor, primary) !== normalize(flavor, compatible)
    )
  ) {
    fail();
  }
  return Object.freeze({
    root: flavor.normalize(root),
    flavor,
  });
};

const requireContainedPath = (root, flavor, value) => {
  if (
    typeof value !== "string"
    || value === ""
    || pathFlavor(value) !== flavor
    || !flavor.isAbsolute(value)
  ) {
    fail();
  }
  const normalized = flavor.normalize(value);
  const relative = flavor.relative(root, normalized);
  if (
    relative === ".."
    || relative.startsWith(`..${flavor.sep}`)
    || flavor.isAbsolute(relative)
  ) {
    fail();
  }
  return normalized;
};

export const resolveToolchainEnvironment = (environment, variables) => {
  if (
    environment === null
    || typeof environment !== "object"
    || Array.isArray(environment)
    || variables === null
    || typeof variables !== "object"
    || Array.isArray(variables)
    || Object.keys(variables).length === 0
  ) {
    fail();
  }
  const { root, flavor } = resolveRoot(environment);
  const result = { toolchainRoot: root };
  for (const [property, variable] of Object.entries(variables)) {
    if (
      !/^[A-Za-z][A-Za-z0-9]*$/u.test(property)
      || !/^[A-Z][A-Z0-9_]*$/u.test(variable)
    ) {
      fail();
    }
    result[property] = requireContainedPath(
      root,
      flavor,
      environment[variable],
    );
  }
  return Object.freeze(result);
};
