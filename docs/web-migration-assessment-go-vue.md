# SportFit Sync 迁移到 Web（Go + Vue）+ 多用户管理：技术评估

> **技术栈（已确定，不再作为开放选项）**
> 后端：Go 1.26（单静态二进制 + PostgreSQL）
> 前端：Vue 3 + TypeScript + Vite + Pinia + Vue Router + Tailwind CSS v4
>
> **与既有文档的关系**
> `docs/web-migration-assessment.md` 是第一轮评估（后端推荐 Kotlin/JVM + Ktor，前端推荐 React/TS）。
> 本文档**取代其中的选型部分**（第 7 章、第 9 章路线图中的栈相关内容），并补充了 Go/Vue 栈独有的
> 保真度风险与工作量修订。第 1–6、8、10、11 章的现状盘点仍以那份文档为准。
>
> **代码基线**：`main` @ `d4832c7` · 评估日期 2026-11-01
>
> **本文档性质**：评估，不含生产代码改动。

---

## 0. 结论摘要

### 0.1 总体判断

**Go + Vue 是可辩护的选型，评估结论是「可以做，但要按 Go 的方式来写，不能按 Kotlin 的方式来翻」。**

三个核心结论：

1. **Vue 几乎免费。** 相对 React，Vue 在本项目上是**中性偏优**：工作量偏差在 ±2 人日以内，且 Vue 的响应式模型
   与本应用的状态形态（单一大 ViewModel、约 20 处 snackbar 通道、方向切换时重新派生）契合度更高。
   **不需要讨论**。

2. **Go 的代价是真实的，但买到的东西更值钱。** Go 放弃了 Android 侧可逐字复用的 5,639 行协议/领域逻辑
   （复用率 43% → **0%**），同时放弃了 **136 个测试 / 291 条断言**这套回归网。净增工作量约 **+30 人日（+20%）**。
   但换来：单静态二进制（无 JRE、无 Gradle）、每并发用户约 10–30 KB 工作集（对比 JVM 的 15–25 MB/进程）、
   `context` 原生取消、goroutine 天然适配「多用户多任务并发」。
   **在一个必须长期托管多个用户的 Web 产品里，这是划算的交换。**

3. **最大的风险不是「Go 能不能写」，而是「翻译时会悄悄改变语义」。**
   我用本机的 Go 1.26.2 + Java 13 + kotlinx-serialization 1.8.1 **实测**了 Kotlin 与 Go 的行为差异，
   确认了 **6 处会静默改变结果**的差异（见 §3）。其中 JSON 数值/布尔强制转换、
   用户的时区、OkHttp 与 Go 的 Cookie 头语义三项，**不写测试根本发现不了**。

### 0.2 栈变更的净影响（一页看懂）

| 维度 | Kotlin + React（第一轮推荐） | Go + Vue（本文档） | 变化 |
|---|---|---|---|
| 可逐字复用的后端代码 | 5,639 LOC（43%） | **0 LOC（0%）** | ▼ 全部重写 |
| 可逐字复用的前端代码 | 0 LOC（44% UI 必须重写） | 0 LOC | — 持平 |
| 现有测试资产 | 136 测试 / 291 断言全部可用 | **全部需重写为 Go 测试**（约 1:1） | ▼ 约 12–18 人日 |
| 部署形态 | JVM + Gradle，镜像 ~250 MB | 单静态二进制，scratch/distroless ~20 MB | ▲ 明显改善 |
| 每并发用户内存 | 15–25 MB（每进程） | 10–30 KB + 8 KB 栈 | ▲ 数量级改善 |
| 取消语义 | 需显式 `Call.cancel()`（现代码**没做**） | `context` 原生 | ▲ 改善 |
| 并发模型 | 协程，需要理解结构化并发 | goroutine + ctx | △ 需换心智模型 |
| 后端工作量 | 35–52 人日 | **79–124 人日** | ▼ +44~+72 |
| 前端工作量 | 45–70 人日 | **47–58 人日** | △ ±2 |
| **总计** | **97–152 人日** | **126–182 人日** | ▼ **+30 人日（+20%）** |

### 0.3 工作量修订（详细）

**后端（Go）**

| 工作包 | 人日 | 说明 |
|---|---|---|
| 协议与核心层重写 | 28–42 | `platform/` 3,070 + `core/` 1,283 + `domain/` 256 = 4,609 行 |
| 同步/导出引擎 + 数据层 | 14–22 | `sync/` 1,263 行 + 58 个 `@Query` → sqlc |
| 服务端骨架 + 多用户模型 + 鉴权 | 12–18 | 4 张表加 `user_id`、会话、RBAC |
| 凭据信封加密 + 重新授权向导 | 6–10 | KMS 选型是前置的产品决策 |
| 调度 + 任务租约 + SSE + 导出制品 | 10–15 | 替代 WorkManager |
| 部署/CI/可观测/限流 | 6–9 | |
| Outbase 服务端登录（视探测结果） | 3–8 | 条件项，见 V1 |
| **小计** | **79–124** | |

**前端（Vue）**

| 工作包 | 人日 |
|---|---|
| 基础层（令牌 + 25 个组件 + Pinia + 路由 + API 客户端 + a11y + 响应式 + i18n 抽取 + 错误边界 + SSE） | 15–18 |
| 10 屏 1:1 功能对等 | 14–17 |
| 授权流程 + MFA 往返 + 导出下载 + 通知 | 4–6 |
| 多用户增量（登录/注册、账号对选择器、每用户策略与设置） | 14–17 |
| **小计** | **47–58** |

> **误差说明**：主导不确定性不是 Go 语法难度，而是 ① Outbase 服务端登录是否可行（±5 人日）；
> ② 七个平台的手写 JSON 解析器在 Kotlin 的宽松语义下从未被测试覆盖（§3.2），Go 重写时若不补
> golden-response 测试，缺陷会以「静默解析失败」的形式漏到线上（±8 人日）。

### 0.4 仍需你决策的事项

第一轮的 D1–D5 全部继续适用（Android 端去留、多用户形态、Strava 应用归属、部署区域、运营方托管全部用户凭据是否可接受）。
Go/Vue 的选型**没有消解任何一项**，只是把第 5 项（凭据托管）从「Android Keystore 无法导出」
变成了「服务端 KMS + 加密存储」——**技术路径更清晰了，但信任问题原样存在**。

新增一项：

> **D6 — Go 协议层的实现纪律。** 本文 §3 列出的 6 处静默语义差异，只有在「**先移植测试、再写实现**」
> 的纪律下才能被拦住。若允许「边写边测」，建议至少先建立每平台一份的 golden-response 响应样本集，
> 否则 Go 重写会把 Kotlin 时代「靠宽松解析兜住」的平台响应漂移问题固化成线上故障。

---

## 1. 栈变更的影响面

### 1.1 后端复用率：43% → 0%

第一轮的核心发现是：`platform/`（3,070 行，**零 `android.*` 依赖**）、`domain/`（256 行）、
`core/` 中的大部分（1,283 行，仅 `CryptoBox` 受限）、`sync/`（1,263 行，仅 `SyncService` 受限）
合计 **5,639 行**，只依赖 OkHttp + kotlinx.serialization + `java.*`，**在 Kotlin/JVM 后端可以近乎逐字复用**。

换成 Go，这 5,639 行的复用价值**归零**：

| 原模块 | LOC | Go 下的处置 | 净变化 |
|---|---|---|---|
| `domain/` | 256 | 重写 | 枚举→字符串枚举+映射；sealed interface→带 tag 的 struct；`SyncAbort`→类型化 error |
| `platform/` | 3,070 | 重写 | 接口→Go interface；`suspend`→channel/ctx；7 个平台客户端逐个重写 |
| `core/` | 1,283 | 重写 | `crypto/md5`+`archive/zip`+`mime/multipart` 进标准库是加分；但宽松 JSON、RFC3986 编码、MIME Base64 需要手写 |
| `sync/` | 1,263 | 重写 | `channelFlow`→channel；`NonCancellable`→`context.WithoutCancel`；Room→SQL |
| **合计** | **5,639** | | **+0 行复用** |

**但有个反向的账**：Go 的标准库直接覆盖了 Kotlin 侧需要自己写的部分——
`crypto/md5`（`Hashing.kt` 的 `ReusableDigest` ThreadLocal 优化可整段删除）、
`archive/zip`（`FitPackaging.kt`）、`mime/multipart`（三个平台的表单上传）、
`net/url` + `net/http`。同时 Go 侧不需要 `Dispatchers.IO` 这一层概念，
34 个文件里约 40 处 `withContext(Dispatchers.IO)` 直接消失。

> **净判断**：Go 重写后的**代码量**预计约 5,500–6,500 行 Go（比 Kotlin 的 4,924 行代码多 12–30%，
> 主要是显式错误处理和 struct 定义），但**概念层更少**（少一个 IO 调度器、少一个 JSON 序列化框架、
> 少一个 HTTP 客户端框架）。工作量净增来自「**没有编译器帮你检查 7 个平台的响应结构**」。

### 1.2 前端 React → Vue：几乎免费

| 维度 | 结论 |
|---|---|
| 组件模型 | 1:1。25 个 Compose 组件 → 25 个 Vue SFC，`Modifier` 链 → CSS 类 |
| 状态管理 | 1:1。单个 `AppViewModel`（872 行）→ 5 个 Pinia store + 1 个屏幕级 store |
| 导航 | 1:1。10 条路由 + 底栏语义（§6.2）|
| 数据获取 | Vue 缺 `useAsyncData`/`useFetch`（那是 Nuxt 的）→ 用 `@tanstack/vue-query` 或手写 composable |
| 虚拟列表 | 无缺口（`@tanstack/vue-virtual`）|
| 无障碍组件库 | `reka-ui`（Radix Vue 后继）覆盖 Dialog/Switch/Checkbox/Slider |
| 生态差距 | 实际用到的面很窄（1 个虚拟列表、1 个滑块、1 个开关、1 个复选框、1 族对话框、1 个 chip、1 个 toast）→ **不构成进度风险** |

**唯一需要设计决策的**：UI 基础库三选一。

| 方案 | 成本 | 权衡 |
|---|---|---|
| **(A) 手写 7 个原语** | ~3.5 人日 | 完全可控，但焦点陷阱和 ARIA 要自己实现（最大的隐藏成本）|
| **(B) `reka-ui` + `headlessui`** | ~1.5 人日 + 1.5 人日视觉层 | **推荐**：焦点陷阱/ARIA/键盘免费，样式自己写以对齐 66 个 M3 角色令牌 |
| **(C) Vuetify / PrimeVue / Naive** | ~1.0 人日 + 2.5 人日覆盖 | 最快出活，但引入第二套设计系统，与现有令牌体系长期打架 |

**推荐 (B)**。

### 1.3 Go 的代价 vs 收益（诚实版）

**收益（对本项目具体成立）**

1. **单静态二进制**：4,924 行业务逻辑 + 数据层 + 7 个平台客户端 → 一个文件，无 JVM / Gradle / Android SDK。
   构建 3 秒 vs 2–5 分钟；镜像用 `distroless` 约 20 MB。
2. **每并发用户的内存**：JVM + Room + OkHttp 每进程 15–25 MB RSS；Go 每请求工作集约 10–30 KB 堆 + 8 KB goroutine 栈。
   **一个二进制服务 500 个并发用户 ≈ 40–80 MB RSS**，对比 500× JVM 进程。
3. **取消终于能用**：现 Kotlin 代码从未调用 OkHttp 的 `Call.cancel()`，取消同步后下载仍在继续。
   Go 的 `net/http` 在 `ctx.Done()` 时**真的中止在途请求**。
4. **`context.WithoutCancel` ≡ `withContext(NonCancellable)`**（Go 1.21+），1:1 对应 5 处非取消收尾块。
5. **`database/sql` + `pgx` + `sqlc`**：58 个 `@Query` 变成带**构建期 SQL 校验**的类型化生成代码
   （Room 的注解处理器没有这个能力），并彻底去掉 Room/Android-SQLite 依赖——**这本来就是 Web 产品的拦路虎**。
6. **`go test` 约 50 ms**（JVM 预热 1–3 s），默认并行。

**代价**

1. **kotlinx-serialization 的宽松语义没有等价物**（§3.2）——**唯一真正不可表达的一个 flag**。
2. **没有 OkHttp 的拦截器模型**：`RoundTripper` 在传输层之下，且 Go 的 `http.Transport` **根本不发送 User-Agent**。
3. **逐请求的请求头覆盖语义不同**（§3.5）——OkHttp 替换 Cookie 头，Go 追加。
4. **进程级单例必须改成按用户隔离**（§2.5）——这是架构变更，不是移植细节。
5. **没有 `SimpleDateFormat`**：Go 的 `time.Parse` 严格、不前缀匹配、不滚动非法日期（§3.9）。
6. **`TimeZone.getDefault()` 在服务端没有对应物**——服务端只有一个时区，用户各有各的（§3.4，**P0**）。
7. **没有内置 SigV4**：手写约 60 行（与现状相同），或引入 `aws-sdk-go-v2`（Kotlin 侧注释已因体积问题拒绝）。
8. **JSON 数字类型**：`json.Unmarshal` 默认把 `1234567890123`（epoch-ms ID）变成 `1.234567890123e+12`——
   **静默精度丢失**，Kotlin 侧免疫（§3.3）。
9. **没有一等公民的结构化并发**：`errgroup.WithContext` 在**第一个错误时取消整组**，
   与本引擎「逐条隔离、继续跑」的刻意设计**完全相反**（§3.6，**P0**）。
