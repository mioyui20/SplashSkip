# 开屏跳过 · SplashSkip

用 Android 无障碍服务识别并点击开屏广告的跳过按钮。支持 **Android 8.0 及以上**，免 Root。规则和处理记录都保存在手机本机。

**[下载最新 APK](https://github.com/mioyui20/SplashSkip/releases/latest)** · [报告适配问题](https://github.com/mioyui20/SplashSkip/issues)

## 安装与使用

1. 从 Release 页下载 `SplashSkip-版本号.apk` 并安装。已有同签名版本时，可覆盖安装并保留设置。
2. 打开「开屏跳过」，从首页进入系统无障碍设置，启用同名服务。返回后确认首页显示「运行中」。
3. 在「应用」里选择目标 App 和跳过模式，点「保存并试用」。也可以保存后从桌面正常打开目标 App。
4. 最长等 12 秒，到「记录」查看这次的试用诊断。遇到误点，可以在记录里直接停用对应 App 的跳过。

Android 13 及以上的部分设备会限制侧载应用开启无障碍。如果开关置灰，首页会提供对应帮助入口。仅在确认信任安装包时，按系统提示允许受限制的设置。[Android 帮助](https://support.google.com/android/answer/12623953?hl=zh-Hans)

## 跳过规则

| 模式 | 行为 |
| --- | --- |
| 忽略（白名单） | 放过这个 App 的界面扫描和自动操作。 |
| 标准 | 优先使用已内置的 App 专用规则。也可以为这个 App 开启通用识别。 |
| 加强跳过（黑名单） | 在开屏时间窗内可直接处理唯一、可定位的「跳过」或「跳过＋倒计时」按钮，也识别「运营推广」「摇动手机」等开屏线索。 |

一般规则在 App 进入前台后的 **8 秒内**判断。点按位置从当前控件读取，点击前会复核窗口、目标和遮挡。登录、支付、授权、视频片头和无法区分的多个候选会放过。部分专用规则支持在 App 设置里手动启用一次补点。

首页可以暂停整个服务，也可以把「开屏跳过」加到 Android 快捷设置。应用只保留最近 **60 条**处理和诊断记录，可随时清除。识别依赖无障碍服务能读到的控件，纯画布绘制的按钮、不同 App 版本和系统界面都可能影响命中。

如遇漏跳，提交 Issue 时可附手机型号、系统版本、目标 App 及版本，还有「保存并试用」产生的诊断原文。

## 从源码构建

需要 Windows PowerShell、JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。脚本会运行规则回归测试，并产出签名校验通过的 APK。

```powershell
.\build.ps1 -Sdk 'C:\Android\Sdk' -Jdk 'C:\Java\jdk-17'
```

产物位于 `out/SplashSkip-版本号.apk`。首次构建会在 `.local/debug.keystore` 生成本地签名密钥。同一个安装包的后续更新需要使用相同密钥。

## 规则参考

- [SKIP 布局记录](https://skip.guoxicheng.top/advance/custom-config/full-reference)
- [Lin-arm 的 GKD 订阅规则](https://github.com/Lin-arm/GKD_subscription)
- [快跳的菜鸟控件记录](https://github.com/pxlwjp0425/autoskip)
