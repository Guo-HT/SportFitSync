# 开发与调试手册（模型交接版）

> 面向接手本项目的下一个模型/开发者。本文只写**本会话中实际执行并验证过**的操作，
> 未验证的会显式标注「未验证」。
>
> 目标工程：`/Users/mac/Code/android/sport-fit-sync-app`
> 源项目（Python，逻辑来源）：`/Users/mac/Code/pyCode/garmin-sync-coros`

---

## 1. 工具链与目录职责

| 组件 | 版本 | 位置 |
|---|---|---|
| Gradle | 8.11.1 | `.tools/gradle-8.11.1/`（工程内，gitignored） |
| AGP | 8.10.1 | Maven |
| Kotlin / KSP | 2.1.21 / 2.1.21-2.0.1 | Maven |
| JDK | 21（Android Studio 内置 JBR） | `/Applications/Android Studio.app/Contents/jbr/Contents/Home` |
| Android SDK | platforms `android-33/36/36.1`，build-tools `30.0.3/36.1.0/37.0.0` | `~/Library/Android/sdk` |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | `app/build.gradle.kts` |

**所有可写产物都放在工程内，不污染用户全局状态**（这是本机沙箱限制导致的设计，见 §8.1）：

| 目录 | 用途 | 是否可删 |
|---|---|---|
| `.tools/` | Gradle 发行版 | 是（`build.sh` 会自动重新下载） |
| `.gradle-home/` | `GRADLE_USER_HOME`（依赖缓存、wrapper） | 是（会重新下载依赖） |
| `.android-home/` | `ANDROID_USER_HOME`（debug keystore、AVD 元数据） | 是（删后需重新生成签名密钥） |
| `.dbcheck/` | 取证时临时存放拉下来的数据库 | 是 |

以上四者都已在 `.gitignore` 中。

---

## 2. 环境准备（只做一次，`build.sh` 已自动化）

**不要直接调用 `gradle`**，本机 PATH 上没有 Gradle。统一用 `./build.sh`，它会设置好全部环境变量：

```bash
JAVA_HOME          = /Applications/Android Studio.app/Contents/jbr/Contents/Home
GRADLE_USER_HOME   = <repo>/.gradle-home
ANDROID_USER_HOME  = <repo>/.android-home
```

### 2.1 为什么不用系统 Java

本机 `java -version` 是 **13**（另有 11），而 AGP 8.10 要求 **17+**，因此必须指向 Android Studio 的 JBR（21.0.10）。`build.sh` 里已做回退：若 `JAVA_HOME` 未设置且 JBR 不存在，会尝试 `/usr/libexec/java_home -v 17+`。

### 2.2 为什么需要 `ANDROID_USER_HOME`

默认 debug keystore 生成在 `~/.android/debug.keystore`。在本机沙箱下该目录不可写，构建会以如下错误失败：

```
Execution failed for task ':app:validateSigningDebug'.
> java.io.IOException: Unable to create debug keystore in /Users/mac/.android because it is not writable.
```

把 `ANDROID_USER_HOME` 指到工程内即可解决（已验证）。

### 2.3 Gradle 发行版下载

`services.gradle.org` 在本网络下极慢（实测约 0.5 MB/min，136 MB 需要数小时）。
`build.sh` 使用**腾讯镜像**，实测约 3.8 MB/s：

```bash
curl -L -o gradle.zip https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip
```

`settings.gradle.kts` 里的 Maven 仓库同样把阿里云镜像放在前面、官方源作为回退。

### 2.4 SDK 路径

`local.properties`（gitignored，**换机器需要重新创建**）：

```properties
sdk.dir=/Users/mac/Library/Android/sdk
```

---

## 3. 日常命令

```bash
cd /Users/mac/Code/android/sport-fit-sync-app

./build.sh :app:assembleDebug          # 构建 debug APK
./build.sh :app:testDebugUnitTest      # JVM 单元测试（快，秒级）
./build.sh :app:connectedDebugAndroidTest   # 仪器测试（需模拟器在线）
./build.sh clean                       # 清理构建产物
```

产物位置：`app/build/outputs/apk/debug/app-debug.apk`

测试报告：

| 类型 | 路径 |
|---|---|
| JVM 单测 | `app/build/test-results/testDebugUnitTest/*.xml` |
| 仪器测试 | `app/build/outputs/androidTest-results/connected/debug/*.xml` |

快速汇总测试结果：

```bash
grep -h "<testsuite " app/build/test-results/testDebugUnitTest/*.xml \
  | sed 's/.*tests="\([0-9]*\)".*failures="\([0-9]*\)".*/\1 \2/' \
  | awk '{t+=$1;f+=$2} END {print t" tests, "f" failures"}'
```

安装与启动：

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell am force-stop com.sportfit.sync.debug
$ADB shell am start -n com.sportfit.sync.debug/com.sportfit.sync.MainActivity
```

> **包名注意**：debug 构建带 `applicationIdSuffix = ".debug"`，包名是 `com.sportfit.sync.debug`，
> Activity 仍是 `com.sportfit.sync.MainActivity`。release 包名为 `com.sportfit.sync`。
> `adb shell pm list packages | grep sportfit` 可确认是否已安装。

---

## 4. 模拟器操作

现有 AVD：**Pixel_7_Pro**，API 33（`system-images/android-33/google_apis_playstore/arm64-v8a`），RAM 2048。

```bash
# 启动（-no-snapshot 避免快照锁问题）
nohup ~/Library/Android/sdk/emulator/emulator -avd Pixel_7_Pro \
  -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect \
  > /tmp/emulator.log 2>&1 &

# 等待就绪（本机软件渲染，冷启动到可交互约 40 秒；再等 20 秒让应用首帧渲染）
~/Library/Android/sdk/platform-tools/adb wait-for-device
~/Library/Android/sdk/platform-tools/adb devices      # 期望 emulator-5554  device

# 截图
$ADB exec-out screencap -p > docs/screenshots/xx.png

# 清空应用数据（保留 APK）
$ADB shell pm clear com.sportfit.sync.debug

# 确认前台窗口
$ADB shell dumpsys window | grep mCurrentFocus
```

### 4.1 快照锁（需要更宽权限）

若 AVD 上次异常退出，模拟器会报：

```
FATAL | A snapshot operation for 'Pixel_7_Pro' is pending and timeout has expired. Exiting...
```

原因是 `~/.android/avd/Pixel_7_Pro.avd/` 下残留 `*.lock`，而该目录在**工作区之外**，
默认沙箱模式下不可写。需要在 bash 工具中以一次性更宽权限重试：

```
sandbox_permissions: danger-full-access
justification: 启动 Android 模拟器需要在 ~/.android/avd 下删除上次异常退出残留的快照锁文件并写入 AVD 运行数据
```

---

## 5. 应用内调试入口

这些是专门为「没有真实账号也能验证」而做的设施，**调试时优先用它们**。

### 5.1 演练模式（仅 debug 构建可见）

`Platform.DEMO`（显示名「演练模式」）：不联网、不需要账号，产生 12 条合成活动。

- 选择方式：源平台与目标平台都选「演练模式」（源≠目标的限制对演练模式豁免）
- 行为是**确定性**的，方便复现：
  - `demo-5` → 固定上传失败（用来验证失败重试）
  - ID 能被 3 整除（3/6/9/12）→ 返回「重复」
  - 其余 → 成功

release 构建中它被 `Platform.pickable(includeDemo = BuildConfig.DEBUG)` 过滤掉。

### 5.2 观察运行状态

| 想知道什么 | 去哪里看 |
|---|---|
| 实时进度 / 逐条日志 / 取消 | 首页同步中卡片 |
| 单次运行完整日志（含级别着色） | 记录 → 某次运行 → 日志 |
| 临时文件、活动缓存、日志、历史占用 | 设置 → 本地缓存 |
| 导出文件 | `/sdcard/Download/SportFitSync/` |

### 5.3 应用内数据位置（设备上）

| 内容 | 路径（相对 app 私有目录） | 备注 |
|---|---|---|
| Room 数据库 | `databases/sportfit-sync.db`（+ `-wal`、`-shm`） | 应用首次运行后才有 |
| 同步中转文件 | `cache/fit-cache/job-{jobId}/` | 成功的会被删掉，失败才留 |
| DataStore 设置 | `files/datastore/sportfit-settings.preferences_pb` | **首次写入设置前该文件不存在** |
| WorkManager 数据库 | `no_backup/androidx.work.workdb`（+ `-wal`、`-shm`） | 定时同步用 |

数据库 7 张表：`accounts`、`cached_activities`、`sync_records`、`sync_jobs`、`sync_logs`、
`exclusions`、`strategies`。

查看全部应用私有文件：

```bash
$ADB shell run-as com.sportfit.sync.debug find . -type f
```

> 调试技巧：想触发一次设置写入（例如为了让 DataStore 文件出现），
> 到「设置 → 后台同步 → 间隔」点任意一个档位即可（`python3 tools/uitap.py "3 小时"`）。

---

## 6. 设备端数据取证

这是验证「账本/计数器是否真的正确」最有力的手段 —— 比看 UI 可靠。

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
SQLITE=~/Library/Android/sdk/platform-tools/sqlite3
PKG=com.sportfit.sync.debug

mkdir -p .dbcheck
for f in sportfit-sync.db sportfit-sync.db-wal sportfit-sync.db-shm; do
  $ADB exec-out run-as $PKG cat "databases/$f" > ".dbcheck/$f" 2>/dev/null
  echo "$f -> $(wc -c < .dbcheck/$f) bytes"
done

$SQLITE .dbcheck/sportfit-sync.db \
  "select id,status,total,okCount,dupCount,failCount,message from sync_jobs order by id;"
$SQLITE .dbcheck/sportfit-sync.db \
  "select activityId,status,attempts,lastError from sync_records order by activityId;"
```

