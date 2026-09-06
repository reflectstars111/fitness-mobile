# 原库提取记录

本次已阅读本项目 AGENTS、README、CONTEXT、架构、开发计划及 Android 入口全部文档，并核对原库领域说明、运动 ADR、许可、依赖清单、几何/协议/配置/离线周期源码及相关测试。

## 来源与范围

来源为 `reflectstars111/BBA-Badminton-Biomechanics-Analytics` 本地工作树。精确 HEAD、逐文件 SHA-256 和 Git 状态见 [来源清单](../provenance/source-manifest.json)。两个运动源文件当时尚未提交，HEAD 只是上下文锚点，不能据此从远端还原这些文件。原项目未被修改、提交或推送。

| 原模块 | 本次结果 |
| --- | --- |
| `motion/geometry.py` | 移植为 `Geometry.java`，保留全部十个输出字段，增加输入校验、命名关节和数值稳定处理 |
| `motion/exercises.py` | 移植为 `Exercise.java`，导出版本化 JSON 动作目录；保留研究门槛及限制 |
| `motion/contracts.py` | 审阅档案、配置和缺失语义；桌面路径、CUDA、模型配置不迁入 |
| `motion/squat.py` | 仅审阅；含全周期阶段回填，未复制为实时状态机 |
| `tests/test_motion_exercises.py` | 参考缺失与姿态拒绝案例，新增独立 Java 回归测试 |

Java 可被后续 Kotlin 调用，当前选择它是为了在已有 JDK 上实际编译验证纯核心；不改变 Android 原生 Kotlin 产品方向。四类协议用于保留知识，其中俯卧撑/髋铰链仍属后续产品范围。未复制 Python 全模块、WebUI、比赛数据、模型权重或第三方示例。

## 许可与资源

核对具体源文件，未发现另行许可证或文件级第三方归属声明；原库根目录提供 Apache-2.0，未发现 NOTICE 文件。原许可完整保存在 [licenses/Good-Badminton-Apache-2.0.txt](../licenses/Good-Badminton-Apache-2.0.txt)，移植文件标记来源与修改。参见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。本项目其余新内容的独立分发许可尚未指定，不自动套用第三方模型授权。

目前不下载任何模型或素材。未来引入 SDK/模型必须单独记录版本、下载地址、哈希、代码与权重许可；资源不随 Git 提交，缺少时显式报告不可用。尚无真机测试记录，因为尚无客户端。

## 验证与仓库边界

本次独立编译并通过 128 个原函数合成参考帧及退化点、缺失、越界、非有限值和姿态阈值回归，共 1431 个检查。它验证移植一致性和拒绝行为，不证明实时计数、纠错准确性或真机性能。自动化见 `.github/workflows/core.yml`。

新仓库只提交本目录审阅过的源码、文档、合成夹具及构建配置，不继承原库历史。默认私有，名称 `reflectstars111/fitness-mobile`。Android 独立构建和真机验收仍按 M1 执行。
