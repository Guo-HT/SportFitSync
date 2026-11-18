# SportFit Sync 迁移到 Web + 增加多用户管理：技术评估

> 评估对象：`main` @ `d4832c7` · 2026-11-01
> 评估方式：全量通读 `app/src/main` 64 个源文件（13,261 行）+ 逐平台 HTTP 协议比对 + 逐 API 的
> Android 依赖清单。全程只读，未修改任何生产代码。
>
> 本文件是**评估**，不是实施方案。结论里标了 🔴 的地方需要你先拍板才能进入开发。
>
> ⚠️ **选型部分已被取代**：本文件第 7 章推荐的技术栈是 **Kotlin/JVM (Ktor) + React/TS**。
> 后续已确定改为 **Go 后端 + Vue 前端**，请以
> **`docs/web-migration-assessment-go-vue.md`** 为准——该文档给出了 Go/Vue 栈独有的
> 保真度风险（实测的 Kotlin↔Go 行为差异）、修订后的工作量与路线图。
> 本文件的第 1–6、8、10、11 章（现状盘点、多用户模型、功能映射、既有缺陷、验证项）仍然有效。

---

## 0. 结论摘要

### 0.1 一句话结论

**技术上可行且成本可控，但这不是"把 Compose 界面搬到浏览器"，而是"保留核心引擎、重写客户端、新建一层服务端"。**
最坏的部分（平台协议层，3,070 行，是整个项目最容易出错、最难逆向、最不该重写的代码）**可以逐字复用**；
最好写的部分（界面层，5,799 行纯展示逻辑）反而必须重写。
多用户不是"加个用户表"，它要求把 4 张表的主键从"平台"改成"账号"，并拆掉 3 个进程级全局单例。

### 0.2 三个必须先接受的前提

| # | 结论 | 影响 |
|---|---|---|
| 1 | **多用户 Web 必须有服务端，无法纯前端实现** | 架构从"单机应用"变成"客户端 + 服务端"。项目当前最大的卖点——"纯本地运行、所有请求由手机直发"（README:23-35, 381）——在多用户托管形态下**必然被打破** |
| 2 | **存量凭据无法迁移，所有平台必须重新授权一次** | Android Keystore 密钥不可导出、绑定设备，服务端解不开任何一条密文。用户要重新输一遍 Garmin/Coros/iGPSPORT/OneLap 密码、重做一次 Strava 授权、重登一次 Outbase |
| 3 | **"账号"的概念变了** | 今天 = 全局每平台一个；多用户后 = 一个用户持有多份平台账号。`SyncDirection` 从 `(源平台, 目标平台)` 变成 `(源账号, 目标账号)`，波及账本、缓存、排除名单、策略、任务全部五张表 |

### 0.3 工作量量级（单名熟悉 Kotlin + 现代前端的工程师）

| 工作包 | 人日 | 说明 |
|---|---|---|
| 服务端骨架 + 多用户模型 | 12–18 | Kotlin/Ktor + PostgreSQL + 鉴权 + 会话 + RBAC |
| 同步/导出/缓存引擎移植 | 15–22 | 引擎逻辑不动，重做存储层与调度层 |
| 平台协议层接入服务端 | 8–12 | 代码逐字复用，主要是 DI 改造与 per-user cookie jar |
| 前端重写（功能对等） | 25–40 | 10 个路由 + 状态层 + 设计系统移植 |
| 前端多用户增量 | 20–30 | 登录/注册、账号管理、方向选择器重做、MFA 握手 |
| 凭据信封加密 + 重新授权向导 | 6–10 | 服务端 KMS、重新授权流程 |
| Outbase 登录打通（**高风险**） | 3–8 | 依赖协议探测结果，见 §8 R1 |
| 部署、CI、可观测性 | 8–12 | 定时任务、限流、审计、备份 |
| **合计** | **97–152 人日** | 约 **5–8 人月**，区间宽度主要来自 Outbase 与前端多用户两块 |

**回归保障现成**：136 个 JVM 单测里有 25 项（指纹、方向矩阵、规划器、FIT 打包、Garmin OAuth1 签名、
Coros STS、OSS endpoint、Outbase 协议）**几乎可以原样变成服务端单测**——这是整个迁移里最值钱的东西。

### 0.4 推荐方案（一句话）

> **Kotlin/JVM (Ktor) 服务端复用 `platform/` + `domain/` + `core/` + `sync/` 全部业务逻辑，
> PostgreSQL 承载加了用户维度的数据，React + TypeScript + Tailwind 重写界面。**

理由见 §7。**不推荐** Compose Multiplatform for Web（§7.2），**不推荐**用 TypeScript 重写平台层（§7.1）。

### 0.5 🔴 需要你决策的 5 件事

| # | 问题 | 选项 | 我的倾向 |
|---|---|---|---|
| D1 | Android 版是**保留**还是**下线**？ | 保留（独立运行）/ 保留但改为服务端客户端 / 下线 | **保留但分叉**：先冻结 Android 版继续独立发布；Web 稳定后再让 Android 版也接服务端（届时"请求由手机直发"的隐私属性又会变化，需重新评估） |
| D2 | 多用户是**自用/小团队**还是**公开 SaaS**？ | 自用 / 小团队 / 公开注册 | 这一条决定安全投入的量级。公开注册意味着要处理平台 ToS、限流滥用、密钥托管信任问题 |
| D3 | Strava 的 `client_id/secret` 怎么给？ | 运营方统一注册一个 Strava 应用 / 每个用户自建（现状） | **运营方统一**。多用户下让每个人自建 Strava 应用不现实。但这意味着所有用户的 Strava 授权都经由你的 `client_secret` |
| D4 | 服务端部署在哪？ | 中国大陆（阿里云等）/ 海外 | OneLap、Coros 中国区、iGPSPORT 中国站都是境内接口，OneLap 甚至是明文 HTTP；海外部署到这三条链路延迟与稳定性都会变差。Strava 又只能海外访问——可能需要**双区部署** |
| D5 | 托管形态下，"平台凭据托管在运营方服务器"这个信任模型你接受吗？ | 接受 / 不接受（则放弃多用户托管） | 这是产品最核心的信任问题。运营方同时持有所有用户的平台密码与 OAuth token、且能看到全部活动文件 |

---

## 1. 现状盘点

### 1.1 功能清单（迁移必须 100% 对等）

