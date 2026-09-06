# Android 0.1 验证记录

记录日期：2026-09-07。范围为本地 Debug 工程验证，不是正式发布、真机性能或真人动作准确性验收。

## 产物与环境

- 应用 `fitness.mobile`，versionName 0.1.0，versionCode 1。
- 本机 Windows，Microsoft JDK 21.0.8+9、Gradle 8.12、AGP 8.9.1、SDK 35 / build-tools 35.0.0。
- 模拟器 `Medium_Phone_API_36.1`，Android 16，x86_64。相机输入为模拟器虚拟场景，未使用真人素材。
- Debug APK：`android/app/build/outputs/apk/debug/app-debug.apk`。交付副本：`build/releases/fitness-mobile-0.1.0-debug.apk`。
- APK SHA-256：`d4ce81ebcb1d2b0cf76fa5c0ac356a051e0e5b30490358cc45f0ddf346e5b885`。此值仅标识本次 Debug 产物，其他机器的 Debug 签名不同，不能要求重建 APK 字节完全一致。

## 已执行

| 检查 | 结果 |
| --- | --- |
| `:app:assembleDebug`、`:app:assembleDebugAndroidTest` | 成功生成应用与设备测试 APK |
| `:motion-core:regressionTest` | 128 原函数合成参考帧、1431 几何/协议检查通过；新增因果状态机场景通过 |
| 因果状态机 | 完整/部分动作、缺失、长间隔、重复/乱序时间戳、换 epoch、超时、恢复及逐前缀一致性通过 |
| `:app:lintDebug` | 0 errors、12 warnings；主要为固定版本/targetSdk 更新提示和 KTX 风格建议，未屏蔽检查 |
| `:app:connectedDebugAndroidTest` | 6 项设备端集成测试全部通过：MPImage/预览生命周期、模型空画面、授权与机位前置条件、未评价空值保存/导出/删除、后台与中断恢复、最终包联网权限 |
| 模拟器安装/启动 | `adb install` 成功；Activity 正常启动，检查了实际渲染画面 |
| 相机权限/模型/预览 | 通过系统弹窗授权后，虚拟相机画面可持续分析；无人体时显示不可评价 |
| 后台与记录 | 开始一组后离开应用，关闭相机、暂停、写入中断草稿；返回不自动续计 |
| 结束与导出 | 无可用观测的组保存 `candidateCount=null`、`not_eligible_no_observations`；通过系统文件选择器导出 JSON 并读回验证 |
| 最终 APK 权限 | `aapt dump permissions` 仅含 CAMERA 与应用内部接收器权限；移除依赖合并带入的 INTERNET/ACCESS_NETWORK_STATE |
| 模型与 Wrapper | 模型哈希、Gradle 分发哈希、Wrapper JAR 官方哈希均已核对 |

首次相机冒烟暴露了 MediaPipe 关闭 MPImage 会回收输入 Bitmap 的问题，造成 Compose 绘制崩溃；现已将推理与显示位图分离，新增设备端回归并重新验证。虚拟相机画面中的推理耗时只用于调试，没有作为手机性能数据发布。

本地原始测试输出、截图和导出样本放在忽略提交的 `build/` 与 `android/app/build/`。新增 Android CI 配置可在后续推送时编译 APK、运行 lint/核心回归；本记录不将尚未运行的远端 CI 算作通过。

## 尚待验证 / 后续实现

- 指定 Android 真机上的安装、前后摄像头、左右映射、旋转/镜像、长时间热量、电量、内存、丢帧与延迟分布。
- 真人深蹲/站姿弯举的漏计、误计、机位拒绝与遮挡恢复；研究门槛不构成动作达标标准。
- 音乐、蓝牙耳机、来电、音频焦点抢占和过期语音；当前仅确认离线中文声音可被初始化，未测耳机实际播放到达时间。
- 多人、换人、移动机位的充分拒绝测试；当前髋部位置和侧面判断只是启发式。
- 完整个人档案、多组会话、训练图表、正式纠错规则、Release 签名和商店发布。

删除历史已通过使用独立测试存储的集成测试，导出已通过系统文件选择器实际验证；权限永久拒绝、真正系统杀进程、旋转重建等更多系统场景需加入后续自动化测试。当前中断恢复测试验证新控制器不会自动续计，不等同覆盖所有系统进程回收路径。