10. **kotlinx 代码生成丢失**：6 个 DTO 文件（`GarminTokens.kt:12-27`、`CorosClient.kt:301-318`、
    `StravaGateway.kt:34-44`、`IgpsportGateway.kt:54-68`、`OutbaseClient.kt:299-305`、`OnelapGateway.kt:28-29`）
    变成手写 struct + json tag（+约 80 行），并**失去编译期 schema 校验**。
11. **Android Keystore 没有 Go 等价物**（§2.7）——**产品决策，不是移植任务**。
12. **MediaStore 没有 Go 等价物** → 签名下载 URL 或服务端临时目录。
13. **Go 的 `%.2f` 是 round-half-even**（Java 是 HALF_UP），用户可见数字末位会变（§3.10）。

---

## 2. Go 后端架构

### 2.1 目标目录结构

```
backend/
├── cmd/sfsync/main.go              # 单二进制入口；配置、优雅退出、DB 迁移
├── internal/
│   ├── config/                     # 环境变量 → 结构体；时区、区域、UA 覆盖
│   ├── domain/                     # ← domain/ 256 行重写；账户作用域的 SyncDirection
│   │   ├── models.go               #   枚举、Platform、SyncDirection（**加 user_id 维度**）
│   │   ├── fingerprint.go          #   ← ActivityFingerprint，字节级必须一致
│   │   └── errors.go               #   ← PlatformException / SyncAbort → 类型化 error
│   ├── platform/                   # ← platform/ 3,070 行重写
│   │   ├── platform.go             #   Gateway 接口、MfaProvider、PlatformContext
│   │   ├── registry.go
│   │   ├── garmin/  coros/  strava/  igpsport/  onelap/  outbase/  demo/
│   ├── protocol/                   # ← core/ 1,283 行重写
│   │   ├── jsonx/                  #   ★ 宽松 JSON 访问器（§3.2），全项目的地基
│   │   ├── hashing.go              #   md5/sha256/hmac/percentEncode/base64
│   │   ├── timeparse.go            #   ★ 带时区参数的宽松时间解析
│   │   ├── cookiejar.go            #   每用户 CookieJar 注册表
│   │   ├── httpclient.go           #   api / transfer / corosSts 三客户端
│   │   ├── fitpkg/                 #   zip/fit 打包
│   │   └── oss/                    #   阿里云 V1 + AWS SigV4 + Coros STS
│   ├── sync/                       # ← sync/ 重写；**串行**引擎
│   │   ├── engine.go  planner.go  export.go  progress.go
│   ├── store/                      # ← data/ + cache/ 重写
│   │   ├── postgres/               #   pgx + sqlc 生成代码
│   │   └── queries/                #   *.sql 源文件（sqlc）
│   ├── crypto/                     # ← CryptoBox 重写：信封加密
│   ├── jobs/                       #   调度器、任务租约、cron
│   ├── api/                        # REST + SSE
│   │   ├── http/                   #   路由、中间件
│   │   ├── auth/                   #   登录、会话、CSRF
│   │   └── sse/                    #   事件总线（按用户扇出）
│   └── web/                        # 嵌入的前端静态资源（或独立部署）
├── db/
│   ├── migrations/                 # ← 4 张表加 user_id 的迁移
│   └── queries/                    # sqlc 源
└── web/                            # Vue 3 前端
```

### 2.2 依赖选型

| 用途 | 选型 | 理由 |
|---|---|---|
| HTTP 服务 | 标准库 `net/http` | Go 1.22+ 的路由增强已够用；少一个依赖 |
| 路由 | `chi` 或 `gorilla/mux` | `chi` 更轻；**或**标准库 + 自写 mux |
| DB 驱动 | `pgx/v5` | 性能与 `pgxpool` |
| SQL | **`sqlc`** | 类型化 + **构建期校验 SQL**；比 ORM 更贴现有 DAO 写法 |
| 迁移 | `golang-migrate` 或 `goose` | |
| 加密 | 标准库 `crypto/aes` + `crypto/argon2` | 信封加密：数据密钥由主密钥派生 |
| 密码哈希 | `golang.org/x/crypto/argon2` | |
| 会话 | 自写（签名 cookie + DB 存会话）| 或 `gorilla/sessions` |
| SSE | 标准库 `net/http` + `http.Flusher` | |
| 测试 | 标准库 `testing` + `net/http/httptest` | |

**刻意不引入**：AWS SDK（体积，与 Kotlin 侧拒绝的理由一致）、Bun/GORM（ORM 会掩盖 SQL 校验价值）、
`tidwall/gjson`（会掩盖 §3.2 的宽松语义问题——宁可显式写清楚）。

> ⚠️ **本机 Go 环境的说明**：评估机上安装的 Go 1.26.2 **缺少** `archive/zip`、`net/http/cookiejar`、
> `net/http/httptest` 三个标准库包（`go list std` 报错）。这是**该机器上 Go 安装被裁剪**的环境问题，
> **不是 Go 的限制**——标准 Go 发行版包含这三包。文档中关于 `cookiejar` 的结论来自同版本源码的行为分析，
> 落地时需在完整 Go 环境复验。

### 2.3 并发模型：串行是特性，不是疏漏

**已确认**：同步引擎在设计上是**逐条串行**的。

- `SyncEngine.kt:263` — `candidates.forEachIndexed { … }`，生产协程内的普通顺序循环。
  无 `async`、无 `launch`、无 `awaitAll`、无 `flatMapMerge`。
- 每条活动的 body 顺序 `await source.download(...)` → `await target.upload(...)`（`:269`、`:279`）。
- `ExportEngine.kt:109` 同样是顺序 `forEachIndexed`。
- `SyncPlanner.plan`（`:115`）顺序遍历。
- 整个业务层唯一的并发是 `channelFlow` 的生产者/消费者分离和 `Dispatchers.IO` 线程切换——**都不并行处理活动**。

**这在 README:536 有明确说明**：串行对平台更友好（避免触发限流、避免同时打多个平台）。

> ⚠️ **Go 移植中最危险的一个「顺手优化」**：有人会本能地改成 `errgroup` + `semaphore` 并发。
> 这会改变 ①事件顺序 ②对 7 个限流平台的并发压力 ③账本 `attempts` 的读-改-写（`:470`、`:489`）产生数据竞争。
> 此外 **`errgroup.WithContext` 在第一个错误时取消整组**，与本引擎「逐条隔离、继续跑」的设计**正好相反**——
> 用错心智模型会让**应用最核心的特性（部分失败不中断）退化**。
>
> **规则：单次运行内保持单 goroutine + 顺序循环。`errgroup` 只用于「多用户多任务」的横向扇出。**

### 2.4 取消与收尾语义

| Kotlin | Go | 注意 |
|---|---|---|
| `CancellationException` | `context.Canceled` / `context.DeadlineExceeded` | Go 的 `*url.Error` 实现 `Unwrap`，`errors.Is` 能穿透 ✅ |
| `withContext(NonCancellable)` | `context.WithoutCancel`（Go 1.21+） | **精确 1:1**：不被父取消，但**保留父的 values**（租户/logger 仍可解析）|
| `channelFlow` + `send()` 背压 | buffered channel(64) + `select` | ⚠️ 裸 `events <- ev` 在消费者消失时**永久阻塞**；必须用 `select` |
| `Dispatchers.IO` / `flowOn` | **无等价物，也不需要** | Go 的调度器会把 goroutine 停在阻塞的 socket 读上。**不要自建 IO 线程池** |
| `catch (error: Exception)` | `if err != nil` | ⚠️ **Go 不捕获 panic**——见 §3.11 |

**收尾块（`SyncEngine.kt:351-391`）的 Go 形态**

```go
defer func() {                                   // 必须覆盖每一条路径
    cleanupCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 30*time.Second)
    defer cancel()
    finalizeJobTemp(cleanupCtx, jobID, keepEvidence)
    applyRetention(cleanupCtx)
}()
```

> ⚠️ `WithoutCancel` 同时**丢掉了 deadline**——DB 不可达时收尾写会永久挂住。**必须再套 `WithTimeout`。**
> ⚠️ Go 的 `defer` 在 **panic 时也会执行**——若在 goroutine 边界做 `recover()`，必须**刻意安排**
> recover-defer 与清理-defer 的先后顺序。

### 2.5 每用户隔离（架构变更，不是移植细节）

三个**进程级单例**在服务端会直接造成跨用户泄漏：

| 原单例 | 位置 | 服务端必须改成 |
|---|---|---|
| `HttpClients.cookieJar` | `core/net/HttpClients.kt:18` | **每用户/每会话**一个 `http.CookieJar`（按账户 ID 注册表）|
| `MfaCoordinator.deferred` | `platform/MfaCoordinator.kt:24` | **每运行**一个 pending channel（配合 SSE）|
| `SyncProgressBus` | `sync/SyncProgressBus.kt:28` | **每会话**的事件注册表 |

**CookieJar 的一个意外好消息**：`net/http/cookiejar` 的行为**严格优于** Kotlin 侧的手写 jar。
Kotlin 的 `InMemoryCookieJar` 按**设置方主机**分桶（`:22-23`），所以由 `sso.garmin.com` 设置、
`Domain=.garmin.com` 的 cookie 会落在 `sso.garmin.com` 桶里，而对 `connectapi.garmin.com` 的请求
只会查 `connectapi.garmin.com` / `garmin.com` / `com` 三桶 → **永远找不到**。
Go 标准库按 cookie 的**生效域**分桶 → 直接可用。**不要手写。**

> ⚠️ `cookiejar.New(nil)` **不带公共后缀表** → 会接受 `Domain=com` 并把该 cookie 发给任意 `*.com` 主机。
> 严格模式需传 `golang.org/x/net/publicsuffix.List`（一个小依赖）。仅用标准库可接受，但保留了这个坑。
> ⚠️ 标准库 `jar` 没有 `clear()` → 换自己的锁整体替换 Jar，或按用户持有 Jar 后直接丢弃。

### 2.6 数据访问层

`Daos.kt` 的 **58 个 `@Query`** → sqlc 的 `.sql` 源文件 + 生成代码。
**Room 没有任何 SQL 校验**，sqlc 有——这是净收益。

**4 张表必须加 `user_id` 并重建主键**（`data/local/Entities.kt`）：

| 表 | 现主键 | 改为 | 风险 |
|---|---|---|---|
| `accounts` | `platform`（`:17`）| `(user_id, platform)` | 一个平台只能绑一个账号 → 多用户下必须放开 |
| `cached_activities` | `(platform, activity_id)`（`:42`）| `(user_id, platform, activity_id)` | 缓存跨用户可见 |
| `sync_records` | `(source, target, activity_id)`（`:88`）| `(user_id, source, target, activity_id)` | ★ **最高危**：指纹去重集按 `(source,target)` 计算（`SyncEngine.kt:188-198`）→ 用户 B 的上传会被用户 A 的账本行**静默抑制** |
| `exclusions` | `(source, target, activity_id)`（`:145`）| 同上 | 排除名单跨用户污染 |

另需：`sync_jobs` / `sync_logs` / `strategies` 加 `user_id`。

**顺带修掉两个既有缺陷**：
- `cached_activities` 缺 `(platform, startTime)` 索引（`Daos.kt:56-64`）——列表按时间排序，建议补 `(user_id, platform, start_time)`。
- `AppDatabase.kt:53` 的 `fallbackToDestructiveMigration(dropAllTables = true)` 与其自身注释（`:36-41`）矛盾。
  在服务端这更危险——**上线即丢数据**。生产库必须关闭。

### 2.7 凭据加密（不可回避的产品决策）

`core/crypto/CryptoBox.kt` 用的是 **Android Keystore 不可导出的设备绑定密钥**（`:26-31, :40, :48-62`），
密文格式 `"v1:" + base64(iv[12]‖ct‖tag[16])`。

**后果**：Android 端已存的凭据在服务端**无法解密** → **所有平台必须重新授权**。
这不是技术障碍，是**产品事实**，必须在对外沟通中说清楚。

服务端方案（二选一，需决策）：

| 方案 | 说明 |
|---|---|
| **信封加密** | 每个用户一个数据密钥（DEK），DEK 由主密钥（KMS / env）包裹存储；凭据用 DEK 加密。推荐：支持密钥轮换 |
| **派生密钥** | 用 Argon2id 从「用户口令 + 全局 pepper」派生 DEK，不落盘。代价：改口令需重新加密全部凭据 |

> ⚠️ 第一轮文档第 10 章提到上游 Flask admin 存在「明文口令经环境变量传递」的缺陷（`docs/design-and-ux-proposal.md:112`）——
> **不要复制这个模式**。

---

## 3. Go 移植保真度地雷（实测）

### 3.1 实测方法

**不靠推测**：用本机的 **Go 1.26.2**、**Java 13**、**kotlinx-serialization 1.8.1**（仓库 `.gradle-home` 缓存里的真实版本）
写了两支对拍程序，跑同一组输入，比较输出。

- Kotlin 侧：`KotlinProbe.java`（用 `JsonElementKt` 的 `getLongOrNull`/`getDoubleOrNull`/`getBooleanOrNull`/`getContentOrNull`
  直接调用 kotlinx 的扩展属性）+ `SimpleDateFormat` + `java.util.Base64`。
- Go 侧：`go_probe.go`（`encoding/json` + `time` + `encoding/base64` + `net/url`）。

**实测结论（Kotlin 侧原始输出）**：

