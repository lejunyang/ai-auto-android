# 第三方 App Manifest

本目录只保存文本 manifest 和 Schema，不保存 APK、下载凭据或商店抓取配置。示例
manifest 是不可直接使用的占位模板，所有身份、来源、大小、摘要和签名字段必须由
操作者根据其合法取得的具体制品替换。

## 仓库外缓存

调用方必须显式传入仓库外绝对路径，例如：

```text
/Volumes/aigo S7 Media/SDK/android-tools/android-apk-cache
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

Node ESM 调用方先加载 manifest，再以具体 Android build-tools 版本目录创建离线
inspector。目录和工具必须位于仓库外，且路径链不能包含符号链接：

```js
import { createAndroidApkInspector } from "../../scripts/test-lab/src/apk-inspector.mjs";
import { loadExternalAppManifest } from "../../scripts/test-lab/src/manifest.mjs";
import { verifyExternalAppArtifact } from "../../scripts/test-lab/src/verifier.mjs";

const manifest = await loadExternalAppManifest("test-lab/apps/manifests/app.json");
const inspector = await createAndroidApkInspector({
  repositoryRoot: "/absolute/repository",
  buildToolsDirectory:
    "/Volumes/aigo S7 Media/SDK/android-tools/android-sdk/build-tools/36.0.0",
  javaPath:
    "/Volumes/aigo S7 Media/SDK/android-tools/jdk-temurin-21.0.7+6/Contents/Home/bin/java",
});
const descriptor = await verifyExternalAppArtifact({
  manifest,
  cacheRoot: "/absolute/repository-external/cache",
  repositoryRoot: "/absolute/repository",
  inspector,
});
```

也可同时显式传入固定的 `aapt2Path`、`apksignerJarPath` 和 `javaPath`，但不能与
`buildToolsDirectory` 混用。inspector 不执行 `apksigner` shell wrapper，只通过
`execFile` 执行固定的二进制与 jar：

```text
aapt2 dump badging <artifact>
java -Xmx256M -jar <fixed-apksigner.jar> verify --print-certs <artifact>
```

调用方不能提供 executable、argv、shell 或额外参数；`aapt2`/`java` 必须是仓库外、
非符号链接且名称固定的普通可执行文件，apksigner jar 同样必须固定且非符号链接。
每次调用都限制时间和输出，并在调用前后校验三者 identity。解析结果严格包含
package、version、versionCode、ABI、minSdk 和签名证书
SHA-256，任何缺失、重复、未知 ABI 或多签名歧义都会失败关闭。公开错误只返回稳定
错误码，不包含工具输出或路径。

验证成功只返回 `verified-external-app` descriptor，不安装 App。后续 runner 若接入
安装，必须使用显式 serial、clean snapshot 和 `lifecycle.mjs` 的类型化端口；没有
runner 时默认保持 verified-only。

## 验证

```text
npm run smoke --prefix scripts/test-lab
make comments
git diff --check
```

自动测试使用 fake executable、注入的 `execFile` 和无 `.apk` 扩展名的非 APK
fixture。真实 APK 的 badging/签名输出、真实安装以及 API 30/33/34 clean AVD 场景
不属于该离线模块的自动测试证据。
