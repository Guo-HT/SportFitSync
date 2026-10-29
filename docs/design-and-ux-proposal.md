# SportFit Sync for Android —— 方案与交互讨论稿

> 状态：**讨论稿，待确认后开工**
> 源项目：`/Users/mac/Code/pyCode/garmin-sync-coros`
> 目标工程：`/Users/mac/Code/android/sport-fit-sync-app`
> 编写日期：2026-10-07

---

> 构建 / 安装 / 调试 / 模拟器 / 设备取证 的操作说明见 [`development-handbook.md`](development-handbook.md)。

## 一、源项目核心逻辑解析

### 1.1 实际结构（与 CLAUDE.md 描述已不一致）

```
garmin-sync-coros/
├── scripts/
│   ├── garmin/    garmin_client.py · garmin_db.py · garmin_sync_coros.py · garmin_url_dict.py
│   ├── coros/     coros_client.py · coros_db.py · coros_sync_garmin.py · region_config.py · sts_config.py
│   ├── igpsport/  igpsport_client.py · igpsport_db.py · igpsport_sync_{garmin,coros,strava}.py
│   ├── onelap/    onelap_client.py · onelap_db.py · onelap_sync_{garmin,coros,strava}.py
│   ├── strava/    strava_client.py · strava_db.py · {garmin,coros,strava}_sync_*.py
│   ├── oss/       ali_oss_client.py · aws_oss_client.py      ← 只为「上传到 Coros」服务
│   ├── utils/     md5_utils.py · coros_oss_credients_utils.py
│   ├── sqlite_db.py  config.py
├── db/            garmin.db · coros.db · igpsport.db · sync_platform.db
├── garmin-fit/ coros-fit/ igpsport-fit/ onelap-fit/   ← FIT/ZIP 落盘，无清理机制
├── web/           Flask 管理台（多用户 / 策略 / cron / 任务日志 / 活动选择 / 排除名单 / 加密凭据）
├── run_sync.py    本地 CLI 入口
└── .github/workflows/  定时 GitHub Action
```

**一句话总结**：核心是「12 条方向脚本 + 每平台一个 SQLite 去重状态表 + FIT 文件中转」，`web/` 只是把脚本包成子进程调度，`oss/` 只是 Coros 的入站通道。

### 1.2 平台能力矩阵

| 平台 | 登录方式 | 列表接口 | 下载（源） | 上传（目标） | 区域 |
|---|---|---|---|---|---|
| **Garmin** | garth SSO→OAuth1→OAuth2 | `/activitylist-service/activities/search/activities` | `/download-service/files/activity/{id}` → **ZIP** | `POST connectapi.{domain}/upload-service/upload/.fit`（multipart） | `.com` / `.cn` |
| **Coros** | 邮箱+MD5(密码) → accessToken | `{teamapi}/activity/query` | `{teamapi}/activity/detail/download` → `fileUrl` | **不能直传**：OSS 中转 + `/activity/fit/import` | region 1/2/3 |
| **iGPSPORT** | Bearer token | `/web-gateway/web-analyze/activity/queryMyActivity` | `queryActivityDetail/{rideId}` → `fitUrl` | —（仅作源） | cn / global |
| **OneLap** | 明文 HTTP + MD5 | `u.onelap.cn/analysis/list` | `u.onelap.cn` + `durl` | —（仅作源） | 国内 |
| **Strava** | OAuth2（自建应用） | `/api/v3/athlete/activities` | `/activities/{id}/export_original` | `POST /uploads`（异步轮询） | 全球 |

### 1.3 上传 / 下载细节（本次重点）

#### Garmin
- **认证**：`garth` 库。SSO（`sso.garmin.com` / 国区 `sso.garmin.cn`）登录 → 拿 `ticket` → `oauth-service/oauth/preauthorized` 换 OAuth1 → `oauth-service/oauth/exchange/user/2.0` 换 OAuth2 access/refresh token。客户端在调用前用装饰器检查 `username`，失效自动重登。
- **下载**：`GET /download-service/files/activity/{activityId}`，返回的是 **ZIP 包**（内含 `.fit`）。脚本按 `{id}.zip` 落盘。
- **上传**：`POST https://connectapi.{domain}/upload-service/upload/.fit`，`multipart/form-data`，头带 `Authorization: OAuth2 token`。判定规则：
  - `202` 且 `detailedImportResult.uploadId` 非空 → `SUCCESS`
  - `409` 且 message 含 `Duplicate Activity` → `DUPLICATE_ACTIVITY`（视作成功）
  - 其它 → `UPLOAD_EXCEPTION`

#### Coros（链路最特殊，也是迁移的关键难点）
- **认证**：`POST https://teamcnapi.coros.com/account/login`，`pwd = md5(明文)`，`accountType=2`。返回 `accessToken / userId / regionId`，`regionId` 决定后续 `teamapi`。
  - region 1 = 国际 `teamapi.coros.com`，2 = 中国 `teamcnapi.coros.com`，3 = 欧洲 `teameuapi.coros.com`