```
=== kotlinx JSON 强制转换 ===
number 123        long=123    double=123.0  bool=null   content=123
number 1.5        long=null   double=1.5    bool=null   content=1.5
number 1e3        long=1000   double=1000.0 bool=null   content=1e3
string "123"      long=123    double=123.0  bool=null   content=123
string "1.5"      long=null   double=1.5    bool=null   content=1.5
string "true"     long=null   double=null   bool=true   content=true
string "TRUE"     long=null   double=null   bool=true   content=TRUE
string "1"        long=1      double=1.0    bool=null   content=1
bool true         long=null   double=null   bool=true   content=true
null              long=null   double=null   bool=null   content=null

=== SimpleDateFormat ===
parse('2026-06-01 10:00:00') 用 yyyy-MM-dd -> 成功（前缀匹配！）
parse('2026-02-30')                      -> 成功（滚动到 3/2）

=== Base64 MIME 解码器 ===
getMimeDecoder('YWJj\nZGVm') -> 'abcdef'   （忽略换行）
getMimeDecoder('YWJj ZGVm')  -> 'abcdef'   （忽略空格）

=== 大小写与编码 ===
String.format("%02X", 255)          -> FF      （大写！）
URLEncoder.encode("a b~*")           -> a+b%7E* （~ 被转义，空格→+）
String.format("%.2f", 1.005)         -> 1.01    （HALF_UP）
```

**Go 侧实测输出**：

```
{"v":1e3}   goType=json.Number  naiveLong=ParseInt->fail  kotlinLong->(1000,true)
=== time.Parse 严格性 ===
parse(2006-01-02, 2026-06-01 10:00:00) -> ERROR    （Kotlin 成功）
parse(2006-01-02, 2026-02-30)          -> ERROR    （Kotlin 成功）
=== base64 ===
StdEncoding("YWJj\nZGVm") -> "abcdef"  err=false   （换行 OK）
StdEncoding("YWJj ZGVm")  -> "abc"      err=true    （空格报错！Kotlin 成功）
=== 编码 ===
hex.EncodeToString      -> ff0a7e      （小写；Kotlin %02X -> FF）
url.QueryEscape("a b~*") -> a+b~%2A     （~ 不转义、* 转义）
percentEncode("a b~*")   -> a%20b~%2A   （RFC3986 正确）
=== 时间 ===
time.RFC1123 (UTC) -> Thu, 01 Jan 1970 00:00:00 UTC   ← OSS 要的是 GMT
=== 浮点 ===
Go %.2f of 1.005 -> 1.00   （Java 给 1.01）
Go %.2f of 2.675 -> 2.67   （Java 给 2.68）
=== bool ===
content="1"  kotlin->(false,false)  strconv.ParseBool->(true,err=false)   <<< 不一致
content="0"  kotlin->(false,false)  strconv.ParseBool->(false,err=false)  <<< 不一致
```

### 3.2 【P0】JSON 数值/布尔强制转换 —— 静默改变结果

**Kotlin 侧语义（实测）**：

| 输入 | `longOrNull` | `doubleOrNull` | `booleanOrNull` | `content` |
|---|---|---|---|---|
| 数字 `123` | 123 | 123.0 | null | `"123"` |
| 数字 `1.5` | **null** | 1.5 | null | `"1.5"` |
| 数字 `1e3` | **1000** | 1000.0 | null | `"1e3"` |
| 字符串 `"123"` | **123** | 123.0 | null | `"123"` |
| 字符串 `"1.5"` | null | 1.5 | null | `"1.5"` |
| 字符串 `"true"` | null | null | true | `"true"` |
| 字符串 `"TRUE"` | null | null | **true**（大小写不敏感） | `"TRUE"` |
| 字符串 `"1"` | **1** | 1.0 | **null** | `"1"` |
| 布尔 `true` | null | null | true | `"true"` |

**Go 的两个陷阱**：

1. **`strconv.ParseInt("1e3")` 失败** → Kotlin 给 1000，朴素 Go 移植给「无值」。
   正确做法：先 `ParseInt`，失败再 `ParseFloat` + 整数性检查（`f == math.Trunc(f)`）。
2. **`strconv.ParseBool("1")` 返回 true**，Kotlin 的 `booleanOrNull("1")` 返回 **null**。
   这条最危险——**Kotlin 认为「不是布尔」→ 走 false 分支；Go 认为是 true → 走 true 分支**，
   行为完全相反且无任何报错。

**Go 参考实现（可直接采用，放在 `internal/protocol/jsonx/`）**：

```go
// decodeJSON 用 UseNumber 保留原始字面量，使 str() 的行为与 kotlinx 的 content 一致。
func decodeJSON(body string) (any, error) {
	dec := json.NewDecoder(strings.NewReader(body))
	dec.UseNumber()                    // ← 全项目的地基，缺了会静默丢精度
	var v any
	if err := dec.Decode(&v); err != nil {
		return nil, err
	}
	return v, nil
}

// str 等价于 JsonElement.str()：字符串→原值；数字→原始字面量；布尔→"true"/"false"；
// null/对象/数组→不存在。kotlinx 对非原始类型返回 null。
func str(v any) (string, bool) {
	switch t := v.(type) {
	case string:
		return t, true
	case json.Number:
		return t.String(), true
	case bool:
		return strconv.FormatBool(t), true
	}
	return "", false
}

// long 等价于 JsonPrimitive.longOrNull()：
//   数字 123 ✓、1e3 → 1000 ✓、1.5 ✗；数字字符串 "123"/"1" ✓；"1e3" ✗
func long(v any) (int64, bool) {
	switch t := v.(type) {
	case json.Number:
		if n, err := strconv.ParseInt(t.String(), 10, 64); err == nil {
			return n, true
		}
		f, err := strconv.ParseFloat(t.String(), 64)
		if err != nil || f != math.Trunc(f) {
			return 0, false
		}
		return int64(f), true
	case string:
		n, err := strconv.ParseInt(strings.TrimSpace(t), 10, 64)
		if err != nil {
			return 0, false
		}
		return n, true
	}
	return 0, false
}

// boolean 等价于 JsonPrimitive.booleanOrNull()：接受 "true"/"false"（大小写不敏感），
// 拒绝 "1"/"0"。⚠️ 不要用 strconv.ParseBool——它接受 "1"/"t"/"T"，与 kotlinx 不一致。
func boolean(v any) (bool, bool) {
	switch t := v.(type) {
	case bool:
		return t, true
	case string:
		switch strings.ToLower(t) {
		case "true":
			return true, true
		case "false":
			return false, true
		}
	}
	return false, false
}
```

> ⚠️ **全局规则**：所有访问器返回 `(T, bool)` / `(T, error)`，**绝不返回裸零值**。
> 这既是对 kotlinx 可空访问器的地道翻译，也直接消灭了 §3.13 那类 null/empty 混淆。

**`AppJson` 四个 flag 的可表达性**（`HttpExtensions.kt:15-20`）：

| flag | Go |
|---|---|
| `ignoreUnknownKeys = true` | ✅ 天然满足（`encoding/json` 总是忽略未知字段）|
| `coerceInputValues = true` | ✅ 天然满足（缺省/null → 零值，不报错，语义相同）|
| `explicitNulls = false` | ✅ 约等于 struct tag 上的 `omitempty`（仅影响编码）|
| **`isLenient = true`** | ❌ **不可表达**——Go 拒绝无引号的键、单引号字符串、无引号字面量 |

**这是唯一一个真正缺失的 flag，也是最大的未测风险**：7 个平台的手写解析器全都依赖宽松语义，
**现有测试一条都没覆盖**（见 §5.2 第 6 项）。
→ **必须为每个平台建一份 golden-response 样本集**（把真实的脱敏响应存成 `.json` fixture，
断言解析结果），这是 Go 重写里性价比最高的一项预防性投入。

### 3.3 【P0】JSON 数字的 float64 精度陷阱

Go 的 `json.Unmarshal` 到 `any` 会把所有数字变成 `float64`。
`1234567890123`（epoch 毫秒 ID）会变成 `1.234567890123e+12`——**精度已经开始丢失**，
再经 `int64(...)` 转换后就是**错的 ID**。Kotlin 侧免疫（`JsonPrimitive.content` 是原始字面量）。

→ **每一个解码器都要 `dec.UseNumber()`**。这是 Go 移植里最容易漏、后果最严重的一处。

### 3.4 【P0】用户时区：服务端只有一个时区，用户各有各的

`core/util/TimeParsing.kt:41-42` 的注释明确写着：**平台返回的时间戳是「用户所在时区的挂钟时间」**。
代码用 `TimeZone.getDefault()`（`:43`），在 Android 上 = 设备时区。

服务端部署后，`time.Local` = **服务器时区** → 每条活动时间都会被平移服务器的 UTC 偏移。

> ⚠️ 这是**静默**的：所有时间都「看起来对」，只是整体偏了。

**必须做的**：
1. 每个账户存一个 **IANA 时区**（`Europe/Shanghai`、`America/New_York`…）。
2. `parseFlexible(value string, loc *time.Location)` 显式传时区，**不依赖 `time.Local`**。
3. 容器里 `import _ "time/tzdata"`（内嵌约 450 KB 的时区库），否则 `time.LoadLocation` 会失败。
4. `time.Local` 只作为单用户部署的兜底。

**测试影响**：`TimeParsingTest.kt:36-39` 和 `OutbaseClientTest.kt:82-88` 用了 `Calendar.getInstance()`
（即 JVM 默认时区）——**在 Go 测试里是潜在 flaky**。Go 版必须显式钉死时区
（推荐测试用 UTC，生产用账户时区）。

### 3.5 【P0】Cookie 头：OkHttp 替换，Go 追加

**这是实测确认的运行时语义差异，不是文档推测。**

- **OkHttp** 的 `BridgeInterceptor` 在 jar 有 cookie 时执行 `requestBuilder.header("Cookie", jarCookies)`，
  即**替换**掉手工设置的 `Cookie` 头。仓库自己在 `HttpClients.kt:68-74` 记录了这件事，
  并且因此把 `corosSts` 客户端配成 `CookieJar.NO_COOKIES`。
- **Go** 的 `http.Client.send` 调用 `req.AddCookie(...)`，即**追加**到已有的 `Cookie` 头后面。
  实测服务端看到的是 `"handset=H; jarcookie=J"`。

**受影响调用点**：
- `platform/onelap/OnelapGateway.kt:77` — 手工设置 `Cookie`
- `platform/coros/CorosStsProvider.kt:222` — 手工设置 `Cookie: CPL-coros-token=…`

> **规则**：**任何需要手工设置 `Cookie` 的请求，都必须用 `Jar: nil` 的独立客户端**。
> 不要依赖共享 jar——两个运行时的行为不同。

**连带**：`corosSts` 客户端在 Go 里就是 `&http.Client{Transport: tr, Timeout: 45*time.Second, Jar: nil}`——**更简单**，
因为 Go 不会像 OkHttp 那样「偷偷覆盖」。

### 3.6 【P0】`errgroup` 会毁掉逐条隔离

`SyncEngine.kt:311-325` 的逐条 try/catch 是**应用的核心特性**：一条活动失败，其余继续。

Go 的 `errgroup.WithContext` **在第一个错误时取消整个组** → 整次运行中断 → **特性退化**。
更隐蔽的是 `errgroup` 的零值 `Group` 不会取消，但配合 `WithContext` 就会。

**规则**：
```go
// ✅ 正确：单 goroutine + 顺序循环
for i, activity := range candidates {
    payload, err := src.Download(ctx, activity)
    if err != nil {
        if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
            return err          // ← 取消不是「单条失败」，必须向上抛
        }
        fail++
        markFailed(ctx, activity, errMessage(err))
        emit(SyncEvent{Outcome: UploadOutcome{Kind: Failed, Reason: errMessage(err)}})
        continue               // ← 逐条隔离
    }
    ...
}

// ❌ 错误：第一个错误就整组取消
g, gctx := errgroup.WithContext(ctx)
for _, a := range candidates { g.Go(func() error { ... }) }
```

> ⚠️ Kotlin 的 `catch (error: Exception)` **不捕获 `Error`**；Go 的 `err != nil` **不捕获 panic**。
> 见 §3.11。

### 3.7 【P1】`percentEncode`：两个现成函数都不对

`core/net/Hashing.kt:90-102` 实现的是 RFC 3986 未保留字符集 `A-Za-z0-9-._~`，且用 **`%02X`（大写）**。

实测：
- Java `URLEncoder.encode("a b~*")` → `a+b%7E*`（`~` 被转义、空格→`+`）
- Go `url.QueryEscape("a b~*")` → `a+b~%2A`（`~` 不转义、`*` 被转义、空格→`+`）
- Go `url.PathEscape("a b~*")` → 同样错（`:` 会原样保留 → `https:%2F%2F…`）

**两个都不等于 RFC 3986**。且**大写/小写十六进制在签名场景是致命的**：
Garmin OAuth1 与 AWS SigV4 需要大写 `%XX`，而摘要类用小写。`HashingTest.kt` 里两种大小写都有断言
（`:26-33` 大写、`:39-40` 小写）——**统一成一种就会挂测试**。

**Go 实现**：
```go
const hexUpper = "0123456789ABCDEF"

// PercentEncode 实现 RFC 3986（未保留字符 A-Za-z0-9-._~），十六进制大写。
func PercentEncode(value string) string {
	var b strings.Builder
	b.Grow(len(value) * 3)
	for _, c := range []byte(value) {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
			c == '-' || c == '_' || c == '.' || c == '~' {
			b.WriteByte(c)
			continue
		}
		b.WriteByte('%')
		b.WriteByte(hexUpper[c>>4])
		b.WriteByte(hexUpper[c&0x0F])
	}
	return b.String()
}
```

