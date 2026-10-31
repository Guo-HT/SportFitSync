# 平台协议实现参考（迁移自 garmin-sync-coros）

> 构建 / 安装 / 调试 / 模拟器 / 设备取证 的操作说明见 [`development-handbook.md`](development-handbook.md)。

本文件是 Android 端实现各平台客户端的**唯一事实来源**，内容来自对 `garth==0.4.38`
源码（`/Users/mac/Library/Python/3.10/lib/python/site-packages/garth/`）与上游录制的
HTTP 报文（VCR cassettes）逐行比对，而非记忆或猜测。

---

## 一、Garmin（最复杂，需要重写 garth）

### 1.1 常量（原样照抄）

| 名称 | 值 |
|---|---|
| OAuth consumer 发现地址 | `https://thegarth.s3.amazonaws.com/oauth_consumer.json` |
| `consumer_key` | `fc3e99d2-118c-44b8-8ae3-03370dde24c0` |
| `consumer_secret` | `E08WAR897WEy2knn7aFBrvegVAf0AFdWBBF` |
| SSO 页面 User-Agent | `Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148` |
| OAuth1 两步调用的 UA | `com.garmin.android.apps.connectmobile` |
| MFA 判定标记 | 响应 `<title>` 中出现子串 `MFA`（实际标题为 `Enter MFA code for login`） |

- consumer key/secret **不硬编码在 garth 里**，而是运行时从 S3 拉取；`.com` 与 `.cn` 共用同一份。
  Android 端策略：内置为默认值 + 首次启动后台刷新覆盖（两端一致，避免离线不可用）。
- **签名算法**：`HMAC-SHA1`，凭据放在 `Authorization` 头（`oauthlib` 默认 `SIGNATURE_TYPE_AUTH_HEADER`）。
- 参数顺序（oauthlib 固定）：`oauth_nonce, oauth_timestamp, oauth_version, oauth_signature_method, oauth_consumer_key, [oauth_token], …`。

### 1.2 登录流程（逐步）

| # | 方法 | URL | Query | Body | 备注 |
|---|---|---|---|---|---|
| 1 | GET | `https://sso.{domain}/sso/embed` | `id=gauth-widget&embedWidget=true&gauthHost=https://sso.{domain}/sso` | – | 建 cookie（`__cf_bm`/`SESSION` 等） |
| 2 | GET | `https://sso.{domain}/sso/signin` | `SIGNIN_PARAMS` | – | 带 `referer` = 上一步最终 URL；提取 `_csrf` |
| 3 | POST | 同上 | `SIGNIN_PARAMS` | `username`,`password`,`embed=true`,`_csrf` | 401 = 密码错；标题 `Success` = 成功 |
| 3b | POST | `https://sso.{domain}/sso/verifyMFA/loginEnterMfaCode` | `SIGNIN_PARAMS` | `mfa-code`,`embed=true`,`_csrf`,`fromPage=setupEnterMfaCode` | 仅 MFA 时；**CSRF 必须从 MFA 页面重新读取** |
| 4 | GET | `https://connectapi.{domain}/oauth-service/oauth/preauthorized` | `ticket`、`login-url`、`accepts-mfa-tokens=true` | – | OAuth1 头（**无 oauth_token**）；UA 换成 connectmobile |
| 5 | POST | `https://connectapi.{domain}/oauth-service/oauth/exchange/user/2.0` | – | `mfa_token=<v>` 或空 | OAuth1 头（**含 oauth_token**），`x-www-form-urlencoded` |

`SIGNIN_PARAMS`（注意 `gauthHost/service/source` 都被**覆盖**成 embed 地址）：
```
id=gauth-widget
embedWidget=true
gauthHost=https://sso.{domain}/sso/embed
service=https://sso.{domain}/sso/embed
source=https://sso.{domain}/sso/embed
redirectAfterAccountLoginUrl=https://sso.{domain}/sso/embed
redirectAfterAccountCreationUrl=https://sso.{domain}/sso/embed
```

正则：
- CSRF：`name="_csrf"\s+value="(.+?)"`
- 标题：`<title>(.+?)</title>`
- ticket：`embed\?ticket=([^"]+)`

**重定向链**（`requests` 自动跟随，需逐跳读取最终响应）：
- 无 MFA：`POST /sso/signin` → **200**，标题 `Success`
- 有 MFA：`POST /sso/signin` → 302 → `GET /sso/verifyMFA/loginEnterMfaCode?<SIGNIN_PARAMS>` 200（标题含 MFA）→ `POST` → 302 → `GET /sso/login?logintoken=…` 200 `Success`
- 密码错：401，标题 `GARMIN Authentication Application`

### 1.3 OAuth1 签名（易错点）

- 签名基串：`METHOD & percentEncode(urlWithoutQuery) & percentEncode(sortedEncodedParams.join("&"))`
- 签名密钥：`percentEncode(consumerSecret) + "&" + percentEncode(tokenSecret ?: "")`
- **`login-url` 在 URL 里不做编码，但在签名基串里被二次编码**（`https%253A%252F%252F…`）。搞错 → `401 Invalid signature`。
- **`mfa_token` 是 form body 参数，必须参与签名**。只改 body 不重签 → 失败。
- preauthorized 调用**不带** `oauth_token`；exchange 调用**必须带**。
- 返回值：preauthorized 是 `text/plain` 的 `oauth_token=…&oauth_token_secret=…[&mfa_token=…&mfa_expiration_timestamp=…]`；exchange 是 JSON。