- **下载**：`POST {teamapi}/activity/detail/download?labelId&sportType&fileType=4` → `data.fileUrl` → `GET` 下载。
- **上传（三步中转，无法绕开）**：
  1. 取 STS：**训练中心 BFF 代理**（2026-10-03 起匿名通道 `faq.coros.com/openapi/oss/sts` 已下线，详见 `platform-protocols.md`）
     `GET https://trainingcn.coros.com/api/proxy/oss/sts?bucket=&service=&v=2`（国际/欧洲区用 `training.coros.com`）
     鉴权靠 Cookie `CPL-coros-token=<登录返回的 accessToken>`（`accesstoken` / `Authorization` 头均不被识别）
     凭据仍是 `credentials` 字段：去掉固定盐 `9y78gpoERW4lBNYL` → Base64 解码 → JSON（AccessKeyId / AccessKeySecret / SecurityToken，或 SecretAccessKey / SessionToken）
  2. 上传 OSS（对象键 **必须是** `fit_zip/{userId}/{md5}.zip`）：
     - region 2：阿里云 `oss-cn-beijing`，bucket `coros-oss`，1MB 分片
     - region 1/3：AWS `s3.eu-central-1`，bucket `coros-s3` / `eu-coros`，5MB 分片
  3. 通知导入：`POST {teamapi}/activity/fit/import`，`form` 字段 `jsonParameter` = `{source:1, timezone:32, bucket, md5, size, object, serviceName, oriFileName}`
     成功判定：`result == "0000"` **且** `data.status == 2`
- **硬约束**：登录即挤掉其它端（单设备）——同步期间不能开 Coros App/网页，否则失败。

#### iGPSPORT
- 登录 `POST {base}/auth/account/login`（`appId: igpsport-web`）→ `data.access_token` 作 Bearer。
- 列表分页 `pageNo/pageSize=100/reqType=0/sort=1`；下载经活动详情拿 `fitUrl` 再 GET。

#### OneLap
- 裸 HTTP（`http://www.onelap.cn/api/login`），密码 MD5；列表 `u.onelap.cn/analysis/list`；下载拼接 `durl`。
- 明文传输，Android 上需要为该域开 `cleartextTrafficPermitted`（安全上不理想，会做域名白名单限定）。

#### Strava
- OAuth2，token 存在 `.strava_token` 或环境变量，过期自动刷新。
- 下载 `export_original`；上传走 `/uploads`，`stravalib` 内部轮询等待处理完成（最长 120s）。

### 1.4 同步状态机与编排

- 每平台一张表，`activity_id` 唯一，状态位 `0=未同步 / 1=已同步 / 2=异常`；`is_sync_coros` / `is_sync_garmin` / `is_sync_strava` 表示"同步到谁"。
- 查询 `WHERE is_sync_x = 0 LIMIT 1000`。
- 编排差异：
  - `Garmin→Coros`：**先全量下载** FIT，再逐个 OSS 上传 + 导入；任一 OSS 异常 → `return` **整体中断**。
  - `iGPSPORT/OneLap→*`：边下边传，异常 `continue` 不中断。
  - `Coros→Garmin`：边下边传，异常 `continue`。

### 1.5 已确认的问题（迁移时要修）

| # | 问题 | 影响 |
|---|---|---|
| 1 | `scripts/strava/*_sync_*.py` 调用签名已过期（`CorosDB(DB_DIR)`、`coros_client.login(email,pwd)`、`GarminClient` 三参数、`GarminDB.getUnSyncActivity(limit=)`） | **Strava 全部方向当前实际不可用** |
| 2 | `CLAUDE.md` 描述的结构与现状不符 | 误导后续维护 |
| 3 | 状态 `2`（异常）不在 `=0` 的查询里 | **失败活动永不自动重试，需人工改库** |
| 4 | 无增量：只能 `limit` 截断，不能按日期区间 | 移动端流量/耗时不可控 |
| 5 | `garmin-fit/` 等目录文件永久堆积，无任何清理 | 正是需求 3 的痛点 |
| 6 | `web/sync_engine.py` 用「子进程 + 环境变量传明文密码」 | Android 必须改为进程内调用 + 加密存储 |
| 7 | 无并发、无取消、无断点续传 | 弱网体验差 |

---

## 二、Android 迁移方案

### 2.1 目标 / 非目标

**目标**
- 手机上**本地直连**各平台，不再依赖服务器 / GitHub Actions / 定时 CI。
- 凭据用 Android Keystore 加密落库，不出现明文。
- 同步可视化：进度、逐条日志、可取消、失败可单条重试。
- 增量同步（按日期区间 + 条数上限），省流量。
- **本地缓存清理**（需求 3）作为一等公民。
- 交互按移动端重排：方向选择 → 预览勾选 → 同步 → 结果闭环。

**非目标（首版不做）**
多用户 / 账号体系、云端同步配置、社交与数据分析图表、iOS。

### 2.2 技术栈与工程结构（构建环境已实测）

| 项 | 选型 | 实测结论 |
|---|---|---|
| 语言 / UI | Kotlin + Jetpack Compose (Material 3) | — |
| 构建 | AGP 8.7 / Gradle 8.9 / JDK 21 | Android Studio 内置 JBR **21.0.10** 可用；`~/.gradle` 已有缓存 |
| SDK | compileSdk 36 / targetSdk 36 / minSdk 26 | SDK 已装 `android-36`、`build-tools 36.1.0` |
| 网络 | OkHttp + kotlinx.serialization | Google Maven / Maven Central 均可达（HTTP 200） |
| 存储 | Room（活动缓存 / 同步记录 / 日志）+ DataStore | — |
| 凭据 | Android Keystore + AES-GCM | — |
| 后台 | WorkManager（定时）+ Foreground Service（前台进度通知） | — |
| DI | 手写 `AppContainer`（不引 Hilt，减编译开销） | — |
| 验收 | AVD `Pixel_7_Pro`(API 33) 已存在，可真机级跑通 | 可构建 + 可运行验证 |

