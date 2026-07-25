# 第三方 App Manifest

本目录只保存文本 manifest 和 Schema，不保存 APK、下载凭据或商店抓取配置。示例
manifest 是不可直接使用的占位模板，所有身份、来源、大小、摘要和签名字段必须由
操作者根据其合法取得的具体制品替换。

## 仓库外缓存

调用方必须显式传入仓库外绝对路径，例如：

```text
/Volumes/aigo S7 Media/Projects/SDK/android-apk-cache
```

制品按 SHA-256 放置，文件名固定为 `artifact`，不使用 `.apk` 扩展名：

```text
<cache-root>/sha256/<sha256前两位>/<完整sha256>/artifact
```

脚本不会创建、下载或复制制品。cache 根、摘要目录和 artifact 不能是符号链接，也
不能位于仓库内。manifest 的 `source.type` 只允许：

- `https`：记录 HTTPS 官方或许可来源 URL 和说明；脚本不会访问该 URL。
- `user-provided`：明确记录由用户在仓库外手工提供，不能附带 URL。

禁止使用应用商店抓取器、未知镜像、认证 URL 或自动下载命令。

## 调用

Node ESM 调用方先加载 manifest，再注入可信的类型化 inspector：

```js
import { loadExternalAppManifest } from "../../scripts/test-lab/src/manifest.mjs";
import { verifyExternalAppArtifact } from "../../scripts/test-lab/src/verifier.mjs";

const manifest = await loadExternalAppManifest("test-lab/apps/manifests/app.json");
const descriptor = await verifyExternalAppArtifact({
  manifest,
  cacheRoot: "/absolute/repository-external/cache",
  repositoryRoot: "/absolute/repository",
  inspector,
});
```

`inspector.inspect()` 必须由可信调用进程实现，可封装固定 `aapt2` 与 `apksigner`
参数，但本模块不会执行 inspector、shell 或 manifest 中的命令。验证成功只返回
`verified-external-app` descriptor，不安装 App。后续 runner 若接入安装，必须使用
显式 serial、clean snapshot 和 `lifecycle.mjs` 的类型化端口；没有 runner 时默认
保持 verified-only。

## 验证

```text
npm run smoke --prefix scripts/test-lab
make comments
git diff --check
```

真实 APK 安装和 API 30/33/34 clean AVD 场景不属于该离线模块的自动测试证据。