### 1.4 Token 与刷新

exchange 成功返回 JSON，字段就是这些（其余客户端自行计算）：
`scope, jti, access_token, token_type, refresh_token, expires_in, refresh_token_expires_in`

- `expires_at = now + expires_in`（Unix 秒，**客户端计算**）
- `refresh_token_expires_at = now + refresh_token_expires_in`
- 过期判定：`expires_at < now`，**没有时钟偏移余量**
- **没有 refresh_token 授权**：刷新 = 用同一个 OAuth1 token 重跑第 5 步 exchange
- garth **不做 401 重试**；原项目靠 `_login_decorator` 抛异常后整链重登来兜底
- 认证头：`Authorization: Bearer <access_token>`（`token_type.title() + " " + access_token`）

### 1.5 活动列表

`GET https://connectapi.{domain}/activitylist-service/activities/search/activities?start=N&limit=100`，满页即继续翻页。
元数据字段：`activityId`、`activityName`、`startTimeGMT`、`distance`(米)、`duration`(秒)。

### 1.6 下载（FIT）

```
GET https://connectapi.{domain}/download-service/files/activity/{activityId}
Authorization: Bearer <access_token>
```
- 返回 **ZIP**（`Content-Type: application/x-zip-compressed`，`content-disposition: …"{id}.zip"`）
- 实测 10 个真实报文：每个 zip 恰好 1 个 DEFLATE 条目，名为 `{activityId}_ACTIVITY.fit`
- 不要发 `referer`
- 判定方式：按魔数 `PK\x03\x04`，不要依赖 Content-Type

### 1.7 上传（FIT）

```
POST https://connectapi.{domain}/upload-service/upload
Authorization: Bearer <access_token>
multipart/form-data，单个 part：name="file"，filename=<本地文件名>，Content-Type: text/plain
```

响应判定（**逐条照抄原项目规则**）：

| 条件 | 结果 |
|---|---|
| HTTP 202 且 `detailedImportResult.uploadId` 存在且非空 | `SUCCESS` |
| HTTP 409 且 `detailedImportResult.failures[0].messages[0].content` 含 `Duplicate Activity` | `DUPLICATE`（视为成功） |
| HTTP 409 其它 message | `FAILED` |
| 其它状态码 / 异常 / JSON 解析失败 | `FAILED` |

- 判空是 `is None or == ''`，**不能用真值判断**（`uploadId == 0` 也算成功）
- 成功响应是 **202**，并带 `location: …/activity-service/activity/status/{id}/{uuid}`；`uploadId` 是序列号不是活动 id
- garth 自带的 `upload()` 看不到 409（先 `raise_for_status()`），所以原项目自己用裸 POST —— Android 端同理，直接读状态码

### 1.8 域名差异

| 项 | 国际 | 中国 |
|---|---|---|
| `domain` | `garmin.com` | `garmin.cn` |
| SSO | `https://sso.garmin.com` | `https://sso.garmin.cn` |
| 全部 API（含下载/上传） | `https://connectapi.garmin.com` | `https://connectapi.garmin.cn` |

注意 **`connect.garmin.com` 是网页版，不是 API 域名**。

### 1.9 坑位清单（实现时必须遵守）

1. **登录后必须去掉 `User-Agent`**（原项目 `garmin_client.py:73` 显式删除，否则取不到数据）。
2. `login-url` 双编码问题（见 1.3）。
3. `mfa_token` 必须参与签名。
4. MFA 页面的 `_csrf` 必须重新读取。
5. Cloudflare：`__cf_bm`/`__cflb`/`_cfuvid` 必须保留并回传 → CookieJar 要覆盖 SSO 全程。
6. 重试不要覆盖 POST（urllib3 `allowed_methods` 默认不含 POST）。
7. 无 401 自动刷新 → Android 端要补一个「发请求前检查过期 + 401 后重登一次」的包装。
8. 下载返回 ZIP 但 `disposition` 里的文件名带 `.zip`；上传前若目标是 Garmin/Strava 需解包成裸 `.fit`。
9. `mfa_expiration_timestamp` 格式 `yyyy-MM-dd HH:mm:ss.SSS`（无时区、无 `T`）。
10. `refresh_expired` 在 garth 里是死代码，不要基于它做逻辑。


---

## 二、Coros（上传通道已恢复：改走训练中心 v2 通道，2026-10-27 实测）

Coros 的**下载**链路一直正常；**上传**链路的 OSS 临时凭证接口在 2026-10-03 被服务端改道，
现在**已有可用通道**：网页版「训练中心」的 BFF 代理。

**结论先行**：
- 上传通道**已恢复可用**，并已用**真实中国区账号（regionId=2）端到端跑通**一次完整上传：
  登录 → STS 凭证 → OSS `PUT` → `activity/fit/import` → 账号里确实多出一条活动；
- 旧的**匿名** STS 接口（`faq.coros.com/openapi/oss/sts`）已不可用（404 / 403）；
- 新的凭证入口是**带 Cookie 的 BFF 代理**，鉴权用 Coros 登录返回的 `accessToken`；
- **国际 / 欧洲区（AWS bucket）未实测**：本节所有「已实测」结论**只覆盖中国区（aliyun）**。

下载链路不受本次变更影响（2026-10-07 实测）：

| 能力 | 端点 | 实测 |
|---|---|---|
| 登录 | `POST https://teamcnapi.coros.com/account/login` | ✅ 成功（`userId=435248150115008513`，`regionId=2` 中国区） |
| 活动列表 | `GET {teamapi}/activity/query` | ✅ |
| 下载 | `POST {teamapi}/activity/detail/download` → `fileUrl` | ✅ |

