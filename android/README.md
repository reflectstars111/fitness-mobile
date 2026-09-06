# Android 客户端开发入口

这里用于后续创建 Fitness Mobile 的正式 Android 工程。当前尚无 Gradle 工程、客户端源代码或 APK，不存在可执行启动指令。

下一步按 `../docs/development-plan.md` 的 M1 建立工程，优先评估官方 MediaPipe 示例；实现 Kotlin + CameraX 相机采集、端侧姿态与系统语音。版本与依赖在实际创建工程时核验锁定，不预先写入未经验证的版本号。

模型、构建输出、签名密钥、私人视频不进入版本控制。签名与发布信息通过本机配置或受控 CI 提供。