| 域 | 能力 |
|---|---|
| 同步 | 5 源 × 5 目标 − 4 自环 = **21 条方向**；按方向建账本；内容指纹跨账号去重；逐条失败隔离；失败自动重试 + 一键重跑失败集；可取消；实时进度 + 逐条日志 |
| 平台 | Garmin（源+目标）、Coros（源+目标）、Strava（源+目标）、iGPSPORT（源+目标）、OneLap（仅源）、Outbase（仅目标）、Demo（Debug 演练） |
| 账号 | 每平台绑定一份凭据；区域选择（Garmin COM/CN、Coros 1/2/3、iGPSPORT cn/global）；状态机（已连接/待验证/异常/未配置）；AES-256-GCM 加密存储；编辑不回显密码 |
| 活动 | 绑定账号时全量拉取（上限 5000），之后增量；本地历史库；活动选择器（筛选/分页/批量排除/批量恢复/全选反选）；永久排除名单 |
| 导出 | 一键把活动写成 `.fit` 存到 `下载/SportFitSync`，不依赖任何平台在线；导出参与同一套去重账本 |
| 缓存 | 6 类缓存分别统计体积；安全项一键清理；危险项二次确认；保留策略（临时文件成功即删、日志 30 天、历史 100 条） |
| 后台 | WorkManager 周期任务（联网约束，默认关闭）；可标记某个策略为后台方向 |
| 其它 | 同步时不熄屏；前台服务保活 + 通知栏进度；Garmin MFA 弹窗；演练模式 |

### 1.2 架构

```
Compose UI (5,799)  ──►  AppViewModel (872, 单一 VM)  ──►  AppContainer (手写 DI, 72)
                            │
                            ├──► SyncEngine (646) / ExportEngine (207)  ──►  PlatformRegistry
                            │         │                                            │
                            │         │                                    PlatformGateway ×7 (3,070)
                            │         │                                            │  ← 纯 JVM, 只依赖 OkHttp
                            │         ▼                                            ▼
                            │    Room 7 张表 (849)                        各平台 HTTP API
                            │    DataStore 设置 (10 键)                   阿里云 OSS / AWS S3
                            │    Android Keystore (AES-256-GCM)          自研签名 (V1 / SigV4)
                            │
                            └──► WorkManager (194) · 前台服务 (130) · MediaStore 导出 (130) · 文件缓存 (218)
```

规模：**13,261 行**主源码 / 64 文件；测试 **136** 个 JVM 单测（15 类）+ **22** 个仪器测试（5 类）。

### 1.3 可移植性实测（逐包统计）

| 包 | 文件 | 行数 | 含 Android import | 硬 Android 依赖 | 判定 |
|---|---:|---:|---:|---:|---|
| `domain/` | 2 | 256 | 0 | 0 | ✅ **逐字复用** |
| `platform/` | 19 | 3,070 | **0** | 0 | ✅ **逐字复用**（仅 OkHttp + kotlinx.serialization） |
| `core/` | 9 | 1,283 | 103 | 103 | ⚠️ 仅 `CryptoBox`（Keystore）需替换，其余全复用 |
| `sync/` | 5 | 1,263 | 130 | 130 | ⚠️ 仅 `SyncService`（前台服务）需替换，其余全复用 |
| `data/` | 5 | 849 | 750 | 156 | 🔄 Room → SQL、DataStore → 用户配置行 |
| `cache/` | 1 | 218 | 218 | 218 | 🔄 文件路径 → 服务端存储抽象 |
| `work/` | 2 | 194 | 194 | 194 | 🔄 WorkManager → 服务端调度 |
| `export/` | 1 | 130 | 130 | 130 | 🔄 MediaStore → 制品存储 + 浏览器下载 |
| `ui/` | 19 | 5,799 | 5,721 | 962 | 🔄 **全部重写**（设计可移植，代码不可翻译） |
| 外壳 | 4 | 199 | — | — | 🔄 `AppContainer` / `MainActivity` / `KeepScreenOn` / `SportFitApp` |

**归总**

| 分类 | 行数 | 占比 |
|---|---:|---:|
| ✅ 服务端**逐字复用**（业务逻辑 + 平台协议） | 5,639 | **43%** |
| 🔄 服务端**需重写/重构**（存储、调度、加密、导出） | 1,494 | 11% |
| 🔄 界面**重写** | 5,799 | 44% |
| 外壳 | 199 | 1.5% |

> **`platform/` 一个 `android.*` import 都没有。** 这是整个评估里最重要的好消息：最容易出错的部分
> （Garmin OAuth1 的双次百分号编码、Coros STS 的加盐 base64、阿里云 V1 与 AWS SigV4 手写签名、
> Outbase 的 `ec` 语义与"待处理"判定、Coros/iGPSPORT 的"先查状态码再查 body"陷阱）**一行都不用改**。

---

## 2. 关键结论一：多用户 Web 必然要求后端

### 2.1 浏览器为什么不能直连各平台

逐平台实测（全部来自源码，非推测）：

| 平台 | 阻断原因 | 证据 |
|---|---|---|
| **Coros** | 私有 API 要求 `origin` / `referer` 头——JS 跨域**无法设置**；且 STS 代理靠 `CPL-coros-token` **Cookie** 鉴权，跨域受 SameSite 限制 | `platform/coros/CorosClient.kt:93-94`；`core/net/HttpClients.kt:67-82`（注释明确说明为何必须用无 Cookie 的客户端） |
| **iGPSPORT** | 同样要求 `origin` / `referer`；OSS 上传需 `accessKeySecret` + `securityToken` | `platform/igpsport/IgpsportGateway.kt:114-115`；`IgpsportOssProvider.kt:40-50` |
| **Garmin** | 无重定向可拦截：CSRF 表单登录需**同步从 HTML 里抠 `_csrf`**，且登录中途会**挂起等 MFA** | `platform/garmin/GarminAuth.kt:78, 97-118` |
| **OneLap** | 明文 **HTTP** → HTTPS 页面里的 http 子资源被浏览器 mixed-content 拦截 | `platform/onelap/OnelapGateway.kt:52, 83` |
| **Outbase** | 上传用**自定义头 `Sessionid`** → 必触发 CORS 预检；登录 cookie 属 `outbase.cn` 域，JS 读不到 | `platform/outbase/OutbaseClient.kt:237-247`；`ui/screens/OutbaseAuthScreen.kt:172-176` |
| **Strava** | 唯一"浏览器原生"的——但换 token 需要 `client_secret`，**不能进浏览器** | `platform/strava/StravaOAuth.kt:69-71` |

还有两条与浏览器无关、但与"多用户"强相关：

- **密钥不能进前端**：Garmin OAuth1 consumer secret、Coros 的 `MD5(明文密码)`、iGPSPORT bearer、
  Outbase 的 `sessionId`——放进 JS 意味着每个访问者都能看到所有人的凭据。
- **后台同步浏览器做不到**：WorkManager 周期任务必须搬到服务端。

### 2.2 这意味着什么

> **"纯本地、无中转服务器、请求由手机直发"（README:23-35, 381）这条产品属性，
> 在多用户托管形态下不可能保留。** 服务端 IP 会成为所有平台看到的请求来源，
> 运营方会持有所有用户的平台凭据并经手全部活动文件。