```
app/src/main/java/com/sportfit/sync/
├── core/        net（OkHttp/重试/日志） · crypto（Keystore AES-GCM） · format（FitNormalizer）
├── data/
│   ├── local/   Room：Account · CachedActivity · SyncRecord · SyncJob · SyncLog · Exclusion
│   ├── prefs/   DataStore：同步选项 · 缓存策略
│   └── repo/
├── platform/
│   ├── PlatformSource.kt / PlatformTarget.kt        ← 统一抽象
│   ├── garmin/  GarminAuth · GarminApi · GarminSource · GarminTarget
│   ├── coros/   CorosAuth · CorosApi · CorosSource · CorosTarget(含 OSS 中转)
│   ├── igpsport/ · onelap/ · strava/
│   └── oss/     StsProvider · AliOssUploader(签名V1) · S3Uploader(SigV4)
├── sync/        SyncEngine · SyncPlanner · SyncNotifier · SyncScheduler(WorkManager)
├── cache/       CacheManager（统计/清理/自动策略）
└── ui/          sync/ · history/ · settings/ · onboarding/ · components/
```

### 2.3 平台抽象（把 12 条脚本收敛成「源 × 目标」组合）

```kotlin
data class ActivityMeta(val id: String, val name: String, val startTime: Instant?,
                        val distanceMeters: Double?, val durationSec: Long?, val sportType: String?)
data class RemoteFile(val bytes: ByteArray, val fileName: String, val isZip: Boolean)
sealed interface UploadOutcome { object Success; object Duplicate; data class Failed(val reason: String) }

interface PlatformSource { suspend fun login(); suspend fun listActivities(limit: Int): List<ActivityMeta>
                           suspend fun download(id: String): RemoteFile }
interface PlatformTarget { suspend fun login(); suspend fun upload(file: RemoteFile): UploadOutcome }
```

- 原来 12 个脚本 → 5 个 Source + 3 个 Target（Garmin / Coros / Strava），方向矩阵由 UI 组合产生，**新增平台只需实现一个接口**。
- `FitNormalizer`：按 `PK\x03\x04` 魔数判断，`ensureZip()`（Coros 目标必需）/ `ensureFit()`（Garmin/Strava 目标必需）。**这一层直接消灭源项目里「有的存 .fit 有的存 .zip」的混乱**。
- `CorosTarget.upload()` 内部封装「STS → OSS PUT → import」三步，对上仍是一个 `upload()`，与 Garmin 目标接口完全一致。

### 2.4 数据模型（Room）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `accounts` | platform, region/authDomain, credsCipher, tokenCipher, status, lastVerifiedAt | 每平台一条 |
| `cached_activities` | platform, activityId, name, startTime, distance, duration, fetchedAt | **本地活动历史**（配置账号时全量落库，之后增量补齐） |
| `sync_records` | **source+target+activityId 唯一**, status(0/1/2), attempts, lastError, syncedAt, **fingerprint** | 替代源项目「每平台一个 db + 状态列」；`fingerprint` = 内容指纹，跨 activityId 识别同一份数据 |
| `sync_jobs` | direction, status, total, ok/dup/fail, startedAt, finishedAt | 一次同步运行 |
| `sync_logs` | jobId, ts, level, message | 逐条日志（可清理） |
| `exclusions` | source+target+activityId, note | 永久排除（沿用 web 版语义） |

> `sync_records` 用 `(source, target, activityId)` 做唯一键，天然支持「同一个 Garmin 活动既同步到 Coros 又同步到 Strava」而互不干扰 —— 源项目用多条状态列才能勉强表达。
>
> 但 activityId 不是「这条活动」的稳定名字（重新导入 FIT、换同平台账号都会换 id），
> 所以额外存一个**内容指纹**（开始时间 + 名称 + 距离 + 时长 + 运动类型的归一化哈希）：
> 指纹命中「本方向已同步」集合的活动按已同步处理，不再重复上传。

### 2.5 同步引擎（相对源项目的改进）

1. **流水线**：下载 → 归一化 → 上传，逐活动独立 try/catch；默认并发 1（对平台友好），设置里可调 1–3。
2. **失败可重试**：失败写 `status=2`，UI 上「重试失败项」直接重跑这批 id；不再有"永不重试"的死角。
3. **可取消**：`Job.cancel()` + 清理临时目录，状态落 `cancelled`。
4. **增量**：配置账号时把该账号的**全部历史活动**拉进本地库（上限 5000 条）；之后每次同步只从网络上取**最新一批**并与本地库合并，**候选集取自本地库**（新活动只有先落库才可能被同步）。单次运行仍受「每次最多 N 条」限制，但老活动因此也能排上队。
5. **预检**：同步前校验账号有效性、网络、区域匹配（如 Garmin CN 账号 + garmin.com 域）。
6. **临时文件策略**：写 `cacheDir/fit-cache/{jobId}/`，**成功即删**；失败保留供诊断（可配）。