### 6.1 三个必踩的坑

1. **必须同时拉 `-wal` 和 `-shm`。** Room 默认 WAL 模式，只拉 `.db` 会拿到**过期快照**。
   本会话中曾因此得到一份错误的账本数据（显示 10 已同步 / 1 未同步 / 1 失败），
   补拉 WAL 后才是真值（11 已同步 / 1 失败）。**不要基于只拉了 `.db` 的结果下结论。**
2. **`cat` 的报错会写进你的输出文件。** `run-as ... cat` 失败时 shell 仍返回 0，
   错误文本进文件。若不检查字节数，会得到 59 字节的「数据库」，随后 `sqlite3` 报
   `file is not a database`。**先看文件大小是否合理。**
3. **数据库只有在应用至少运行过一次后才存在。** 全新安装（或 `connectedDebugAndroidTest`
   之后，见 §8.3）直接拉会得到 `No such file or directory`。先启动一次应用。

---

## 7. UI 自动化（不要用像素坐标）

### 7.1 工具：`tools/uitap.py`

通过 `uiautomator` 无障碍树**按文本**定位控件。Compose 的布局一变，写死的像素坐标就失效 ——
这是本会话中 UI 验证反复失败的根因，换用该工具后立刻暴露了一个真实缺陷（§8.6）。

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

python3 tools/uitap.py --dump                  # 列出所有可见控件的中心坐标与文本
python3 tools/uitap.py "选择活动同步"            # 找到并点击
python3 tools/uitap.py "拉取最新列表" --wait 8   # 最多重试 8 秒（等界面/网络）
python3 tools/uitap.py "导出" --contains        # 子串匹配
python3 tools/uitap.py "返回" --no-tap          # 只定位不点击（用于断言）
```

输出形如 `762,2694  选择活动同步`（坐标 + 命中的文本）；找不到时退出码为 1。

### 7.2 会打断自动化的弹窗

点击「一键同步」或「选择活动同步」前会触发：

| 弹窗 | 触发条件 | 处理 |
|---|---|---|
| 系统通知权限（API 33+） | 首次触发同步 | 点 `Allow`（用 uitap.py 找不到，它是系统对话框，需用坐标 `715,1721` 或 `--dump` 定位） |
| 「Coros 单设备登录提醒」 | 源或目标含 Coros | `python3 tools/uitap.py "继续"` |

建议在自动化脚本里对非必需弹窗使用容错写法：`python3 tools/uitap.py "继续" --wait 3 || true`。

### 7.3 一个可复制的完整流程

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

$ADB shell input swipe 720 2400 720 900 250   # 首页滚到底部
python3 tools/uitap.py "选择活动同步" --wait 6
python3 tools/uitap.py "继续" --wait 3 || true  # Coros 提醒，可能不存在
sleep 3
python3 tools/uitap.py "拉取最新列表" --wait 8
sleep 7
python3 tools/uitap.py "全选" --wait 6
python3 tools/uitap.py "导出文件" --wait 6
sleep 15
$ADB shell ls -la /sdcard/Download/SportFitSync/
```

---

## 8. 沙箱与踩坑记录（重要，能省掉大量时间）

### 8.1 工作区之外的写入会被拒绝

本机文件策略为 `workspace-write`：只能改 `/Users/mac/Code/android/sport-fit-sync-app` 之内。
这直接决定了工程结构 —— Gradle 发行版、`GRADLE_USER_HOME`、`ANDROID_USER_HOME` 全部放进工程内。

报错形如：`[sandbox: file access denied under workspace-write mode]`。
确实需要访问工作区外时，用 bash 工具的 `sandbox_permissions` + `justification` 申请一次性放宽。

### 8.2 `/tmp` 写入也被拒 —— 曾导致我得出错误结论

我在 `/tmp` 下解压 APK 检查 dex，解压静默失败，于是我「确认」了类不在包里，
转而怀疑构建系统没有重新编译。**实际是构建正常，我的检查方法坏了。**

教训：验证脚本失败时，先怀疑验证方法本身，再怀疑被测对象。

### 8.3 `connectedDebugAndroidTest` 会在跑完后卸载应用

AGP 的默认行为。表现是：测试全绿，但随后 `am start` 报
`Error type 3 / Activity class does not exist`，`pm list packages` 里也查不到。

**跑完仪器测试后要重新安装**：

```bash
./build.sh :app:assembleDebug
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
```

注意 `install -r` 在**卸载之后**执行等于全新安装 —— **应用数据会被清空**（账本、日志、设置全没）。

### 8.4 Room schema 变更必须升 `version`，而且**不能只靠破坏性迁移**

新增表/列后若不升版本，已装应用的旧库在打开时会抛
`Room cannot verify the data integrity`。本项目当前版本为 **3**（v3 给 `sync_records`
加了 `fingerprint` 列）。

⚠️ **坑：这个库里不只有缓存。** `accounts` 表存着用户加密后的账号凭据，`sync_records`
是去重账本。`fallbackToDestructiveMigration(dropAllTables = true)` 一旦被触发，
**用户刚配好的账号和账本会一起消失**（要重新输密码，而且所有活动会被当成没同步过
再传一遍）。所以每次升版本都要写**显式的非破坏性迁移**，并注册进去：

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_records ADD COLUMN fingerprint TEXT NOT NULL DEFAULT ''")
    }
}
// build(): .addMigrations(MIGRATION_2_3)  ← 破坏性迁移只作为其它版本的兜底
```

验证靠 `app/src/androidTest/.../AppDatabaseMigrationTest.kt`：它用 `MigrationTestHelper`
重建 v2 库、塞进账号和账本，再迁移到 v3，断言两者都还在。
`MigrationTestHelper` 需要 schema JSON 出现在 androidTest 的 assets 里，
所以 `app/build.gradle.kts` 里有：

```kotlin
sourceSets { getByName("androidTest") { assets.srcDir("$projectDir/schemas") } }
```


### 8.5 不要相信「构建没有更新我的改动」这个直觉

本会话中我一度怀疑 Gradle 的 up-to-date 判断失效（多次出现 `BUILD SUCCESSFUL in 1s`
且任务标记 `UP-TO-DATE`）。**结论是：判断是正确的，改动确实已被编译。**

正确验证方式（任选，都要看客观证据，不要凭速度猜）：

```bash
# 1) APK 大小/时间戳是否变化
ls -la app/build/outputs/apk/debug/app-debug.apk

# 2) 产物类文件的时间戳是否晚于源文件
stat -f "%m %N" app/src/main/java/.../Foo.kt \
                app/build/tmp/kotlin-classes/debug/com/sportfit/sync/.../FooKt.class

