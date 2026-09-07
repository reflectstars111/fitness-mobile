# 独立项目架构与接入边界

已确定原生 Android 路线：Kotlin + Compose 界面、CameraX + MediaPipe 适配与独立 Java 动作核心。历史路线对比见 [客户端架构建议](client-architecture-proposal.md)。

## 目录与职责

当前目录中的 `README.md`、`CONTEXT.md`、`docs/` 是本项目的独立文档；`android/` 是可构建的 0.3 客户端；`core/` 是不依赖 Android 的动作核心。

相机和文件回放共用 `PoseEngine`。视频由 MediaCodec 顺序解码，以原始展示时间戳输入规则；相机独立增加实时到达期限。`ChestPressCoach` 按可见部位输出躯干变化候选，不套用站姿弯举或全身合格判决。通用推胸依据以版本化 JSON 打包，无需器械品牌。细节见 [video-replay.md](video-replay.md)。

当前用 `app` 与 `motion-core` 两个 Gradle 模块实现以下职责，后续按实际复杂度拆分：

1. 相机与姿态适配：旋转、镜像、时间戳、帧背压与模型输出映射。
2. 动作核心：不依赖 UI/相机 SDK 的状态机及质量判断。
3. 反馈调度：去重、冷却、优先级、过期丢弃和无积压语音投递。
4. 用户流程：档案、动作选择、摆机位、训练、报告。
5. 本地存储：会话恢复、版本化配置、用户删除和数据导出。

实时链路在手机内运行；可选云端同步不成为计数和语音的必要条件。默认不上传原始视频。离开前台、来电、音频焦点丢失和相机权限撤销均需明确状态处理。

## 与已有 Python 成果的关系

以下路径从现有仓库根目录计算，属于参考位置，不是移动端构建依赖：

| 已有模块 | 参考用途 | 必须重新验证的部分 |
| --- | --- | --- |
| `badmintondataprocess/src/badminton_data_process/motion/exercises.py` | 四类动作协议与姿态约束 | 新姿态模型下的信号分布和阈值 |
| `motion/squat.py`（同目录） | 连续周期、缺帧中断及区间约定 | 离线阶段回填不能进入实时状态机 |
| `motion/geometry.py`、`motion/contracts.py` | 角度定义、档案与配置契约 | 17 点与手机模型关键点映射、坐标轴和单位 |
| `motion/evaluation.py` | 人工标注、区间匹配和评价思路 | 增加误提醒、延迟、打断频率评价 |
| `badmintondataprocess/tests/test_motion*.py` | 合成与端到端回归思路 | 不能替代手机端测试和真人精度验收 |

不使用 `sys.path` 指向父项目，不复制 Python 全模块为第二份维护源，不在手机项目中打包比赛素材或桌面权重。

共享采用协议文件和去标识化测试夹具。已导出动作目录 `protocols/exercises.v1.json` 和固定合成几何夹具，来源工作树指纹见 `provenance/source-manifest.json`。已实现 `LiveCounter` 时间戳观测、因果状态机、候选证据与反馈过期契约，详见 `docs/v1-contracts.md`；完整模型观测序列的数据集回放和真人校准仍待完成。

## 上游参考（接入时重新核验）

- [MediaPipe 官方 Android Pose Landmarker 示例](https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/pose_landmarker/android)：优先评估相机、姿态和设备集成；不是现成纠错器。
- [MediaPipe 官方说明](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android)：核对实时流接口、坐标与模型版本。
- [OmniPose-Fit](https://github.com/grloper/OmniPose-Fit)：参考动作协议、机位指导和语音结构；未经本项目安装验收，复制前确认许可。
- [ML Kit Pose Detection](https://developers.google.com/ml-kit/vision/pose-detection)：备选对照；核对 Beta 状态和分发条件。

## 两个项目独立发布

羽毛球继续使用原启动脚本和 Conda 环境。Android 后续独立使用 Gradle、JDK、Android SDK 和真机测试，不修改羽毛球启动命令。

用户已明确授权独立建仓。本目录使用独立私有仓库 `reflectstars111/fitness-mobile`，维护许可清单、Android/纯核心构建、CI 和资源校验。Debug APK 用于试用，Release 发布和真人验收尚未完成。
