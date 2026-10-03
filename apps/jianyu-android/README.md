# Jianyu Android

见隅的官方原生 Android 参考 App。技术栈为 Kotlin、Jetpack Compose 与 Android Keystore；它不是 WebView，也不以浏览器原型为运行时依赖。

## 当前闭环

1. 建立本机加密的 Family Vault；
2. 从孩子此刻主动表达的兴趣、问题或行为开始；
3. 分开记录 Child、Caregiver、Shared 目标及 School Window；
4. 逐次确认后，由 Context Firewall 向家庭配置的 BYOK AI 提供最小任务上下文；
5. AI、声明式 Pack 与公共 World Brief 通过彼此独立的公开接口形成候选，本机 Opportunity Gate 再按时间、预算、出行、年龄、孩子意愿与家长精力过滤；
6. 提供同等地位的 `Nothing`；
7. 记录家庭选择、孩子拒绝与可选反馈；7 岁以后可选择不保存当次 Context，孩子的纠正作为新证据保留，不会偷偷覆盖原记录；
8. 形成非 KPI、非打卡式的版本化事件与 Evidence 足迹。

## 模块

```text
app           原生 Compose 壳、页面与状态协调
core:model    版本化 Family Vault / Event / Opportunity / Pack 数据结构与迁移
core:domain   生命周期、Provider/Policy/Pack/BrandConfig 公共契约、Gate 与多样性选择
core:data     Android Keystore Vault、独立 BYOK 密钥存储与 OpenAI-compatible 适配器
```

依赖只能从外向内：`app -> core:*`，`core:data -> core:domain/model`，`core:domain -> core:model`。`core:model` 不依赖 Android，也不依赖见隅品牌。

## 构建

要求 JDK 17 和 Android SDK 35：

```powershell
cd D:\test\JIAOYU\apps\jianyu-android
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

调试 APK 生成于 `app\build\outputs\apk\debug\app-debug.apk`。

## Android 界面测试

界面测试只使用虚构的内存家庭状态，不读取真实家庭保险箱。先安装 API 35 Google APIs x86_64 系统镜像，并在独立 AVD 目录中创建名为 `Jianyu_UiTests_API_35` 的专用模拟器；默认目录是项目的 `.toolchains\avd`，也可通过 `ANDROID_AVD_HOME` 指定。之后从项目根目录运行：

```powershell
.\scripts\run-android-ui-tests.ps1
```

脚本只接受端口 5556 上身份匹配的测试模拟器，先编译、再安装测试包并运行，不使用 Gradle 的 `connectedDebugAndroidTest` 清理流程。不要在存有家庭资料的模拟器上运行 `connectedDebugAndroidTest`：该流程可能卸载 App，连同本机保险箱一起清除。视觉验收仍需分别检查 360 dp、1.3 倍字体、浅色与深色，以及实际设备的辅助功能。

## 当前安全边界

- 家庭状态以 AES-256-GCM 加密后写入 App 私有目录；密钥由 Android Keystore 产生且不可导出。
- 关闭 Android 系统备份，避免 Vault 被未定义地复制。
- 正式发现使用家庭自行配置的 OpenAI-compatible BYOK Provider；每次调用需要确认，且只发送 Context Firewall 生成的最小任务上下文。
- 历史足迹默认不发送。家庭可在单次调用中另行选择最多 3 条可见摘要；本地投影只接受近期、非私密的孩子表达/选择/直接观察，排除 AI 推测、测评、教师与导入记录，并在发送前展示完整预览。
- API Key 使用独立 Android Keystore 密钥加密，不进入 Family Vault、事件或未来同步对象。
- 离线模板是明确标注的演示/故障排查模式，不冒充 AI 推荐。
- 可选的 World Brief HTTPS 客户端读取 `org.foe.world-brief-feed/v1` 兼容服务；服务可收到用户填写的地区原文、固定未来 14 天窗口、语言与公共类别，因此不要在地区栏填写姓名或精确地址。孩子兴趣与家庭生活描述不会作为独立字段发送给该服务，主题匹配回到本机完成。项目仍不捆绑或运营实际服务；离线模式只使用明确标注的合成数据。
- “删除家庭保险箱”同时删除本机密文与 Keystore 密钥，实现本设备上的加密擦除。
- 原始 Vault 密文仍绑定本设备密钥；设置页另提供实验性的便携加密恢复包和单独恢复码，可手动迁移到另一台设备。该路径已有错误密钥、篡改、随机 nonce 与往返测试，但尚未经过独立安全审计，也不等同于自动同步。

## 扩展纪律

新的 World Brief、AI、活动搜索或同步能力必须通过公共 Provider/Policy/Pack/BrandConfig 契约接入。同步前必须在客户端完成加密，World Brief 不得读取 Family Vault，任何 Provider 只获得 Context Firewall 明确允许的最小字段。

仓库级原则与路线见根目录的 `ARCHITECTURE.md`、`MVP-PRD.md`、`SECURITY.md`、`PRIVACY.md` 和 `EXTENDING.md`。
Android 页面边界与后续扩展规则见 `docs/UI-ARCHITECTURE.md`。
