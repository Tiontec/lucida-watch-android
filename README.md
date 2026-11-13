# 澄影 · 安卓壳工程（11.1.5）
全屏 WebView 装入 `app/src/main/assets/web/index.html`（= lucida-watch-android_11.1.5.html），虚拟源 https://lucida.local。
含原生双摄（Camera2 + OpenGL 合成 + MediaCodec，照片/视频，画中画/分屏）。屏幕按 602×494 比例居中。
打包：上传到 GitHub → Actions → 澄影 安卓自动打包 → 下载 APK。本地：`gradle assembleDebug`（需 JDK17 + Android SDK 34）。
接口见 BRIDGE.md，状态见 STATUS.md。
