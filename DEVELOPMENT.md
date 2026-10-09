# Development / 开发指南

Jianyu's primary deliverable is the native Android app in `apps/jianyu-android`; the browser project is a prototype. This repository is a **developer preview**. Use fictional family data only. The green checks below do not certify the app for real children's records or prove live AI recommendation quality.

见隅的主交付物是 `apps/jianyu-android` 中的原生安卓 App；浏览器项目只是原型。当前是**开发预览版**，只使用虚构家庭数据。测试通过不等于已经适合保存真实儿童资料，也不等于真实 AI 推荐质量已验证。

## Prerequisites / 环境

- JDK 17.
- Android SDK platform 35 and Build Tools 35.0.0; point `ANDROID_HOME` or `ANDROID_SDK_ROOT` to that SDK.
- Node.js 22 or newer for the dependency-free public JavaScript checks.
- The checked-in Gradle wrapper downloads Gradle 8.11 on first use. Its distribution SHA-256 is pinned, and CI checks the wrapper JAR against Gradle's published hash before executing it. Android dependencies also require network access on a clean machine; existing offline caches are not part of the repository.

## Reference checks / 基础验证

Run from the repository root:

```text
node scripts/check.mjs
node --test tests/*.test.js
```

Run the Android checks from `apps/jianyu-android`:

```text
Windows:  .\gradlew.bat test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease :app:lintRelease
Unix:     bash ./gradlew test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease :app:lintRelease
```

The debug APK is `apps/jianyu-android/app/build/outputs/apk/debug/app-debug.apk`. It is a debug-signed development artifact, **not** a family-ready release. The Release build is an unsigned R8/lint check, not a distributable APK. CI is configured to repeat source, unit, Debug/Release build, and lint checks on a clean Ubuntu runner and scan the checked-out tree and Git history with a SHA-256-pinned Gitleaks binary. Check the actual Actions result for the proposed public commit; a workflow file or a green local run is not a green remote run. CI does not run an Android emulator, contact a real AI provider, review cryptography, or publish an APK.

调试 APK 不是面向家庭的发行包；Release 构建只检查 R8 与 lint，产物未签名，不可分发。CI 还会用固定校验值的扫描工具检查源码和 Git 历史，并在运行 Gradle 前校验启动文件；它不运行安卓模拟器、不调用真实 AI、不做独立密码学审查，也不发布 APK。

## Self-hosted NAS web app / NAS 网页版

`apps/jianyu-web-nas` is a separate, dependency-free Node 22 server plus an unbuilt ESM browser client. The recursive glob is the one that includes its tests; the flat `tests/*.test.js` glob used above for the public contracts does not reach `tests/nas-web/`:

```text
node --test "tests/**/*.test.js"
node --test "tests/nas-web/*.test.js"
```

Run the server without Docker for a quick change. The static root must be the repository root, because the client imports `packages/*` by relative path:

```text
JIANYU_STATIC_ROOT=. node apps/jianyu-web-nas/server/server.mjs
```

Build and run the image. The build context is the repository root, not the app directory:

```text
docker build --file apps/jianyu-web-nas/Dockerfile --tag jianyu-nas-web:local .
docker run --detach --name jianyu-nas --publish 8080:8080 --volume jianyu-data:/data jianyu-nas-web:local
curl http://127.0.0.1:8080/healthz
```

`docker compose --file apps/jianyu-web-nas/docker-compose.yml up --build --detach` does the same with a named volume. Changing the published port needs both the mapping and `JIANYU_PORT`, because the image's HEALTHCHECK reads that variable inside the container. Deployment, reverse-proxy TLS, and the honest limits are in [apps/jianyu-web-nas/README.md](apps/jianyu-web-nas/README.md). The CI `nas-web-reference` job builds the image, writes through it on a fresh volume as the non-root account, starts the container on a scratch volume, waits for the health check, checks the served client and its security headers, and asserts the data volume holds nothing but the three ciphertext directories.

`apps/jianyu-web-nas` 是无依赖的 Node 22 服务器加无构建浏览器客户端，测试走递归 glob。镜像构建上下文是仓库根目录；部署、反向代理 TLS 与诚实边界见该目录的 README。

## Android UI tests / 安卓界面测试

On Windows, `scripts/run-android-ui-tests.ps1` runs instrumented tests only on the dedicated disposable `Jianyu_UiTests_API_35` AVD. It may install or remove the test App and its synthetic Vault. **Never target a daily-use emulator or a device containing family data.** The script checks the AVD identity before proceeding. Keep these UI results separate from CI's JVM/build results.

在 Windows 上，`scripts/run-android-ui-tests.ps1` 只对专用、可丢弃的 `Jianyu_UiTests_API_35` 模拟器运行界面测试。不要在日常设备或保存家庭资料的模拟器上运行。构建通过不代表界面测试已通过。

## Public repository checklist / 公开仓库核对清单

1. Review the exact files to be committed. Local vaults, exports, recovery bundles, credentials, Android signing keys, screenshots of real people, and provider responses must not enter Git.
2. Rotate any testing credential previously shared outside secure storage. Run an independent secret scanner on both the proposed source and the actual committed history; `.gitignore` alone is not a secret scanner. Repeat the scan whenever the proposed commit or history changes, and verify the remote CI scan after pushing.
3. Review third-party code, assets, sample content, and their licenses. The source-only [dependency and asset inventory](docs/DEPENDENCY-LICENSE-REVIEW.md) records the current direct families and its limits. `LICENSE` and `NOTICE` describe this project's intended license, not rights to material copied from elsewhere; distributing a signed APK needs a separate resolved/transitive review.
4. Prepare the chosen confidential reporting route and maintainer notifications. GitHub private vulnerability reporting can only be enabled after a repository becomes public. This repository's reporting route is enabled; check its current status in `SECURITY.md` and keep notifications monitored.
5. Make a curated initial commit, test a fresh clone, and confirm the CI workflow actually passes remotely. A workflow file alone is not a green remote run.
6. Label any source release as an experimental developer preview. Do not call the APK production-ready or request real child/family information until the release gates in `SECURITY.md` and `MVP-PRD.md` are met. Current gaps are recorded in `docs/IMPLEMENTATION-STATUS.md`.

公开仓库应核对提交及历史的准确内容、轮换测试密钥、独立扫描密钥、核对第三方素材许可、从全新检出验证构建，并确认远端 CI 真正运行通过。GitHub 私密漏洞报告只能在仓库公开后启用；本仓库已启用并核验 API 状态与公开报告入口，但尚未独立验证通知实际送达。仅有配置文件不算验证。当前版本应标为开发预览，不应收集真实儿童资料。
