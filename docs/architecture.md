# 独立项目架构与接入边界

## 目录与职责

当前目录中的 `README.md`、`CONTEXT.md`、`docs/` 是本项目的独立文档；`android/` 是后续客户端入口，目前只含开发说明。

客户端实现时再创建可构建工程，按以下职责拆分，而不是预先堆砌空模块：

1. 相机与姿态适配：旋转、镜像、时间戳、帧背压与模型输出映射。
2. 动作核心：不依赖 UI/相机 SDK 的状态机及质量判断。
3. 反馈调度：去重、冷却、优先级、过期和语音队列。
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

共享采用协议文件和去标识化测试夹具：明确 schema 版本、关键点名称、单位、时间戳、状态、规则版本。已导出动作目录 `protocols/exercises.v1.json` 和固定合成几何夹具，来源工作树指纹见 `provenance/source-manifest.json`。已迁移纯 Java 几何与协议核心，独立校验，不在运行时访问原项目。完整的带时间戳观测/反馈协议与实时回放状态机仍待实现。

## 上游参考（接入时重新核验）

- [MediaPipe 官方 Android Pose Landmarker 示例](https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/pose_landmarker/android)：优先评估相机、姿态和设备集成；不是现成纠错器。
- [MediaPipe 官方说明](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android)：核对实时流接口、坐标与模型版本。
- [OmniPose-Fit](https://github.com/grloper/OmniPose-Fit)：参考动作协议、机位指导和语音结构；未经本项目安装验收，复制前确认许可。
- [ML Kit Pose Detection](https://developers.google.com/ml-kit/vision/pose-detection)：备选对照；核对 Beta 状态和分发条件。

## 两个项目独立发布

羽毛球继续使用原启动脚本和 Conda 环境。Android 后续独立使用 Gradle、JDK、Android SDK 和真机测试，不修改羽毛球启动命令。

用户已明确授权独立建仓。本目录使用独立私有仓库 `reflectstars111/fitness-mobile`，已维护提取许可清单、纯核心独立编译测试、CI 和资源排除策略，详见 `docs/extraction.md`。此阶段仓库用于源码管理；Android 构建与真人验收仍未完成，不能据此发布客户端。
