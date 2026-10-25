# HQPE Streamer (hqpe-dlna)

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher.xml" width="96" height="96" alt="HQPE Streamer Logo" />
</p>

<p align="center">
  <b>极简、免 Root 的 Android 系统媒体音频内录与 HQPlayer Embedded DLNA 实时推流桥接器</b><br>
  <i>Lightweight, Non-root Android Audio Capture & Real-time L16/WAV UPnP DLNA Streamer for HQPlayer Embedded</i>
</p>

<p align="center">
  <a href="https://github.com/qinsijie/hqpe-dlna/actions/workflows/build-apk.yml"><img src="https://github.com/qinsijie/hqpe-dlna/actions/workflows/build-apk.yml/badge.svg" alt="CI Build Status" /></a>
  <img src="https://img.shields.io/badge/Android-10%2B%20(API%2029%2B)-blue.svg" alt="Android 10+" />
  <img src="https://img.shields.io/badge/Format-PCM%2048kHz%20%2F%2016--bit%20Stereo-brightgreen.svg" alt="Audio Format" />
  <img src="https://img.shields.io/badge/License-MIT-orange.svg" alt="License MIT" />
</p>

---

## 📖 项目简介 (Overview)

**HQPE Streamer** 是一款专为音响发烧友与 Hi-Fi 玩家打造的轻量级 Android 应用。

### 解决的核心痛点：
- **HQPlayer Embedded (HQPE)** 是业界公认的顶级音频重采样与卷积滤波器引擎，但其输入流主要依赖 DLNA/UPnP (MediaRenderer)、Roon RAAT 或硬件输入。
- 在 Android 移动端，像 **落雪音乐 (LX Music)、网易云音乐、QQ 音乐、Spotify、Apple Music、Bilibili** 等流媒体应用，往往无法原生支持 UPnP/DLNA 推流，或者仅能投送限定的在线音频 URL，无法享受到 HQPlayer 的升频 (PCM/DSD) 与滤波算法。
- 传统的投屏协议（如 AirPlay 1/2）受制于苹果生态与有损 AAC 转码；蓝牙传输存在明显的音质损耗与延迟。

**HQPE Streamer** 借助 Android 10+ 官方内录 API (`AudioPlaybackCapture`)，在**免 Root** 前提下直接捕获系统播放的原始 PCM 裸流（48kHz / 16-bit / 双声道），在手机本地启动微型 HTTP 服务，并通过严格合规的标准 UPnP/DLNA AVTransport SOAP 指令让 HQPlayer Embedded 实时拉取并渲染播放，实现手机端全局音频无缝直达 HQPlayer！

---

## ✨ 核心特性 (Key Features)

1. **纯净内录与零二次污染**：
   - 采用 Android 10+ 原生 `AudioPlaybackCapture`，直接截取系统音频总线。
   - 兼容 `USAGE_MEDIA`、`USAGE_UNKNOWN` 与 `USAGE_GAME`，全面覆盖各类自研音频引擎（如 Flutter 播放器、AudioTrack 第三方引擎）。
   - 保持 48,000Hz、16-bit 原始 PCM 采样率，不做任何有损压缩。

2. **微型流媒体服务 (Local HTTP Server)**：
   - 监听 `0.0.0.0:8088`，输出 `/stream.wav`。
   - 采用特别调校的标准 44 字节 RIFF/WAV 头（`0x7FFFFFF7` 流标记），彻底解决 PotPlayer、DirectShow 及 HQPE 报 `cbFormat: 0, GUID_NULL` 或无声的兼容性难题。
   - 毫秒级无缓存即时刷送 (`flush`)，延迟极低。

3. **合规 DLNA/UPnP 设备自动发现与动态控制**：
   - 内置纯 Kotlin 轻量 SSDP 探测器，严格规范携带 `MAN: "ssdp:discover"` 双引号，避免被路由器或 HQPE 拦截。
   - 动态拉取设备描述 XML，精确过滤并剔除局域网内的路由器（iKuai/OpenWrt）与 NAS（Synology/fnOS），仅展示真正的 `MediaRenderer`。
   - 动态解析真实的 `AVTransport` 控制端点（支持自动处理绝对路径与相对路径），杜绝固定路径导致的 `HTTP 501 Not Implemented` 错误。

