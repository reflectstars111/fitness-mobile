# Fitness Mobile · 实时健身陪练

架好手机，戴上耳机，在训练中获得适时提醒，训练后查看可复核的记录。

这是独立于 BBA 羽毛球网页的手机应用项目，名称暂定为 **Fitness Mobile**。已实现 **Android 0.2 实验版与可安装 Debug APK**：端侧姿态分析画面、深蹲/站姿弯举候选计数、弯举偏移/晃动提醒、自设深蹲幅度提示、离线语音与可追溯本地记录。已通过构建和模拟器工程验证，**尚未完成真人准确性和真机性能验收**。

## 两个项目，分别开发

| 项目 | 场景 | 开发位置 |
| --- | --- | --- |
| BBA 羽毛球网页 | 上传完整录像，清洗、跟踪、赛后复盘 | 原有 `badmintondataprocess/` 等目录，保持不变 |
| Fitness Mobile | 手机摄像头实时观察、耳机提醒、组后总结 | 本目录 `fitness-mobile/` |

本目录独立管理，远端为 [reflectstars111/fitness-mobile](https://github.com/reflectstars111/fitness-mobile)（私有）。手机端不得依赖父目录的 Python 包、Conda、模型权重或绝对路径才能运行。

## 产品流程

个人档案与授权 → 选择动作 → 摆机位与可见性检查 → 实时训练与语音 → 组后数据报告。

环绕采集和个性化三维建模作为可选高级方向，不作为普通训练必须完成的步骤。档案、采集、模型估计与经过验证的三维测量必须分别标记。

## 从哪里开始

- [开发计划与验收门槛](docs/development-plan.md)
- [技术边界与现有成果接入](docs/architecture.md)
- [项目领域约定](CONTEXT.md)
- [Android 开发入口](android/README.md)
- [已提取核心与测试方法](core/README.md)
- [提取范围、来源和验证记录](docs/extraction.md)
- [实时提醒规则与使用流程](docs/coaching-v0.2.md)
- [实时计数、缺失与反馈契约](docs/v1-contracts.md)

首期采用 Kotlin、Jetpack Compose、CameraX、MediaPipe Pose Landmarker 与系统语音。已维护独立 Gradle Wrapper、Debug 依赖锁、模型指纹与构建脚本；具体命令和工具链见 [Android 说明](android/README.md)。手机运行不依赖 Python 或原项目。

## 已有成果如何使用

原项目的 `badmintondataprocess/src/badminton_data_process/motion/` 保留为离线算法验证工具，已有深蹲、俯卧撑、弯举、髋铰链的二维候选逻辑及报告。它不是手机端代码，也未完成新增动作的真人精度验收。

移动端继承动作语义、质量约定和测试数据格式，不复制整套 Python 运行时。离线与实时实现通过版本化协议及固定回放样本做一致性验证。原 WebUI 健身页暂时保留，供研究回归使用。

继承代码保留 Apache-2.0 原许可与修改声明，见 [来源与许可](THIRD_PARTY_NOTICES.md)。本项目其余内容尚未确定独立分发许可；模型与 SDK 接入前需单独审计。