这不是实现取舍，是**产品定位的改变**，必须在立项时说清楚（D2、D5）。单用户自托管可以保留"直发"，
多用户公开托管不行。

### 2.3 反过来看：Web 形态在几处比 Android 更好

| 现状（Android） | Web/服务端形态 |
|---|---|
| 前台服务保活，否则 Activity 被杀 → 任务被截断（`sync/SyncService.kt:31-56`） | 服务端任务，**关掉浏览器也在跑**——更稳 |
| WorkManager 周期任务，进程被杀就丢（`work/SyncScheduler.kt:53-70`） | 服务端 cron，**更准** |
| 进程被杀后 `RUNNING` 状态的任务**没人回收**，历史列表里堆"进行中"僵尸行 | 服务端任务**租约 + 心跳 + 回收器**，天然解决 |
| 每个用户要自己注册 Strava 应用（`ui/screens/AccountEditScreen.kt:162-170`） | 运营方一个应用，所有人共用 |
| 导出写 MediaStore 到 `下载/SportFitSync` | 服务端制品 + 浏览器下载，可直接 ZIP 打包 |
| 同步时靠 `FLAG_KEEP_SCREEN_ON` 撑着 | `navigator.wakeLock`（可见标签页内） |

---

## 3. 关键结论二：43% 的代码可以逐字复用

### 3.1 直接复用（无需改动）

- `domain/Models.kt` —— `Platform` 枚举、`SyncDirection`、`ActivityMeta`、`SyncStatus`、`UploadOutcome`、`SyncAbort`
  （`domain/Models.kt:15-131`）
- `domain/ActivityFingerprint.kt` —— 严格指纹（跨账号同平台比对）与宽松指纹（跨平台比对）
  （`:62-87` / `:114-124`）。**多用户下必须按 (用户, 源账号, 目标账号) 分组**（§5.4）
- `platform/**` —— 7 个平台的全部协议实现。`PlatformSource` / `PlatformTarget` / `PlatformGateway`
  三个接口（`platform/Platform.kt:54-104`）设计得极好：登录在 `createSource/createTarget` 构造期完成，
  凭据由 `PlatformContext` 注入，**平台层本身完全不知道用户是谁**——这让"加用户维度"几乎不需要动它
- `core/net/Hashing.kt` —— MD5 / SHA-256 / HMAC-SHA1 / HMAC-SHA256 / RFC3986 百分号编码
- `core/oss/OssUploaders.kt` —— 阿里云 OSS V1 签名 + AWS SigV4 **手写实现**
  （`:123-281` / `:286-364`）。项目刻意不引 SDK（注释 `:19-23`），这 120 行已经对着真实 bucket 验证过，
  **必须原样保留**
- `core/oss/CorosStsProvider.kt` —— 双通道 STS（代理优先 + 旧版匿名兜底）、加盐 base64 解码
- `core/format/FitPackaging.kt` —— ZIP↔FIT 归一化（按魔数 `PK\x03\x04` 判别，不依赖 Content-Type）
- `sync/SyncPlanner.kt` —— 候选选择规则（`:99-165`）
- `sync/SyncEngine.kt` —— 逐条失败隔离、去重记账、重试语义、取消语义（`:123-392`）

### 3.2 需要接口抽取后复用（纯逻辑，依赖需倒置）

当前这些类直接依赖 Room / 文件系统 / Keystore。抽出接口后逻辑一行不改：

| 抽取接口 | 替换掉 | 用在 |
|---|---|---|
| `LedgerStore` / `ActivityStore` / `JobStore` | Room DAO | `SyncEngine`、`ExportEngine` |
| `PayloadStaging` | `java.io.File` | `SyncEngine.stagePayload` |
| `FileSink` | MediaStore | `ExportEngine` |
| `SecretBox` | `CryptoBox`（Keystore） | `AccountRepository` |
| `BlobStore` | `context.cacheDir` | `CacheManager` |
| `CookieJarFactory` | 全局单例 jar | `HttpClients` |
| `MfaChannel` | 全局单例 deferred | `MfaCoordinator` |

### 3.3 必须重写

`data/local/*`（Room → PostgreSQL DDL）、`data/prefs/*`（DataStore → `user_settings` 行）、
`work/*`（WorkManager → 服务端调度）、`sync/SyncService.kt`（前台服务 → 服务端任务 + SSE）、
`export/ActivityExporter.kt`（MediaStore → 制品存储）、`core/crypto/CryptoBox.kt`（Keystore → KMS 信封加密）、
`ui/**`（全部）、外壳四件套。

### 3.4 回归保障

| 测试类 | 数量 | 迁移后去向 |
|---|---:|---|
| `SyncPlannerTest` | 25 | ✅ 服务端单测，**几乎不改** |
| `ActivityFingerprintTest` | 16 | ✅ 服务端单测 |
| `OutbaseClientTest` | 20 | ✅ 服务端单测 |
| `CorosStsProviderTest` | 12 | ✅ 服务端单测 |
| `OssEndpointTest` | 10 | ✅ 服务端单测 |
| `FitPackagingTest` | 8 | ✅ 服务端单测 |
| `SyncDirectionMatrixTest` | 8 | ✅ 服务端单测（21 条方向钉死） |
| `GarminOAuth1Test` | 5 | ✅ 服务端单测 |
| `IgpsportOssProviderTest` / `HashingTest` / `TimeParsingTest` / `StravaOAuthTest` | 余下 | ✅ 服务端单测 |
| `AppDatabaseMigrationTest` | 1 | 🔄 换 PostgreSQL 迁移工具 |
| `CacheManagerTest` / `ActivityExporterTest` / `SyncEngineTest` / `KeepScreenOnTest` | 22（仪器） | 🔄 重写为服务端测试 |

**这是迁移最大的利好**：最容易出回归的地方（协议签名、指纹、账本语义）恰好是纯函数，
已经有单测钉住，且这些测试在迁移后**继续有效**。

---

## 4. 关键结论三：存量凭据无法迁移

`CryptoBox` 用的是 Android Keystore 里**不可导出、绑定设备**的硬件密钥
（`core/crypto/CryptoBox.kt:26-31, 40, 48-62`）。设备解锁密钥丢了、或密钥被轮换，
密文即永久不可解（`:84-87` 吞掉所有异常返回 null）。

**推论：服务端解不开任何一条从 Android 端存下来的凭据。** 迁移后每个用户必须：

| 平台 | 需要用户做什么 |
|---|---|
| Garmin | 重新走 SSO 登录（可能再触发一次 MFA） |
| Coros | 重输邮箱 + 密码 |
| iGPSPORT | 重输账号 + 密码 |
| OneLap | 重输账号 + 密码 |
| Strava | 重新走一次 OAuth 授权（换到新的 redirect_uri） |
| Outbase | 重新登录并重新采集 sessionId |