# 3) 直接在 dex 里找特征字符串（注意：解压要在工作区内做）
mkdir -p .apkcheck && unzip -q -o app/build/outputs/apk/debug/app-debug.apk -d .apkcheck
for d in .apkcheck/classes*.dex; do grep -aq "FlowRow" "$d" && echo "found in $d"; done
rm -rf .apkcheck
```

`grep` 直接作用于 `.class` 文件不可靠（本机 `strings` 会把它误判为 Mach-O fat file 并报
`truncated or malformed`）—— **查 dex 才可信**。

### 8.6 重复的逻辑会漏改（真实缺陷）

`SyncEngine` 里解析账号凭证的逻辑出现在三处，我在两处加了「演练平台无需账号」的特判，
第三处 `refreshSourceActivities` 漏了 —— 结果同步和导出正常，但「拉取最新列表」对演练模式
直接报「未配置账号」。用 `tools/uitap.py` 按文本驱动流程时才暴露出来。

**改动这类分支逻辑时，全仓库搜索同名调用点，或先收敛成单一函数。**

### 8.7 模拟器冷启动比你想象的慢

软件渲染（`swiftshader_indirect`）下，从 `am start` 到首帧渲染完成需要 **15~25 秒**。
过早截图会拍到启动图标（splash），过早点击会点空。**`sleep 20` 起步**，或用
`tools/uitap.py --wait` 等待目标控件出现。

---

## 9. 排障速查

| 现象 | 原因 | 处理 |
|---|---|---|
| `gradle: command not found` | 未用 `./build.sh` | 用 `./build.sh <task>` |
| `Unable to create debug keystore in /Users/mac/.android` | `ANDROID_USER_HOME` 未设置 | 用 `./build.sh` |
| `Could not initialize native services` | `GRADLE_USER_HOME` 指向工作区外 | 用 `./build.sh` |
| Gradle 发行版下载龟速 | `services.gradle.org` 慢 | 用 `build.sh`（腾讯镜像） |
| `Activity class does not exist` | 应用被卸载 | 重新 `adb install -r` |
| `Room cannot verify the data integrity` | schema 改了没升 `version` | 升 `Database(version = N)` + 写非破坏性迁移（见 §8.4） |
| 升级后账号没了 | 走了破坏性迁移 | 补显式 `Migration` 并注册（见 §8.4） |
| `sqlite3: file is not a database` | 拉库失败，文件里是报错文本 | 检查文件大小；先启动一次应用 |
| 账本数据与 UI 对不上 | 只拉了 `.db`，缺 WAL | 同时拉 `-wal`、`-shm` |
| 模拟器 `snapshot operation ... pending` | AVD 残留锁 | 申请放宽权限后删 `*.lock` |
| 截图是启动图标 | 应用还没渲染完 | `sleep 20` 后重截 |
| `tools/uitap.py` 找不到控件 | 控件在屏幕外/被弹窗遮挡 | 先滑动；`--dump` 看当前实际有什么 |
| 同步/导出报 `Flow invariant is violated` | `flow {}` 中跨协程上下文 `emit` | 改用 `channelFlow` + `send`，见 **§11** |
| 平台登录成功但同步立刻中断 | 同上（平台在自己的 IO 上下文里打日志） | 同上 |
| 同步到 Coros 报「无法获取 Coros OSS 临时凭证」 | **两条通道都没拿到凭证** —— 训练中心 BFF 代理（`trainingcn.coros.com` / `training.coros.com`）与旧的匿名 v1 接口都没返回凭证。常见原因是 **Coros 登录态已失效**（重新登录）或**网络被拦截** | 见 §12；先重新登录 Coros 账号再重试；仍失败则改用「导出文件」。**若账号在 regionId 1/3（国际/欧洲），当前只有中国区（regionId=2）验证过** —— AWS 路径未实测，先用「导出文件」兜底 |
| 配好账号后「回不到同步页」 | 非 Tab 页面把底部导航栏藏掉了，用户失去「同步」入口 | 底部栏常驻 + 「去添加账号」直达账号管理，见 **§14.1** |
| 同步时只处理了最近 N 条，老活动一直没轮到 | 单次运行有处理量上限（设置里的「每次最多 N 条」），老活动排在后面 | 调大上限或分多次跑；历史活动本身已在本地库里（见 §14.2） |
| 同一条活动被传了两遍 | 源平台换了 activityId（重新导入 FIT / 换同平台账号），按 id 去重看不出来 | 已用内容指纹兜住，见 **§14.3**；实在要重复同步请用「重新同步已同步项」 |

---

## 10. 代码结构导览与扩展入口

```
app/src/main/java/com/sportfit/sync/
├── domain/Models.kt          Platform 枚举、SyncDirection、ActivityMeta、SyncStatus
├── core/
│   ├── crypto/CryptoBox.kt        Android Keystore + AES-GCM，凭据加密
│   ├── format/FitPackaging.kt     ZIP/FIT 归一化（ensureRawFit / ensureZip）
│   ├── net/                        OkHttp 双档客户端、CookieJar、宽松 JSON 工具、Hashing
│   ├── oss/                        Coros STS + 阿里云 V1 / AWS SigV4 签名上传
│   └── util/TimeParsing.kt
├── platform/
│   ├── Platform.kt                 PlatformSource / PlatformTarget / PlatformGateway 接口
│   ├── PlatformRegistry.kt         平台 → Gateway 映射
│   ├── garmin/  coros/  strava/  igpsport/  onelap/    各平台客户端
│   └── demo/DemoGateway.kt         演练平台（仅 debug）
├── data/local/                Room 实体 / DAO / Database
├── data/prefs/                DataStore 设置
├── data/repo/                 账号仓库（加解密边界）
├── sync/
│   ├── SyncEngine.kt              同步编排（逐活动隔离、失败可重试、可取消）
│   ├── SyncPlanner.kt             候选选择纯函数（已单测覆盖）
│   ├── ExportEngine.kt            离线导出
│   ├── SyncService.kt             前台服务（保活 + 进度通知）
│   └── SyncProgressBus.kt         引擎与服务的进度通道
├── export/ActivityExporter.kt     写文件到 Downloads
├── cache/CacheManager.kt          分类统计 / 清理 / 保留策略
├── work/                      WorkManager 定时同步
└── ui/                        Compose 界面（AppViewModel 为单一 VM）
```

### 10.1 加一个新平台

1. `domain/Models.kt` 的 `Platform` 枚举加一项（决定 `canBeSource` / `canBeTarget`）。
2. 新建 `platform/<name>/`，实现 `PlatformGateway`，内部提供 `PlatformSource` / `PlatformTarget`。
3. 注册到 `PlatformRegistry.gateway()`。
4. 若它需要额外字段（如区域、client id），在 `AccountEditScreen` 的 `regionOptions()` 里补选项。
5. 更新 `SyncDirectionMatrixTest` 里的 `upstreamDirections` —— **这个测试会强制你面对「方向数变了」这件事**。

### 10.2 新增一条同步方向

通常不需要写新代码：方向由「源 × 目标」组合产生。只有当上游确实多了一类脚本
（例如新增了一个目标平台）时才需要动枚举与 Registry。

### 10.3 已知未完成 / 未验证（接手前务必知道）

| 项 | 状态 |
|---|---|
| **真实平台网络请求** | **绝大多数仍未发出过**。**Coros 中国区（regionId=2）已用真实账号端到端验证通过**：登录 / STS 取凭证 / OSS PUT / `fit/import` 四步全部成功（账号 `regionId=2` / `service=aliyun` / `userId=435248150115008513`，2026-10-27，见 §12.6）。**但国际 / 欧洲区（regionId 1/3，AWS bucket）仍未验证** —— 签发凭证、AWS SigV4 上传均未实测。Garmin SSO/OAuth1、Strava WebView 回调同样只有协议级实现 + 静态验证。 |
| 通知栏「取消」按钮 | 未做（通知目前只展示进度） |
| 导出附 CSV 清单 | 未做 |
| 多方向并发 / 限速 | 未做（当前串行，对平台最友好） |
| `:app:installDebug` 任务 | **未验证**（一直用的是 `adb install -r`） |
| release 构建与签名 | **未验证**（只构建过 debug） |

### 10.4 相关文档

| 文档 | 内容 |
|---|---|
| `docs/development-handbook.md` | **本文**：构建/安装/调试/模拟器/取证/踩坑 |
| `docs/design-and-ux-proposal.md` | 需求理解、交互决策记录、实现进度、验证证据 |
| `docs/platform-protocols.md` | Garmin 协议实现规格（依据 garth 源码与上游 HTTP 报文逐行核对） |
| `docs/screenshots/` | 各页面与关键流程的设备截图 |
| `tools/uitap.py` | 按文本定位控件的 UI 自动化工具 |


---

## 11. 协程与 Flow 陷阱

### 11.1 真实事故：`flow {}` 里跨上下文 `emit`

**这是本项目唯一一个流出到真实使用、并被用户实际撞到的缺陷。** 首次用真实账号运行时同步立刻中断：

```
同步中断: Flow invariant is violated: Flow was collected in
[ProducerCoroutine{Active}@a9499a0, Dispatchers.IO], but emission happened in
[kotlinx.coroutines.UndispatchedMarker@c5c5847, UndispatchedCoroutine{Active}@9d45959, Dispatchers.IO].
Please refer to 'flow' documentation or use 'flowOn' instead
```

日志里最后一行是 `Coros 登录成功 (userId=…, regionId=2)` —— 即**平台登录成功之后、第一条平台侧日志发出的瞬间**崩溃。

**根因链条**（三者缺一不可，所以看代码时很难发现）：

1. 引擎写成 `flow { … }.flowOn(Dispatchers.IO)` —— 生产者已运行在 `Dispatchers.IO`；
2. `PlatformContext.log` 被直接接到引擎的 `log()`，而 `log()` 内部调用 `emit()`；
3. 各平台客户端**在自己的 `withContext(Dispatchers.IO)` 内部**调用 `log(…)`。
   由于生产者已经在 IO 上，这个嵌套 `withContext` 走 **undispatched 快路径**，
   于是 `emit` 发生在 `UndispatchedCoroutine` 里 —— 与收集者的 `ProducerCoroutine` 不是同一个上下文，
   Flow 的不变量检查随即失败。

**影响面**：凡是「在 `withContext` 内打日志」的平台都会中招，也就是**每一个真实平台**：

| 文件 | 行 | 日志内容 |
|---|---|---|
| `coros/CorosClient.kt` | 116 | Coros 登录成功 ← **首个触发的** |
| `garmin/GarminAuth.kt` | 104 / 237 | 提交两步验证码 / Garmin 登录成功 |
| `garmin/GarminApi.kt` | 46 | 访问令牌已过期，正在刷新 |
| `strava/StravaGateway.kt` | 66 | Strava 令牌已过期，正在刷新 |
| `igpsport/IgpsportGateway.kt` | 103 | iGPSPORT 登录成功 |
| `onelap/OnelapGateway.kt` | 72 | OneLap 登录成功 |

**修复**：两个引擎从 `flow {}` 改为 `channelFlow {}`，`emit` 改为 `send`。
`ProducerScope.send` 基于 Channel，**允许从任意协程/上下文调用**，不再受不变量约束。
`flowOn(Dispatchers.IO)` 保留不变。

```kotlin
// 修复前 —— 平台在自己的 IO 上下文里调用 log() 就会崩
fun run(request: SyncRequest): Flow<SyncEvent> = flow { … emit(…) }.flowOn(Dispatchers.IO)