---

## 三、交互方案

### 3.1 信息架构

底部导航 3 个 Tab：**同步 / 记录 / 设置**。首次启动走 3 步引导（欢迎 → 添加账号 → 权限：通知 + 电池优化白名单）。

> 底部栏**在所有页面常驻**（包括账号管理、账号编辑、缓存页这些非 Tab 页面）。
> 曾经只在 Tab 页面显示，结果用户「配置完账号就找不到回同步页的路」——
> 凡是用户可能「配置完就回主流程」的页面，主流程都必须一步可达。

### 3.2 主流程

```
[首次] 引导 → 添加账号（选平台→填账号→区域→登录校验）→ 回到首页
[日常] 首页 → 选源平台 ⇄ 选目标平台（一键互换）
             ├─ 「一键同步」   → 直接同步全部未同步
             └─ 「选择活动」   → 活动列表（待同步/已同步/已排除；筛选、全选、长按排除）
                              → 勾选后「同步选中 n 条」
       → 同步中（进度卡：x/y、当前活动、已用时、预计剩余；日志可展开；可取消）
       → 结果页（成功 n / 重复 m / 失败 k；失败项可「重试」；查看日志）
[设置] 账号管理 · 同步选项（并发/增量范围/仅WiFi/定时同步）· 本地缓存 · 关于与日志导出
```

### 3.3 关键页面线框

```
┌─ 同步 ────────────────────────────┐   ┌─ 选择活动 ────────────────────────┐
│  源平台            ⇄              │   │ Garmin → Coros      [全选][反选]  │
│  ┌────────┐      ┌────────┐       │   │ 筛选: 近30天 ▾  类型: 全部 ▾      │
│  │ Garmin │      │ Coros  │       │   │ ┌──┬────────────┬─────┬────┬───┐ │
│  │ ✓已连接│      │ ✓已连接│       │   │ │☑ │ 晨跑 10.2km│ 10-06│32:10│   │ │
│  └────────┘      └────────┘       │   │ │☑ │ 骑行 42.1km│ 10-05│1:38│   │ │
│                                   │   │ │☐ │ 夜跑 5.0km │ 10-04│18:02│已同步│
│  待同步 37 条 · 最近 10-06         │   │ └──┴────────────┴─────┴────┴───┘ │
│  ┌─────────────────────────────┐  │   │              [同步选中 2 条]      │
│  │      一键同步全部            │  │   └───────────────────────────────────┘
│  └─────────────────────────────┘  │
│  [ 选择活动同步 ]                  │   ┌─ 同步中 ──────────────────────────┐
│                                   │   │ ▓▓▓▓▓▓░░░░  12/37                 │
│  ── 最近一次 ──                    │   │ 正在同步: 骑行 42.1km             │
│  10-06 21:00  成功 12 / 重复 3     │   │ 已用 01:12 · 预计剩余 02:30        │
│                       [查看详情]   │   │ ▸ 展开日志                        │
└───────────────────────────────────┘   │              [ 取消 ]             │
                                        └───────────────────────────────────┘
┌─ 设置 ▸ 本地缓存 ──────────────────┐
│  共占用 128.4 MB                   │
│  ┌──────────────────────────────┐  │
│  │ 临时 FIT/ZIP 文件   96.2 MB ▸│  │ ← 同步成功后已自动清理，剩余为失败项
│  │ 活动列表缓存        12.1 MB ▸│  │
│  │ 同步日志/历史       20.1 MB ▸│  │
│  ├──────────────────────────────┤  │
│  │ 一键清理安全项（推荐）        │  │
│  │ 清除同步记录 ⚠ (会导致重复同步)│  │ ← 危险操作，二次确认
│  │ 清除账号与登录态 ⚠            │  │
│  └──────────────────────────────┘  │
│  自动清理: 每次同步成功后删除临时文件 ▾│
└────────────────────────────────────┘
```

### 3.4 竞品参考与取舍

| 参考 | 借鉴点 | 取舍 |
|---|---|---|
| **Sync My Tracks** (Android) | 源/目标双选择 + 一键同步 + 逐活动勾选 | 采纳为主交互骨架 |
| **RunGap** (iOS) | 账号卡片 + 同步历史列表 | 采纳账号卡片与历史 |
| **官方 Garmin/Coros App** | 底部 Tab、大按钮、卡片化 | 采纳视觉语言；不抄其社交 |
| **Health Sync** | 同步范围/过滤规则（时间、类型） | 采纳「增量范围」设置 |

**相对源项目 web 交互的优化点**
1. 去掉「用户/策略/子进程」三层概念，2 次点击内可发起同步。
2. 方向选择可互换（⇄），不必先建策略。
3. 同步前可预览 + 勾选（沿用 web 的 select-activities 设计，但改为默认流程而非隐藏入口）。
4. 失败可重试、可取消 —— web 版没有。
5. 缓存可见可控 —— web 版没有。

### 3.5 需求 3：本地缓存清理设计

