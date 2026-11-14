# 澄影 · 安卓壳工程（11.1.5）
全屏 WebView 装入 `app/src/main/assets/web/index.html`（= lucida-watch-android_11.1.5.html），虚拟源 https://lucida.local。
含原生双摄（Camera2 + OpenGL 合成 + MediaCodec，照片/视频，画中画/分屏）。屏幕按 602×494 比例居中。
打包：上传到 GitHub → Actions → 澄影 安卓自动打包 → 下载 APK。本地：`gradle assembleDebug`（需 JDK17 + Android SDK 34）。
接口见 BRIDGE.md，状态见 STATUS.md。
---

## 下载安装包

所有版本在 **[Releases](https://github.com/Tiontec/lucida-watch-android/releases)**。

| 版本 | 主要内容 |
|---|---|
| v11.1.5 | 手表版 Android 壳，Camera2 双摄 + OpenGL 合成 |
| v11.1.0 | 早期外壳，内置手表版网页 |

每个版本包含：

- `澄影手表-android-<版本>.apk` —— 已用固定密钥签名，可直接安装
- `Lucida-watch-<版本>-web.html` —— 该版本内置的网页源文件

## 协议与作者

本项目由 **雾峥奕** 创作，以《澄影非商业开源许可协议 1.0》开源。

| | |
|---|---|
| ✅ 允许 | 学习、研究、修改、非商业分享、自行打包安装 |
| ✅ 必须 | 保留作者署名；衍生作品沿用同一协议；分发时附带协议全文 |
| ❌ 禁止 | **任何商业用途**、**倒卖 / 转售 / 付费分发**、冒充原创、违法用途 |

完整条款见 [LICENSE](LICENSE)。

> 简单说：随便改、随便玩、随便传，但**不能拿去卖钱**，也不能说是你写的。