> ⚠️ `Hashing.kt:105` 的 `urlEncodeQuery` → `url.QueryEscape` 是可接受的（仅用于日志展示，`:104`），
> 但要注意它与 Java `URLEncoder` 也不等价（`~` 的处理不同）——**只影响日志文本**。

### 3.8 【P1】十六进制大小写

| 用途 | Kotlin | Go | 规则 |
|---|---|---|---|
| `md5Hex` / `sha256Hex` | 小写（`HEX_DIGITS`，`Hashing.kt:66`）| `hex.EncodeToString` 小写 | ✅ 直接匹配 |
| `percentEncode` | **大写**（`%02X`，`Hashing.kt:98`）| 需 `strings.ToUpper` | ⚠️ 手写实现（§3.7）|
| OSS `Content-MD5` / SigV4 签名 | 小写（`OssUploaders.kt:331`）| `hex.EncodeToString` 小写 | ✅ 匹配 |
| `GarminOAuth1.kt:114` / `OutbaseClient.kt:124` | 小写（`%02x`）| 小写 | ✅ 匹配 |

**规则：写两个不同的辅助函数，别图省事统一。**

### 3.9 【P1】Base64：MIME 解码器 vs 标准库

`Hashing.kt:38-39` 用的是 `java.util.Base64.getMimeDecoder()`——**忽略所有非字母表字符**。
实测：Java 接受 `YWJj\nZGVm` **和** `YWJj ZGVm`（含空格）。

Go 的 `base64.StdEncoding`：
- `YWJj\nZGVm` → **成功**（Go 的解码器本来就会跳过 `\r\n`）
- `YWJj ZGVm` → **失败**（空格报错）

`decodeCorosStsCredentials`（`Hashing.kt:114-115`）在服务端 STS 响应里解码凭据——
**如果 STS 返回的 base64 带空格或换行，朴素 Go 移植会直接失败**。

```go
// Base64Decode 复现 java.util.Base64.getMimeDecoder() 的容错：跳过所有空白。
func Base64Decode(s string) ([]byte, error) {
	var b strings.Builder
	for _, r := range s {
		switch r {
		case '\r', '\n', ' ', '\t':
			continue
		}
		b.WriteRune(r)
	}
	out, err := base64.StdEncoding.DecodeString(b.String())
	if err != nil {
		// Java 的 MIME 解码器容忍缺失的填充
		return base64.RawStdEncoding.DecodeString(strings.TrimRight(b.String(), "="))
	}
	return out, nil
}
```

### 3.10 【P1】RFC1123 的 GMT vs UTC

`OssUploaders.kt:153-155` 用 `SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)` + `TimeZone.getTimeZone("GMT")`。
Java 的 `'GMT'` 是**字面量**，所以输出恒为 `GMT`。

Go 的 `time.RFC1123` + `time.UTC` 输出 **`UTC`**（实测确认）→ **签名直接失效**。

```go
// ✅ 与 Java 字节级一致
date := time.Now().UTC().Format("Mon, 02 Jan 2006 15:04:05 GMT")
// ❌ 会输出 "UTC"
date := time.Now().UTC().Format(time.RFC1123)
```

`amzDate`（`OssUploaders.kt:296-297`，`SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)`）：
```go
amzDate := time.Now().UTC().Format("20060102T150405Z")   // ✅ 与 Java 一致
```
（Go 的 `Z` 只有在布局是 `Z07:00`/`Z0700` 时才是时区标记，裸 `Z` 是字面量——这里正好符合需要。）

### 3.11 【P1】时间解析的严格性差异

`core/util/TimeParsing.kt:17-26` 的 8 个 `SimpleDateFormat` 模式 → Go 布局：

| Java | Go |
|---|---|
| `yyyy-MM-dd'T'HH:mm:ss.SSSXXX` | `2006-01-02T15:04:05.000Z07:00` |
| `yyyy-MM-dd'T'HH:mm:ssXXX` | `2006-01-02T15:04:05Z07:00` |
| `yyyy-MM-dd'T'HH:mm:ss'Z'` | `2006-01-02T15:04:05Z` |
| `yyyy-MM-dd'T'HH:mm:ss` | `2006-01-02T15:04:05` |
| `yyyy-MM-dd HH:mm:ss` | `2006-01-02 15:04:05` |
| `yyyy-MM-dd HH:mm` | `2006-01-02 15:04` |
| `yyyy/MM/dd HH:mm:ss` | `2006/01/02 15:04:05` |
| `yyyy-MM-dd` | `2006-01-02` |

**两个差异（实测）**：
1. **前缀匹配**：Java 的 `parse("2026-06-01 10:00:00")` 用 `yyyy-MM-dd` 模式**成功**；Go 的 `time.Parse` **报错**。
2. **宽松滚动**：Java 的 `parse("2026-02-30")` **成功**（滚到 3/2）；Go **报错**。

**建议：用严格解析（`time.Parse`）**。理由：
- 8 个模式按顺序尝试，`yyyy-MM-dd HH:mm:ss` 在 `yyyy-MM-dd` **之前**，所有真实平台载荷
  （Garmin 的 ISO+偏移、iGPSPORT/OneLap 的 `yyyy-MM-dd HH:mm:ss`、Coros 的 epoch）都能正确命中；
- **没有任何一个现有测试锁定宽松行为**（`TimeParsingTest.kt` 只测 4 个用例，不含前缀匹配和滚动）。

若将来遇到确实需要宽松的载荷，再手写约 40 行的宽松解析器。

**Epoch 判定**（`TimeParsing.kt:33-36`：全数字且长度 ≥ 9 → 秒，≥ 12 → 毫秒）：
```go
if len(value) >= 9 && allASCIIDigits(value) {
	n, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return 0, false          // Kotlin toLongOrNull() 溢出返回 null
	}
	if len(value) >= 12 {
		return n, true
	}
	return n * 1000, true
}
```
> ⚠️ `allASCIIDigits` 必须是**纯 ASCII**判断。

### 3.12 【P1】浮点舍入

Java `String.format("%.2f", 1.005)` → `1.01`（HALF_UP）；Go `fmt.Sprintf("%.2f", 1.005)` → `1.00`（round-half-even，且 1.005 的二进制值实际是 1.00499…）。
实测 `2.675`：Java `2.68`，Go `2.67`。

**影响面很小**：
- `cache/CacheManager.kt:208-216` 的 `formatBytes` 是用户可见的缓存容量文本 → 末位可能差 0.01。
- 其余是内部计算。

**修法**：`math.Round(x*100)/100` 后再格式化。

> 顺带：Java 的 `Math.round(x)` = `floor(x+0.5)`；Go 的 `math.Round` 是**四舍五入远离零**，
> 对负数半值不同（`-2.5`：Java → -2，`math.Round` → -3）。`ActivityFingerprint.kt` 用了 `Math.round`，
> **必须用 `math.Floor(v+0.5)`** 才能字节级一致——指纹是**与服务端账本的线协议**。

### 3.13 【P1】panic 会杀掉整个服务

Kotlin 的 `catch (error: Exception)` **不捕获 `Error`**（`OutOfMemoryError` 等）；
Go 的 `err != nil` **不捕获 panic**。→ 一个平台的响应触发 panic ⇒ **整个 Go 进程崩溃**。

**这是 Android 端不存在的失败类别**（Android 上进程挂掉只是 App 崩溃）。

**必须做**：在每条活动的 goroutine 边界 `recover()`：
```go
func runActivity(...) (outcome UploadOutcome, err error) {
	defer func() {
		if r := recover(); r != nil {
			outcome = UploadOutcome{Kind: Failed, Reason: fmt.Sprintf("内部错误: %v", r)}
			err = nil                      // panic 降级为「单条失败」，不中断整次运行
		}
	}()
	...
}
```
> ⚠️ 注意 `defer` 的注册顺序：recover-defer 必须在清理-defer **之后**注册，
> 这样 panic 时清理逻辑看到的仍是「已标记为失败」的一致状态。

### 3.14 【P2】杂项速查

| 项 | Kotlin | Go | 处置 |
|---|---|---|---|
| **重定向上限** | OkHttp 20 跳 | 默认 10 跳后报错 | 显式设 `CheckRedirect` |
| **`?:` 的空值语义** | `a ?: b` 只在 **null** 时回退 | `if s == ""` 会把空串也回退 | ⚠️ `CorosStsProvider.kt:293-299` 的字段名回退、`IgpsportOssProvider.kt:121` 的 `bucketName`→`bucket` 都要用 **nil 检查** |
| **UTF-16 截断** | `String.take(40)` 按 **UTF-16 码元** | `s[:40]` 按**字节** | ⚠️ `export/ActivityExporterNaming.kt` 的文件名会截断 CJK → 用 `[]rune(s)[:40]` |
| **UUID** | `UUID.randomUUID()` = v4 | `crypto/rand` 拼装 | ⚠️ `OutbaseClient.kt:97-103` 用 UUID 前 4 位十六进制做分片 → 必须保持 v4 的均匀性 |
| **User-Agent** | OkHttp 自动发 `okhttp/4.x`；另有硬编码的安卓 UA（`HttpClients.kt:84-86`）| **Go 的 `http.Transport` 根本不发送 User-Agent** | ⚠️ 必须显式设置。Coros/iGPSPORT/OneLap 登录**依赖**那个安卓 UA 字符串 → 保留为默认值但做成可配置。**Garmin 的 API 调用不要带 UA**（`GarminApi.kt:53-58`）|
| **`ByteArray.equals`** | 值相等 | Go 切片 `==` 是**引用相等** | ⚠️ 用 `bytes.Equal`（`FitPackaging.kt:24-31`）|
| **`substringBeforeLast`** | 无点号时返回整串；`"42."` → `"42"` | `strings.TrimSuffix` 语义不同 | ⚠️ `FitPackaging.kt:117-119` 用 `LastIndex` 语义实现 |
| **URL 解码** | `URLDecoder.decode` 把 `+`→空格 | `url.PathUnescape` **不**处理 `+` | ⚠️ 用 `url.QueryUnescape`（`GarminOAuth1Test.kt:47,51`）|
| **查询参数排序** | 稳定排序 | `sort.Slice` **不稳定** | ⚠️ 用 `sort.SliceStable`（Garmin OAuth1 签名参数顺序敏感）|
| **`zip` 字节** | Java deflate | Go deflate **字节不同** | ⚠️ 自洽即可（PUT 的、签名的、告知 MD5 的是同一份）。但**不要**断言跨实现的 ZIP 字节 |
| **ZIP 条目顺序** | Java 按 local header 顺序 | Go 按 central directory 顺序 | ⚠️ 多条目时不同；现有测试不覆盖 |
| **日志脱敏** | `HttpLoggingInterceptor` 在 `BASIC` 级别**根本不打印请求头** | — | ⚠️ **修正第一轮文档的判断**：脱敏清单目前是**潜在**而非活跃缺口；但一旦调高日志级别就会泄漏。Go 侧**始终**脱敏，并补上 `Sessionid`、`x-oss-security-token`、`x-amz-security-token` |
| **查询串泄漏** | 两边都不脱敏 query | 同 | ⚠️ Coros 旧通道把 `sign=…` 放 query、Strava 回调把 `code=…` 放 query → **只记录 `URL.Path`** |

---

## 4. Go 移植难度表（逐文件）

> 仓库实际有 **34** 个源文件（4,924 行代码）、**15** 个测试文件（2,150 行）。
> 预估 Go 侧约 **5,500 行**（+12%）。

### 4.1 核心原语 `core/`

| 文件 | 难度 | 关键点 | 预估 Go LOC |
|---|---|---|---|
| `core/net/Hashing.kt` (116) | **S** | 全标准库；`ReusableDigest` ThreadLocal 可删；需手写 `percentEncode` + 容错 Base64 | 130 |
| `core/net/HttpExtensions.kt` (84) | **M** | ★ 宽松 JSON 访问器（§3.2），全项目地基 | 140 |
| `core/net/HttpClients.kt` (87) | **S** | 4 客户端 → `http.Client`；`noRedirect()` **是死代码**（仅定义处 1 处命中）→ 删 | 120 |
| `core/net/InMemoryCookieJar.kt` (50) | **S** | 用标准库 `cookiejar`（**比手写的更正确**）；但必须**按用户隔离** | 55 |
| `core/util/TimeParsing.kt` (53) | **M** | 8 个模式 → Go 布局；★ 时区参数化 | 90 |
| `core/format/FitPackaging.kt` (123) | **S** | `archive/zip`；`bytes.Equal` 替代 `ByteArray.equals` | 110 |
| `core/crypto/CryptoBox.kt` (103) | **XL** | ★ Android Keystore 无等价物 → 需 KMS + 迁移故事 | 120 + KMS |
| `core/oss/OssUploaders.kt` (364) | **L** | 阿里云 V1 + AWS SigV4 手写；RFC1123 GMT；amzDate；virtual→path 回退 | 300 |
| `core/oss/CorosStsProvider.kt` (303) | **M** | 双通道 STS；加盐解码；3 组字段名变体（nil 语义！）| 200 |

### 4.2 领域与平台契约

| 文件 | 难度 | 关键点 | 预估 Go LOC |
|---|---|---|---|
| `domain/Models.kt` (131) | **S** | 枚举→字符串枚举；sealed→tagged struct；`SyncAbort`→类型化 error；★ `SyncDirection` 要加账户维度 | 200 |
| `domain/ActivityFingerprint.kt` (125) | **S** | 纯字符串拼接 + SHA-256；★ **字节级必须一致**；`Math.round`→`math.Floor(v+0.5)` | 70 |
| `platform/Platform.kt` (104) | **M** | 接口；`PlatformException.Auth/Network/Protocol`→类型化 error + `errors.As`；`suspend`→channel/ctx | 150 |
| `platform/PlatformRegistry.kt` (32) | **S** | `when`→map/switch | 30 |
| `platform/MfaCoordinator.kt` (42) | **S** | `CompletableDeferred`→`chan string`/ctx | 60 |