| 分类 | 内容 | 默认 | 风险 |
|---|---|---|---|
| 临时 FIT/ZIP | `cacheDir/fit-cache/**` | 同步成功后自动删 | 无 |
| 活动列表缓存 | `cached_activities` | 保留 7 天 / 上限 5000 条 | 无（会重新拉取） |
| 同步日志 | `sync_logs` | 保留 30 天 | 无 |
| 同步历史 | `sync_jobs` | 保留最近 100 条 | 无 |
| **同步记录（去重状态）** | `sync_records` | **不自动清** | ⚠ 清除后全部活动会被视为未同步 |
| **账号与凭据** | `accounts` + Keystore | **不自动清** | ⚠ 需重新登录 |

交互要点：设置页显示**分类占用体积**（可下钻到单类清理）、「一键清理安全项」按钮、危险项独立分区 + 输入确认 + 明确后果文案；自动清理策略可选（每次同步后 / 保留 N 天 / 从不）。

---

## 四、里程碑与验收

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M1** | 工程骨架、底部导航、账号管理（加密）+ 本地缓存页 | 模拟器可跑通添加/删除账号、缓存统计与清理 |
| **M2** | Garmin↔Coros 双向打通（含 Garmin SSO、Coros OSS 中转） | 真实账号端到端同步成功，重复活动判定正确 |
| **M3** | 活动选择页、同步进度/日志/取消、失败重试、记录详情 | 断网/取消/重试三条异常路径可用 |
| **M4** | iGPSPORT / OneLap 作为源 | 两平台 → Garmin/Coros 跑通 |
| **M5** | Strava 双向（OAuth2）、WorkManager 定时同步、打磨 | 定时任务在后台按配置触发 |

> 每阶段结束我会给出可安装的 APK 与模拟器截图/录屏供你验收。

---

## 五、决策记录（已确认，2026-10-07）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 平台范围 | **保持原项目全部支持的方向不变**：源 = Garmin / Coros / iGPSPORT / OneLap / Strava，目标 = Garmin / Coros / Strava，共 12 条方向（含 Strava 双向） |
| 2 | 首页形态 | **两者都要**：默认「方向选择器 ⇄ 一键同步」，另提供「保存为常用策略」 |
| 3 | 同步流程 | **默认进入活动选择页**，首页保留「一键同步全部」快捷入口 |
| 4 | 缓存策略 | 同步成功**立即删除**临时 FIT/ZIP；活动列表缓存保留 **7 天**；同步日志保留 **30 天**；历史保留最近 100 条 |
| 5 | Garmin 2FA | 不确定 → **两条路径都实现**：无 MFA 直接通过；`<title>` 含 `MFA` 时弹出验证码输入界面，提交后继续 |

补充确认（按建议执行，未反对即生效）：
- 后台定时同步首版提供开关，**默认关闭**；
- 危险清理项（同步记录 / 账号凭据）**不自动清理**，需二次确认；
- iGPSPORT / OneLap 仅作为源（与原项目一致）。

### 已完成的工程基线

构建环境（全部在工作区内自包含，不污染 `~/.gradle`、`~/.android`）：

| 项 | 值 |
|---|---|
| Gradle | 8.11.1（腾讯镜像下载，`.tools/`） |
| AGP / Kotlin / KSP | 8.10.1 / 2.1.21 / 2.1.21-2.0.1 |
| JDK | Android Studio 内置 JBR 21.0.10 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| 构建入口 | `./build.sh :app:assembleDebug`（自动准备 Gradle、`GRADLE_USER_HOME`、`ANDROID_USER_HOME`） |

首次构建结果：**BUILD SUCCESSFUL**，产出 `app/build/outputs/apk/debug/app-debug.apk`（19 MB，multidex），
已确认 Room 生成的 `*Dao_Impl`、`CryptoBox`、`FitPackaging`、`HttpClients` 等类均进入 dex。


---

## 六、实现进度（滚动更新）

### 已完成