### 2.1 变更时间与根因（上游）

| 项 | 内容 |
|---|---|
| 变更时间 | **2026-10-03**，Coros 关闭了「免登录（匿名）」的 OSS STS 临时凭证通道 |
| 上游记录 | `XiaoSiHwang/garmin-sync-coros` 的 issue **#41**（现象与根因）、PR **#42**（2026-10-08 合并，修复） |
| 本项目核实 | 2026-10-27 直接 curl 复现旧接口的 404 / 403 / 401，并定位到新的 v2 通道 |

上游 PR #42 的结论：Coros 关掉了匿名签名的 STS 通道，凭证改为由**网页版训练中心的 BFF 代理**
代发，代理用**登录态 Cookie** 鉴权，而不是静态 `sign`。

### 2.2 旧接口现状（实测 2026-10-27）

| 请求 | 响应 | 含义 |
|---|---|---|
| `GET https://faq.coros.com/openapi/oss/sts?bucket=…&service=…&app_id=…&sign=…&v=2` | **404**（nginx HTML） | 路由已不存在 —— 上游项目与 coros-connect 用的都是这个形态 |
| `GET https://faq.coros.com/openapi/oss/sts/?…`（**带尾斜杠**） | **403** `{"msg":"sts for this bucket is only available via the v2 channel"}` | 签名仍然有效，但该 bucket 被策略要求走 **v2 通道** |
| `GET https://faq.coros.com/openapi/v2/oss/sts/` | **401** `{"code":401,"msg":"unauthorized","data":{"errorCode":"AUTH_INVALID"}}` | v2 通道**存在**，但这个入口不接受现有的鉴权方式 |

> 第二行正是新通道存在的证据：服务端明确说「这个 bucket 只能走 v2 通道」，
> 而 v2 通道就是 2.3 的训练中心代理。

三个区域的 bucket **全部**返回同一条 v2 提示，且各自签名仍然有效：

| bucket / service | sign | 结果 |
|---|---|---|
| `coros-oss` / aliyun（中国） | `9AD4AA35AAFEE6BB1E847A76848D58DF` | 403 → 要求 v2 |
| `coros-s3` / aws（国际） | `E34EF0E34A498A54A9C3EAEFC12B7CAF` | 403 → 要求 v2 |
| `eu-coros` / aws（欧洲） | `877571111A1EE5316E4B590103D4B5B3` | 403 → 要求 v2 |

> 签名判定方法：`sign` 是**参与请求的静态密钥**，改动任何查询参数都会得到
> `{"msg":"signature error"}`。因此「收到 v2 提示」= 签名已通过、被策略拒绝；
> 而「signature error」= 这组 bucket/sign 不匹配。
> 例如 `coros-s3` + `9AD4…`、或 `coros-oss` + `E34E…` 都会得到 signature error。
>
> 签名是**按 bucket** 的（`coros-s3` 与 `eu-coros` 都是 aws，但签名不同）；
> 本项目实现里这三个签名按 regionId 保存（1 / 2 / 3），测试区域回退到按 service 取的
> 「尽力而为」签名。

历史探测中**仍然有效**的结论：

- 加 `channel=v2` / `version=2` 查询参数 → `signature error`（额外参数破坏签名）。
- `/openapi/oss/sts/v2/`、`/openapi/oss/v2/sts/` → `signature error`（路径变更同样破坏签名）。
- 带 `accesstoken` / `Authorization` 头 → 一律 `AUTH_INVALID`（在 `/openapi/v2/oss/sts/` 上）；
  换成训练中心代理后同样不被识别，答的是 `reason":"no_token"`（见 2.3）。
- 第三方库 `NYT87/coros-connect` 截至 2026-10-27 仍用旧的 v1 形态，且对中国区直接抛
  `Provider not implemented`。
- 上游 `XiaoSiHwang/garmin-sync-coros` 已在 PR #42 跟进新通道，不再是旧结论里的「同样不可用」。

### 2.3 新的 v2 通道 = 网页版训练中心的 BFF 代理（实测 2026-10-27，含真实账号 200）

新的凭证入口不是 `faq.coros.com` 上的路径，而是**网页版「训练中心」的 BFF 代理**：

| 区域 | 请求 |
|---|---|
| 中国区 | `GET https://trainingcn.coros.com/api/proxy/oss/sts?bucket=coros-oss&service=aliyun&v=2` |
| 国际 / 欧洲区 | `GET https://training.coros.com/api/proxy/oss/sts?bucket=coros-s3&service=aws&v=2` |

**鉴权方式：Cookie `CPL-coros-token=<Coros 登录返回的 accessToken>`**
（**不是** `accesstoken` 头，也**不是** `Authorization` 头）。

实测证据（2026-10-27；前三行为直接 curl，末行为**真实账号经应用代码**发出）：

| 请求 | 响应 | 说明 |
|---|---|---|
| 不带 Cookie | HTTP **401** `{"code":401,"msg":"unauthorized","reason":"no_token"}` | 端点存在，但没有令牌 |
| 带 `Cookie: CPL-coros-token=dummytoken123` | HTTP **401** `{"code":401,"msg":"unauthorized","reason":"token_invalid"}` | **cookie 被识别**，只是令牌无效 |
| 改用 `accesstoken:` 头 / `Authorization:` 头 | 仍是 `reason":"no_token"` | 这两种头**不被识别** |
| 带**真实** `Cookie: CPL-coros-token=<登录返回的 accessToken>` | HTTP **200** `{"code":200,"data":{"credentials":"…"}}` | **成功**（2026-10-27 真实中国区账号，见 2.7） |

