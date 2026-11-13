# 状态（11.1.5）诚实说明
- 已做：Java 源码用 android.jar(API 33) 编译通过；角度/镜像变换链 PipelineTest 全部通过（10 万+采样点）。
- **未做**：从未用 Gradle 打出 APK、从未在任何安卓设备/模拟器运行。Camera2 前后同时打开、GL 合成、MediaCodec 录像、麦克风同步、键盘高度上报都只是按文档写成，**必须真机验证**。
- 系统不允许前后摄像头同时工作的机型（多数老手表/低端机）：原生双摄会返回 unsupported，网页自动回退到「连拍合成」。
- 手表固定 API 27（Android 8.1）：无 getConcurrentCameraIds，只能打开时才知道能否双摄。
- 打包：推到 GitHub，Actions「澄影 安卓自动打包」生成 Lucida-Watch-apk（固定签名，可覆盖安装）。