| 模块 | 内容 | 状态 |
|---|---|---|
| 工程基线 | Gradle 8.11.1 / AGP 8.10.1 / Kotlin 2.1.21 / JDK 21 / compileSdk 36；`./build.sh` 自包含构建（工具链、Gradle 缓存、Android 用户目录全在工程内，不污染 `~/.gradle`、`~/.android`） | 构建通过，APK 可安装运行 |
| 凭据安全 | `CryptoBox`：Android Keystore 硬件密钥 + AES-256-GCM，`v1:base64(iv‖cipher‖tag)` 落库；禁用云备份 | 完成 |
| FIT 归一化 | `FitPackaging`：按 `PK\x03\x04` 魔数判断，`ensureRawFit()` / `ensureZip()`；消灭源项目 `.fit`/`.zip` 混用 | 完成，8 项单测 |
| HTTP 层 | OkHttp 双档（控制面 45s / 传输面 10min）+ 共享 CookieJar + 宽松 JSON 导航工具 | 完成 |
| Garmin | SSO（Cloudflare cookie 链）→ ticket → OAuth1（HMAC-SHA1；`login-url` 双编码、`mfa_token` 参与签名）→ OAuth2；MFA 走 UI 弹窗；列表分页 / ZIP 下载 / multipart 上传（202+uploadId、409+Duplicate） | 完成，5 项单测 |
| Coros | MD5 密码登录 → regionId → 列表 / 下载；上传三步链路：STS 临时凭证（训练中心代理 + `CPL-coros-token` Cookie）→ 阿里云 V1 签名或 AWS SigV4 `PUT` 到 `fit_zip/{userId}/{md5}.zip` → `fit/import` 校验 `status==2` | 完成，**2026-10-27 真实账号端到端验证通过**（中国区） |
| 实时体检 | `CorosLiveCheck`（单测源集内）：凭据只从环境变量读，阶段可选（登录 / 取凭证 / 上传对象 / 导入 / 列活动），默认 `a,b,c` 不改账号；Coros 这条通道三周内变了两次，随时可复测 | 完成 |
| iGPSPORT | 双站点（cn/global）token 登录、分页列表、详情 → `fitUrl` 下载 | 完成 |
| OneLap | 明文 HTTP（仅白名单该两域）、MD5 登录、列表 `durl` 下载 | 完成 |
| Strava | OAuth2 令牌刷新、活动列表、`export_original` 下载、`/uploads` 上传并轮询完成 | 完成（授权 UI 待补） |
| 数据层 | Room 七表：`accounts` / `cached_activities` / `sync_records`（源+目标+活动 唯一，带内容指纹）/ `sync_jobs` / `sync_logs` / `exclusions` / `strategies`；v2→v3 **非破坏性迁移**（账号与账本不能被清掉） | 完成，迁移有仪器测试 |
| 同步引擎 | 逐活动隔离（失败不中断整批）、失败可重试、可取消、运行日志落库、临时目录按次隔离 | 完成 |
| 本地历史 | 配置账号后自动全量拉取历史活动落库（`warmUpPlatform`），之后每次同步只做增量；候选集 = 本地库全集；导出同样以本地库为全集 | 完成，2026-10-28 按用户要求补齐 |
| 内容去重 | `ActivityFingerprint`（时间+名称+距离+时长+运动类型的归一化哈希）：跨 activityId 认出同一份数据，已同步的自动跳过；显式勾选也套用，「重新同步已同步项」可绕过 | 完成，17 项新单测 |
| 导航可达 | 底部导航栏**常驻**（非 Tab 页面也显示），同步页「去添加账号」直达账号管理页 —— 修掉「配置完账号回不到同步页」 | 完成 |
| 缓存清理 | 6 个分类 + 安全/危险分区、一键清理安全项、危险项二次确认、成功即删临时文件、日志 30 天 / 历史 100 条；**在用账号的活动列表不再按时间自动清理**（它是增量同步的本地历史） | 完成 |
| UI | 首页方向选择器（5 源 ⇄ 3 目标，可互换）+ 一键同步 + 实时进度/日志/取消 + 结果与重试；活动选择页（勾选、全选/反选、排除/恢复、拉取最新）；记录列表 + 详情日志；设置（后台同步、间隔、Coros 提醒、同步条数）；账号管理 + 表单（分平台区域）；缓存页 | 模拟器逐页验证 |
| 后台同步 | WorkManager 周期任务，默认关闭，联网约束 | 完成 |
| 落盘暂存 | 引擎把下载到的载荷写入 `cache/fit-cache/job-{id}/`，成功即删、失败保留；修正了原先「临时文件分类永远为 0」的空转问题 | 完成，设备实测 450B·1 项 |
| 演练模式 | Debug 专用内置平台（`Platform.DEMO`）：12 条合成活动、确定性失败与重复，无需账号/网络即可跑通全流程；Release 构建在 UI 中隐藏 | 完成，设备端跑通 |
| 后台保活 | `SyncService` 前台服务（`dataSync` 类型）渲染进度通知；后台 Worker 升级为 `setForeground()` 以突破 WorkManager 10 分钟上限；首次同步前请求通知权限 | 完成，设备实测 |
| 方向记忆 | 源/目标选择与策略切换后写入设置，重启不再回落到默认方向 | 完成，设备实测 |
| 仪器测试 | `CacheManagerTest`（8 项）+ `ActivityExporterTest`（2 项）+ `SyncEngineTest`（4 项）+ `AppDatabaseMigrationTest`（1 项，验证 v2→v3 迁移不丢账号与账本），跑在真机/模拟器上：保留策略、危险分类需 `force`、一键清理不动账本、MediaStore 写入与读回 | 完成，模拟器通过 |
| 方向矩阵校验 | `SyncDirectionMatrixTest`（8 项）把「5 源 × 3 目标 − 3 个自环 = 12 条」与上游 12 个 `<src>_sync_<dst>.py` 脚本逐一对应，少一条就编译失败 | 完成 |
| **离线导出** | `ActivityExporter`：API 29+ 走 MediaStore 落到真正的 `下载/SportFitSync`（无需存储权限），更低版本回落到应用外部目录；文件名清洗非法字符与空白；`ExportEngine` 复用同步的进度/日志/历史管线，伪目标 `local-files` | 完成，设备上产出真实文件 |
| 常用策略 | Room `strategies` 表（方向去重、可命名、单一后台方向）、首页卡片一键切换/设为后台/删除，后台 Worker 优先使用标记的方向 | 完成，模拟器验证持久化 |
| Strava 授权 | `StravaOAuth` + 内嵌 WebView 拦截 `http://localhost/exchange_token` 回调，换码后加密存 token；账号页提供「授权」入口 | 完成（需用户自建 Strava 应用） |
| 单元测试 | **78 项全绿**，覆盖 FIT/ZIP 归一化、RFC3986 编码与 HMAC、Garmin 签名基串（含双编码回归）、时间解析、Strava OAuth 回调、同步候选选择（失败可重试等 7 条规则 + 指纹去重 5 条 + 处理量上限 3 条）、内容指纹 9 条、Coros STS 通道选择与凭证解码（12 项）、实时体检入口 `CorosLiveCheck`（无凭据时自动跳过）；其中一项抓出真实缺陷：`oauth_signature` 未写入 Authorization 头（会导致 Garmin 恒定 401） | 完成 |