> `no_token` 与 `token_invalid` 的区别就是证据本身：只有 Cookie 能走到「令牌校验」这一步，
> 所以端点、Cookie 名、主机分区都是对的。

成功响应结构与旧接口一致（**已用真实账号实测确认** —— 2026-10-27 中国区账号返回 HTTP **200**，
响应体逐字如下）：

```
{"code":200,"data":{"credentials":"<base64+salt>"}}
```

凭证 blob 的解法**不变**：去掉固定盐 `9y78gpoERW4lBNYL` → base64 解码 → JSON。

实测（**阿里云 / 中国区**形态）解码后的字段名与长度：

| 字段 | 含义 | 实测形态 | 长度 |
|---|---|---|---|
| `AccessKeyId` | 临时 AK | 形如 `STS.NZ6i…`（`STS.` 前缀是 STS 临时凭证的特征） | 29 字符 |
| `AccessKeySecret` | 临时 SK | — | 44 字符 |
| `SecurityToken` | 会话令牌（上传时放在 `x-oss-security-token` 头） | — | 876 字符 |

> 「`AccessKeySecret` 还是 `SecretAccessKey`」这个悬念**已由中国区实测解决**：阿里云形态就是
> `AccessKeyId` / `AccessKeySecret` / `SecurityToken`。
> AWS 形态（`SecretAccessKey` / `SessionToken`）**未实测**，实现里对两套字段名都做了兼容。

### 2.4 区域 → bucket / service → BFF 主机

| regionId | bucket | service | BFF 主机 |
|---|---|---|---|
| 1 | `coros-s3` | aws | `training.coros.com` |
| 2 | `coros-oss` | aliyun | `trainingcn.coros.com` |
| 3 | `eu-coros` | aws | `training.coros.com` |
| 101 | `test-coros` | aws | `training.coros.com` |
| 102 | `coros-test-bj` | aliyun | `trainingcn.coros.com` |
| 103 | `test-eu-coros` | aws | `training.coros.com` |

bucket / service 来自上游 `sts_config.py`；BFF 主机按「阿里云 → 中国区站点，其余 → 国际站点」
对应，本项目 `CorosStsProvider` 的映射与上表一致。

### 2.5 本项目的落地方式

| 位置 | 改动 |
|---|---|
| `core/oss/CorosStsProvider.kt` | `fetch(regionId, accessToken)` 按顺序尝试两个通道：① 带 `CPL-coros-token` Cookie 的**训练中心代理**；② 旧的**匿名 v1 接口**（保留作回退，以防 Coros 恢复匿名通道）。**任一通道成功即返回凭证**；全部失败时把每个通道的失败原因合并成一条可读错误。 |
| `platform/coros/CorosGateway.kt` | `CorosTarget.upload()` 把 Coros 登录得到的 `accessToken` 传给 STS 提供方。 |
| `core/net/HttpClients.kt` | 新增一个专供 STS 使用的 OkHttp 客户端 `corosSts`，`cookieJar = CookieJar.NO_COOKIES`。 |

> **为什么 STS 客户端必须禁用 cookie jar**：OkHttp 的 `BridgeInterceptor` 会用 cookie jar 里
> 该主机的 cookie **覆盖手工设置的 `Cookie` 头**（源码 `requestBuilder.header("Cookie", cookieHeader)`），
> 共享 jar 会把 `CPL-coros-token` 悄悄抹掉 → 401 `no_token`。
> 禁用 jar 才能保证该 Cookie 原样发出。

### 2.6 上游 PR #42 顺带修掉的两个次要缺陷（本项目原本就没有）

上游 PR #42 除通道外还修了两处。本项目**一开始就没有这两个问题**，列出以免误判：

| 上游缺陷 | 本项目做法 |
|---|---|
| `garmin_sync_coros.py` 上传异常时 `print(err)` 后 `exit()`，**CI 仍显示绿色** | `SyncEngine` 是**逐条活动隔离**：单条失败只标记该条并继续后面的活动 |
| `garmin_db.getUnSyncActivity()` 只查 `is_sync_coros = 0`，被标记为 2（异常）的活动**永不重试** | `SyncPlanner.selectCandidates` 把 `SyncStatus.FAILED` 与 `UNSYNCED` **同等对待**（失败会自动重试），另有「重试失败项」的 `SyncEngine.requeueFailed()` |

### 2.7 中国区端到端实测（2026-10-27，真实账号）

测试账号：`userId=435248150115008513`，`regionId=2`（中国区），`service=aliyun`，
`bucket=coros-oss`。走的是**应用自己的代码路径**，不是另写的一次性脚本。

| 阶段 | 动作 | 结果 |
|---|---|---|
| 1 | `POST {teamapi}/account/login` | ✅ 成功（`userId=435248150115008513`、`regionId=2`） |
| 2 | `CorosStsProvider.fetch()` 取 STS 凭证 | ✅ HTTP **200**，拿到 `AccessKeyId` / `AccessKeySecret` / `SecurityToken` |
| 3 | 阿里云 OSS `PUT`（V1 签名 HMAC-SHA1），对象键 `fit_zip/{userId}/{md5}.zip` | ✅ **成功** |
| 4 | `POST {teamapi}/activity/fit/import` | ✅ 成功，服务端 `result=0000` 且 `data.status==2` |
| 5 | 应用真实入口 `CorosTarget.upload()`（ensureZip → STS → OSS PUT → import） | ✅ 返回 `Success`，**账号里新增一条活动**（id=`481335029083963393`） |