### 4.3 七个平台实现

| 文件 | 难度 | 关键点 | 预估 Go LOC |
|---|---|---|---|
| `garmin/GarminAuth.kt` (345) | **XL** | SSO cookie 走查 + HTML 抓取 + MFA 挂起往返 + token 交换；协程→Go 形态变化最深 | 380 |
| `garmin/GarminOAuth1.kt` (116) | **M** | OAuth1 HMAC-SHA1、**双重百分号编码**、oauthlib 参数顺序；Go 无 OAuth1 库 → 手写 | 130 |
| `garmin/GarminApi.kt` (192) | **M** | 分页 + multipart；★ Garmin 的 part 用 `text/plain`，Go 的 `CreateFormFile` 硬编码 `application/octet-stream` | 200 |
| `garmin/GarminTokens.kt` (51) | **S** | `@Serializable` data class → struct + tag | 50 |
| `garmin/GarminGateway.kt` (63) | **S** | | 70 |
| `igpsport/IgpsportGateway.kt` (457) | **L** | client+gateway+target 三合一；multipart + OSS + notify；★ **先判状态再读 body**（`:390-398`）| 420 |
| `igpsport/IgpsportOssProvider.kt` (148) | **S** | `bucketName`→`bucket` 回退是 **null 语义**不是空串语义 | 140 |
| `coros/CorosClient.kt` (318) | **M** | JSON 分页；业务码 `result != "0000"`；OSS import；MD5 口令 | 320 |
| `coros/CorosGateway.kt` (130) | **M** | OSS→import 两步；兜底 catch-all → `Failed` | 130 |
| `strava/StravaGateway.kt` (249) | **M** | OAuth2 refresh + 异步上传轮询（`delay(3000)` × 30）| 260 |
| `strava/StravaOAuth.kt` (103) | **S** | ★ `authorizeUrl` 是**裸拼接**（`:34-41`）→ **不要「顺手修正」**，测试断言了字面量 | 90 |
| `onelap/OnelapGateway.kt` (161) | **M** | 明文 HTTP；★ 手工设置 `Cookie`（`:77`）→ 需 `Jar: nil` 客户端 | 160 |
| `outbase/OutbaseClient.kt` (305) | **M** | 两步 CDN 上传 + 注册 + 业务码分类；`Calendar`→`time.Location` | 280 |
| `outbase/OutbaseAuth.kt` (43) | **S** | Cookie 头解析 | 40 |
| `outbase/OutbaseGateway.kt` (89) | **S** | | 90 |
| `demo/DemoGateway.kt` (122) | **S** | `delay(150)` → `ctx` 定时器。★ **必须保留 `ctx.log` 的形状**——它是「Flow invariant is violated」那个 bug 的回归防线（`:26-33`）| 120 |

### 4.4 同步引擎

| 文件 | 难度 | 关键点 | 预估 Go LOC |
|---|---|---|---|
| `sync/SyncEngine.kt` (646) | **XL** | `channelFlow` + 3 处 `NonCancellable` + 逐条隔离 + Room DAO→SQL；★ Flow→channel 是最高风险处 | 450 |
| `sync/ExportEngine.kt` (207) | **M** | 同构但无账本；`MediaStore`→签名 URL / 临时目录 | 260 |
| `sync/SyncPlanner.kt` (240) | **S** | 纯函数；⚠️ `selectedIds: Set<String>?` 的 **nil vs 空集**区分（Go map 抹掉了它）| 170 |
| `sync/SyncProgressBus.kt` (40) | **S** | 进程单例 → **每会话**注册表 | 50 |

---

## 5. 测试资产：136 测试 / 291 断言

### 5.1 逐文件可移植性

| # | 文件（行数 / 测试数） | 结论 | JVM 依赖的断言 | 预估 Go LOC |
|---|---|---|---|---|
| 1 | `core/net/HashingTest.kt` (60/6) | ✅ 直接搬 | 无（全是跨语言向量，Go 侧实测 md5/sha256/hmacSha1 一致）| 55 |
| 2 | `core/format/FitPackagingTest.kt` (90/8) | ✅ 直接搬 | ⚠️ 从不比对 ZIP 字节（只比条目名和抽出的内容）→ Go 的 deflate 差异不可见 | 100 |
| 3 | `core/oss/CorosStsProviderTest.kt` (184/12) | ✅ 直接搬 | ⚠️ `StsResult` sealed → `(creds, reason, ok)`；中文错误文案用 `contains` 断言 → **必须逐字一致** | 190 |
| 4 | `core/oss/OssEndpointTest.kt` (105/11) | ✅ 直接搬 | ⚠️ `assertThrows(IllegalArgumentException)` → `errors.Is`；⚠️ scheme 正则不能用 `url.Parse` 单独做（`url.Parse("oss-cn-beijing.aliyuncs.com")` 会解析成 path）| 110 |
| 5 | `core/util/TimeParsingTest.kt` (47/4) | ⚠️ 最 JVM 依赖 | ⚠️ `Calendar.getInstance()` = JVM 默认时区（`:36-39`）→ Go 必须显式钉时区；`GMT+08:00` → `time.FixedZone` | 70 |
| 6 | `domain/ActivityFingerprintTest.kt` (267/12) | ✅ 直接搬 | ⚠️ 全是相等/不等断言、无 golden hex → 自洽即可；⚠️ `Math.round` 负数半值与 `math.Round` 不同 | 250 |
| 7 | `domain/SyncDirectionMatrixTest.kt` (186/8) | ✅ 直接搬 | ⚠️ `Platform.entries` → slice；21/25/4 三个计数必须精确复现；缺失 gateway 要返回 **error** 而非 nil | 190 |
| 8 | `export/ActivityExporterNamingTest.kt` (48/5) | ✅ 直接搬（仅命名）| ⚠️ `.take(40)` 按 UTF-16 码元 vs Go 字节 → **必须加一个 CJK 用例**（现有测试是 ASCII，测不出来）；`name.length == 46` 要改成 rune 计数 | 60 |
| 9 | `HashPerfTest.kt` (70/3) | ⚠️ 阈值型 | ⚠️ `perCallMicros < 20.0` → Go 更快，同样阈值通过但信号变弱；「digest 复用不污染」在 Go 里**变成空测试** → 改成 `go test -bench` | 60 或 bench 文件 |
| 10 | `platform/coros/CorosLiveCheck.kt` (179/1) | ✅ 直接搬 | ⚠️ `runBlocking`→普通测试函数；CRC16 ARC（poly 0xA001）→ `crc16.MakeTable(crc16.ARC)` 等价；UUID 必须 v4 | 190 |
| 11 | `platform/garmin/GarminOAuth1Test.kt` (116/5) | ✅ 直接搬 | ⚠️ `URLDecoder` → `url.QueryUnescape`；⚠️ `url.encodedPath` → `u.EscapedPath()`；⚠️ 排序必须**稳定**；`HEADER_ORDER` 是固定表不是字母序 | 130 |
| 12 | `platform/igpsport/IgpsportOssProviderTest.kt` (130/7) | ✅ 直接搬 | ⚠️ 5 处 `assertThrows(PlatformException.Protocol)` → `errors.As` 辅助函数；中文文案逐字 | 140 |
| 13 | `platform/outbase/OutbaseClientTest.kt` (195/14) | ✅ 直接搬 | ⚠️ `Calendar.getInstance()`（`:82-88`）= **潜在 flaky**，Go 里钉死；`classifyRegister` 抛 `PlatformException.Auth` → Go 返回 `(outcome, error)` | 210 |
| 14 | `platform/strava/StravaOAuthTest.kt` (54/5) | ✅ 直接搬 | ⚠️ `authorizeUrl` 裸拼接 → **不要**换成 `url.Values.Encode()`；`extractCode` 手工 split → **不要**换成 `url.ParseQuery`（后者会解码 `+` 且对坏转义报错）| 60 |
| 15 | `sync/SyncPlannerTest.kt` (419/24) | ✅ 直接搬 | ⚠️ **`selectedIds: Set<String>?`** —— Kotlin 区分 `null`（未选择）与 `emptySet()`（明确空选）；Go 的 nil map 与空 map 都是 `len()==0` → 必须用指针或独立 `HasSelection bool`，否则「明确选了零个」会静默变成「没选」 | 430 |

**合计约 2,250 行 Go**（对比 Kotlin 2,150 行，≈1:1）。

### 5.2 JVM 特有行为清单（必须逐条处理）

| # | JVM 行为 | 触点 | Go 处置 |
|---|---|---|---|
| 1 | `SimpleDateFormat` + `TimeZone.getDefault()` | `TimeParsingTest.kt:36-39`、`OutbaseClientTest.kt:82-88` | 显式 `*time.Location` 参数；测试钉死时区 |
| 2 | `SimpleDateFormat` 宽松滚动（`2026-02-30`→3/2）| **无测试锁定**——仅生产行为 | 严格 `time.Parse`（安全）|
| 3 | `SimpleDateFormat` 前缀匹配 | **无测试锁定** | 严格 `time.Parse`（安全）|
| 4 | `SimpleDateFormat` `Locale.US` | `OssUploaders.kt:154,296`——**线上可见**，非测试可见 | Go 布局与 locale 无关 ✅ |
| 5 | `java.util.Base64` MIME 容错 | `Hashing.kt:39` | 解码前剥离空白（§3.9）|
| 6 | **kotlinx JSON 宽松语义**（`isLenient=true`）| **无测试覆盖**——但 7 个平台解析器全依赖它 | ★ 最大的未测风险：**每个平台建一份 golden-response fixture** |
| 7 | `MessageDigest` ThreadLocal 复用 | `HashPerfTest.kt:57-68` | Go 里无意义 → 改 benchmark |
| 8 | `String.format("%.2f")` HALF_UP | 仅打印（`HashPerfTest.kt:32,52`）| 影响 `CacheManager.formatBytes`；`math.Round(x*100)/100` |
| 9 | `%02X` 大写 vs 小写 hex | `HashingTest.kt:26-33`（大写）**和** `:39-40`（小写）| 分开两个辅助函数 |
| 10 | `URLEncoder` vs RFC3986 | `HashingTest.kt:23-33` | 手写 `percentEncode`；`url.QueryEscape` **过不了这个测试** |
| 11 | `URLDecoder`（`+`→空格）| `GarminOAuth1Test.kt:47,51` | `url.QueryUnescape` |
| 12 | `String.length` = UTF-16 码元 vs Go = 字节 | `ActivityExporterNamingTest.kt:43-47` | 按 rune 计数 |
| 13 | `assertThrows` | 9 处 | `errors.As` |
| 14 | `UUID.randomUUID()` = v4 | `CorosLiveCheck.kt`、`OutbaseClient.kt:171`、`IgpsportGateway.kt:328` | `crypto/rand` + v4 位掩码 |

### 5.3 建议的移植顺序：**测试先行**

这是 Go 重写**最关键的一条工程纪律**：

> **逐文件「先移植测试，再写实现」。**
> 13 个测试文件 / 291 条断言就是 Kotlin 实现的**可执行规格说明**。
> 如果先写 Go 实现再补测试，开发者会不自觉地**按自己以为的语义**写，
> 而测试只能验证「Go 实现 == Go 实现」——**上面 14 条 JVM 差异就会全部溜进去**。

推荐顺序（按「依赖倒序 + 风险优先」）：

1. **第 0 批（地基，先做）**
   - `HashingTest` → `hashing.go`（含手写 `percentEncode`）
   - **新建** `jsonx` 包的 golden fixture 测试（把 7 个平台各 1 份脱敏真实响应存成 fixture）
   - `TimeParsingTest` → `timeparse.go`（**先定好时区参数的设计**）
   - `FitPackagingTest` → `fitpkg`
2. **第 1 批（协议纯函数）**：`ActivityFingerprintTest`、`GarminOAuth1Test`、`StravaOAuthTest`、
   `OssEndpointTest`、`CorosStsProviderTest`、`IgpsportOssProviderTest`
3. **第 2 批（逻辑）**：`SyncPlannerTest`（★ 注意 nil vs 空集）、`SyncDirectionMatrixTest`
4. **第 3 批（客户端）**：`OutbaseClientTest`、`HashPerfTest`（→ benchmark）
5. **第 4 批（集成）**：`CorosLiveCheck`
6. **最后**：同步引擎（`SyncEngine` 无直接测试，靠上述单测 + 端到端冒烟兜底）

---

## 6. Vue 前端

### 6.1 状态管理映射