**产品含义**：这不是"迁移"，是"重新配置"。首屏需要一个**重新授权向导**，
按平台列出"待重新授权 / 已就绪"，而不是直接进主界面。

**同时**这反而是好事：Android 端的加密模型本来就没有"密钥轮换"和"吊销"能力，
服务端改用信封加密（KEK + per-user DEK）后可以补上这两项。

---

## 5. 多用户管理设计

### 5.1 现状：一个平台一个账号，全局唯一

```
accounts 表：@PrimaryKey val platform: String      ← 平台本身就是主键
              (data/local/Entities.kt:17)
```

**两个 Garmin 账号在结构上不可能存在。** 并且整条链路都是"按平台"而非"按账号"：

| 表 | 主键 | 问题 |
|---|---|---|
| `accounts` | `(platform)` | 🔴 共享服务端上，用户 B 的 Coros 行会覆盖用户 A 的 |
| `cached_activities` | `(platform, activity_id)` | 🔴 撞车；且 activityId 只在**账号内**唯一 |
| `sync_records`（去重账本） | `(sourcePlatform, targetPlatform, activityId)` | 🔴 **最高危**：两个用户同步同一条 Garmin 活动到 Coros 会共用一行，用户 B 的上传会被用户 A 的账本**静默跳过** |
| `exclusions` | `(sourcePlatform, targetPlatform, activityId)` | 🔴 同上 |
| `sync_jobs` / `sync_logs` / `strategies` | 自增 id | 🔴 无归属，历史与日志会跨用户泄露 |

而且**整个 Room schema 一个外键都没有**（实测），所有关系靠约定字符串维系。

### 5.2 模型演进

```
今天（单用户、单设备）：
    Platform ──► accounts(platform)         每平台 1 份凭据
      │        cached_activities(platform, activityId)
      │        sync_records(source, target, activityId)
      └──────► strategies / sync_jobs / sync_logs   （全部隐含"就是这台设备的那个用户"）

多用户：
    User ─┬─► platform_accounts(id, user_id, platform, label, region, …)
           │        └─ 源账号 id / 目标账号 id 取代"平台"
           ├─► cached_activities(account_id, activity_id)
           ├─► sync_records(source_account_id, target_account_id, activity_id)
           ├─► exclusions(source_account_id, target_account_id, activity_id)
           ├─► strategies(user_id, source_account_id, target_account_id)
           ├─► sync_jobs(user_id, …) ─► sync_logs(job_id FK)
           └─► user_settings(user_id, …)   ← 10 个 DataStore 键
```

**关键判断：`SyncDirection` 必须从平台对变成账号对。**
`domain/Models.kt:75-87` 的 `SyncDirection(source: Platform, target: Platform)` 是贯穿全代码的字符串
（`AppViewModel.currentDirection()`、账本、排除名单、策略、活动选择器全部按它建键）。
多用户下它应变成 `(sourceAccountId, targetAccountId)`。**这一个类型改动会波及 5 张表 + 界面上的方向选择器**，
是整个多用户改造里工作量最集中的一处。

`local-files`（导出伪目标，`domain/Models.kt:48`）也要变成一个**虚拟账号行**，
否则导出路径写账本时（`sync/ExportEngine.kt:122-131`）没有 FK 可指。

### 5.3 服务端 schema（建议）

```sql
users(id, email UNIQUE, password_hash, role, status, created_at, updated_at)
      -- role: ADMIN | USER

user_settings(user_id PK FK→users, default_source_account_id, default_target_account_id,
              list_limit, background_enabled, background_interval_hours,
              warn_coros_single_session, delete_temp_after_success,
              activity_retention_days, log_retention_days, job_history_limit)

platform_accounts(id, user_id FK→users, platform, label, region,
                  secret_cipher, token_cipher, status, last_verified_at, last_error,
                  created_at, updated_at,
                  UNIQUE(user_id, platform, label))     -- 允许同平台多账号
                  -- 迁移期用 UNIQUE(user_id, platform) 保持"每平台一个"的既视感

cached_activities(id, account_id FK→platform_accounts, activity_id, name, start_time,
                  distance_meters, duration_sec, sport_type, fetched_at,
                  UNIQUE(account_id, activity_id))
                  INDEX (account_id, start_time DESC)   -- 今天缺这个索引，见 §10

sync_records(id, user_id FK→users,
             source_account_id FK→platform_accounts,
             target_account_id FK→platform_accounts,
             activity_id, status, attempts, last_error, synced_at, fingerprint, updated_at,
             UNIQUE(source_account_id, target_account_id, activity_id))
             INDEX (source_account_id, target_account_id, status)
             INDEX (source_account_id, activity_id)

exclusions(id, user_id, source_account_id, target_account_id, activity_id, note, created_at,
           UNIQUE(source_account_id, target_account_id, activity_id))

strategies(id, user_id, name, source_account_id, target_account_id,
           enabled, use_for_background, created_at, updated_at)
           -- 部分唯一索引: UNIQUE(user_id) WHERE use_for_background
           --   今天这个"至多一个后台策略"是靠代码保证的 (data/local/Daos.kt:406-414)

sync_jobs(id, user_id, source_account_id, target_account_id, target_kind,
          status, total, ok_count, dup_count, fail_count,
          started_at, finished_at, message, lease_expires_at)
          -- lease_expires_at + 心跳回收器，替代今天缺失的"僵尸 RUNNING 行"回收

sync_logs(id, job_id FK→sync_jobs ON DELETE CASCADE, ts, level, message)
          -- 今天没有这个 FK (data/local/Entities.kt:137)，日志会变孤儿
```

### 5.4 三处进程级全局单例 → 必须按用户隔离 🔴

这是多用户最容易漏、后果最严重的一类问题：

| 全局单例 | 位置 | 共享服务端上的后果 | 改法 |
|---|---|---|---|
| `HttpClients.cookieJar` | `core/net/HttpClients.kt:18` | **用户 A 的 Garmin SSO cookie 会被带到用户 B 的请求里**（同一 host） | jar 由 `PlatformContext` 注入，per-user / per-session |
| `MfaCoordinator.deferred` | `platform/MfaCoordinator.kt:24` | 两个用户同时登录 Garmin，后一个把前一个的 MFA 挑战顶掉 | per-(user, job) 的 MFA 注册表 |
| `SyncProgressBus` | `sync/SyncProgressBus.kt:28` | 用户 A 的同步进度广播给用户 B | per-job 的事件流（服务端天然按 job 隔离） |

另有一处是**正确性问题**而非泄露：指纹去重集合今天是按 `(source, target)` 查的
（`sync/SyncEngine.kt:188-198`），必须改成按 `(user, source_account, target_account)`，
否则用户 B 的上传会被用户 A 的账本吞掉。

### 5.5 凭据托管：信封加密