### 真实平台联调进展

**已打通**：Garmin 真实账号的**登录态复用 + 活动列表**。实测拉到 **200 条真实活动**
（`杭州市 公路骑行` 112.3 km、`Thio 虚拟骑行` 等，名称/时间/距离均正确），
说明 `GarthTokens` 持久化、`GarminApi` 分页与 JSON 解析、以及整条选取链路可用。

**已验证登录**：Coros 真实账号登录成功（`userId`/`regionId=2` 中国区）。

**已打通到最后一跳**：Garmin FIT 真实下载成功（613,433 字节的 112 km 骑行文件），
FIT 归一化与暂存正常，链路（当时）止步于 Coros 取 OSS 凭证。

**外部阻塞已解除并端到端验证（2026-10-27 真实账号）**：Coros 曾下线匿名 v1 OSS STS 接口，
现已定位到替代通道 —— 网页版训练中心的 BFF 代理
`GET {trainingcn|training}.coros.com/api/proxy/oss/sts?bucket=&service=&v=2`，
鉴权用 Cookie `CPL-coros-token=<登录 accessToken>`（详见
[`platform-protocols.md`](platform-protocols.md) 与上游 issue #41 / PR #42）。
本项目已改为走该通道，旧匿名接口仅作回退。

真实账号（regionId=2 中国区）实测四段全绿：登录 → **STS 返回 200 + 阿里云形态凭证**
（`AccessKeyId` 29 字符 / `AccessKeySecret` 44 / `SecurityToken` 876）→ **OSS PUT 成功**
→ **`fit/import` 返回 `result=0000` 且 `status=2`**（`CorosTarget.upload()` 返回 `Success`），
账号里新增了一条活动。**国际 / 欧洲区（regionId 1/3，AWS bucket）仍未验证**。

**新发现**：Coros **不按内容去重** —— 重新导入账号里已有的活动会生成副本；且每次打包 ZIP
的 md5 都不同（ZIP 含时间戳），所以「同一活动重复同步」= 新对象 + 新活动。
**去重依赖本项目账本 `sync_records`**（上游靠 SQLite `is_sync_coros` 标志位，作用相同）。

**已打通（2026-10-07 真机实测）**：**Coros → Garmin 端到端成功**
（下载 12,709 字节 FIT → 上传 Garmin → 账本置为已同步）。

**仍未验证**：Strava 的全部链路、iGPSPORT / OneLap 的真实拉取、
`*→coros` 的国际/欧洲区（AWS）路径。

**真实账号测试还暴露并修复了 3 个演练平台无法发现的缺陷**（Coros 业务错误码、
下载缺 `sportType`、方向无法对调），详见
[`development-handbook.md`](development-handbook.md) §13.3。

这不是可以靠写更多代码解决的事，需要真实账号：

| 平台 | 需要 | 注意 |
|---|---|---|
| Garmin | 邮箱 + 密码 +（国际区/国区？是否开两步验证） | 国区必须选 garmin.cn |
| Coros | 邮箱 + 密码 | **登录会挤掉手机上的 Coros App** |
| Strava | 自建 API 应用的 Client ID / Secret | 回调域填 localhost |

其余功能（编排、账本、缓存、归一化、导出、后台保活）都已用内置演练平台在设备上端到端验证过。

### 未做（非需求内，按需再加）

- 通知栏的「取消」按钮（当前通知只展示进度）
- 导出时附带 CSV 元数据清单
- 多方向并发与限速设置（当前串行，对平台最友好）

### 设备端端到端演练（无需真实账号）

用内置演练平台跑完整流程，方向「演练模式 → 演练模式」，并对拍设备数据库（含 WAL）：

| 证据 | 实测值 | 说明 |
|---|---|---|
| 任务 1 | `PARTIAL` total=12 · ok=7 · dup=4 · fail=1 | 与界面「成功 7 / 重复 4 / 失败 1」一致 |
| 任务 2（重试失败项） | `FAILED` **total=1** · fail=1 | 只重跑失败项，不是全量重来 |
| `demo-5` 账本行 | status=2 · **attempts=2** | 失败可重试，且尝试次数被正确累计（源项目此处是永久死角） |
| 账本汇总 | status=1 → 11 条，status=2 → 1 条 | 去重状态正确 |
| 活动缓存 | 12 条 | |
| 同步日志 | INFO 38 / SUCCESS 11 / ERROR 2 / WARN 2 | |
| 临时文件缓存 | 450 B · 1 项 | 11 个成功的即时删除，仅保留失败项作为排查证据 |
| ZIP→FIT 归一化 | 全流程通过 | Garmin 式 ZIP 被正确解包；否则演练目标会报「FIT 归一化失败」 |