| Compose 模式 | 位置 | Vue 对应 | 难度 |
|---|---|---|---|
| `stateIn(viewModelScope, WhileSubscribed(5_000))` 热 `StateFlow` | `AppViewModel.kt:72-90` | Pinia store `state`；**5 秒停收集的 teardown 在 Vue 里没有对应物**——但服务端才是数据源，无需复刻 | 易 |
| `MutableStateFlow` + `asStateFlow()` | `:94-120` | Pinia `state` + `actions`；`it.copy{}` → `{...state}` | 易，1:1 |
| `combine(_source,_target)` → `SyncDirection` | `:108-111` | `computed(() => ({source, target}))`——Vue 的 `computed` 会批量依赖，**双重重启 bug 不会发生** | 易，**严格更好** |
| `flatMapLatest` + 4 路 `combine` + `flowOn(Default)` | `:131-180` | `watchEffect((onCleanup) => {...})`，`onCleanup` 中止上一次请求 = **`flatMapLatest` 精确等价** | **需处理**（§6.6 深度响应式）|
| 冷 `Flow` 交给屏幕订阅 | `:583,654,656,702` | composable + `shallowRef`；★ 保留 `remember(direction)` 的重置语义（`watch` 或 `:key` 强制重挂载）| 易 |
| `suspend fun` 一次性调用 | `:346,348,586,651,689,784` | `async/await` | 易，1:1 |
| **`pendingMfa` 进程内挂起** | `:92` + `MfaCoordinator.kt:19-41` | **无 1:1 对应**——挂起点必须移到服务端，浏览器经 SSE 得知 `mfa.required` 后回填 | **架构变更**（§6.5）|
| 一次性 toast 通道 | `:119-120`（18 处写入）| `useToastStore().push(msg)` + 全局 `<Toaster/>` | 易 |
| 400 行日志环形缓冲 | `:853` | `logs.value = [...logs.value, line].slice(-400)` 会每次复制并重渲染 → 用环形缓冲或 `shallowRef` + `triggerRef` | 中 |
| 可取消句柄 | `:182`、`:497-502` | `AbortController`（客户端）+ `POST /api/runs/{id}/cancel`（服务端，真正的取消）| 易/中 |
| 进程级全局总线 | `SyncProgressBus.kt:28` | 服务端事件流 → `EventSource` | 重写 |

**推荐 store 切分**（5 个全局 + 1 个屏幕级）：
`useSyncStore`（方向、运行态、汇总、策略）· `useAccountsStore` · `useSettingsStore` ·
`useJobsStore` · `useCacheStore` · `usePickerStore`（**屏幕级，方向变化时重置**）。

> ⚠️ **最大的陷阱**：Android 端是**一个** `AppViewModel` 管全部（`AppViewModel.kt:65`）。
> 朴素移植会做一个巨型 Pinia store——能跑，但会把**屏幕局部状态**和**全局状态**混在一起。
> Compose 里 picker 的 `selected`/`filter`/`loadedCount` 放在 `remember(direction)` 里（**方向一变就重置**），
> 搬进全局 store 而不重置 → 切方向后残留旧选择。
> **对策**：`watch(() => directionKey, () => picker.reset())`，或 `<ActivityPickerScreen :key="directionKey" />`。

### 6.2 路由与底栏语义（**必读，抄错了会复现既有 bug**）

`ui/AppRoot.kt:44-61` 定义 **10 条路由**：3 个 tab（`sync`/`history`/`settings`）+ 7 个非 tab
（`select`/`accounts`/`account/{platform}`/`cache`/`strava-auth`/`outbase-auth`/`job/{jobId}`）。

**三条承重的语义**：

1. **底栏永远可见**，包括在 7 条非 tab 路由上（`AppRoot.kt:81-114`）。
   注释（`:81-84`）说明了原因：子页面藏掉底栏会让「**配置完账号回不到同步页**」的用户 stranded。
2. **非 tab 路由上「都不选中」**（`AppRoot.kt:88`）——7 个子页面上底栏**零高亮**。
3. **切 tab 清空整个返回栈**：`navigate(tab) { popUpTo(findStartDestination()) { inclusive = true }; launchSingleTop = true }`
   （`:95-107`）。注释（`:99-102`）是经典 bug 记录：*saveState/restoreState 会把上一个子页面复活，
   点「同步」又落回账号管理*。

**Vue Router 4 没有 `popUpTo`**，`router.replace()` 只替换栈顶。

> ⚠️ **重要提示**：Vue Router 4 **没有** `popUpTo`；`router.replace()` 只替换**栈顶**一项，
> 更早的条目（例如 `/sync`）仍留在浏览器后退栈里。
> **真正重要的不变式**（「切 tab 一定落在 tab 根页，绝不复活子页面」）**由 `replace` 保持了**——
> 这正是 Compose 注释里说 saveState/restoreState 破坏掉的那一条。
> 若确实需要把栈截断成只剩 `[tab]`，唯一的办法是 `window.history.go(-back)`——
> **异步且有竞态**，且会吞掉用户自己的历史记录。**不建议**；建议用 `replace` + 一个
> `beforeEach` 守卫来定义「从 tab 根页按后退」的行为。

```ts
// src/router/index.ts
export const TABS = [
  { name: 'sync', path: '/sync', label: '同步' },
  { name: 'history', path: '/history', label: '记录' },
  { name: 'settings', path: '/settings', label: '设置' },
] as const

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: { name: 'sync' } },
  { path: '/sync', name: 'sync', component: SyncScreen, meta: { tab: 'sync' } },
  { path: '/history', name: 'history', component: HistoryScreen, meta: { tab: 'history' } },
  { path: '/settings', name: 'settings', component: SettingsScreen, meta: { tab: 'settings' } },
  // 7 条非 tab 路由：无 meta.tab ⇒ 底栏「都不选中」
  { path: '/select', name: 'select', component: ActivityPickerScreen },
  { path: '/accounts', name: 'accounts', component: AccountsScreen },
  { path: '/account/:platform', name: 'account-edit', component: AccountEditScreen, props: true },
  { path: '/cache', name: 'cache', component: CacheScreen },
  { path: '/strava-auth', name: 'strava-auth', component: StravaAuthScreen },
  { path: '/outbase-auth', name: 'outbase-auth', component: OutbaseAuthScreen },
  { path: '/job/:jobId', name: 'job', component: JobDetailScreen, props: true },
  { path: '/:pathMatch(.*)*', redirect: { name: 'sync' } },
]
```

```vue
<!-- AppShell.vue：底栏在 router-view 之外，永远渲染 -->
<template>
  <AppBackground>
    <div class="relative z-10 flex h-dvh flex-col">
      <main class="min-h-0 flex-1 overflow-y-auto">
        <router-view v-slot="{ Component }"><component :is="Component" /></router-view>
      </main>
      <BottomNav :selected="selectedTab" @select="onTabClick" />
    </div>
  </AppBackground>
</template>
<script setup lang="ts">
const selectedTab = computed<TabName | null>(() => (route.meta.tab as TabName) ?? null)

async function onTabClick(tab: TabName) {
  if (route.name === tab) return                 // AppRoot.kt:94 — 已在根页，什么都不做
  await router.replace({ name: tab })            // 永远 replace，绝不 push
}
</script>
```

> ⚠️ **`selectedTab` 必须用 `route.meta.tab`，不能用路径前缀推导**——
> `/account/:platform` 和 `/job/:jobId` 会误判。

### 6.3 逐屏映射与工作量

| 屏 | Compose LOC | 1:1 对等 | +多用户 | Δ | 说明 |
|---|---|---|---|---|---|
| **SyncScreen** | 530 | 2.5 | 4.0 | +1.5 | Hero 渐变卡 + 2 个 chip 网格 + 6 个 SectionCard + 进度卡 + 2 个对话框 + 通知权限请求。+1.5 = chip 变成**账号对**（平台 + 脱敏邮箱）|
| **ActivityPickerScreen** | 633 | 4.0 | 4.5 | +0.5 | ★ 唯一需要**虚拟列表**的屏（1,000 行）+ 服务端分页（50/页）+ 滚动到底检测 + `Set` 选择集 + 2 个批量流程 + 底部操作栏 |
| **SettingsScreen** | 437 | 1.75 | 2.5 | +0.75 | 5 个间隔 chip + **4 档吸附滑块** + 2 个开关 + 6 行账号。+0.75 = 每用户设置、cron 替代 WorkManager |
| **CacheScreen** | 365 | 1.75 | 2.25 | +0.5 | 用量条（纯 CSS）+ 安全/危险分类 + 危险确认框。数据源变成**服务端** |
| **JobDetailScreen** | 287 | 1.25 | 1.5 | +0.25 | 摘要卡 + **2 条正则**从日志文本派生失败列表（**1:1 移植**）+ 等宽日志块 |
| **AccountsScreen** | 289 | 1.5 | 2.5 | +1.0 | 6 张固定平台卡。+1.0 = 变成**用户的账号列表** + 添加入口 |
| **AccountEditScreen** | 268 | 1.5 | 2.0 | +0.5 | 按平台分支的表单 + 区域 chip + 密文框 |
| **HistoryScreen** | 271 | 1.25 | 1.5 | +0.25 | 作业卡 + 26 dp 图标列 + `"—"` 占位处理 |
| **StravaAuthScreen** | 221 | 0.75 | 1.25 | +0.5 | ★ WebView 换成**重定向/弹窗 + 等待态** |
| **OutbaseAuthScreen** | 211 | 1.0 | 1.25 | +0.25 | ★ 服务端驱动登录 + 轮询/等待态 |
| **屏幕小计** | 3,512 | **17.25** | **22.75** | +5.5 | |
| *基础层* | — | **15.25** | **17.75** | +2.5 | 22 个组件 3.5 + Pinia 3.0 + 令牌/Tailwind 1.5 + a11y 1.5 + 响应式/桌面 1.5 + SSE 1.0 + API 客户端 1.5 + 路由 0.75 + i18n 抽取 0.75 + 错误边界 0.75 + SVG 0.5 + 图标 0.5 |
| **鉴权**（登录/注册/会话/路由守卫/401/登出）| 新增 | — | **2.5** | +2.5 | Android 端 0 行 |
| **MFA 服务端往返** | — | — | **1.5** | +1.5 | 进程内 `CompletableDeferred` 撑不住断线 |
| **导出 → 服务端 ZIP 下载** | — | 0.5 | **1.5** | +1.0 | `ReadableStream` 进度条 |
| **浏览器通知 + Service Worker** | — | 0.25 | **1.25** | +1.0 | |
| **管理/配额/任务管理面** | — | — | **1.5** | +1.5 | 仅当多用户产品需要 |
| **合计** | | **≈ 33**（28–38）| **≈ 47.5**（42–56）| **+14.5** | |

### 6.4 设计系统映射

**令牌总量约 154 个**：26 个原始色 + 66 个 M3 角色（亮/暗各 33）+ 12 个平台色 + 14 个字型 + 6 个形状 + 30 个间距。
**规模很小**，一个 Tailwind v4 的 `@theme` 块就能装下。

关键点：

- **M3 的 5 个 `surfaceContainer*` 层级**在 CSS 里没有概念，但应用**每一级都显式覆盖**了 →
  就是 5 个 hex 值。建议**改名**为 `--surface-0 … --surface-5` 的 6 级明度阶梯（意图更清晰，不依赖 M3 词汇）。
- **`scrim`**（`:59`、`:103` = `#000000`）：M3 的 scrim 是 32% 黑，应用设成了纯黑。
  Web 的对话框背景用 `::backdrop { background: rgb(0 0 0 / 0.32) }`，**不要直接用这个令牌**。
- **`inverseSurface`/`inverseOnSurface`**：Android 的 Snackbar 用反色 → 浅色模式下是深底浅字。
  Web 要么复刻，要么把 toast 改成卡片风格——**但必须二选一并保持一致**。
- **`tnum`**（`Type.kt:113`）：`font-variant-numeric: tabular-nums`（Tailwind 的 `tabular-nums`）。
  ⚠️ 字体必须带 `tnum` 字形——Roboto / SF Pro / Segoe 有，Liberation Sans / Arial **没有** → 数字会跳动。
  **建议内嵌带 `tnum` 的 webfont（Inter、Roboto Flex）**。
- **间距刻度不是 4pt 网格**：30 个不同值、235 处使用，其中 **71 处（30.2%）不在 4pt 网格上**
  （1/2/5/6/10/14/17/18/22/26/50 dp）。这些**不是凑出来的**——1 dp 是发丝边框、10 dp 是 chip 内边距、
  18 dp 是图标框、50 dp 是按钮高。**不要强行拉到 4pt 网格**，那会移动 `docs/design-and-ux-proposal.md:301-321`
  锁定的版式。策略：Tailwind 默认 4px 刻度覆盖 17 个值 + 补一个 2px 半档（`0.5/1.5/2.5/3.5/4.5/5.5`）再吸收 7 个，
  只剩 `5/17/50` 三个用任意值。

**5 个 VectorDrawable → 内联 SVG**（约 0.5 人日）：

| 资源 | 用在哪 | 处置 |
|---|---|---|
| `ic_decor_rings` | `Design.kt:74`（AppBackground，280 dp，`offset(96,−72)`，`alpha 0.16`，`ColorFilter.tint(primary)`）| `fill="none" stroke="currentColor"`，宿主设 `color: var(--primary); opacity:.16` |
| `ic_hero_sync` | `Design.kt:151,189` | 内联 SVG，**不染色**（在深色 hero 上）|
| `ic_empty_accounts` | `AccountsScreen.kt:95` | 内联 SVG |
| `ic_empty_activities` | `SyncScreen.kt:237`、`ActivityPickerScreen.kt:302` | 内联 SVG |
| `ic_empty_history` | `HistoryScreen.kt:84`、`SyncScreen.kt:322` | 内联 SVG |

另有 2 个是 Android 专属资产：`ic_launcher_foreground` → **PWA/favicon**（`<group scaleX=4.0 translateX=6>` 变换要展平），
`ic_notification` → **Web 通知图标**。

### 6.5 不可移植切片（精确 LOC）