4. **智能局域网网卡路由探测**：
   - 独创 UDP 出口路由探测算法：自动寻找通往目标 HQPlayer 的真实物理 WiFi 网卡（`wlan0`），自动规避 VPN/Clash 虚拟网卡（`tun0`）与移动蜂窝网卡（`rmnet`）干扰。
   - 同时支持界面手动编辑推流 IP，应对复杂的双路由器多网段环境。

5. **Hi-Fi 质感深色主题 (Hi-Fi Dark Aesthetic)**：
   - 纯黑黑曜石背景 (`#111215`) 配低饱和深灰卡片 (`#1C1E22`)。
   - 顶部居中标题配圆形动态旋转扫描图标。
   - 56dp 胶囊实体按键，高对比度防失真配色。
   - 实时音频监控面板：动态绿点 `🟢 RUNNING` / 灰点 `⚪ STOPPED`，实时吞吐量 (KB/s)、RMS 分贝仪 (dB) 与静音异常预警。

---

## 🏗️ 架构与音频流转链路 (Architecture & Audio Pipeline)

```
┌────────────────────────────────────────────────────────┐
│                      Android 手机                      │
│                                                        │
│  [ 第三方音乐 App ] (落雪音乐 / 网易云 / QQ音乐 / B站)    │
│           │                                            │
│           ▼ (AudioAttributes: MEDIA / UNKNOWN / GAME)  │
│  [ Android 系统音频总线 (System Audio Framework) ]     │
│           │                                            │
│           ▼ (AudioPlaybackCapture / 48kHz 16bit Stereo)│
│  [ MediaCaptureService (Foreground Service) ]          │
│           │                                            │
│           ▼ (PCM Buffer 数据块推送)                    │
│  [ LocalHttpServer (0.0.0.0:8088/stream.wav) ]         │
│           ▲                                            │
└───────────┼────────────────────────────────────────────┘
            │ HTTP GET (Chunked / Stream)
            │ (局域网 Wi-Fi: 10.10.10.x:8088)
┌───────────┼────────────────────────────────────────────┐
│           ▼                                            │
│  [ HQPlayer Embedded (10.10.10.120) ]                  │
│     ├── UPnP Control: SetAVTransportURI & Play         │
│     ├── 实时拉取 PCM 流                               │
│     ├── 多项式卷积 / 升频滤波 (poly-sinc / sinc-L ...)  │
│     └── 输出至 DAC (USB / Ravenna / NAA / ALSA)        │
│                                                        │
│                      家庭音响系统                      │
└────────────────────────────────────────────────────────┘
```

---

## 🚀 使用指南 (User Guide)

### 1. 准备工作
- 确保 Android 手机与运行 **HQPlayer Embedded** 的主机（如 Linux / HQPE OS / fnOS Docker 等）连接在**同一个局域网 WiFi** 下。
- 确保 HQPlayer Embedded 已开启 DLNA/UPnP Renderer 渲染器功能（默认端口一般为 `49152` 或 `5000`）。

### 2. 操作步骤
1. **启动应用**：打开 **HQPE Streamer**。
2. **选择目标设备**：
   - 点击右上角的 **刷新/扫描按钮** 🔄，应用将自动搜索局域网中的 HQPlayer 渲染器。
   - 在下拉菜单中选中你的 HQPlayer 设备（系统会自动填充 IP、端口与动态控制端点）。
   - 若局域网广播受限，亦可直接手动在输入框中填入 HQPE 的 IP（例如 `10.10.10.120`）与端口（例如 `49152`）。
3. **确认推流 IP**：
   - 检查“本机推流设置”中的 IP 地址是否与 HQPlayer 在同一网段。若有偏差，可点击“自动探测”或手动修改。