```
KEK（主密钥，来自环境变量 / KMS，永不落库）
  └─ wrap ─► 每用户 DEK（AES-256，存 users 表或密钥表）
              └─ encrypt ─► platform_accounts.secret_cipher / token_cipher
                            格式沿用 "v1:" + base64(iv‖ct‖tag)，便于将来识别与迁移
```

- 沿用现有 `CryptoBox` 的密文格式（`core/crypto/CryptoBox.kt:64-71`），迁移期新旧并存可识别
- **不要**把平台 token 下发给浏览器；浏览器只拿到不透明句柄
- 补上 Keystore 给不了的两件事：**密钥轮换**（`keyVersion` 字段）与**凭据吊销**
- WebCrypto 支持 AES-GCM，但**密钥不能放在浏览器**——那等于把所有用户的明文交给前端

### 5.6 配额与滥用防护（公开托管的前提）

| 约束 | 来源 | 服务端对策 |
|---|---|---|
| Garmin SSO 约 10 分钟 5 次登录即 429（README:541-543） | 平台 | per-user 登录频控 + 全局登录队列 |
| Coros 单设备登录：同步会把手机 App 挤下线（README:541） | 平台 | token 持久化、尽量少重登；UI 明确提示 |
| 平台私有 API 被封 / 账号风险 | 平台 ToS | 公开托管前须取得平台方许可，见 R4 |
| 单用户占满服务器 | 运营 | per-user 并发上限、队列、全局并发闸 |
| 存储增长 | 运营 | 每用户配额 + 保留策略（今天已有 4 个保留参数，直接复用） |

### 5.7 服务端新增的三个机制

1. **MFA 握手**：Garmin 登录中途挂起等验证码。今天是进程内 `CompletableDeferred`；
   服务端要变成「任务进入 `AWAITING_MFA` 状态 → 浏览器轮询/SSE 感知 → POST 验证码 → 任务续跑」。
   `MfaProvider`（`platform/Platform.kt:25-27`）这个接口形状设计得好，**几乎不用改**。
2. **任务租约**：`sync_jobs` 加 `lease_expires_at`，心跳续租，超时由回收器标记为 `FAILED`。
   今天完全没有这个机制（进程被杀后 `RUNNING` 行永远挂着）。
3. **时区**：`core/util/TimeParsing.kt:43` 用 `TimeZone.getDefault()` 解析平台墙钟时间，
   注释明确说"按 UTC 解释会让每条活动都偏移"。服务端跑在 UTC 时必须**显式接收用户的 IANA 时区**
   （存进 user profile），不能靠服务器默认时区。
   导出文件名同理（`export/ActivityExporter.kt:113-128` 用了设备本地时区）。

---

## 6. 功能对等映射表

| # | Android 能力 | Web 对应 | 难度 | 备注 |
|---|---|---|---|---|
| 1 | 底部三 Tab + 7 个二级页 | 同构路由（10 条） | S | 底栏必须常驻（设计稿 §3.1 `:217-221`） |
| 2 | Tab 切换语义：换栈、丢弃子页 | 路由器 `replace` | S | **别用普通 `<Link>`**，会复现"配完账号回不到同步页"的老 bug（`ui/AppRoot.kt:98-102`） |
| 3 | 方向选择（源×目标） | 变成**两个账号选择器** | L | 同平台多账号后，平台配色不足以区分，需要账号后缀标签 |
| 4 | 活动选择器（筛选/分页/批量排除/批量恢复/全选反选） | 同 | L | 今天分页在客户端（`PAGE_SIZE=50`，上限 1000 行）→ 改服务端分页 + 虚拟列表 |
| 5 | 同步中实时进度 + 逐条日志 | **SSE / WebSocket** | M | 取代 `channelFlow` + `SyncProgressBus` |
| 6 | 前台服务保活 | 服务端任务 | — | **变简单了** |
| 7 | 后台定时同步 | 服务端 cron（per-user） | M | 比 WorkManager 准 |
| 8 | 同步时不熄屏 | `navigator.wakeLock` | S | 仅可见标签页有效；后台靠服务端任务 |
| 9 | 通知栏进度 | Notifications API（需授权）或页内提示 | M | 今天的"通知栏取消"本来就未做（README:533） |
| 10 | 账号管理（6 平台固定卡片） | **账号管理**（用户下 N 个账号，可改名/分组/删除） | L | 语义变化，不只是布局变化 |
| 11 | Strava 授权（内嵌 WebView 拦截回调） | **浏览器重定向 + 服务端换码** | S | WebView 那 221 行是被**替换**而非移植（`ui/screens/StravaAuthScreen.kt`） |
| 12 | Outbase 授权（WebView 采 cookie） | **无浏览器等价物** | **L/XL** | 见 R1，最高风险项 |
| 13 | Garmin MFA 弹窗 | 任务态 "需要验证码" + 提交 | M | 见 §5.7 |
| 14 | 缓存统计 + 安全/危险分类清理 | 服务端存储核算 API | M | "本地缓存"概念变成"服务端用量"，文案要改 |
| 15 | 离线导出到 `下载/SportFitSync` | 服务端制品 + 浏览器下载 / ZIP | M | 文件名清洗规则必须逐字保留 |
| 16 | 凭据加密（Keystore） | 服务端信封加密 | M | 见 §5.5 |
| 17 | 演练模式（Demo） | 保留 | S | **必须保留 `DemoGateway` 的代码形态**（`withContext(IO)` + `ctx.log`）——它是回归测试面，不是装饰（`platform/demo/DemoGateway.kt:26-33`） |
| 18 | 21 条方向矩阵 | 同 | S | `SyncDirectionMatrixTest` 钉死，服务端继续跑 |
| 19 | 缓存页 5 个 VectorDrawable | 5 个内联 SVG | S | 平台图标用的是**通用图标**不是品牌 Logo，无版权问题 |
| 20 | 深色模式 | `prefers-color-scheme` | S | 64 个色值全部可直接映射 CSS 变量 |
| 21 | —（新增）登录 / 注册 / 会话 / 找回密码 | — | M | Android 版完全没有 |
| 22 | —（新增）桌面响应式布局 | — | M | 现设计是**单列手机布局**，桌面端需新设计，无视觉回归基线 |
| 23 | —（新增）错误边界 / 加载态 | — | S | Compose 里没有错误边界，composable 崩就是崩 |
| 24 | —（新增）i18n | — | S-M | **现在所有文案是硬编码中文**，`strings.xml` 只有启动器标签 |

---

## 7. 技术选型

### 7.1 服务端：Kotlin/JVM (Ktor) —— 推荐 ✅