第 5 步是关键：它证明的是**应用里那套代码**能跑通，而不是一段一次性脚本能跑通 ——
`CorosTarget.upload()` 判定成功的条件正是服务端 `result=0000` 且 `data.status==2`
（见 `CorosClient.importFromOss()`）。

> 本次导入的载荷取自账号里**原有**活动（`太原市 公路骑行`，2.2 km），所以新出现的那条活动
> 其实是它的**副本** —— 这不是 bug，而是 Coros 不去重导致的必然结果，详见 2.8。

### 2.8 重要发现：Coros **不按内容去重**（2026-10-27 实测）

把账号里**已有**的活动（`太原市 公路骑行`，2.2 km）重新打包上传并导入（即 2.7 第 5 步那次导入），
Coros **照样新建了一条**同名、同时长、同距离的活动（id=`481335029083963393`），
既没有「已存在」的提示，也没有拒绝。

两个叠加的原因：

| 原因 | 说明 |
|---|---|
| 每次打包的 ZIP **md5 都不同** | ZIP 内含时间戳 → 内容变了 → 对象键 `fit_zip/{userId}/{md5}.zip` 每次都是**新对象** |
| Coros 服务端**不比对内容** | 即使用户、时间、距离完全一致，也按新活动处理（不是「同 FIT 判重」） |

**因此：同一活动重复同步会得到「新对象 + 新活动」。**
去重**完全依赖本项目的账本 `sync_records`**（主键 `sourcePlatform` + `targetPlatform` +
`activityId`）—— 这与上游用 SQLite 的 `is_sync_coros` 标志位是同一件事。

> **对用户的含义**：本项目「重新同步已同步活动」的开关（`includeAlreadySynced`）在 Coros 侧
> **会产生副本活动**，因为 Coros 侧没有任何去重兜底。开启前请知情。

### 2.9 结论与影响

- 上传通道**已恢复可用**，且**中国区链路已端到端验证通过**（真实账号跑通登录 → STS → OSS →
  import，账号里确实多出一条活动）：四个方向（`garmin→coros`、`strava→coros`、`igpsport→coros`、
  `onelap→coros`）**不再被 STS 阻塞**。
- 上一轮「协议级验证」留下的两个悬念已经关闭：
  ① 凭证字段名 —— 中国区实测为 `AccessKeyId` / `AccessKeySecret` / `SecurityToken`；
  ② 能否拿到 **200 + 凭证** —— **已拿到**，且结构与旧接口完全一致。
- 探测方法仍然有效、值得保留：BFF 代理在**不带 Cookie** 时答 `no_token`、**带无效 Cookie** 时答
  `token_invalid`，两者能区分「端点 / 主机分区 / Cookie 机制错了」与「令牌本身失效」，
  是排查这条通道的第一手线索。
- **国际 / 欧洲区（AWS）尚未实测**：本次只跑通了中国区（aliyun）通道，见 2.10。
- **去重要靠本项目的账本**：Coros 不按内容去重，重复同步会产生副本活动，见 2.8。
- 其余 **8 条方向不受影响**（写入 Garmin 走 multipart 上传、写入 Strava 走 `/uploads`，
  都不经过 OSS）。
- 应用内的**离线导出**（`ActivityExporter`）保留为兜底退路：把活动存成本地 `.fit`，
  可手动导入任意平台。

### 2.10 仍待验证的事项

以下是**中国区以外**或**传输层以下**的部分，本次真实账号实测**没有覆盖**，不要当成已验证：

1. **国际 / 欧洲区（regionId 1 / 3，`coros-s3` / `eu-coros`，AWS）能否签发凭证**：
   测试账号是中国区，这两套 bucket 走的是**另一条 STS 通道**（`training.coros.com` + `service=aws`）。
   目前**只有兼容代码，未实测**。
2. **AWS SigV4 签名上传（`AwsS3Uploader`）的真实可用性**：国际 / 欧洲区的对象存储是 AWS S3，
   本项目用**手写的 SigV4**（region `eu-central-1`）签名，**未对真实 bucket 验证过**。
3. **STS 凭证的有效期与是否需要续签**：临时凭证会过期，但响应体里**没有**明确的过期字段；
   本项目每次上传都重新取一次凭证（`CorosTarget.upload()` 内部调用 `CorosStsProvider.fetch()`），
   所以目前**没有踩到过期问题**，但**有效期长度未实测**。

### 2.11 实时体检工具：`CorosLiveCheck`

Coros 这条通道**三周内已经变了两次**（2026-10-03 关掉匿名 STS，2026-10-27 才摸到训练中心代理），
所以配了一个**可随时复测**的实时体检用例：

`app/src/test/java/com/sportfit/sync/platform/coros/CorosLiveCheck.kt`

- **凭据只从环境变量读取**，不写进代码、不落盘：`COROS_EMAIL` / `COROS_PASSWORD`；
  没设置就**自动跳过**，常规单测跑批保持干净。
- **阶段由 `COROS_LIVE_STAGES` 选择**（逗号分隔），默认 `a,b,c`（**不改账号**）：

