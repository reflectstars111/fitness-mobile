# Android 0.2 实验版

原生 Kotlin + Compose 客户端，提供动作选择、机位与授权确认、CameraX 采集、MediaPipe Lite 端侧姿态分析画面、深蹲/站姿弯举二维候选计数、弯举实时偏移提醒、自设深蹲幅度提示、离线中文语音及可追溯本地组记录。无 INTERNET 权限，默认不保存视频。

当前已构建 Debug APK，并在 Android 16 x86_64 模拟器运行模型与集成测试；尚未完成真人准确性、耳机音频焦点和真机性能验收。APK 仅用于工程试用，不是正式纠错产品。

## 工具链

| 项目 | 固定版本 |
| --- | --- |
| JDK | 本机 Microsoft 21.0.8+9；CI Temurin 21.0.8+9 |
| Gradle | 8.12，官方 Wrapper 和分发 SHA-256 |
| Android Gradle Plugin | 8.9.1 |
| Kotlin / Compose 编译器插件 | 2.1.0 |
| compileSdk / targetSdk / build tools | 35 / 35 / 35.0.0 |
| minSdk / ABI | 26 / arm64-v8a、x86_64 |
| Compose BOM | 2025.02.00 |
| CameraX | 1.4.1 |
| MediaPipe Tasks Vision | 0.10.32 |

这是已构建验证的固定组合，非所有依赖的最新版；发商店前需复核 targetSdk、SDK 更新与设备覆盖。依赖见 `app/build.gradle.kts`，已解析 Debug/测试传递依赖见 `app/gradle.lockfile`。Release 签名与发布尚未配置。

## 构建与运行

安装对应 JDK、Android SDK platform 35 与 build-tools 35.0.0。设置 `JAVA_HOME`、`ANDROID_HOME`，或在本目录建立忽略提交的 `local.properties`，写入 `sdk.dir=<本机 SDK 路径>`。

从仓库根目录运行：

```powershell
./scripts/fetch-model.ps1
./android/gradlew.bat -p android :app:assembleDebug :app:lintDebug :motion-core:regressionTest
```

Linux/macOS 使用 `bash android/gradlew -p android ...`；模型脚本需要 PowerShell 7，也可从 `app/src/main/assets/model-info.json` 的固定 URL 下载到同目录，构建前会强制核对哈希。模型不进入 Git；APK 包含已校验模型，手机无需联网下载。

产物：`app/build/outputs/apk/debug/app-debug.apk`。通过 `adb install -r <APK>` 或传到手机安装。首次选择动作和身体侧、确认本机处理与单人固定侧面、授予相机权限，打开相机后开始一组。画面不清楚时不会补计；暂停恢复需要重新准备。关闭相机不结束当前组；点击“结束保存”完成本组。

设备/模拟器连接后执行：

```powershell
./android/gradlew.bat -p android :app:connectedDebugAndroidTest
```

测试实际加载模型处理空画面，并检查推理图像释放后预览仍可绘制。相机人工冒烟与测试结果见 [验证记录](../docs/validation-v0.2.md)。CI 编译应用和设备测试 APK、运行 lint 与纯核心回归；CI 不宣称已执行真机测试。

## 当前边界

已实现候选报数和实验性动作提醒，规则与流程见 [提醒契约](../docs/coaching-v0.2.md)。没有负重判断或医学评价，提醒阈值尚待教练和真人验证。画面与骨骼同帧，显示帧率随推理速度变化。多人/侧面门槛是保守启发式，身份连续性并非已验证追踪。机位改变需主动暂停并重新确认。前置只镜像显示，观察左右指身体自身左右。

记录最多保留 50 组，可导出 JSON 或删除；进程退出草稿标记中断，不自动续计。完整个人档案、多组会话管理、正式报告图表、真人校准和设备测量继续按开发计划实施。