| 候选 | 评价 |
|---|---|
| **Kotlin/JVM + Ktor** | ✅ **推荐**。`platform/` 3,070 行 + `domain/` + `core/` + `sync/` **原样搬过去**；OkHttp 原生支持；136 个 JVM 单测几乎不变；协程/Flow 语义不变；团队无需换语言 |
| Kotlin/JS 或 Node/TS 服务端 | ❌ 要用 TS 重写 `Hashing`（MD5/HMAC/RFC3986）、`FitPackaging`、`OssUploaders` 的两套签名器、`CorosStsProvider` 的加盐解码、`AppJson` 的容错取值器 —— **约 1,000 行高风险密码学/协议代码重写**，且丢失现成单测。得不偿失 |
| 复用上游 Python Flask admin | ❌ 上游 admin 确实有多用户/cron/策略（设计稿 §1.1 `:39` 可作**模型参考**），但它没有本项目修掉的那些缺陷（失败重试、指纹去重、逐条隔离），也不覆盖 iGPSPORT/Outbase 通道。**参考它的数据模型，不要复用它的代码** |

**Ktor 选型细节**
- HTTP 客户端：**沿用 OkHttp**（不要换 Ktor Client），否则要重写 7 个平台的请求构造
- 数据库：**PostgreSQL** + Exposed 或 JDBI（Room → SQL DDL；顺带补上今天完全缺失的外键与索引）
- 迁移工具：Flyway（替代 Room `Migration`，并**去掉 `fallbackToDestructiveMigration`**，见 §10）
- 调度：quartz / shedlock + 一个工作池（替代 WorkManager）
- 实时：SSE（比 WebSocket 简单，够用且能穿透代理）
- 文件：本地卷或对象存储（替代 `cacheDir` 与 MediaStore）
- 密钥：环境变量 / KMS（替代 Keystore）

### 7.2 前端：React + TypeScript + Tailwind —— 推荐 ✅

| 候选 | 评价 |
|---|---|
| **React + TS + Tailwind** | ✅ **推荐**。设计系统**已 100% 规格化**（64 色值 / 12 字型 / 25 个可复用组件 / 视觉规范在设计稿 §3.6 `:301-321`），Tailwind 主题可机械映射；生态成熟（虚拟列表、SSE、OAuth 弹窗、文件下载、a11y、IME、响应式）；错误边界与代码分割成熟 |
| Compose Multiplatform for Web | ⚠️ **不推荐**（详见下） |
| Vue / Svelte | ⚠️ 可行，与 React 工作量相近；React 生态在虚拟列表与数据流上更成熟 |

**为什么不推荐 Compose Multiplatform for Web**
1. 生产成熟度：Web 渲染器（Canvas/Wasm）仍偏实验性；对一个要托管他人凭据与活动文件的多用户产品，渲染器层的风险不划算。
2. 真正不可移植的只有 **~556 行（9.6%）**：`StravaAuthScreen`(221) + `OutbaseAuthScreen`(211) + 权限块 + `MainActivity` + `Format.kt`。
   也就是说 **~90% 的界面层逻辑本就要重写**——用 Compose MPP 省下的只是一层"看起来像"的模板，
   却换来了渲染器风险 + 生态缺失（虚拟列表、响应式、a11y 都要自己补）。
3. `ui/` 里 75–80% 的行数是排版逻辑（`weight`/`fillMaxWidth`/`padding`/`FlowRow`/`LazyColumn`），
   在 flexbox/TSX 里几乎可以逐段照抄——**但 `Modifier` 链没有等价模板语言**。
4. 设计系统转 CSS 变量几乎是零成本的（`tnum` → `font-variant-numeric: tabular-nums` 是精确对应）。

**界面层估算**：功能对等 25–40 人日；多用户增量 20–30 人日；Outbase 若需服务端浏览器再加 5–11 人日。

### 7.3 部署拓扑

因 OneLap（境内 + 明文 HTTP）、Coros 中国区、iGPSPORT 中国站与 Strava（仅海外）
的可达性冲突，**很可能需要双区部署**（D4）。至少需要：
- 出网白名单（Coros STS 代理、阿里云 OSS、AWS S3 eu-central-1、OneLap 明文主机）
- per-user 并发闸与登录频控
- 凭据库加密 + 备份策略（备份也必须加密）
- 审计日志（谁在什么时候拉取了哪个平台的数据）

---

## 8. 风险登记册

| ID | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| **R1** | 🔴 **Outbase 无可用服务端登录接口**。今天靠 WebView 采 `sessionId` cookie（`ui/screens/OutbaseAuthScreen.kt:172-176`），浏览器读不到他源 cookie（HttpOnly + 同源）。备选：① 服务端复刻其登录 POST（文档称"存在"但**从未实现/验证**）② 服务端无头浏览器（Playwright 持久化 profile）——运维重、验证码风险高 | 中高 | 高 | **先做协议探测再排期**。探测不通就把 Outbase 目标方向在 Web 版降级/延后，不要拖住整体 |
| **R2** | 🔴 **凭据无法迁移**，所有用户必须重新授权 | 确定 | 中 | 重新授权向导；支持"分平台逐步迁移" |
| **R3** | 🔴 **多用户数据串号**：`accounts`/`sync_records`/`cached_activities`/`exclusions` 四表主键不含用户维度 | 确定（若不改必然发生） | 极高 | §5.3 schema；每个 DAO 查询强制用户作用域；**写跨用户隔离的集成测试** |
| **R4** | 🔴 **平台 ToS / 封号风险**：本项目大量使用逆向出的私有 API（README:526-532 自陈 iGPSPORT、Outbase、Strava、Garmin MFA 等"未端到端实测"）。公开托管 = 运营方承担账号风险与合规风险 | 中 | 高 | D2 先定"自用/小团队"还是"公开"；公开前逐平台确认授权口径 |
| **R5** | 🟠 **服务端 IP 暴露给平台**，"请求由手机直发"的隐私属性改变 | 确定 | 中 | 文案与隐私政策同步修改；给用户明确告知 |
| **R6** | 🟠 **MFA 握手是服务端新机制**，今天靠进程内 `CompletableDeferred` | 中 | 中 | per-(user,job) 通道 + 任务状态机；Garmin 是唯一用到 MFA 的平台，范围可控 |
| **R7** | 🟠 **Coros 单设备登录**：服务端每次重登都会把用户手机 App 挤下线 | 中 | 中 | token 持久化已具备；把重登频率降到最低；UI 明示 |
| **R8** | 🟠 **界面重写量大**，且桌面响应式布局**无视觉回归基线**（现有 37 张截图全是手机尺寸） | 确定 | 中 | 优先做移动端；桌面端先出设计稿再实现 |
| **R9** | 🟡 **日志泄露凭据**：`HttpLoggingInterceptor` 只 redact 了 `Authorization`/`accesstoken`/`Cookie`/`Set-Cookie`，**漏了 `Sessionid` 与 `x-oss-security-token`**（`core/net/HttpClients.kt:27-30`） | 低 | 高 | 服务端日志白名单化：只允许自定义字段名，禁止全量打印 header |
| **R10** | 🟡 **平台单点登录速率限制**（Garmin 10 分钟 5 次） | 中 | 中 | per-user + 全局频控 |
| **R11** | 🟡 **Strava `client_id/secret` 归属**：目前存在用户行里（`platform/strava/StravaGateway.kt:223-227`，塞在 email/password 槽） | 确定 | 中 | 迁到部署级配置（D3） |
| **R12** | 🟡 **时区语义漂移**：服务端 UTC ≠ 设备本地时区，影响时间解析与导出文件名 | 确定 | 中 | 用户 profile 存 IANA 时区，显式传入 |
| **R13** | 🟡 **缺少 i18n 基建**：全部文案硬编码中文 | 确定 | 低 | 若面向海外必须补；若只做中文可延后 |