| 项 | 位置 | LOC | 代码 | 原因 |
|---|---|---|---|---|
| StravaAuthScreen 的 WebView 核心 | `StravaAuthScreen.kt:124-158` | 35 | 33 | `android.webkit.*` |
| OutbaseAuthScreen 的 WebView 核心 + `CookieManager` 取 cookie | `OutbaseAuthScreen.kt:104-138,66-67,111,172-176` | 45 | 41 | `android.webkit.*` + 第三方 cookie 读取 |
| 通知权限启动器 | `SyncScreen.kt:120-134` | 15 | 14 | Android 运行时权限模型 |
| `Format.kt` 的 JVM API | `Format.kt:3-13` | 48 | 38 | ★ **格式可移植，实现不可移植** |
| `MainActivity.kt` | 全文件 | 61 | 39 | Android 入口 |
| `SyncProgressBus` | 全文件 | 40 | 24 | 进程内单例 → SSE |
| `MfaCoordinator` | 全文件 | 42 | 25 | 进程内挂起 → 服务端 pending job |
| `startExport` 写 Downloads 目录 | `AppViewModel.kt:457-495` | 39 | 33 | Android 文件系统 → 服务端 ZIP 流 |
| **硬不可移植小计** | | **325** | **247** | |
| 两个授权屏整屏替换 | | 432 | 362 | 其中约 195 LOC 的布局可复用 |

**总计：5,799 行 `ui/` → 约 2,600–3,400 行 Vue**（减少约 55–60%，因为 Compose 的 `Modifier` 链冗长、CSS 能折叠重复）。

**`Format.kt` 必须逐字复现的格式**（`res/values/strings.xml` **只有 `app_name`**，其余 **309 处硬编码中文**）：

| 函数 | Java 格式 | 空值 | JS 等价 | 风险 |
|---|---|---|---|---|
| `dateTime(ms)` | `MM-dd HH:mm` | `"—"` | ⚠️ `Intl.DateTimeFormat('zh-CN',…)` 出的是 **`07/14 15:04`（斜杠）**，不是短横 → **必须手写**（`getMonth()`+`padStart`）| **高** |
| `date(ms)` | `yyyy-MM-dd` | `"—"` | `Intl.DateTimeFormat('sv-SE')` → `2026-11-01` ✅ | 低 |
| `time(ms)` | `HH:mm:ss` | `"—"` | `Intl.DateTimeFormat('en-GB',{hour12:false})` ✅ | 低 |
| `distance(m)` | `%.2f km` / `%.0f m` | `"—"` | `(m/1000).toFixed(2)` / `Math.round(m)` | ⚠️ Java `%.2f` 是 HALF_UP，JS `toFixed` 受二进制浮点影响（1.005 → `"1.00"` vs Java `"1.01"`）|
| `duration(s)` | `%d:%02d:%02d` / `%d:%02d` | `"—"` | 手写 + `padStart(2,'0')` | 低 |
| `elapsed(a,b)` | 同上 | `"—"` | ⚠️ Java 整数除法**向零截断** → `Math.trunc((b-a)/1000)` | 低 |
| `CacheManager.formatBytes` | `%.2f GB` / `%.1f MB` / `%.0f KB` / `N B` | — | ⚠️ **这个 `String.format` 没带 locale** → 逗号小数区域会打成 `1,23 GB`；JS `toFixed` 恒用 `.` → **建议在移植时修掉** |

**`"—"` 占位符的 5 处检测**必须继续工作：
1. `ActivityPickerScreen.kt:589-596` `activityMeta()` —— 丢掉格式化结果**恰好等于 `"—"`** 的字段，
   用 `" · "` 连接（所以缺字段时**不留悬空的 `"· —"`**）
2. `HistoryScreen.kt:60` `EMPTY_PLACEHOLDER = "—"`
3. `HistoryScreen.kt:255-258` `startedAtOrNull()` → `Format.dateTime() == "—"` 时返回 `null`
4. `HistoryScreen.kt:266-269` `elapsedOrNull()`
5. `JobDetailScreen.kt:129-132` —— 用上面两个 → **整行 InfoRow 消失**，而不是显示「耗时 —」

→ **TS 里导出单个 `export const EMPTY = '—'`**，保留完全相同的 `=== EMPTY` 判断。

### 6.6 Vue 特有风险

| # | 风险 | 等级 | 对策 |
|---|---|---|---|
| 1 | **1,000 行的深度响应式** | **高** | `PICKER_LIMIT = 1000`（`AppViewModel.kt:861`）。Vue 的 `reactive()` 默认深代理 → 1,000 行 × 7 字段全被代理；且 `computed` 过滤每次产生**新数组** → 重建 1,000 个 VNode。Compose 的 `LazyColumn` 只组合约 10 个可见行。**对策（必须）**：虚拟列表（`@tanstack/vue-virtual`）+ `shallowRef` + `markRaw` + `computed` + 独立的 `ref<Set<string>>` 选择集。**没有虚拟列表，picker 会明显卡顿** |
| 2 | **虚拟列表的 key 稳定性** | 中 | `:key="item.activityId"`，**绝不能用数组下标**（列表按 `startTime` 降序且分页）。服务端分页必须给**全局一致的排序**（`startTime DESC, activityId ASC`），否则第 2 页到达时滚动位置会跳 |
| 3 | **composable 与 store 的边界** | 中 | picker 的 `selected`/`filter`/`loadedCount` 必须**屏幕级**并在方向变化时重置（§6.1）|
| 4 | **没有 SSR/SSG 就没有 `useAsyncData`/`useFetch`/payload hydration** | 中 | 用 `@tanstack/vue-query`（最接近 `stateIn`+`WhileSubscribed`+`flatMapLatest`）或手写 composable。首屏 = 鉴权检查 + 3 个并行请求 |
| 5 | **没有声明式布局系统** | 中 | `Modifier` 链 → CSS 类。但要**把散落的布局不变量收敛成原语**（`.screen-gutter`=16px、`.icon-col`=26px、`.hero-text`=62%、`.subpage-header`）以保留「一个不变量一处」的纪律 |
| 6 | **日志 `v-html` XSS** | **高** | 服务端来的日志文本**绝不用 `v-html`**；只渲染文本节点；配 CSP |
| 7 | **`<Teleport>` 对话框没有焦点管理** | 中 | 用 `reka-ui` 的 `DialogRoot`，或手写焦点陷阱 + `aria-modal` + Esc + 焦点还原 |
| 8 | **Tailwind JIT 提取不到动态类名** | 中 | 色调映射用**静态字符串查表**（`TONE_CLASS = {OK: '…'}`），**不要字符串拼接** |
| 9 | **ref 解包约定不一致** | 中 | 统一选一种：`reactive` store 直接属性访问，或 setup store 显式 `.value` |
| 10 | **多标签页 SSE 重复连接** | 低 | `BroadcastChannel` 选主标签页 |
| 11 | **26 dp 的「排除/恢复」按钮不满足 WCAG 2.2 SC 2.5.8（≥24px，实际 26px 勉强过但偏小）** | 中 | ★ **修掉而不是复刻**：视觉尺寸不变，命中区扩到 ≥44px（`h-6.5` + `p-2.5`）。**这同时应该作为 Android 端的缺陷上报** |
| 12 | **复选框的 48dp 命中区 / 20dp 可见框 + `offset(x=14.dp)` hack** | 中 | `ActivityPickerScreen.kt:556-562`。Web 里改成 `<button role="checkbox" aria-checked>` + `min-w-11 min-h-11`，14dp 偏移变成布局决定（`-mr-3.5`）|
| 13 | **无障碍标注缺失** | 中 | `contentDescription = null` 出现 30+ 次 → 图标按钮要显式 `aria-label` |
| 14 | **浏览器通知需要 HTTPS + 用户手势** | 中 | `Notification.requestPermission()` 必须在「一键同步全部」点击里调（✅ 正是用户手势）|
| 15 | **深色模式没有手动覆盖** | 低 | Android 端也没有，但 Web 产品需要「跟随系统/浅/深」+ `localStorage` |

**浏览器端特有的两个协议约束**（不是 UI 问题）：

- **Strava**：`REDIRECT_URI = "http://localhost/exchange_token"`（`StravaOAuth.kt:29`）今天是被 WebView 拦截的。
  浏览器不能依赖 `http://localhost` 回调（80 端口需特权，且 Strava 的「Authorization Callback Domain」要改成产品域名）。
  两个方案：**(a)** 改成产品域名下的 HTTPS 回调（需改 Strava 应用配置）；**(b)** **弹窗**——
  `window.open(authUrl,'strava','width=520,height=680')`，回调页 `window.opener.postMessage({code}, origin)`。
  **(b) 可行**，因为回调是 first-party，不受第三方 cookie 限制。
- **Outbase**：`OutbaseAuth.LOGIN_URL` 在 **`outbase.cn`** 上设 `sessionId` cookie，
  今天靠 `CookieManager.getCookie("https://outbase.cn")` 读取（`OutbaseAuthScreen.kt:172-176`）。
  **浏览器里做不到**——第三方 cookie 默认被 Chrome/Safari/Firefox 拦截，`SameSite=Lax` 的 cookie 不会随跨站子资源请求发送。
  → **必须由服务端完成 Outbase 登录**（服务端浏览器会话）或**让用户粘贴 session id**。
  **这是整个移植中最难的协议约束**，也是第一轮遗留的 V1 验证项。

### 6.7 前端工作量小结

| 场景 | 人日 |
|---|---|
| 1:1 单用户功能对等 | **≈ 33**（28–38）|
| 含多用户增量（登录、账号对选择器、每用户策略与设置、MFA 往返、服务端 ZIP 导出、浏览器通知）| **≈ 47.5**（42–56）|

**建议排期**：① 基础层（15 pd）→ ② 8 个非授权屏 1:1（14 pd）→ ③ 授权流 + MFA + 导出 + 通知（4 pd）→ ④ 多用户增量（14.5 pd）。

---

## 7. 多用户模型（不变，但 Go/Vue 下有几点强化）

第一轮的多用户设计结论**全部不变**，此处只补充 Go/Vue 带来的强化点：

| 第一轮结论 | Go/Vue 下的强化 |
|---|---|
| 4 张表必须加 `user_id` 并改主键 | 不变；sqlc 的**构建期 SQL 校验**让改主键更安全 |
| 指纹去重集必须按用户隔离（★最高危）| 不变；Go 侧**必须**在 SQL 层加 `user_id` 条件，不能靠应用层过滤 |
| 凭据不能迁移（Keystore 不可导出）| **更明确**：Go 侧就是「服务端 KMS + 重新授权」，没有中间态 |
| 3 个进程级单例会跨用户泄漏 | ★ **在 Go 上更致命**：一个 Go 进程服务所有用户，全局 jar = 直接串号。必须按用户隔离 |
| `SyncDirection` 必须变成账户作用域 | 不变；Go 侧体现为 `SyncDirection` 带 `user_id` |
| Room 无外键，关系靠约定字符串 | 不变；Go/PG 里应**显式建外键**（FK 是免费的约束）|

---

## 8. 风险登记册（修订版）

> 合并第一轮的 R1–R13、12 项既有缺陷，以及 Go/Vue 栈新增的风险。
> **优先级按「后果 × 概率」排序。**

### 8.1 P0 —— 必须有明确方案才能开工

| # | 风险 | 后果 | 对策 |
|---|---|---|---|
| **G1** | **凭据密钥托管**（`CryptoBox` 的 Keystore 密钥不可导出）| Android 已存的凭据全部无法解密 → 所有用户重新授权 | **产品决策**（信封加密 vs 派生密钥），先定 KMS 再写代码 |
| **G2** | **JSON 宽松语义 + float64 数字类型** | 7 个手写解析器**静默**解析失败或精度丢失，**现有测试零覆盖** | 全局 `dec.UseNumber()` + §3.2 的访问器 + **每平台 golden-response fixture** |
| **G3** | **用户时区** | 所有活动时间被平移服务器 UTC 偏移（静默）| 账户存 IANA 时区，贯穿 `parseFlexible`；容器内嵌 `time/tzdata` |
| **G4** | **CookieJar / 令牌缓存是进程级** | **跨用户会话泄漏** | 每用户 `http.CookieJar` + 每会话事件总线 |
| **G5** | **`errgroup.WithContext` 用错** | 逐条隔离丢失 → **应用最核心的特性退化** | 单 goroutine 顺序循环；`errgroup` 只用于多用户扇出 |
| **G6** | **Outbase 服务端登录不可行**（第一轮 V1 未决）| Outbase 作为**目标方向**在 Web 上不可用 | **开工前必须验证**；不通过则砍掉 Outbase 目标方向 |

### 8.2 P1 —— 会在移植中咬人

| # | 风险 | 后果 | 对策 |
|---|---|---|---|
| **G7** | `percentEncode` 被「顺手」换成 `url.QueryEscape` | Garmin OAuth1 `401 Invalid signature` | 手写实现 + 保留 golden 向量 |
| **G8** | 十六进制大小写被统一 | 阿里云 V1 / SigV4 签名不匹配 | 两个独立辅助函数，两套 golden 测试 |
| **G9** | panic 杀掉整个进程 | 一个平台的坏响应导致全站不可用 | goroutine 边界 `recover()` |
| **G10** | 重定向上限 20 → 10 | 深层 SSO 链提前中断 | 显式 `CheckRedirect` |
| **G11** | `.take(40)` 按字节截断 | CJK 文件名损坏 | `[]rune(s)[:40]` + **新增 CJK 用例** |
| **G12** | `selectedIds` 的 nil / 空集语义丢失 | 「明确选零个」变成「没选」 | 指针或独立 `HasSelection bool` |
| **G13** | `?:` 的 null 语义被写成 `== ""` | STS 字段名回退行为改变 | 全部访问器返回 `(T, bool)` |
| **G14** | `hashicorp`-式的手工 User-Agent 缺失 | Coros/iGPSPORT/OneLap 登录失败（Go 的 transport **不发 UA**）| 显式设置，默认沿用安卓 UA 字符串但可配置 |
| **G15** | 1,000 行列表没有虚拟化 | picker 卡顿 | `@tanstack/vue-virtual` + `shallowRef` + `markRaw` |
| **G16** | `v-html` 渲染服务端日志 | XSS | 只渲染文本节点 + CSP |
| **G17** | MFA 挂起点移到服务端后无超时 | 用户离开后同步永久挂起（**这是现有 Android 端的真实缺陷**）| 服务端 MFA 超时 + 恰好一次语义 + 断线存活 |