### 后台保活实测

| 检查项 | 结果 |
|---|---|
| 前台服务 | `ServiceRecord{… sync.SyncService}` · `isForeground=true` · `foregroundId=41` · 通知频道 `sync-status` |
| 切后台后仍完成 | 同步中按 HOME 回到桌面，25 秒后设备数据库新增 `job 3`（`FAILED` total=1）—— 任务完整跑完，未被系统回收 |
| 前台服务启动异常 | 无 `ForegroundServiceStartNotAllowed` 等错误 |
| 方向记忆 | 选择「演练模式 → 演练模式」后杀进程重启，方向仍然保持 |

### 缓存需求逐条验证

| 需求 | 验证方式 | 结果 |
|---|---|---|
| 分类统计 | 设备运行后查看缓存页 | 临时文件 / 活动缓存 / 日志 / 历史 / 排除名单 / 去重记录 六类，各自字节与条目数 |
| 一键清理安全项 | `CacheManagerTest.clearing_all_safe_categories_leaves_the_ledger_intact` | 活动、日志、历史清空，**账本保留 1 条**（不会触发重复上传） |
| 危险项二次确认 | 仪器测试 + 设备 UI 实测 | `clear()` 无 `force` 返回 false 且不改数据；UI 弹出确认框并说明重复上传后果 |
| 成功即删临时文件 | 设备实测 | 12 条活动的同步中，11 条成功即时删除，仅保留 1 条失败证据（450 B · 1 项） |
| 活动缓存保留 7 天 | `CacheManagerTest.retention_keeps_recent_activities_and_drops_ones_past_seven_days` | 1 天前的保留，8 天前的删除 |
| 日志保留 30 天 | `CacheManagerTest.retention_keeps_recent_logs_and_drops_ones_past_thirty_days` | 1 天前的保留，31 天前的删除 |
| 策略可配置 | `retention_honours_a_changed_policy` / `retention_prunes_job_history_to_the_configured_limit` | 窗口放宽后不误删；历史上限生效 |

### 离线导出（不依赖任何账号的退路）

目标平台的导入接口一旦变更或失效，用户仍需要能把数据取出来 —— 导出的 `.fit` 文件可被这个领域里所有工具读取。

设备实测产出的文件：

```
/sdcard/Download/SportFitSync/
  2024-05-01_晨跑_测试_demo-1.fit   64 B     ← 中文名保留，"/" 被清洗为 "_"
  test-1791344495585.fit           512 B     ← 仪器测试写入并读回校验字节一致
```

| 验证 | 方式 |
|---|---|
| 文件名清洗 | `ActivityExporterNamingTest` 5 项（非法字符、空白折叠、空名回落、长度上限） |
| MediaStore 写入 + 读回 | `ActivityExporterTest` 2 项（真机，字节级比对） |
| 无权限 | 使用 `MediaStore.Downloads` + `IS_PENDING`，未申请任何存储权限 |

选择页「导出文件」的**完整点击链路已在模拟器上跑通**（不再是人工猜坐标）：

```
拉取最新列表 → 共 12 条 · 可同步 12 条
全选          → 已选 12 条
导出文件      → /sdcard/Download/SportFitSync/ 下生成 12 个文件
```

```
2026-10-06_演练活动 1_demo-1.fit    2090 B
2026-10-05_演练活动 2_demo-2.fit    2090 B
...
2026-09-25_演练活动 12_demo-12.fit  2097 B
```

文件大小差异（2090 与 2097）恰好等于活动 ID 长度差异 —— 说明写出的确实是**解包后的裸 FIT**，`ensureRawFit` 在导出路径同样生效。

> 工具补充：`tools/uitap.py` 通过 `uiautomator` 无障碍树按**文本**定位控件，取代按像素猜坐标。Compose 布局一变，像素脚本就失效，这是本项目 UI 验证反复失败的根因。

> 本轮由该工具暴露并修复的真实缺陷：`SyncEngine.refreshSourceActivities` 漏了演练平台的账号特判（另外两处有），导致「拉取最新列表」对演练模式直接报「未配置账号」——同一逻辑散落三处就会这样。

### 验证证据

- `./build.sh :app:assembleDebug` → BUILD SUCCESSFUL，APK 约 19.6 MB
- `./build.sh :app:testDebugUnitTest` → **48 tests, 0 failures**（8 个测试类）
- `./build.sh :app:connectedDebugAndroidTest` → **10 tests, 0 failures**（Pixel_7_Pro API 33）
- 模拟器 Pixel_7_Pro (API 33) 逐页验证：首页/设置/缓存/账号/账号表单/策略保存与重启后持久化/活动选择页，截图见 `docs/screenshots/`
- 设备数据库抽查：`sync_jobs` / `sync_records` / `cached_activities` / `sync_logs` 与界面显示完全一致
- 主代码 56 个 Kotlin 文件，测试 6 个文件（35 项）
- 设备端 JVM 之外的验证：前台服务存活、通知发布、切后台完成同步、方向持久化、数据库账本一致性

> 注：拉取设备数据库时需同时取 `-wal` 与 `-shm`，Room 默认 WAL 模式；只取 `.db` 会得到过期快照。