| 阶段 | 做什么 | 对账号的影响 |
|---|---|---|
| `a` | 登录（`account/login`） | **有** —— Coros 是单设备登录，手机 App 会被挤下线 |
| `b` | 取 OSS 凭证（训练中心通道） | 无 |
| `c` | `PUT` 一个 ZIP 对象进 bucket | 无（只留一个孤儿对象） |
| `d` | 走 `CorosTarget.upload()` 做 `activity/fit/import` | **有** —— **会在账号里创建活动** |
| `e` | 列出账号最新活动 | 无 |

用法：

```bash
cd /Users/mac/Code/android/sport-fit-sync-app
export COROS_EMAIL='you@example.com' COROS_PASSWORD='…'
# 默认 a,b,c：不改账号
./build.sh :app:testDebugUnitTest --tests "*CorosLiveCheck" --rerun --console=plain -i
# 需要真正导入时才加 d
COROS_LIVE_STAGES=a,b,c,d,e ./build.sh :app:testDebugUnitTest --tests "*CorosLiveCheck" --rerun --console=plain -i
```

> `d` 阶段会**在账号里新建活动**，且 Coros 不去重（见 2.8）——**别把 `d` 放进常规跑批**。


---

## 三、Coros API 的两个硬性约定（2026-10-07 真机实测）

这两点都**无法从上游代码看出**，只有用真实账号请求才会暴露。Android 端曾因此产生两个真实缺陷。

### 3.1 业务错误用 HTTP 200 返回，不是 401

```
有效 token:  200, 195,548 字节  {"apiCode":"C33BB719","data":{"count":972,"dataList":[…]}…}
失效 token:  200,      82 字节  {"result":"1019","message":"Access token is invalid"}
```

**只判断 HTTP 状态码会漏掉鉴权失效。** 后果很隐蔽：查询返回空列表（无异常）→ 上层以为
缓存的 token 仍然有效 → 永远不重新登录 → 界面显示「0 条活动」而不是报错。

正确做法：解析响应体，`result` 存在且不为 `"0000"` 即为业务失败；
`result == "1019"` 或 message 含 "token" 时按「登录态失效」处理并触发重新登录。
参见 `CorosClient.requireBusinessSuccess()`。

> 同类风险：其余平台也可能用 200 返回业务错误。新增平台时**不要只看 HTTP 状态码**。

### 3.2 下载接口的 `sportType` 必填且必须精确匹配

```
POST {teamapi}/activity/detail/download?labelId={id}&sportType=200&fileType=4
  → 200 {"result":"0000","data":{"fileUrl":"https://oss.coros.com/fit/…/….fit"}}

sportType=1（错误值）  → 200 {"result":"1031","message":"Parameter input error"}
省略 sportType         → 200 {"result":"1031","message":"Parameter input error"}
```

`labelId` + `sportType` 是一对定位键。默认成 1（跑步）会让**所有非跑步活动下载失败**，
且错误信息 `Parameter input error` 完全看不出是哪个参数的问题。

因此本项目把 `PlatformSource.download()` 的入参从 `activityId: String` 改成了
`activity: ActivityMeta` —— 有的平台除 id 外还需要类型等字段，接口只传 id 是不够的。

---

## 四、Garmin 侧：SSO 登录有速率限制

短时间内反复登录会触发：

```
requests.exceptions.HTTPError: 429 Client Error: Too Many Requests
  for url: https://sso.garmin.com/sso/signin?…
```

本次在约 10 分钟内触发 5 次 SSO 登录后出现。

**缓解方式**：`garth` 支持把 OAuth 令牌落盘（`client.dump()` / `client.load()`），但上游
Python 项目没有使用，所以**每次运行脚本都是一次全新 SSO 登录**。
Android 端把令牌持久化在 `accounts.tokenCipher`（Keystore 加密），正常运行**不会**重复登录 ——
这是移动端相对脚本的一个实际优势。调 Garmin 相关代码时注意这一点：不要在循环里反复登录。

---

## 五、iGPSPORT（新增上传通道：OSS 暂存 + uploadByOss 通知，2026-10-31 接入）

### 5.1 概述：以前为什么只把 iGPSPORT 当源

iGPSPORT 一直只被当作**源平台**：上游 Python 项目 `garmin-sync-coros` 的 12 个同步脚本里，
只有 `igpsport_sync_{garmin,coros,strava}.py` 三个方向 —— **上游从来没把 iGPSPORT 当过目标**，
本项目早期照抄了这个结论（`Platform.IGPSPORT` 的 `canBeTarget = false`）。

但「上游没接」≠「iGPSPORT 没有导入接口」。iGPSPORT 的**网页端确实有**一条导入通道，
只是它是「先暂存、再通知」的两段式结构：

```
登录换 token → 用 token 换阿里云 OSS 临时凭证 → 把 FIT 传到 OSS → 通知服务端导入
```