### 8.3 P2 —— 需要盯，但不阻塞

| # | 风险 | 说明 |
|---|---|---|
| **G18** | `zip` 字节与 Java 不同 | 自洽即可；不要断言跨实现字节 |
| **G19** | ZIP 条目顺序不同（central directory vs local header）| 单条目场景无影响 |
| **G20** | 日志脱敏清单不全（`Sessionid`、`x-oss-security-token`、`x-amz-security-token`）| ⚠️ 修正第一轮判断：当前是**潜在**缺口（BASIC 级别不打印请求头）；Go 侧**始终**脱敏 |
| **G21** | 查询串泄漏（Coros `sign=…`、Strava `code=…`）| 只记录 `URL.Path` |
| **G22** | `String.length`（UTF-16）vs Go 字节 | 见 G11 |
| **G23** | `cachejar.New(nil)` 无公共后缀表 | `Domain=com` 会被发给任意 `*.com`；传 `publicsuffix.List` |
| **G24** | Vue 没有 SSR/SSG 就没有内建数据获取 | 首屏需等 3 个并行请求 |
| **G25** | Go 的 `%.2f` round-half-even | 缓存容量末位差 0.01；`math.Round(x*100)/100` |
| **G26** | `Math.round` vs `math.Round` 负数半值 | `ActivityFingerprint` 用 `math.Floor(v+0.5)`——**指纹是线协议** |
| **G27** | 缺失 `Content-Length` 时的分块上传差异 | `bytes.Reader` 会自动设置 ✅ |

### 8.4 第一轮的 12 项既有缺陷（**Web 化过程中应一并修掉**）

第一轮文档第 10 章列出的 12 项，在 Go/Vue 下**大部分直接消失**（它们都是 Room / Android 生命周期 / Compose 的产物）：

| 原缺陷 | 在新栈下的处置 |
|---|---|
| `fallbackToDestructiveMigration(dropAllTables = true)` 与注释矛盾 | **更危险**（上线即丢数据）→ 服务端必须关闭 |
| `cached_activities` 缺 `(platform, startTime)` 索引 | 补 `(user_id, platform, start_time)` |
| 房间 schema 无外键 | PG 里**显式建外键**（免费约束）|
| `SyncDirection` 不含账户维度 | 改为账户作用域 |
| 指纹去重集按 `(source,target)` 计算 | 改为按用户 |
| `JobDetailScreen` 用**正则从日志文本**派生失败列表 | 服务端建**结构化失败表**（不解析文本）|
| `MAX_UI_LOG_LINES = 400` 每次复制数组 | 环形缓冲 |
| 26 dp 触摸目标 | 修到 ≥44px（同时上报 Android 端）|
| `MfaCoordinator` 无限期挂起 | 服务端超时 |
| `OkHttp` 取消未真正生效 | Go 的 `ctx` 原生支持 ✅ |
| `noRedirect()` 死代码 | 不移植 |
| `EmptyHint` 组件零调用点 | 不移植 |

---

## 9. 路线图（修订）

| 期 | 内容 | 人日 | 里程碑 |
|---|---|---|---|
| **第 0 期** | **验证 + 决策**（不写生产代码）| 5–8 | ① Outbase 服务端登录可行性（**V1，最高风险**）② Strava 应用归属与回调域名（**D3**）③ 部署区域与双区域方案（**D4**）④ 凭据托管方案（**G1**）⑤ 每平台 golden-response 样本集采集（**G2 的前置**）⑥ 用户时区模型（**G3**）|
| **第 1 期** | **Go 后端地基** | 20–28 | 单二进制骨架 + PG + sqlc + 4 张表加 `user_id` + 会话/鉴权 + 凭据信封加密 + `jsonx` 宽松 JSON 包 + `hashing`/`timeparse`/`httpclient` + **按用户隔离的 cookie jar** + SSE 骨架 |
| **第 2 期** | **协议层移植（测试先行）** | 28–42 | 按 §5.3 的顺序：先搬 13 个测试文件，再写实现；7 个平台逐个接通（Garmin 最难，Outbase 次之）|
| **第 3 期** | **同步引擎 + 调度 + 导出** | 14–22 | 串行引擎 + 逐条隔离 + 账本 + 取消 + 任务租约 + cron + 服务端 ZIP 导出 |
| **第 4 期** | **Vue 前端** | 33–47 | 基础层 → 8 屏 1:1 → 授权流/MFA/导出/通知 → 多用户增量 |
| **第 5 期** | **联调、迁移、运维** | 12–18 | 端到端联调 + 真实平台回归 + 部署/CI/可观测/限流 + 文档 + **Android 端去留决策落地**（D1）|
| **合计** | | **126–182** | ≈ **6–9 人月**（2 人团队约 3–4.5 个月）|

**关键路径**：第 0 期的 **Outbase 验证** → 第 2 期的 **Garmin 移植** → 第 4 期的 **MFA 往返**。
其余可并行（后端与前端在第 1、4 期可重叠，前端可用 mock API 先做）。

---

## 10. 工作量总表

| 工作包 | 人日 |
|---|---|
| 后端：协议与核心层重写 | 28–42 |
| 后端：同步/导出引擎 + 数据层 | 14–22 |
| 后端：服务端骨架 + 多用户 + 鉴权 | 12–18 |
| 后端：凭据信封加密 + 重新授权向导 | 6–10 |
| 后端：调度 + 任务租约 + SSE + 导出制品 | 10–15 |
| 后端：部署/CI/可观测/限流 | 6–9 |
| 后端：Outbase 服务端登录（条件项）| 3–8 |
| 前端：基础层 | 15–18 |
| 前端：10 屏 1:1 | 14–17 |
| 前端：授权流 + MFA + 导出 + 通知 | 4–6 |
| 前端：多用户增量 | 14–17 |
| 验证与决策（第 0 期）| 5–8 |
| 联调/迁移/运维 | 12–18 |
| **合计** | **126–182 人日** |

**对比**：第一轮（Kotlin + React）**97–152 人日**。**净增 ≈ +30 人日（+20%）**。

---

## 附录 A：可直接采用的 Go 参考实现

### A.1 RFC 3986 `percentEncode`（大写十六进制）

见 §3.7 的完整实现。

### A.2 宽松 JSON 访问器

见 §3.2 的完整实现（`decodeJSON` / `str` / `long` / `boolean`），并附 `double`：

```go
// double 等价于 JsonPrimitive.doubleOrNull()：数字 ✓、数字字符串 ✓。
func double(v any) (float64, bool) {
	switch t := v.(type) {
	case json.Number:
		f, err := strconv.ParseFloat(t.String(), 64)
		if err != nil { return 0, false }
		return f, true
	case string:
		f, err := strconv.ParseFloat(strings.TrimSpace(t), 64)
		if err != nil { return 0, false }
		return f, true
	}
	return 0, false
}

// number 等价于 HttpExtensions.kt:80-84：先试 double，再把原始 content 当 double 解析。
func number(v any) (float64, bool) {
	if d, ok := double(v); ok { return d, true }
	s, ok := str(v)
	if !ok { return 0, false }
	f, err := strconv.ParseFloat(s, 64)
	if err != nil { return 0, false }
	return f, true
}
```

### A.3 容错 Base64

见 §3.9。

### A.4 OSS 日期头

见 §3.10。

### A.5 逐条隔离的活动执行器

见 §3.6。

---

## 附录 B：Kotlin → Go 语义对照速查

| Kotlin | Go | 陷阱 |
|---|---|---|
| `suspend fun` | 返回 channel / `ctx` 阻塞 | |
| `withContext(Dispatchers.IO)` | **无（不需要）** | 不要自建 IO 线程池 |
| `withContext(NonCancellable)` | `context.WithoutCancel(ctx)` | ⚠️ 必须再套 `WithTimeout` |
| `CancellationException` | `context.Canceled` / `DeadlineExceeded` | `errors.Is` 可穿透 `*url.Error` |
| `channelFlow` + `send()` | buffered chan + `select` | ⚠️ 裸 send 会永久阻塞 |
| `catch (e: Exception)` | `if err != nil` | ⚠️ **不捕获 panic** → 需 `recover()` |
| `throw PlatformException.Auth` | typed error + `errors.As` | ⚠️ 基类 `PlatformException` 要单独表示 |
| `a ?: b` | `nil` 检查 | ⚠️ **不是 `== ""`** |
| `s.take(40)`（UTF-16）| `[]rune(s)[:40]` | ⚠️ 按字节会截断 CJK |
| `String.format("%02X")` | `fmt.Sprintf("%02X")` | ⚠️ 与 `hex.EncodeToString` 不同 |
| `String.format("%.2f")`（HALF_UP）| `math.Round(x*100)/100` + `%.2f` | |
| `Math.round(x)`（floor(x+0.5)）| `math.Floor(x+0.5)` | ⚠️ `math.Round` 对负数半值不同 |
| `java.util.Base64.getMimeDecoder()` | 剥空白 + `StdEncoding` | ⚠️ 空格会报错 |
| `SimpleDateFormat` | `time.Parse`（严格）| ⚠️ 前缀匹配 / 宽松滚动会变 |
| `TimeZone.getDefault()` | **账户的 `*time.Location`** | ⚠️ P0：服务端只有一个时区 |
| `java.net.URLDecoder.decode` | `url.QueryUnescape` | ⚠️ `PathUnescape` 不处理 `+` |
| `java.net.URLEncoder.encode` | `url.QueryEscape` | ⚠️ 与 Java 不等价（`~` 的处理）|
| RFC 3986 `percentEncode` | **手写**（附录 A.1）| ⚠️ 两个现成函数都不对 |
| `Locale.US` | Go 布局与 locale 无关 | ✅ |
| `ByteArray.equals` | `bytes.Equal` | ⚠️ Go 切片 `==` 是引用相等 |
| `UUID.randomUUID()` | `crypto/rand` + v4 掩码 | ⚠️ Outbase 用前 4 位做分片 |
| `mutableMapOf` / `mutableSetOf` | map / slice | ⚠️ Go map 无序 → 需要顺序时用 slice + seen map |
| `Set<String>?`（nil vs 空）| 指针 / `HasSelection bool` | ⚠️ Go map 抹掉了这个区别 |
| `assertThrows(X)` | `errors.Is` / `errors.As` | |
| `MessageDigest` ThreadLocal 复用 | 不需要 | ⚠️ 相关性能测试变空测试 |

---

## 附录 C：实测程序

评估中用到的两支对拍程序（**未提交进仓库**，可按需重建）：

**Kotlin 侧**（`KotlinProbe.java`）：调用仓库 `.gradle-home` 缓存中**真实版本**的
`kotlinx-serialization-json 1.8.1`（`JsonElementKt.getLongOrNull/getDoubleOrNull/getBooleanOrNull/getContentOrNull`），
配合 `SimpleDateFormat`、`java.util.Base64`、`String.format`。

**Go 侧**（`go_probe.go`）：`encoding/json`（`UseNumber`）、`time`、`encoding/base64`、`net/url`。

**运行方式**：
```
# Kotlin 侧
javac -cp "<kotlinx-serialization-json-1.8.1.jar>:<kotlinx-serialization-core-1.8.1.jar>:<kotlin-stdlib-2.1.21.jar>" -d . KotlinProbe.java
java -cp ".:<同上>" KotlinProbe

# Go 侧
go run go_probe.go
```

**实测环境**：Go 1.26.2 darwin/arm64 · Java 13.0.2 · kotlinx-serialization 1.8.1。
> ⚠️ 评估机上的 Go 安装**缺少** `archive/zip`、`net/http/cookiejar`、`net/http/httptest` 三个标准库包
> （`go list std` 报错）。这是该机 Go 安装被裁剪的**环境问题，不是 Go 的限制**；
> 标准 Go 发行版包含这三包。`cookiejar` 的结论来自同版本源码的行为分析，落地时需在完整 Go 环境复验。

---

## 附录 D：第一轮评估遗留的验证项（V1–V7）

| # | 验证项 | 与本文档的关系 |
|---|---|---|
| **V1** | **Outbase 服务端登录可行性** | ★ **升为 P0（G6）**，是第 0 期第一件事 |
| **V2** | Coros STS 能否从服务器 IP 获取 | 升为 P0 级前置验证（第 0 期）|
| **V3** | Strava 应用归属与回调域名 | ★ 浏览器**必须**改成 HTTPS 回调或弹窗（§6.5），决策项 D3 |
| **V4** | 各平台的服务条款是否允许服务端代持凭据 | 决策项 D5，**未消解** |
| **V5** | OneLap 的明文 HTTP 在生产环境的可接受性 | 部署区域决策（D4）的一部分 |
| **V6** | 导出制品的存储与保留策略 | 第 3 期设计输入 |
| **V7** | `isLenient=true` 在 7 个平台真实响应上是否必要 | ★ 由第 0 期的 **golden-response 样本集**回答（G2）|

---

*本文档为评估结论，不含生产代码改动。仓库仅新增 `docs/web-migration-assessment.md`（第一轮）与本文件。*
