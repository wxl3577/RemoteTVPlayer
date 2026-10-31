# 远程电视播放器 RemoteTVPlayer

Android TV / 安卓电视盒子的极简全屏播放器。Android 5.0（API 21）及以上。

启动后从 `http://82.156.239.39/movie.txt` 实时获取 UTF-8 文本中的视频直链并自动播放。
不需要触屏、鼠标、账号或输入框，电视桌面可直接打开。

| 遥控器按键 | 功能 |
| --- | --- |
| ↑ | 重新读取远程地址，从头自动播放 |
| ↓ | 暂停 / 继续 |
| ← / → | 快退 / 快进 10 秒，可长按 |
| 确定（可选） | 暂停 / 继续 |
| 返回 | 退出应用 |

播放时提示自动消失。切到后台会停止播放并释放解码器，回到应用时重新读取地址；同一地址恢复原进度。
读取或播放失败后按 5、10、20、30 秒间隔自动重试，随后每 30 秒重新读取，也可按 ↑ 立即重试。

## 远程文本格式

`movie.txt` 填写一行完整的 HTTP/HTTPS 视频直链，不要写 Markdown、网页 HTML 或 JSON：

```text
https://your-video-server.example/movie.mp4?token=example
```

支持 UTF-8 BOM、空行、以 `#` 开头的注释；多条时仅播放第一条。查询参数、`*`、`%2C`、`+` 等原样保留。
APK 只内置文本地址，不内置你的迅雷签名直链。修改服务器文件，下次启动或按 ↑ 即生效。
应用不缓存文本，文本最大 64 KB。

目前服务器曾返回 HTTP 404，需要先上传可访问的 `movie.txt`。迅雷签名直链有有效期；失效后请用新直链更新文本。
HEVC、HDR、高码率、音轨格式的实际支持取决于电视硬件解码能力和网络。这里没有绕过源站的授权、签名或访问限制。
支持 Media3 的 MP4、MKV、HLS、DASH 等媒体；无扩展名下载链接也会探测容器。HTTP 明文仅为给定的文本服务器 IP 放行；其他源请使用 HTTPS。

## APK 下载和安装

仓库 **Actions → Build APK → 成功运行 → Artifacts → RemoteTVPlayer-APK** 下载压缩包，解压后安装 `RemoteTVPlayer.apk`。
首个正式交付也会附在 Releases。用 U 盘或 ADB 侧载到电视，按系统提示允许该安装来源。

```sh
adb install -r RemoteTVPlayer.apk
```

当前提供的是可直接安装的 debug 测试签名 APK。CI 缓存 debug 签名以便重复构建安装；缓存被清除后签名可能改变，此时要先卸载旧版。正式分发请自行配置私有 release 签名，不要把私钥提交到仓库。

## 构建与验证

JDK 17、Android SDK 35、Gradle 8.11.1、AGP 8.9.2、Media3 1.5.1。

```sh
gradle testDebugUnitTest lintDebug assembleDebug
gradle connectedDebugAndroidTest
```

GitHub Actions 自动执行单元测试、Android Lint 和 APK 构建，并通过 Android 模拟器测试启动、四个方向键及前后台生命周期。
可在 Actions 手动运行。没有广告、分析 SDK 或后台播放服务。