---

## 9. 实施路线图

分五期，每期都有可验收产出。**建议严格按序**，因为后面的依赖前面的。

### 第 0 期：协议探测与决策（3–5 人日，**先做，否则后面全是空中楼阁**）
1. **Outbase 服务端登录探测**（R1）——决定这条方向做不做
2. Strava 应用注册与回调域名确认（D3）
3. 部署区域决策（D4）、托管形态决策（D2、D5）
4. 平台 ToS 确认（R4）
5. 产出：一份"平台能力矩阵（Web 版）"，标明每条通道在服务端的可行性与验证状态

### 第 1 期：服务端骨架 + 领域层搬迁（15–20 人日）
- Ktor 工程骨架、PostgreSQL、Flyway、日志、配置、CI
- 抽接口：`LedgerStore` / `ActivityStore` / `JobStore` / `PayloadStaging` / `FileSink` / `SecretBox` / `BlobStore` / `CookieJarFactory` / `MfaChannel`
- `domain/` + `core/` + `platform/` + `sync/` 搬迁，**逐字搬**
- 136 个 JVM 单测接进 CI（**这一期结束时应该 136 个测试全绿且没改几个断言**）
- 产出：能跑通 Demo 平台全流程的命令行同步器

### 第 2 期：多用户模型 + 凭据安全（18–25 人日）
- `users` / `platform_accounts` / `user_settings` + 四表主键改造 + 外键与索引补齐
- 注册 / 登录 / 会话 / RBAC（ADMIN / USER）
- 信封加密（KEK + per-user DEK）、密钥轮换、凭据吊销
- per-user cookie jar / MFA 通道 / 任务隔离
- 跨用户隔离集成测试（**专门写攻击性用例**：A 的账本不得影响 B、A 的 cookie 不得进入 B 的请求）
- 产出：多用户后端 + API 契约（OpenAPI）

### 第 3 期：前端重写（25–40 人日）
按 iOS 版报告（`docs/ios-cmp-ui-port-report.md` §7–8）的经验排序：**先落地除 OAuth 外的全部页面，再接授权流程，最后打磨**
- 设计系统移植（色值 → CSS 变量 / Tailwind 主题；字型；5 个 SVG 插画；深色模式）
- 25 个可复用组件 → React 组件
- 10 条路由 + 底栏语义（注意 §6-2 的换栈语义）+ Snackbar → toast store
- 状态层：`AppViewModel` 872 行 → 服务端数据 + 客户端 store
- 活动选择器：服务端分页 + 虚拟列表 + 批量操作
- 缓存页：服务端用量核算 API
- 产出：功能对等的 Web 界面（除授权流程）

### 第 4 期：授权流程与后台任务（12–18 人日）
- Strava：重定向 + 服务端换码（`client_secret` 不出服务端）
- Outbase：按第 0 期结论实施
- Garmin MFA 握手（任务态 + 提交）
- 服务端 cron（per-user）+ 任务租约与回收器
- SSE 实时进度
- 导出：服务端制品 + 浏览器下载 / ZIP
- 重新授权向导
- 产出：功能对等的 Web 完整版

### 第 5 期：多用户界面增量 + 硬化（20–30 人日）
- 账号管理（多账号、改名、分组、删除）
- 方向选择器 → 账号对选择器
- 登录 / 注册 / 找回密码
- 桌面响应式布局
- 错误边界、加载态、空态、i18n
- 配额、限流、审计日志、监控告警
- 压测与安全测试

### 并行建议
第 1 期与第 3 期可部分并行（前端可用 mock API 先做）；第 0 期**必须**先完成。
第 2 期的接口抽取（`LedgerStore` 等）会同时改动第 1 期的代码，建议由同一人主导。

---

## 10. 迁移期间应修的既有缺陷（不要照搬到服务端）

以下都是本次通读代码时发现的**现存问题**，在服务端重写时顺手修掉，别把 bug 迁移过去：

| # | 问题 | 位置 | 严重度 |
|---|---|---|---|
| 1 | `fallbackToDestructiveMigration(dropAllTables = true)` 会**清空含凭据的 accounts 表**；且没有 `MIGRATION_1_2`，v1→v3 直接清库。文件内两条注释自相矛盾（一条说"绝不能破坏性迁移"，一条就用了破坏性兜底） | `data/local/AppDatabase.kt:36-57` | 🔴 |
| 2 | `sync_logs.job_id` **没有外键**指向 `sync_jobs`；`pruneJobs` 删任务时不删日志 → 孤儿日志 | `data/local/Entities.kt:137` vs `data/local/Daos.kt:279-286` | 🟠 |
| 3 | `cached_activities` **缺 `(platform, startTime)` 索引**，而每次读取都是 `ORDER BY startTime DESC LIMIT ?` → 全表扫描 + 排序 | `data/local/Daos.kt:56-64` | 🟠 |
| 4 | 进程被杀后 `RUNNING` 状态的任务**永不回收**，历史列表堆积僵尸行 | 全局 | 🟠 |
| 5 | `CacheManager` 的 `KEY_ACCOUNTS` 被标记为危险项但 `when` 分支里**没有它** → 清理账号永远返回 false | `cache/CacheManager.kt:119, 127` | 🟡 |
| 6 | `clearAllSafe()` **不清理 exclusions**，但 exclusions 在快照里被归类为安全项 | `cache/CacheManager.kt:144-150` | 🟡 |
| 7 | 日志脱敏**漏了 `Sessionid`（Outbase）与 `x-oss-security-token`（阿里云/AWS）** | `core/net/HttpClients.kt:27-30` | 🔴（服务端日志里） |
| 8 | 硬编码 Android UA（`Mozilla/5.0 (Linux; Android 14)…`）在服务端会**谎报平台** | `core/net/HttpClients.kt:84-86` | 🟡 |
| 9 | `HttpClients.noRedirect()` 是**死代码**，无任何调用方 | `core/net/HttpClients.kt:60-65` | 🟡 |
| 10 | 缓存策略设计稿要求危险项"**输入确认**"，实现只有**单击确认弹窗** —— 文档与实现不一致，迁移前需定稿 | 设计稿 §3.5 `:288-299` vs `ui/screens/CacheScreen.kt:204-225` | 🟡 |
| 11 | 账号页等 5 处文案写死"Android Keystore"/"凭据加密保存在本机" | `ui/screens/AccountsScreen.kt:81,116`、`AccountEditScreen.kt:83`、`SettingsScreen.kt:214`、`CacheScreen.kt:196` | 🟡（文案） |
| 12 | 活动失败清单是**用正则从日志文本里解析**出来的，没有专门的失败表 | `ui/screens/JobDetailScreen.kt:260-287` | 🟡（建议服务端落一张失败明细表） |