本项目 2026-10-31 把这条通道接上了（方案移植自另一个 Python 项目
[Dunky-Z/FitSync](https://github.com/Dunky-Z/FitSync) 的 `src/igpsport_client.py`），
iGPSPORT 从此**既作源也作目标**。

> ⚠️ **端到端未用真实账号实测**：本节描述的链路**没有用真实 iGPSPORT 账号跑通过一次完整上传**。
> 详见 5.10。

### 5.2 区域差异：token 与站点绑定

iGPSPORT 有**中国站**与**国际站**两套彼此独立的站点：

| 区域（账号的 `region` 字段） | `baseUrl` | Origin / Referer |
|---|---|---|
| `cn`（中国站） | `https://prod.zh.igpsport.com/service` | `https://login.passport.igpsport.cn` |
| `global`（国际站） | `https://prod.en.igpsport.com/service` | `https://login.passport.igpsport.com` |

**关键：token 只在签发它的那个站点上有效** —— 中国站签发的 token 拿到国际站用会 401。
所以「用哪个站点」是**账号属性**，不是每次请求的可选项：账号表里的 `region` 决定 `baseUrl`，
换站点等于换账号（这也是 UI 上两个站点分成两条账号记录的原因）。

### 5.3 完整链路（4 步）

#### 步骤 1：登录换 Bearer token

```
POST {baseUrl}/auth/account/login
Content-Type: application/json
Origin: https://login.passport.igpsport.cn          ← 按站点切换
Referer: https://login.passport.igpsport.cn/

{"username":"<账号>","password":"<密码>","appId":"igpsport-web"}
```

成功响应（HTTP 200）：

```json
{"code":0,"message":"success","data":{"access_token":"<bearer>"}}
```

- 取 `data.access_token`，后续每个请求都带 `Authorization: Bearer <token>`。
- `appId` 固定为 `igpsport-web`（网页端的应用标识）。
- `code != 0` 就是业务失败 —— **不能只看 HTTP 状态码**（与 Coros 同类问题，见第三节）。

#### 步骤 2：取 OSS 临时凭证

```
GET {baseUrl}/mobile/api/AliyunService/GetOssTokenForApp
Authorization: Bearer <token>
```

成功响应（HTTP 200）：

```json
{"code":0,"message":"success","data":{
  "accessKeyId":"...",
  "accessKeySecret":"...",
  "securityToken":"...",
  "endpoint":"oss-cn-xxx.aliyuncs.com",
  "bucketName":"..."
}}
```

- 这是**阿里云 STS 临时三元组 + OSS 落点**。`endpoint` 与 `bucketName` **由平台下发**，
  客户端既不猜也不硬编码。
- 2026-10-31 实测：不带 `Authorization` 请求这个路由 → **HTTP 401**（路由存在，需要鉴权）。

#### 步骤 3：把 FIT 传到阿里云 OSS

```
PUT https://{bucket}.{endpoint-host}/{object-key}
Authorization: OSS {accessKeyId}:{signature}
Date: <RFC1123 GMT>
Content-MD5: <base64(md5(payload))>
Content-Type: application/octet-stream
x-oss-security-token: {securityToken}

<裸 FIT 字节流>
```

- **载荷必须是裸 FIT，不能是 ZIP**（与 Garmin / Strava 同一条规则，见 5.9 第 2 条）。
- 签名用**阿里云 OSS V1（HMAC-SHA1）**，canonical resource 是 `/{bucket}/{object-key}`。
- 对象键规则：`1456042-{uuid4}`（`1456042` 是参考项目沿用的固定应用标识）。

#### 步骤 4：通知 iGPSPORT 导入

```
POST {baseUrl}/web-gateway/web-analyze/activity/uploadByOss
Authorization: Bearer <token>
Content-Type: application/json

{"fileName":"<活动标题>.fit","ossName":"1456042-<uuid4>"}
```

成功判定：**HTTP 200 且响应体 `code == 0`**。

2026-10-31 实测（不带 `Authorization`）：**HTTP 403**，响应体是

```json
{"code":0,"message":"success","data":"The token was expected to have 3 parts, but got 0."}
```

—— 路由存在，服务端把 bearer token 当 JWT 解析。

> ⚠️ 注意这个 403 响应体的 `code` **也是 0**。只判 `code == 0` 会把鉴权失败当成成功，
> 所以顺序必须是**先判 HTTP 状态码，再判 `code == 0`**。

### 5.4 OSS 细节

#### 对象键

```
{应用标识}-{uuid4}      例：1456042-3f2a1c9e-…-（uuid4）
```

#### 签名方式：阿里云 V1（HMAC-SHA1）

与 Coros 通道用的是**同一套 V1 签名**（`core/oss/OssUploaders.kt` 的 `AliyunOssUploader`）：

```
PUT\n
{base64(md5(body))}\n
{contentType}\n
{date}\n
x-oss-security-token:{securityToken}\n
/{bucket}/{objectKey}
```

`Authorization: OSS {accessKeyId}:{base64(hmacSha1(accessKeySecret, stringToSign))}`

#### 地址风格：虚拟托管优先，路径风格兜底

平台下发的 `endpoint` 形态不固定（可能带 `https://` 前缀、尾部斜杠，甚至带路径段），
所以客户端**先把 endpoint 归一化**：去掉 scheme、去掉尾部 `/`、去掉路径段，只留主机名。

然后：

1. 先试**虚拟托管风格**：`https://{bucket}.{host}/{object-key}`
2. 失败**回退一次**到**路径风格**：`https://{host}/{bucket}/{object-key}`

V1 的 canonical resource 在**两种风格下都是** `/{bucket}/{object-key}`，
所以**同一个签名覆盖两种风格**，回退时不需要重算签名。

> 这个回退**只对平台下发的 endpoint 开启**。Coros 的 bucket 是已知的
> （`coros-oss` @ `oss-cn-beijing.aliyuncs.com`），而且虚拟托管风格是拿真实账号
> 验证过的，所以它保持**单次尝试 + 原错误消息**不变——否则一次真正的权限错误
> 会被第二次注定失败的请求掩盖掉原因。实现见
> `core/oss/OssUploaders.kt` 的 `AliyunOssUploader(fallbackToPathStyle = …)`。

#### content-type

`application/octet-stream`（裸 FIT 没有标准 MIME 类型）。

### 5.5 活动标题（`fileName`）规则

iGPSPORT 里显示的活动标题**来自我们发过去的 `fileName`**，而不是从 FIT 内部解析出来的。

规则：

1. 取**源活动的标题**（源平台提供的活动名）；
2. 文件名非法字符 `[<>:"/\|?*]` 全部替换成 `_`，再去掉首尾空白；
3. **扩展名固定为 `.fit`**（标题本身已以 `.fit` 结尾时不再重复追加），
   整体截断到 120 字符；
4. 标题为空 / 空白时，退回用**载荷的文件基名**（去扩展名）；再空则用 `activity`。

也就是说，「活动在 iGPSPORT 里叫什么」这件事**由源平台决定**：
Garmin → iGPSPORT 传过去也叫 Garmin 上的那个名字，Strava → iGPSPORT 同理。

### 5.6 目标端兜底比对（跨平台去重）

iGPSPORT 的上传目标**实现了 `listActivities`**（复用步骤 1 的 token 与 `queryMyActivity` 接口），
所以引擎的「目标端兜底比对」对 iGPSPORT 同样生效 —— 上传前先在目标端活动列表里比对，
同一份数据不会被传第二遍。

### 5.7 token 的缓存与探活

- Bearer token **缓存在账号行**里（`accounts.tokenCipher`，用 Keystore 支撑的 `CryptoBox` 加密落库）。
- 用缓存 token 之前先**探活**：发一次 `queryMyActivity`（`pageSize=1`）。
- 探活拿到 **HTTP 401** → 判定登录态失效 → **重新登录**并覆盖缓存。

### 5.8 本项目的落地方式

| 位置 | 职责 |
|---|---|
| `platform/igpsport/IgpsportGateway.kt` | `IgpsportSource`（下载）+ `IgpsportTarget`（上传）；目标端复用同一个 `IgpsportClient` 的 token 与 `queryMyActivity` |
| `platform/igpsport/IgpsportOssProvider.kt` | `IgpsportOssProvider` 取 `GetOssTokenForApp` 的凭证，解析成 `IgpsportOssCredentials`（含平台下发的 `endpoint` / `bucketName`） |
| `core/oss/OssUploaders.kt` | 泛化后的阿里云 V1 签名上传器：`OssUploaders.forAliyun(AliyunOssCredentials)`，endpoint / bucket 由调用方给出（Coros 与 iGPSPORT 共用） |
| `domain/Models.kt` | `Platform.IGPSPORT` 的 `canBeTarget` 由 `false` 改为 `true` |

上传顺序（`IgpsportTarget.upload()`）：

```
ensureRawFit(payload)                      ← 必须是裸 FIT
  → IgpsportOssProvider 取 STS 凭证
  → OssUploaders.forAliyun(...).upload("1456042-{uuid4}", bytes, "application/octet-stream")
  → POST {baseUrl}/web-gateway/web-analyze/activity/uploadByOss
  → 判定：HTTP 200 且 code == 0
```

### 5.9 坑位清单（实现时必须遵守）

1. **成功判定是「HTTP 200 且 `code == 0`」**，不能只看 HTTP 状态码 ——
   iGPSPORT 的业务错误会用 200 返回（与 Coros 同类问题，见第三节）；
   而鉴权失败返回的是 403 + `code == 0` 的畸形响应体（见步骤 4）。
2. **`fileName` 必须带 `.fit` 扩展名**，且**载荷必须是裸 FIT，不能是 ZIP**。
3. **区域是账号属性**：token 与站点绑定，中国站的 token 在国际站用会 401。
4. **`endpoint` / `bucket` 由平台下发**，不要硬编码；先归一化再拼 URL。
5. **虚拟托管 → 路径风格只回退一次**：两次都失败就是真失败，不要无限重试。
6. **`1456042-` 这个对象键前缀是参考项目的固定应用标识**，不要改。
7. **`fileName` 的非法字符要清洗**（`[<>:"/\|?*]` → `_`），否则可能被服务端拒绝。
8. **OSS PUT 成功不等于导入成功**：导入是否成立要看步骤 4 的 `uploadByOss` 判定，
   中间会留下一个孤儿对象（OSS 上没有对应活动），这与 Coros 通道的行为一致。

### 5.10 端到端未用真实账号实测

**这条链路没有用真实 iGPSPORT 账号跑通过一次完整上传。**

已实测的只有**路由探活**（2026-10-31，均为未带有效 token 的请求）：

| 请求 | 响应 | 含义 |
|---|---|---|
| `GET {baseUrl}/mobile/api/AliyunService/GetOssTokenForApp` | **HTTP 401** | 路由存在，需要鉴权 |
| `POST {baseUrl}/web-gateway/web-analyze/activity/uploadByOss` | **HTTP 403** `{"code":0,"message":"success","data":"The token was expected to have 3 parts, but got 0."}` | 路由存在，服务端把 bearer token 当 JWT 解析 |

**未实测**：带真实 token 的登录 → 取 OSS 凭证 → OSS `PUT` → `uploadByOss` 通知 → 账号里出现新活动。

因此本节的所有具体结论（凭证字段名、OSS 参数、对象键规则、标题规则）**来自参考项目
[Dunky-Z/FitSync](https://github.com/Dunky-Z/FitSync) 的 `src/igpsport_client.py` 与本项目的实现**，
**不是**本项目录下的真实报文。请按「协议级实现」对待，不要按「已验证」对待 ——
这与 Garmin / Coros / Strava 三条通道的实测状态**不同**。