// 修复后
fun run(request: SyncRequest): Flow<SyncEvent> = channelFlow { … send(…) }.flowOn(Dispatchers.IO)
```

### 11.2 为什么演练平台没发现它（最重要的教训）

`DemoGateway` 当时**既不 `withContext`、也不调用 `log`** —— 它和真实客户端的代码形态不一样，
于是所有演练都绕开了这条路径。

已把它改成与真实客户端同形态（`withContext(Dispatchers.IO) { …; log(…) }`），
并在文件注释里写明：**这段形态是回归面，不是装饰，不要"优化"掉。**

> 一般化结论：**替身（fake/demo/mock）的价值取决于它与真实实现共享多少代码路径。**
> 一个"更干净"的替身反而会掩盖缺陷 —— 让替身模仿真实实现的外部行为，
> 包括看起来多余的那些 `withContext` 和日志调用。

### 11.3 自查同类问题的方法

出现「收集上下文 ≠ 发送上下文」的思路时，按这个顺序 grep：

```bash
# 1) 所有 Flow 构建点
grep -rn "= flow {\|flow {" app/src/main/java --include=*.kt

# 2) 所有发送点
grep -rn "\bemit(" app/src/main/java --include=*.kt

# 3) 会被"外部"调用的回调（日志、进度、事件），看它们是否触达 emit
grep -rn "log:\|suspend (String) -> Unit" app/src/main/java --include=*.kt
```

### 11.4 本次全仓库排查结论

| 检查项 | 结论 |
|---|---|
| `flow {}` 构建点 | 仅 `SyncEngine.run`、`ExportEngine.run` 两处，**均已改为 `channelFlow`** |
| 引擎之外的 `emit` | 无 |
| 平台侧 `log(…)` 调用点 | 8 处（见上表），修复后全部安全（`send` 不挑上下文） |
| Room / DataStore 产生的 Flow | 库自身实现，安全 |
| `combine` / `map` / `flatMapLatest`（`AppViewModel`） | 不涉及手动 `emit`，安全 |
| `SyncProgressBus`（`MutableStateFlow`） | 线程安全，可从任意上下文 `publish` |

### 11.5 本次修复的验证

| 证据 | 结果 |
|---|---|
| 修复前（真实账号，job 1/2） | `FAILED`，message 为 `Flow invariant is violated` |
| 修复后（演练模式，job 3） | `PARTIAL`，12 条：成功 7 / 重复 4 / 失败 1 |
| 平台侧日志是否送达 | 是 —— 日志里出现 `演练：已下载 demo-1`、`演练：正在处理 demo-1` 等**在平台 IO 上下文内发出**的条目 |
| 真实 Garmin 拉取（仅登录+列表，不写任何平台） | **成功拉到 200 条真实活动**（名称/时间/距离均正常），无新增 Flow 错误 |

> 注：`app/src/androidTest/.../SyncEngineTest.kt`（4 项）是为这个缺陷补的回归测试，
> 但**尚未执行** —— 运行 `connectedDebugAndroidTest` 会卸载应用并清空数据，
> 从而抹掉已配置的真实账号与 Keystore 密钥（见 §8.3）。需要时再跑。


---

## 12. Coros 上传通道：匿名 STS 已下线，改走训练中心 BFF 代理

**状态：已找到新的对接方式并完成适配，并已用真实账号跑通全链路。**
**中国区（regionId=2）端到端验证通过**：登录 → STS 取凭证 → OSS PUT → `activity/fit/import` 全部成功；
**国际 / 欧洲区（regionId 1/3，AWS 路径）仍未验证** —— 逐条区分见 §12.6。

本节结论以 **2026-10-27** 的实测为准。§13 是 **2026-10-07 的历史快照**（当时结论为「通道已下线」），
两处冲突时**以本节为准**。2026-10-07 那轮探测的原始证据仍保留在
[`platform-protocols.md`](platform-protocols.md#二coros上传通道已恢复改走训练中心-v2-通道2026-10-27-实测) 的 §二。

### 12.1 根因：Coros 关掉了「免登录（匿名）」的 STS 通道

| 事实 | 说明 |
|---|---|
| **变更时间** | **2026-10-03**，Coros 关闭了「免登录（匿名）」的 OSS STS 临时凭证通道 |
| **根因记录** | 上游 `XiaoSiHwang/garmin-sync-coros` 的 **issue #41** 记录了现象与根因 |
| **修复记录** | 上游 **PR #42**（**2026-10-08 合并**）给出了修复 —— 改走新的鉴权通道 |

- issue：<https://github.com/XiaoSiHwang/garmin-sync-coros/issues/41>
- PR：<https://github.com/XiaoSiHwang/garmin-sync-coros/pull/42>

本项目此前的实现与上游 `master` **完全一致**（同一个匿名端点、同一组 bucket/sign），
所以两边是**同一天失效** —— 这不是本项目的 bug，是服务端策略变更。

### 12.2 旧接口现状（2026-10-27 实测）

| 请求 | 结果 | 含义 |
|---|---|---|
| `GET https://faq.coros.com/openapi/oss/sts?bucket=…&service=…&app_id=…&sign=…&v=2` | **404**（nginx HTML） | 路由已不存在 |
| `GET https://faq.coros.com/openapi/oss/sts/?…`（带尾斜杠） | **403** `{"msg":"sts for this bucket is only available via the v2 channel"}` | 加尾斜杠才到达 API；签名有效，但被要求走 v2 |
| `GET https://faq.coros.com/openapi/v2/oss/sts/` | **401** `{"code":401,"msg":"unauthorized","data":{"errorCode":"AUTH_INVALID"}}` | v2 通道**存在**，但必须带新的鉴权凭据 |

> 三个区域的签名仍然有效（换成错的 `sign` 会得到 `signature error`），
> 说明是**策略变更**（不再允许匿名），而不是凭据失效。

### 12.3 新的 v2 通道 = 网页版「训练中心」的 BFF 代理（核心）

Coros 网页版「训练中心」前端要拿 OSS 临时凭证来上传文件，它**不直连 OSS**，而是走自家后端代理：

| 区域 | 端点 |
|---|---|
| 中国区 | `GET https://trainingcn.coros.com/api/proxy/oss/sts?bucket=coros-oss&service=aliyun&v=2` |
| 国际 / 欧洲区 | `GET https://training.coros.com/api/proxy/oss/sts?bucket=coros-s3&service=aws&v=2` |

**鉴权方式**（与旧接口最大的不同）：

```
Cookie: CPL-coros-token=<Coros 登录返回的 accessToken>
```

- 凭据必须放在 **Cookie** 头里，且名字是 **`CPL-coros-token`**；
- `accesstoken` 头、`Authorization` 头**都不被识别**（会落回 `no_token`）。

**成功响应结构沿用旧接口**，凭证解法不变：

```json
{"code":200,"data":{"credentials":"<base64+salt>"}}
```

解法仍是**去盐 `9y78gpoERW4lBNYL` → base64 → JSON**。

### 12.4 可复现的探测命令

```bash
# 旧匿名通道（已死）
curl -sS -o /dev/null -w "%{http_code}\n" \
  "https://faq.coros.com/openapi/oss/sts?bucket=coros-oss&service=aliyun&app_id=1660188068672619112&sign=9AD4AA35AAFEE6BB1E847A76848D58DF&v=2"
# 新 BFF 通道（不带 token）
curl -sS "https://trainingcn.coros.com/api/proxy/oss/sts?bucket=coros-oss&service=aliyun&v=2"
# 新 BFF 通道（带假 token，应答 token_invalid 而不是 no_token）
curl -sS -H "Cookie: CPL-coros-token=dummytoken123" \
  "https://trainingcn.coros.com/api/proxy/oss/sts?bucket=coros-oss&service=aliyun&v=2"
```