---

## 11. 迁移前需要确认的事实（本次评估无法在离线环境验证）

| # | 待验证 | 为什么重要 |
|---|---|---|
| V1 | Outbase 的登录接口能否在服务端复刻（邮箱/密码/短信验证码 → `sessionId`） | 决定 R1，决定 Outbase 目标方向在 Web 版是否成立 |
| V2 | Coros STS 代理接口在服务端 IP 上是否仍可用（历史上三周内变了两次，README:307） | 决定 Coros 目标方向 |
| V3 | iGPSPORT `uploadByOss` 通道端到端是否可用（README:530 自陈未实测） | 决定 iGPSPORT 目标方向 |
| V4 | 平台是否允许云服务器 IP 调用（Garmin SSO / Coros / iGPSPORT 的风控） | 决定托管形态可行性 |
| V5 | 平台是否允许托管他人凭据（ToS） | 决定 D2/D5 的可行边界 |
| V6 | Strava 对"一个应用服务多人"的速率与配额 | 决定 D3 |
| V7 | 现有 Android 用户是否有迁移诉求、规模多大 | 决定第 0 期重新授权向导的投入 |

> V1–V3 与现有单测里的 `CorosLiveCheck`（`app/src/test/.../CorosLiveCheck.kt`）是同一套思路：
> **凭据只从环境变量读，不设变量就跳过**。建议按同样模式补 `OutbaseLiveCheck` / `IgpsportLiveCheck`，
> 在服务端 CI 里做成"有凭据才跑"的探活任务。

---

## 附录 A：分层可移植性明细

```
可复用（43%）
├── domain/Models.kt              131 行   平台枚举 / 方向 / 状态 / 异常体系
├── domain/ActivityFingerprint.kt 125 行   严格指纹 + 宽松指纹
├── platform/Platform.kt           104 行   三个核心接口 + PlatformContext
├── platform/PlatformRegistry.kt    ~35 行  静态工厂
├── platform/{garmin,coros,strava,igpsport,onelap,outbase,demo}/  2,785 行
├── core/net/{Hashing,HttpClients,HttpExtensions,InMemoryCookieJar}  ~600 行
├── core/oss/{OssUploaders,CorosStsProvider}                        ~667 行
├── core/format/FitPackaging.kt     123 行
├── core/util/TimeParsing.kt        ~55 行
├── sync/{SyncPlanner,SyncProgressBus}                              ~280 行
└── sync/{SyncEngine,ExportEngine} 的业务逻辑                        ~853 行（需抽接口）

需重构（11%）
├── data/local/{Entities,Daos,AppDatabase}   Room → PostgreSQL DDL（顺带补外键/索引）
├── data/prefs/SettingsStore.kt             DataStore → user_settings 行
├── data/repo/AccountRepository.kt          CryptoBox → SecretBox
├── cache/CacheManager.kt                   cacheDir → BlobStore
├── work/{SyncScheduler,SyncWorker}.kt      WorkManager → 服务端 cron
├── sync/SyncService.kt                     前台服务 → 服务端任务 + SSE
├── export/ActivityExporter.kt              MediaStore → 制品存储 + 浏览器下载
└── core/crypto/CryptoBox.kt                Keystore → 信封加密

需重写（44%）
└── ui/**                                   5,799 行：10 路由 + 状态层 + 设计系统
    其中真正不可移植的仅 ~556 行（9.6%）
```

## 附录 B：Android 依赖分类汇总（迁移检查表）

| 类别 | 数量 | 典型符号 | Web 对应 |
|---|---:|---|---|
| **纯展示层** | 235 处 `dp` + ~200 处 `sp` + 25 个组件 | `Modifier.weight`、`FlowRow`、`LazyColumn` | flexbox / CSS / 虚拟列表 |
| **Room** | 7 张表 + 全部 DAO | `@Entity`、`@Query`、`Room.databaseBuilder` | PostgreSQL + SQL |
| **WorkManager** | 2 文件 | `PeriodicWorkRequestBuilder`、`CoroutineWorker` | 服务端 cron + 工作池 |
| **前台服务 / 通知** | 2 文件 | `Service`、`startForeground`、`NotificationCompat` | 服务端任务 + SSE / Notifications API |
| **Keystore** | 1 文件 | `KeyGenParameterSpec`、`AndroidKeyStore` | KMS + 信封加密 |
| **MediaStore / SAF** | 1 文件 | `MediaStore.Downloads`、`IS_PENDING` | 服务端制品 + 浏览器下载 |
| **WebView / CookieManager** | 2 屏幕 | `shouldOverrideUrlLoading`、`CookieManager.getCookie` | OAuth 重定向 / 服务端登录 |
| **DataStore** | 1 文件 | `preferencesDataStore` | `user_settings` 行 |
| **窗口 / 权限** | 4 处 | `FLAG_KEEP_SCREEN_ON`、`POST_NOTIFICATIONS`、`enableEdgeToEdge` | `navigator.wakeLock` / Notification API / `dvh` + safe-area |
| **生命周期 / 导航** | 4 处 | `repeatOnLifecycle`、`NavHost`、`ViewModelScope` | hooks / 路由器 / store |
| **资源** | 7 处 | `painterResource(R.drawable.*)` | 5 个内联 SVG |
| **纯 JVM 但浏览器没有** | 2 处 | `TimeZone.getDefault()`、`SimpleDateFormat` | `Intl.DateTimeFormat` + 显式时区 |

**实测确认的"缺席"**（无需移植）：零 `WindowInsets`、零剪贴板、零生物识别、零相机、零地图、
零 `PullToRefresh`、零 `DatePicker`/`TimePicker`、零 `ModalBottomSheet`、零 `TopAppBar`、
零 `FloatingActionButton`、零自定义字体、零 `collectAsStateWithLifecycle`。
**Android 专属面小得出奇，且集中在 8 个文件里**——这是整个迁移可 tractable 的根本原因。

---

*评估基于 `main` @ `d4832c7` 的全量源码通读。未修改任何生产代码。*
