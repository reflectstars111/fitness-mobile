# 客户端架构建议

状态：2026-09-07 已确定原生 Android 路线并实现 0.1 实验版。以下保留原路线对比理由；当前实现见 `android/` 和 `docs/v1-contracts.md`。

## 推荐

以 Android 首期、实时相机与端侧推理为优先时，推荐 Kotlin + Jetpack Compose，采用单应用、清晰模块边界和单向状态流。原因是相机、模型、生命周期、音频与现有 Java 核心可在同一平台调试，减少跨语言接口维护。此为工程判断，不是本项目的性能实测结论。

若近期需要 Android/iOS 共用产品界面，Flutter + 平台原生实时引擎也是可行路线。Flutter 承担档案、动作选择、训练控制、历史与报告；Android Kotlin 承担相机、姿态、动作状态机和语音调度。iOS 仍需独立实现平台引擎，现有 Java 核心不能直接当作 iOS 库；先共享协议和固定测试期望，再根据双平台需求决定核心移植方式。

## 职责和依赖

| 部分 | 责任 | 边界 |
| --- | --- | --- |
| 产品界面 | 页面、用户操作、训练状态展示 | 发送命令、订阅状态，不拥有逐帧计数逻辑 |
| 会话协调 | 启停、暂停、配置、错误与恢复 | 单一会话状态源，串行处理状态转换 |
| 平台采集与姿态 | CameraX、MediaPipe 适配、旋转、背压 | 单调时间戳、模型身份、命名关节；不积压旧帧 |
| 纯动作核心 | 几何、质量门槛、因果状态机、候选证据 | 不依赖 UI、相机或模型 SDK，保留现有 Java 核心 |
| 反馈调度 | 优先级、冷却、去重、过期、TTS | 播放前复核观察资格和会话身份 |
| 本地持久化 | 会话摘要、事件、配置、删除与导出 | 异步写入；默认不保存视频、不依赖云端 |

这些是职责边界，先用少量实际模块实现，不预建大量空模块。原生界面建议 ViewModel 暴露不可变 UI 状态；动作引擎独立串行处理观测，推理和存储不阻塞界面线程。

## Flutter 接入时的约束

Flutter 通过平台通道调用 Kotlin/Swift，是官方支持的集成机制。建议以类型化命令与事件边界封装引擎：启动、暂停、结束、配置变更；返回会话状态、候选次数、质量状态、提醒和错误。协议带 schema 版本、session ID、单调时间戳和规则版本，旧会话回调必须丢弃。

相机帧、推理、动作状态机与实时语音保持在平台侧；常规平台通道只传紧凑结果。预览通过原生视图或纹理接入，具体方案须在目标机验证旋转、叠层和帧同步。骨骼若由 Flutter 绘制，使用带帧时间戳的关键点结果并测量预览对齐，不通过 Dart 往返传送整幅相机图像。

Flutter 官方说明 Platform Views 存在性能取舍；不能仅凭技术选型承诺流畅度。MediaPipe Android LIVE_STREAM 提供异步结果，处理忙时可能忽略新输入；这不代表可以忽略动作核心的时间间隔检查。长间隔、看不清、身份不明必须暂停或重置候选，不跨缺口补计。

## 下一个可验收增量

先完成版本化观测和会话事件契约，以及深蹲的因果状态机与缺帧/乱序回放测试，再接通正式 Android 工程的相机到姿态预览。该核心工作兼容两种界面路线。首个真机增量验证全链路、生命周期和延迟，之后逐步接入候选计数与语音；四类研究协议不同时升级为正式产品能力。

## 官方依据

- [Flutter 平台通道](https://docs.flutter.dev/platform-integration/platform-channels)
- [Flutter Android Platform Views 与性能取舍](https://docs.flutter.dev/platform-integration/android/platform-views)
- [MediaPipe Pose Landmarker Android 实时流](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android)