预期输出（**2026-10-27 实测**）：

| 命令 | 预期 |
|---|---|
| 旧匿名通道 | `404` |
| 新 BFF（不带 token） | HTTP 401 `{"code":401,"msg":"unauthorized","reason":"no_token"}` |
| 新 BFF（带假 token） | HTTP 401 `{"code":401,"msg":"unauthorized","reason":"token_invalid"}` |

> `no_token` → `token_invalid` 的变化**证明 Cookie 机制被服务端正确识别**：
> 请求已经过了「有没有带凭据」这一关，只是凭据本身无效。

### 12.5 本项目的落地方式（代码已改）

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/sportfit/sync/core/oss/CorosStsProvider.kt` | `fetch(regionId, accessToken)`：依次尝试 ① 带 `CPL-coros-token` Cookie 的 **BFF 代理**（按区域选 `trainingcn.coros.com` / `training.coros.com`）；② 旧的**匿名 v1 接口**（保留回退） |
| `app/src/main/java/com/sportfit/sync/platform/coros/CorosGateway.kt` | `CorosTarget.upload()` 把 **Coros 登录得到的 `accessToken`** 传给 STS 提供方 |
| `app/src/main/java/com/sportfit/sync/core/net/HttpClients.kt` | 新增 **STS 专用客户端 `HttpClients.corosSts`**，`cookieJar = CookieJar.NO_COOKIES` |
| `app/src/test/java/com/sportfit/sync/core/oss/CorosStsProviderTest.kt` | 新增单测：区域→代理主机映射、无令牌时跳过代理通道、旧接口永远兜底、阿里云/AWS 两种凭证解析、`token_invalid` / `no_token` 区分等 |
| `app/src/test/java/com/sportfit/sync/platform/coros/CorosLiveCheck.kt` | 新增**实时体检**测试：用真实账号跑「登录 → STS 取凭证 → OSS PUT → `fit/import`」。凭据**只从环境变量读**，阶段由 `COROS_LIVE_STAGES` 控制，**不设环境变量就跳过**（不污染常规单测）。用法见 **§12.10** |

> 两处细节：
> - **登录令牌为空时会直接跳过 BFF 通道**，只走旧接口 —— 所以报错里「已尝试 N 个通道」可能是 1 而不是 2。
> - 旧接口的 `sign` 是**按 bucket** 划分的，不是按存储服务：`coros-s3`（国际）与 `eu-coros`（欧洲）虽然都是 AWS，签名却不同，共用会得到 `signature error`。

**踩坑记录（务必知道）**：OkHttp 4.12 的 `BridgeInterceptor` 会用 cookie jar 里该主机的 cookie
**覆盖手工设置的 `Cookie` 头**（源码：`requestBuilder.header("Cookie", cookieHeader)`）。
若沿用共享的 `InMemoryCookieJar`，一旦 jar 里存过 `trainingcn.coros.com`（或父域 `coros.com`）的 cookie，
`CPL-coros-token` 就会被吃掉 → 401。
**因此 STS 请求必须用 `CookieJar.NO_COOKIES` 的独立客户端。**

### 12.6 已验证 / 未验证（务必区分，不要混为一谈）

**已验证 —— 真实账号端到端（2026-10-27，中国区 `regionId=2` / `service=aliyun` / `userId=435248150115008513`）**：

| 环节 | 实测结论 |
|---|---|
| **STS 训练中心代理** | HTTP **200**，响应体 `{"code":200,"data":{"credentials":"<base64+salt>"}}` —— 与旧接口结构**完全一致**，**凭证解法（去盐 → base64 → JSON）与字段名都没变**，只是换了传输通道 |
| **凭证形态** | 解码后是**阿里云形态**：`AccessKeyId`（形如 `STS.NZ6i…`，**29 字符**）、`AccessKeySecret`（**44 字符**）、`SecurityToken`（**876 字符**） |
| **OSS PUT** | **成功**（**阿里云 V1 签名**），对象键 `fit_zip/{userId}/{md5}.zip` |
| **`activity/fit/import`** | **成功**：走应用真实路径 `CorosTarget.upload()`（ensureZip → STS → PUT → import）返回 **`Success`**（服务端 `result=0000` 且 `data.status==2`），账号里新增一条活动 `id=481335029083963393` |

此前的**协议级结论**（curl，2026-10-27）同样成立，叠在上面这条真实链路之上：

- 新端点存在、路径正确；
- `Cookie: CPL-coros-token` 这一鉴权机制**被服务端识别**（`no_token` → `token_invalid`）；
- 区域主机分区正确（中国区 `trainingcn.coros.com`、国际区 `training.coros.com`）。

**未验证**：

- **国际 / 欧洲区（regionId 1 / 3，AWS bucket `coros-s3` / `eu-coros`）能否签发凭证** ——
  本次实测账号是**中国区**，**AWS 路径完全未实测**；
- **AWS SigV4 上传路径**（`AwsS3Uploader` 已有实现，但从未对真实 AWS bucket 跑过）；
- **STS 凭证的实际有效期**（未观察凭证到期后的行为）。

> 结论：**中国区链路已端到端验证通过；国际 / 欧洲区仍未验证。**
> 下一个接手者要做的第一件事：拿一个 `regionId=1` 或 `3` 的账号，用 **§12.10** 的实时体检工具复检一遍。

### 12.7 上游 PR #42 的两个次要缺陷：本项目原本就没有

| 上游 PR #42 的问题 | 本项目的做法 |
|---|---|
| 上传异常时 `print(err)` 后 `exit()`，**CI 仍绿**（失败被吞掉） | `SyncEngine` **逐条隔离失败并继续**，失败项落 `FAILED` 状态并上报 |
| `getUnSyncActivity()` 只查 `is_sync_coros = 0`，**异常活动永不重试** | `SyncPlanner` 把 `FAILED` 与 `UNSYNCED` **同等对待**（自动重试），另有 `SyncEngine.requeueFailed()` |

即：上游 PR #42 修的是**主通道**，这两个可靠性缺陷是它顺带没覆盖到的；
本项目在适配前就已经具备对应机制。

### 12.8 影响范围与处置

- 受影响的仍然只有**以 Coros 为目标**的 4 条方向：
  `garmin→coros`、`strava→coros`、`igpsport→coros`、`onelap→coros`；
  其余方向不经过 OSS，不受影响。
- 反向（**Coros → Garmin**）不受影响：Coros 的登录/列表/下载都正常。
- **兜底**：离线导出始终可用 —— 活动选择页 → 勾选 → 「导出文件」，
  文件写到 `Download/SportFitSync/*.fit`，可手动导入 Coros 或任意平台。
- 现在 `*→coros` 的失败点从「端点不存在」前移到了「登录态 / 网络 / 区域」，
  报错文案也相应变成了「两条通道都拿不到 OSS 临时凭证」（见 §9 排障表）。

**当前（真实账号实测后）的失败语义**：

| 现象 | 真实含义 | 处置 |
|---|---|---|
| 报错「无法获取 Coros OSS 临时凭证（已尝试 N 个通道）」，且末尾带「训练中心代理拒绝了当前登录令牌」 | **Coros 登录态失效**（`accessToken` 过期 / 被顶号） | 重新登录 Coros 账号后重试（Coros 单设备登录，手机 App 会被挤下线） |
| 同样的报错，**没有**「拒绝当前登录令牌」字样 | **网络被拦截**（代理 / 防火墙 / DNS），请求根本没到达服务端 | 换网络环境重试；仍失败则改用「导出文件」离线导入 |
| 报错且账号在 **regionId 1 / 3**（国际 / 欧洲） | 走的是 **AWS bucket**，而**中国区（regionId=2）之外从未实测过** | 参照 §12.10 用对应区域账号复检；期间用「导出文件」兜底 |
| 登录后提示已被挤下线 | Coros **同一账号只允许一个会话** | 同步前先关掉手机上的 Coros App，同步完再登回去 |

**踩坑 / 注意事项（务必知道）**：

- **Coros 不按内容去重。** 把账号里**已有的活动**重新导入，Coros 会**新建一条同名、同时长、同距离的副本**。
- **同一活动重复同步 = 新对象 + 新活动。** 因为每次打包的 ZIP 里含**时间戳**，md5 每次都不同
  → 对象键 `fit_zip/{userId}/{md5}.zip` 每次都是新的 → Coros 侧看不到「同内容」，只能新建。
- **去重依赖本项目账本 `sync_records`**（上游靠 SQLite 的 `is_sync_coros` 标志位，作用相同）：
  账本里已有记录的活动不会再被选中同步。
- 因此在活动选择页打开「**显示已同步**」并重新勾选已同步过的活动再同步，
  **Coros 侧会留下副本**，对象存储里也会多一个对象。**同步前先确认自己真的想重传。**

### 12.9 排查这类「上游接口变更」的通用方法（实战得出）

```bash
# 1) 先确认是"路由没了"还是"参数不对" —— 404 与 401/403 的含义完全不同
curl -sS -o /dev/null -w "%{http_code}\n" "https://faq.coros.com/openapi/oss/sts"
curl -sS "https://faq.coros.com/openapi/oss/sts/"          # 注意尾斜杠

# 2) 看响应体的业务错误码，而不是只看 HTTP 状态
#    {"msg":"...v2 channel","code":403}  = 签名通过、策略拒绝
#    {"msg":"signature error","code":401} = 这组参数/密钥不匹配

# 3) 确认上游是否已适配（不要假设本地 clone 是最新）
curl -sS "https://raw.githubusercontent.com/<owner>/<repo>/master/<path>"

# 4) 找替代实现时，注意它们可能"看起来支持、实际没实现"
#    例：coros-connect 对中国区直接 throw new Error('Provider not implemented')

# 5) 新的鉴权通道往往藏在「网页版前端在调什么」里
#    本例：训练中心 BFF 代理 + Cookie: CPL-coros-token=<accessToken>
#    顺着网页版的 XHR 找 /api/proxy/... 路径，比猜接口参数快得多
```

> 教训：**「本地 clone 里的上游代码」不等于「上游当前可用的做法」。**
> 本项目 2026-10-07 的实现与当时的上游 `master` 完全一致，两边同时失效；
> 上游 2026-10-08 的 PR #42 里那行 `Cookie: CPL-coros-token=<accessToken>` 才是可用做法 ——
> **协议类代码必须定期用真实请求体检，静态比对无法发现这类退化。**

### 12.10 实时体检工具：`CorosLiveCheck`（可复用）

上游接口会再次变动，所以通道类代码必须能**随时用真实请求复检**，而不是等下次线上故障才发现退化。
`app/src/test/java/com/sportfit/sync/platform/coros/CorosLiveCheck.kt` 就是为此准备的：
它是**普通单元测试**（放在 `app/src/test/`），但**不设环境变量就直接跳过**，
因此**不会污染常规 `testDebugUnitTest`**。

```bash
cd /Users/mac/Code/android/sport-fit-sync-app
export COROS_EMAIL='you@example.com' COROS_PASSWORD='…'
./build.sh :app:testDebugUnitTest --tests "*CorosLiveCheck" --rerun --console=plain -i
```

**凭据只从环境变量读**（`COROS_EMAIL` / `COROS_PASSWORD`），**不写进代码、不写进日志**；
阶段由环境变量 **`COROS_LIVE_STAGES`** 控制（逗号分隔，**默认 `a,b,c`**）：

| 阶段 | 做什么 | 副作用 |
|---|---|---|
| `a` | `account/login` 登录 | **有** —— Coros 单设备登录，手机上的 Coros App 会被挤下线 |
| `b` | 经训练中心代理签发 OSS 凭证 | 无 |
| `c` | 向 bucket `PUT` 一个 ZIP 对象 | bucket 里多一个**孤儿对象**（账号里看不到活动） |
| `d` | 用应用自己的 `CorosTarget` 走 `activity/fit/import` | **有** —— 账号里**新增一条活动** |
| `e` | 列出账号最新活动 | 无 |

输出形如 `LIVE|a|登录成功 userId=… regionId=2` / `LIVE|b|STS 成功 bucket=coros-oss service=aliyun`。

> ⚠️ **踩坑：Gradle 守护进程会缓存环境变量。**
> 改完 `COROS_LIVE_STAGES` 再跑，如果阶段没变 —— 说明**守护进程还在用旧的环境变量**。
> 必须 `./build.sh --stop`（停掉守护进程）或加 `--rerun`（强制重跑）才会生效。**本次实测踩到过这个坑。**

> ⚠️ **`d` 阶段会真的在账号里创建活动**，而且 **Coros 不按内容去重**（见 §12.8），
> 所以**常规体检不要带 `d`**；只有想复现「新建活动」时才单独加上。
> 需要验证国际 / 欧洲区时，用一个 `regionId=1` 或 `3` 的账号跑同一套流程即可（目前仍未验证）。


---

## 13. 上游最新状态与「走哪条路」的结论（2026-10-07 实测）

> ⚠️ **本节是 2026-10-07 的历史快照**，结论已被 §12 取代：上游随后在
> **PR #42（2026-10-08 合并）** 补上了新的 STS 通道，本项目也已适配。
> 本节保留是为了记录当时的排查过程，读时请以 **§12** 为准。

### 13.1 上游 `XiaoSiHwang/garmin-sync-coros` 现况

| 事实 | 证据 |
|---|---|
| 上游 master 已到 `9a24fec`（PR #40），**本地 clone 落后** | `git log origin/master` 显示 `8476e6b`，本地 HEAD 是 `71db06e` |
| 最新提交修的是 **Coros → Garmin**：`coros_db.getUnSyncActivity()` 改为 `WHERE is_sync_garmin IN (0, 2)`、`coros_sync_garmin.py` 异常后 `continue` 而非 `exit()`、`coros_client.downloadActivitie()` 去掉吞异常的 `try/except` | `8476e6b.patch` |
| PR #40 新增 `scripts/backup_activities.py`：**只下载不上传**的备份工具 | README「Local activity backups」；脚本已存在于 master |
| **OSS 客户端一字未改**，仍是失效的 `https://faq.coros.com/openapi/oss/sts` | `git grep` master 上的 oss 目录 |
| README 提到的 `sync-coros-garmin.ps1` **在仓库中不存在**（404） | 实测 raw 请求 |

### 13.2 两个方向的实测结论

在真实账号上跑同一个上游代码库：

| 方向 | 结果 |
|---|---|
| **Garmin → Coros** | ❌ 失败于取 OSS 凭证。Python 侧报 `Expecting value: line 1 column 1 (char 0)`（对 404 的 HTML 做 `json.loads`），Android 侧报 403「only available via the v2 channel」——**同一个服务端变更** |
| **Coros → Garmin** | ✅ 成功。上游原版代码返回 `DUPLICATE_ACTIVITY`（Garmin 正确去重）；Android 端返回 `SUCCESS` 并写入 12,709 字节的 FIT |

**因此**：`*→coros` 的 4 条方向在 Coros 恢复导入通道前不可用；
其余 8 条 + **Coros→Garmin** 可用。这与上游最近的工作重心一致。

> 上游 README 第一句「如果出现无法同步请检查下代码是否最新」并不适用于本次故障 ——
> 问题在服务端，检查代码版本无法解决。

### 13.3 真实账号测试暴露了 3 个演练平台抓不到的缺陷

这是本项目最重要的一课。内置「演练模式」能验证编排、账本、缓存、归一化，
但**服务端的接口约定它模拟不了**：

| 缺陷 | 症状 | 根因 |
|---|---|---|
| Coros 业务错误当成功 | 列表显示「0 条活动」，无任何报错 | Coros 用 **HTTP 200 + `result:"1019"`** 表示 token 失效，客户端只判断了 401 |
| 下载缺 `sportType` | `1031 Parameter input error` | `activity/detail/download` 的 `sportType` 必填且需精确匹配；接口只传了 id，默认成 1，**所有非跑步活动都失败** |
| 方向无法对调 | 点另一个平台无反应 | 互斥规则把「对侧当前选中的平台」置灰，用户只能找到那个小交换按钮 |

三个都已修复并重新在真机验证。**结论：替身平台能验证你的代码，验证不了对方的接口约定。**

### 13.4 Garmin SSO 速率限制（操作注意）

约 10 分钟内 5 次 SSO 登录即触发 `429 Too Many Requests`。
上游 Python 每次运行都重新登录（未使用 `garth` 的令牌落盘），所以**调试时不要反复跑脚本**。
Android 端持久化令牌，正常使用不会重复登录。

---

## 14. 2026-10-28 用户反馈的三件事（导航 / 本地历史 / 内容去重）

用户在真机装上上一版 APK 后提了三条：

1. 「设置账号后，无法回到同步页面」；
2. 「每次配置新账号后，应将当前所有活动数据拉取到本地数据库进行存储，之后每次同步任务时进行增量更新」；
3. 「可以根据时间及活动数据内容判断活动数据是否为已同步过的相同数据。判定为相同的数据，不需要再同步」。

这一节记录这三件事的根因、做法与后续要注意的地方。

### 14.1 导航：底部栏曾经只在 Tab 页面上有

**根因**：`AppRoot` 里 `showBottomBar = Tab.entries.any { it.route == currentDestination?.route }`，
所以「账号管理」「账号编辑」这类**非 Tab 页面把底部导航栏藏掉了**。
账号流程是「同步页 → 去添加账号 → 设置页 → 账号管理 → 平台 → 保存 → 回到账号管理」，
用户停在一个没有底部栏的页面上，看不到「同步」入口，只能靠系统返回键绕路 —— 体验上就是「回不去」。

**做法**：

- 底部导航栏**常驻**（所有目的地都渲染）。非 Tab 页面不属于任何 Tab，
  `selected` 判定自然全是 false，表现为「栏在、但不选中任何一项」；
- 同步页的「去添加账号」**直达账号管理页**，省掉「先跳设置、再点账号管理」这一步；
- 账号管理页顶部返回箭头 → 回到栈底的同步页。

**⚠️ 还有一个更隐蔽的坑（模拟器实测才发现）**：底部栏切 Tab 一开始用的是官方文档里
最常见的那套写法：

```kotlin
navController.navigate(tab.route) {
    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
```

它的问题在于：`saveState` 会把「上次在这个 Tab 里打开的子页面」（账号管理、活动选择…）
一起存进该 Tab 的状态，`restoreState` 又把它恢复回来 —— 于是
**用户点「同步」，落到的却是上次打开过的账号管理页**。
实测：账号编辑页点「同步」→ 落回账号管理；从记录页点「同步」→ 也落回账号管理。
表现就是「怎么都回不到同步页」。

正确写法是**切 Tab 时清空栈、重建 Tab 根页面**：

```kotlin
if (currentDestination?.route == tab.route) return@NavigationBarItem   // 已在该 Tab，不重建
navController.navigate(tab.route) {
    popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
    launchSingleTop = true
}
```

> 教训一：把「导航入口」藏起来省的那点空间，代价是用户找不到路。
> 凡是用户可能「配置完就回主流程」的页面，都要保证主流程一步可达。
> 教训二：**「点 Tab 落在哪一页」是产品行为，不是框架细节**。
> 单 NavHost + 底部栏的组合里，`saveState/restoreState` 会把子页面一起带走，
> 必须用「清栈重建根页面」把落点钉死。

### 14.2 本地历史：配置账号时全量拉取，之后只做增量

**之前的行为**：活动列表只在同步/刷新时按 `listLimit`（默认 200）拉取，
本地库里只有最近那批；老活动既看不到，也永远轮不到被同步。

**现在的行为**：

| 时机 | 网络 | 本地库 |
|---|---|---|
| 保存账号后（`SyncEngine.warmUpPlatform`） | 翻页拉取**全部**历史（上限 `HISTORY_LIMIT = 5000`） | 全量落库 |
| 每次同步（`SyncEngine.run`） | 只拉**最新一批**（`listLimit` 条） | 与本地库合并（upsert） |
| 每次同步的候选集 | — | **本地库的全集**（新活动只有先落库才可能被同步） |
| 「选择活动同步」的刷新 | 拉全量 | 同上 |

要点：

- 单次运行仍受「每次最多 N 条」限制（`SyncPlanner.applyLimit`），行为与旧版一致；
  **但候选集来自本地库**，所以老活动也能排上队（旧版永远只能看到最新 200 条）。
- **显式勾选的活动不受上限限制** —— 用户点名了哪几条就跑哪几条。
- 活动列表因此**不再是「用完即弃」的缓存**：`applyRetention()` 原来会按
  `fetchedAt` 把 7 天没刷新过的行删掉，那会让「还没来得及同步的老活动」永远失去机会。
  现在只清理**账号已经删掉**的平台的列表（`activityRetentionDays` 改成这个含义），
  在配置中的账号，其列表一直保留到用户手动清理。
- `ExportEngine` 也改成以本地库为全集：否则在「选择活动同步」里勾选一条老活动，
  导出时会因为「只取最新 N 条」被悄悄丢掉。

### 14.3 内容指纹：跨 activityId 认出「同一份数据」

账本的主键是 `(source, target, activityId)`，但 **activityId 不是「这条活动」的稳定名字**：

- 把同一份 FIT 重新导入源平台 → **新 id，内容一样**；
- 换成同平台的另一个账号 → id 来自另一个命名空间，**内容一样**；
- 平台自己重发 id。

这些情况下 id 说「新的」，数据说「已经同步过了」，再传一遍就是在目标端制造副本。

**做法**：`ActivityFingerprint.of(...)` 对「开始时间 + 名称 + 距离 + 时长 + 运动类型」
做归一化后取 SHA-256：

- **时间必须存在**：没有开始时间就没有可用指纹（返回 `ActivityFingerprint.NONE`），
  这时退回按 id 判断；
- 只用**列表元数据**，绝不哈希 FIT 内容 —— 否则每条活动都得先下载才能判断它是不是已经同步过；
- 归一化：名称 trim + 小写、距离取整到米，避免同一条活动的表示差异算出两个指纹。

指纹存在 `sync_records.fingerprint`（v3 新增的列，默认空串 = 未知）。
选择候选时，命中该方向「已同步指纹集合」的活动按**已同步**处理，不再重复上传。
「选择活动同步」页与首页统计也用同一套判断，所以界面上就能看出「这条其实传过了」。

**取舍（写在这里以免以后被当成 bug 改回去）**：

- 用户显式勾选的活动**仍然**套用指纹规则 —— 内容完全重复再传一遍只会产生副本；
  真要重复同步请用「重新同步已同步项」（`includeAlreadySynced`），那个开关会绕过指纹规则；
- 指纹只影响**「已经同步过」**的判断，失败项照常重试；
- 指纹是**每个方向**一份：Garmin→Coros 传过了，不代表 Coros→Garmin 也传过。

### 14.4 去重要发生在「拉列表的那一刻」

用户反馈的原话是：**「点击进入同步任务后，拉取运动记录列表时，就进行运动数据比对去重」**。
这不只是时机问题 —— 上一版虽然也跳过重复项，但有三个洞：

1. **账本里没有落地**：被指纹跳过的活动，账本行还是「未同步」，
   选择器里靠指纹临时显示成「已同步」，账本本身却是另一回事；
2. **日志数字是错的**：老代码把「按 id 已经同步过的」也算进「内容相同」，
   于是每次同步都报「N 条活动与已同步的数据内容相同」，N 等于全部已同步数 ——
   这个数字说明不了任何事；
3. **只比了本地账本**：目标账号里可能**早就**有这些活动了（之前用别的工具同步过，
   或者本地账本被清过），再传一遍就是副本 —— 而本地账本对此一无所知。

**现在的做法**（`SyncEngine.run()` 里，拉完列表立刻做）：

```
读取 Garmin 活动列表（增量，本次最多 200 条）…
本次拉取 200 条活动
本地共 500 条活动记录
比对去重：已同步 150 条 · 内容相同 12 条 · 目标端已有 5 条 · 已排除 1 条 · 待同步 332 条
本次将同步 200 条活动
```

- `SyncPlanner.plan(...)` 一次给出**五个分类**：`candidates` / `alreadySynced` /
  `duplicatesByContent` / `duplicatesOnTarget` / `excluded`；
- **判定顺序**：先看 id（这个 id 我们同步过吗），再看指纹（数据一样吗）。
  顺序反过来的话「内容相同」会吞掉所有已同步的活动，报出来的数字就没意义了；
- **目标端比对**：`PlatformTarget.listActivities(limit)`（默认返回空列表 = 不支持，
  自动退化成「只比本地账本」）。目前只有 **Coros** 实现了它
  （`CorosTarget` 复用 `activity/query`），其它平台保持默认；
  查询失败也只是退化成不比，**不会中断同步**；
- **跨平台用宽松指纹**：运动类型和名字各平台表示不同
  （Garmin `"running"` / Coros `"8"` / Strava `"run"`），
  所以 `ActivityFingerprint.looseOf(...)` 只用「开始时间 + 距离 + 时长」；
  距离和时长**同时**缺失时返回 `NONE`（数据太少，宁可不比也不误跳）；
- **结论写回账本**：判定为重复的活动，账本行写成「已同步 + 指纹」，
  状态才和界面一致，下次也能按 id 直接跳过；
- 「选择活动同步」页的刷新同样拉完立刻比对，提示语变成
  「本地活动列表已更新：500 条（已同步 150 · 内容相同 12 · 待同步 338）」。

**误跳的兜底**：宽松指纹要同时对上「开始时间（毫秒）+ 距离（米）+ 时长（秒）」，
正常活动不可能撞上；万一真撞了，「重新同步已同步项」可以强制再传一遍。

### 14.5 这一轮改动的落点

| 位置 | 改动 |
|---|---|
| `ui/AppRoot.kt` | 底部栏常驻；「去添加账号」直达账号管理 |
| `ui/AppViewModel.kt` | 保存账号后触发全量预热；选择器/统计改用指纹判断状态 |
| `ui/screens/AccountsScreen.kt` | 每个平台显示「本地已存 N 条活动」 |
| `ui/screens/AccountEditScreen.kt` | 提示「保存后会自动拉取历史活动」 |
| `ui/screens/SettingsScreen.kt` | 「每次最多拉取 N 条」改为「每次同步最多处理 N 条」 |
| `sync/SyncEngine.kt` | `warmUpPlatform()`；同步改为增量拉取 + 本地全集；拉完立刻比对去重、结论写回账本 |
| `sync/SyncPlanner.kt` | `plan()` 五分类（候选/已同步/内容相同/目标端已有/已排除）+ `applyLimit()` |
| `sync/ExportEngine.kt` | 以本地库为全集；指纹落库 |
| `domain/ActivityFingerprint.kt` | 内容指纹（严格）+ 跨平台宽松指纹（纯函数） |
| `platform/Platform.kt` | `PlatformTarget.listActivities()`（默认空列表 = 不支持） |
| `platform/coros/CorosGateway.kt` | `CorosTarget` 支持查询目标端已有活动 |
| `data/local/{Entities,Daos,AppDatabase}.kt` | `fingerprint` 列、`syncedFingerprints()`、`upsertRecords()` 批量写账本、`observeCounts()`、v2→v3 非破坏性迁移 |
| `cache/CacheManager.kt` | 不再按时间清理在用账号的活动列表 |
| `app/src/androidTest/.../AppDatabaseMigrationTest.kt` | 迁移不丢账号与账本 |

用例数：单元测试 **95** 条（指纹 16、规划器 25），设备端 **20** 条。

---

## 15. 视觉体系（2026-10-29 的整体改版）

之前的功能都做对了，但界面是「默认 Material3 的灰白卡片堆叠」：没有主次、没有品牌感、
空状态只有一行灰字。这次把视觉体系重新立了一遍，**功能一个字没改**。

### 15.1 设计原则

1. **一层底图铺全 app**：`AppBackground` 在 `AppRoot` 里画一次（顶部主色淡染 → 页面底色
   的垂直渐变 + 右上角一圈同心圆环装饰），切页面时背景是连续的，页面本身只管内容；
2. **卡片不用阴影，用「容器色 + 细边框」**：Material3 默认阴影在浅色底上发脏，
   `surfaceContainerLow` + `outlineVariant` 描边更干净；
3. **状态有颜色语义**：辅色(青绿)=已同步/成功，第三色(琥珀)=提醒/部分成功，
   错误色(珊瑚红)=失败，其余走中性色 —— 这套语义在状态标签、统计数字、进度条里一致；
4. **数字用等宽数字**：`NumericTextStyle` 带 `fontFeatureSettings = "tnum"`，
   统计数字刷新时不会左右跳；
5. **每个平台一个固定色**：`PlatformPalette` 给 Garmin/Coros/Strava/iGPSPORT/OneLap
   各一个颜色，徽章、芯片、方向指示都用它，列表里一眼能认出是哪个平台。

### 15.2 落地的东西

| 位置 | 内容 |
|---|---|
| `ui/theme/Color.kt` | 品牌色板 + 插画专用色 + `PlatformPalette` 平台色 |
| `ui/theme/Theme.kt` | 完整 M3 明暗配色（含 `surfaceContainer*` 层级）、圆角、`LocalHeroPalette` |
| `ui/theme/Type.kt` | 完整字阶 + `NumericTextStyle` / `NumericSmallTextStyle` |
| `ui/components/Design.kt` | `AppBackground` / `ScreenHeader` / `HeroCard` / `StatTile` / `StatRow` / `PlatformAvatar` / `EmptyState` / `SectionTitle` / `SoftChip` / `HairLine` / `BorderedSurface` |
| `ui/components/Common.kt` | `SectionCard` 升级（图标 + 细边框）、`StatusBadge` 改成圆点胶囊、`PlatformChip` 用平台色 |
| `res/drawable/ic_hero_sync.xml` | 首页头图：循环箭头 + 心电曲线 |
| `res/drawable/ic_empty_activities.xml` | 空状态：运动路线 + 图钉 |
| `res/drawable/ic_empty_history.xml` | 空状态：秒表 |
| `res/drawable/ic_empty_accounts.xml` | 空状态：人形 + 对勾徽章 |
| `res/drawable/ic_decor_rings.xml` | 背景装饰：同心圆环 |

插画都是手写的 **VectorDrawable**（= Android 上的 SVG），不依赖任何图片资源，
深浅色主题下都靠固定配色保证对比度。

### 15.3 改版时的纪律

- 只用 `MaterialTheme.colorScheme` 里的颜色，不写死 `Color(0xFF…)`；
  平台色、插画色是唯二的两处例外，且都集中在 theme / components 两个文件里；
- **不动行为**：ViewModel 调用、onClick 语义、按钮文案含义一律不变；
- 改版和功能改动分开提交，评审时能一眼看出哪些是「样子」哪些是「功能」。

### 15.4 改版后的自检（无视觉模型也能查）

本机没有可用的视觉模型（`minimax-cn` 只有 MiniMax-M3，omlx 的是纯文本模型），
所以改版的「好不好看」靠三道**程序化自检**兜底：

1. **对比度审计**：对每张截图，用 `uiautomator dump` 拿文本节点坐标，
   再在截图里取该区域的实际像素 → 文字色 vs 背景色算 WCAG 对比度。
   浅色主题 6 个页面全部 ≥ 4.5；深色主题抽查 3 个页面同样全部 ≥ 4.5。
   唯一低于阈值的是活动选择页底部两个**禁用**按钮（M3 默认禁用态约 2.2，
   按规范禁用控件可豁免）。
2. **重叠检测**：两两比对文本节点的外接矩形，6 个页面**无一重叠**。
3. **文案完整性**：把改版前后每个页面的中文字符串抽出来做集合差，
   逐条确认「消失的文案」是换了说法（拆成统计小卡）还是被搬到了公共函数
   （`jobStatusLabel`/`jobStatusTone`）——功能没丢。

复现命令见 `tools/audit.py`（逐屏 dump + 截图 + 比对）。

### 15.5 改完之后的实测走查（第二轮）

第一轮改完「看着齐了」，但没人能看图（这台机器上 `minimax-cn` 只有 MiniMax-M3，
`omlx` 是纯文本模型，**没有任何模型声明图像输入**）。于是换了个办法做走查：
让子代理用 **`uiautomator dump` 取每个文本节点的精确 bounds + `screencap` 逐像素
比对 + numpy 按 WCAG 公式算对比度 + OCR 读渲染结果**，把「好不好看」变成
「坐标对不对、对比度够不够、文字有没有被切」。

它查出来的**真缺陷**（都不是审美问题，是能指出来的 bug）：

| 缺陷 | 现象 | 修法 |
|---|---|---|
| 活动行「时间·距离·时长」被截断 | 渲染成 `3.64…`，整页 4/4 行命中 | 去掉每行重复的平台头像（同源平台，顶部已写明），该行独占整行宽度 |
| 按钮文字被顶偏 15~16dp | 文案里手写了两个空格 `"  一键同步全部"` | 删掉空格，图标与文字的间隔交给 M3 `ButtonContent` |
| 卡片和页面底色几乎同色 | 卡片 `#F0F1F8` vs 底色 `#F6F7FB`，只差 2% | 新增 `LocalSurfacePalette`：浅色主题卡片纯白、深色主题用亮一档的容器色 |
| 平台头像图标看不见 | Strava 橙 2.51:1、Coros 2.85:1、Garmin 3.39:1（图形要 ≥3:1） | 实色底 + 白色图标 |
| 背景装饰等于隐形 | 白色描边压在浅底上，高通偏差只有 1/765 | 用主题主色重新着色 |
| 子页面标题会「跳」 | 返回箭头 x=71/84/113，标题 y=172/200/201 | 抽 `SubPageHeader` 统一 |
| 状态徽章列抖动 | 徽章跟在平台名后面，起点 x=586/551/562/670/595 | 文字列 `weight(1f)` + 徽章右对齐成固定列 |
| 空值漏到界面 | `2026-08-15 · 3.64 km · —`、`耗时 —` | 缺字段就整段省略 |
| 主 CTA 像禁用态 | `同步 0 条` 2.25:1、`导出文件` 2.31:1 | 显式指定 `disabledContentColor = onSurfaceVariant`（≈4.6:1） |

**教训**：没有视觉模型不等于没法验收。把「好不好看」拆成
**坐标 / 对比度 / 截断 / 重叠 / 字号断层** 这几项可测量的指标，
用 dump + 截图就能测，比「我觉得还行」靠谱得多。`tools/audit.py` 就是这套脚本。

### 15.6 验收口径（以后每次改版都跑）

1. `./build.sh :app:compileDebugKotlin --console=plain --rerun-tasks` 零 error 零 warning；
2. `tools/audit.py`：浅色 6 页 + 深色 3 页，文本对比度全部 ≥4.5、无文本重叠；
3. 图形（图标/进度条/描边）对比度 ≥3:1；
4. 改版前后中文字符串集合差逐条核对，功能文案不许消失；
5. 滚动区最后一行能完整滚出来（不被视口底边或底部操作栏切一半）。
