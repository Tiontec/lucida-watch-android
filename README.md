# 澄影 · 安卓壳工程（11.1.5）
全屏 WebView 装入 `app/src/main/assets/web/index.html`（= lucida-watch-android_11.1.5.html），虚拟源 https://lucida.local。
含原生双摄（Camera2 + OpenGL 合成 + MediaCodec，照片/视频，画中画/分屏）。屏幕按 602×494 比例居中。
打包：上传到 GitHub → Actions → 澄影 安卓自动打包 → 下载 APK。本地：`gradle assembleDebug`（需 JDK17 + Android SDK 34）。
接口见 BRIDGE.md，状态见 STATUS.md。
---

## 协议与作者

本项目由 **雾峥奕** 创作，以《澄影非商业开源许可协议 1.0》开源。

| | |
|---|---|
| ✅ 允许 | 学习、研究、修改、非商业分享、自行打包安装 |
| ✅ 必须 | 保留作者署名；衍生作品沿用同一协议；分发时附带协议全文 |
| ❌ 禁止 | **任何商业用途**、**倒卖 / 转售 / 付费分发**、冒充原创、违法用途 |

完整条款见 [LICENSE](LICENSE)。

> 简单说：随便改、随便玩、随便传，但**不能拿去卖钱**，也不能说是你写的。