4. **开始投送**：
   - 点击底部的 **「开始投送音频」** 大按钮。
   - 首次启动会弹出系统的录音与屏幕录制授权弹窗，点击 **「立即开始」**（Android 14+ 可自主选择单个应用或整机音频）。
   - 状态栏将常驻前台服务通知，界面显示 `正在向 HQPE 成功推流中`。
5. **打开音乐播放**：
   - 打开落雪音乐 (LX Music)、网易云音乐等任意播放器播放喜爱的歌曲。
   - 观察应用界面的“音频捕获实时诊断”面板：状态显示为 `🟢 RUNNING`，速率为 `~192 KB/s`，RMS 振幅随音乐起伏（如 `-15.2 dB`）。
   - 此时 HQPlayer 已接管音频并开始升频播放！

---

## 💡 避坑与常见问题指南 (FAQ & Troubleshooting)

### Q1: 手机外放依然有声音，但 HQPlayer 也能正常听到，如何静音手机？
- **原因**：部分国产 Android ROM（MIUI/HyperOS、ColorOS、OriginOS、OneUI 等）对后台服务的音频路由接管权限进行了严格限制，阻止了第三方应用在后台强制把媒体通道音量置 0。
- **解决方案**：**直接按手机侧边的物理音量减键** 将手机媒体音量调至最低或静音即可。由于 Android 10+ 的 `AudioPlaybackCapture` 是在系统混音前端截取 PCM 数据，因此**调低手机外放音量完全不会影响内录抓流与 HQPlayer 的音量大小**。

### Q2: 局域网 PC 使用 PotPlayer 抓流测试报 `GUID_NULL / cbFormat: 0` 无法播放？
- **原因**：某些流媒体播放器（如 DirectShow 滤镜）在读取 Live HTTP WAV 流时，如果 RIFF 头长度填 `0`，会被解析器误当做“文件已到结尾”而直接终止。
- **优化**：本项目已将 WAV RIFF 头的 ChunkSize 设置为 `0x7FFFFFF7`（约 2GB 的近无限流长度），既符合微软 WAV 标准，又彻底解决了 PotPlayer、foobar2000 以及各类 UPnP 播放器的兼容性。

### Q3: 实时诊断面板标黄警告“捕获到静音数据 (0x00)”？
- **原因**：Android 系统安全策略规定，若某个应用在 `AndroidManifest.xml` 中显式声明了 `android:allowAudioPlaybackCapture="false"`，系统会强制向录制进程输出全 0 静音包。
- **排查**：目前国内主流播放器（网易云、QQ 音乐、酷狗、落雪音乐等）均已默认支持内录。若遇到静音，请检查是否使用了特定加密播放器或防录音保护功能。

### Q4: 提示 `SetAVTransportURI` 超时 (Timeout)？
- **原因**：手机若开启了 Clash、Surge、V2Ray 等 VPN 代理工具，虚拟网卡（`tun0`）可能会将局域网本地流量劫持或阻断。
- **解决方案**：在 VPN 软件中开启“绕过局域网”或“分流”，或者点击应用内的“自动探测”确保绑定的推流出口为真实 WiFi IP（如 `192.168.x.x` 或 `10.10.x.x`）。

---

## 🛠️ 编译与构建 (Build & CI)

本项目无需本地配置繁重的 Android SDK，全流程由 GitHub Actions 自动化编译流水线驱动：

1. **GitHub Actions 自动构建**：
   - 每一次推送至 `main` 分支，都会自动触发 [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)。
   - 流水线基于 `ubuntu-latest` 与 JDK 17，自动执行 `./gradlew assembleDebug`。
   - 构建产物自动打包为 Artifact `hqpe-dlna-debug-apk`，可随时在 GitHub Actions 页面直接下载测试。

2. **本地手动构建（可选）**：
   ```bash
   git clone https://github.com/qinsijie/hqpe-dlna.git
   cd hqpe-dlna
   ./gradlew assembleDebug
   # 生成 APK 路径: app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 📄 开源协议 (License)

本项目采用 [MIT License](LICENSE) 开源。欢迎 Star、Fork 与提交 Pull Request！
