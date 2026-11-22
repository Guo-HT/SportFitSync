## SportFit Sync v0.1.1

把运动记录从一个平台搬到另一个平台的 Android 应用。

上传的是**活动文件本体**（原始 `.fit`），不是平台导出的 CSV 摘要——路线、功率、心率、踏频等原始数据完整保留。

### 这一版改了什么

**1. 佳明分成两个账号了**

之前佳明只有一个账号加一个「区域」下拉框，国际区（garmin.com）与中国区（garmin.cn）二选一。
但这两个站其实是**两套独立的账号体系**：SSO 域名、OAuth 授权域名、活动接口域名全都不同，
同一个邮箱在两边也是两个账号，而用户常常两个站都有。

现在它们是**两个平台**，各自独立保存凭据、各自缓存活动，并且**可以互相搬运**：

| | Garmin 国际区 | Garmin 中国区 |
|---|---|---|
| 站点 | garmin.com | garmin.cn |
| 可作源 / 可作目标 | ✅ / ✅ | ✅ / ✅ |
| 账号 | 独立一条（Keystore 加密） | 独立一条（Keystore 加密） |

账户列表里会同时出现「Garmin 国际区」和「Garmin 中国区」两张卡片，可以分别配置。

老用户无需操作：升级时**国区账号会自动搬到新的位置，密码不用重输**（只改数据库里的主键，密文原样保留）。

**2. 「一键同步全部」现在真的同步全部**

之前这个按钮被「每次同步处理条数」（默认 200）悄悄截断了。账号刚配好时本地已经存了
最多 5000 条历史，点一次却只走 200 条，剩下的还得再点十几次。

根因是一个设置项在同时管两件事：「这一轮从平台补拉多少条新的」和「本地已存的最多推多少条」。
现在这两件事分开了：

- **「一键同步全部」一次把积压全部推完**，不再停在固定条数上；
- 设置项改名为「每次拉取活动条数」，只管从平台补拉多少条新的；
- **后台定时同步仍然有界** —— 那是无人值守的作业，不该霸着设备跑几小时，
  剩下的下个周期接着推（账本记着推到哪里了）。

### 同步方向

**6 个源 × 6 个目标 − 5 个自环 = 31 条方向**（上一版 21 条）

| | 源（可读） | 目标（可写） |
|---|---|---|
| Garmin 国际区 | ✅ | ✅ |
| Garmin 中国区 | ✅ | ✅ |
| Coros | ✅ | ✅ |
| Strava | ✅ | ✅ |
| iGPSPORT | ✅ | ✅ |
| Outbase | — | ✅ |
| OneLap | ✅ | — |

矩阵由 `SyncDirectionMatrixTest` 钉死，少一条方向就编译失败。

### 特性

- **跨账号去重** — 内容指纹识别「同一份数据换了新 activityId」，不在目标端制造副本
- **失败可重试** — 单条失败不影响整批；失败项自动重试，也能一键重跑失败集
- **可取消 · 可观察** — 实时进度、逐条日志、前台服务保活，切后台不中断，同步期间屏幕常亮
- **本地历史库** — 绑定账号时全量拉取（上限 5000 条），之后只做增量；老活动也能排上队
- **离线导出** — 一键把活动写成 `.fit` 存进 `下载/SportFitSync`，不依赖任何平台在线
- **缓存可视可控** — 6 类缓存分别统计体积；安全项一键清理，危险项二次确认
- **凭据加密** — Android Keystore 硬件密钥 + AES-256-GCM，数据库里不落明文，且不参与云备份
- **后台定时同步** — WorkManager 周期任务（联网约束），默认关闭
- **常用策略** — 保存常用方向，一键切换；可指定一个作为后台方向

### 安装

下载 **[app-release.apk](https://github.com/Guo-HT/SportFitSync/releases/download/v0.1.1/app-release.apk)**（{{SIZE}} 字节），传到手机后点击安装。首次需要允许「安装未知来源应用」。

- 最低系统版本：Android 8.0（API 26）
- 目标 API：36
- 包名：`com.sportfit.sync`
- 架构：arm64-v8a / 通用（无 native 代码，任意 ABI 均可安装）

> 这是**侧载**应用，不经过应用商店。本版沿用与 v0.1.0 相同的签名密钥，**直接覆盖安装即可，无需卸载**，
> 本地活动库与账号都会保留。

### 校验

```
SHA-256: {{SHA256}}
```

```bash
shasum -a 256 app-release.apk
```

签名证书 SHA-256：`00a2027e4b473788a2319307cb2c36f49b03c1802ba6808ff89955330e68b1bc`
（APK Signature Scheme v2 + v3，与 v0.1.0 相同）

### 已知限制

诚实起见，下面这些部分**尚未用真实账号端到端验证**：

| 平台 / 链路 | 状态 |
|---|---|
| **iGPSPORT 上传** | 导入通道移植自 `Dunky-Z/FitSync`，**未用真实账号跑通** |
| **Outbase 上传** | CDN 暂存 + 注册导入链路，**未用真实账号跑通** |
| **佳明跨区互搬** | 国际区 ⇄ 中国区的方向已实现，**未用两套真实账号跑通** |
| **Coros 国际 / 欧洲区** | regionId 1/3 走 AWS bucket，签发凭证与 SigV4 上传路径**未实测**（中国区 regionId=2 已端到端验证通过） |
| **Strava** | 协议级实现 + 静态验证；授权需自建 Strava 应用 |
| **Garmin SSO / OAuth1 / MFA** | 协议级实现 + 静态验证；登录态复用与活动列表曾用真实账号拉通 200 条真实活动 |

平台硬性约束：

- **Coros 是单设备登录** — 同步会把你手机上的 Coros App 挤下线，同步前请先关掉它
- **Garmin SSO 有速率限制** — 约 10 分钟内 5 次登录即触发 `429 Too Many Requests`（本应用会持久化令牌，正常使用不会重复登录）
- **Coros 不按内容去重** — 重复同步同一活动会在账号里产生副本；请谨慎使用「重新同步已同步项」
- **OneLap 走明文 HTTP** — 仅对 `www.onelap.cn` 与 `u.onelap.cn` 两域精确放行，未全局开启明文

### 文档

完整说明见仓库 [README](https://github.com/Guo-HT/SportFitSync/blob/v0.1.1/README.md)：

- [开发与调试手册](https://github.com/Guo-HT/SportFitSync/blob/v0.1.1/docs/development-handbook.md) — 构建 / 模拟器 / 设备取证 / 排障
- [平台协议参考](https://github.com/Guo-HT/SportFitSync/blob/v0.1.1/docs/platform-protocols.md) — 各平台协议实现规格
- [设计稿与实现进度](https://github.com/Guo-HT/SportFitSync/blob/v0.1.1/docs/design-and-ux-proposal.md) — 需求、决策记录与验证证据

### 许可证

[Apache License 2.0](https://github.com/Guo-HT/SportFitSync/blob/v0.1.1/LICENSE) · 署名见 [NOTICE](https://github.com/Guo-HT/SportFitSync/blob/v0.1.1/NOTICE)

上游项目：[`garmin-sync-coros`](https://github.com/XiaoSiHwang/garmin-sync-coros)（功能原型）、[`FitSync`](https://github.com/Dunky-Z/FitSync)（iGPSPORT 导入通道）。
