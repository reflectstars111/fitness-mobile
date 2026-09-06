# 来源与许可声明

`core/src/main/java/fitness/mobile/core/Geometry.java`、`Exercise.java`、`protocols/exercises.v1.json` 及由源函数产生的测试参考数据派生自 Good-Badminton / BBA 健身研究工作树。

Copyright 2026 Good-Badminton contributors.

继承部分适用 Apache License 2.0，完整原文及版权声明见 [许可证副本](licenses/Good-Badminton-Apache-2.0.txt)。移植代码已标明修改；协议从 Python 定义导出为 JSON，测试夹具为合成生成数据。具体来源和修改见 [来源清单](provenance/source-manifest.json)。

BBA 来源：https://github.com/reflectstars111/BBA-Badminton-Biomechanics-Analytics

Good-Badminton 上游归属：https://github.com/yo-WASSUP/Good-Badminton

0.1 Android 版新增 MediaPipe Tasks Vision 0.10.32、官方 Pose Landmarker Lite float16 v1 模型，以及 AndroidX、Kotlin 等依赖。模型 URL、哈希和官方模型卡授权依据见 `android/app/src/main/assets/model-info.json`；SDK Apache-2.0 原文见 `licenses/MediaPipe-Apache-2.0.txt`，来源为 MediaPipe v0.10.32 的 LICENSE。许可证副本随 APK assets 打包。模型以下载脚本获取，不进入 Git。

AndroidX 与 Kotlin 的代码许可证为 Apache-2.0；传递依赖版本保存在 `android/app/gradle.lockfile`，正式分发前继续维护完整第三方组件清单。Gradle Wrapper 由 Gradle 8.12 生成并保留其脚本许可头；wrapper JAR SHA-256 为 `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`，已与官方校验值核对。

未引入 ML Kit、RTMPose、OpenSim、OmniPose-Fit 代码或权重。本项目新增独立内容尚未指定分发许可。
