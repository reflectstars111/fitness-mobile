# 可移植动作研究核心

`src/main/java/fitness/mobile/core/` 包含从原研究代码迁移的纯 Java 几何/协议和新增因果计数器，已由 Kotlin/Android 调用。核心无第三方运行依赖，以 Java 11 目标编译；相机、模型和语音由独立 Android 模块管理。

- `Geometry`：二维角度、屈曲角、躯干/上臂倾角、覆盖率、图像比例。
- `Exercise`：四类动作的信号、阶段名称、机位说明和研究姿态门槛。
- `LiveCounter`：单组因果候选计数，稳定准备、屈曲/返回门槛、缺失中断与证据事件。
- `src/test/fixtures/geometry.tsv`：128 个原 Python 函数输出的固定合成帧，无真人数据。

坐标必须是旋转处理后一致像素坐标，x 向右、y 向下；不可直接将宽高比例不同的归一化坐标用于角度计算。以关节名称传入 COCO17 对应子集，不能将 MediaPipe 33 点数组直接按索引套用。置信度由未来模型适配器定义并验证；覆盖率是该 17 点子集比例。

缺失、低置信度、越界、非有限点不参与计算，缺失指标返回 `null`。`postureReason` 返回 `null` 仅表示通过这一道姿态门槛；`signalReason` 额外核对主信号和侧面视角。二者均不证明追踪身份、可见性、时序或动作质量合格。阈值未在手机模型下校准，不得直接用于正式纠错。

在仓库根目录运行：

```powershell
powershell -NoProfile -File scripts/test-core.ps1
```

从仓库根目录执行上述相对命令；其他目录请给脚本绝对路径。需要 JDK 11+ 和 PowerShell。测试不访问原项目、不需要 Python 或下载模型。CI 固定 Temurin 11.0.28+6、Java 11 编译目标，Action 固定提交；本机验证使用 Microsoft JDK 11.0.31。当前无 Maven/Gradle 第三方依赖，因此无依赖锁文件；Android 创建时独立锁定完整工具链。

维护者可在原代码审阅和许可复核后，用 `python scripts/export-reference.py <原仓库目录>` 更新快照。它只用于导出，会执行指定源码；不是正常构建、测试或手机运行步骤。更改源时必须一起审阅指纹、Java 实现、协议与固定期望。
