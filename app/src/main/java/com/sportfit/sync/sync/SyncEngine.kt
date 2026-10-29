package com.sportfit.sync.sync

import com.sportfit.sync.cache.CacheManager
import com.sportfit.sync.core.format.FitPayload
import com.sportfit.sync.data.local.AppDatabase
import com.sportfit.sync.data.local.CachedActivityEntity
import com.sportfit.sync.data.local.SyncJobEntity
import com.sportfit.sync.data.local.SyncLogEntity
import com.sportfit.sync.data.local.SyncRecordEntity
import com.sportfit.sync.data.local.toActivityMeta
import com.sportfit.sync.data.repo.AccountRepository
import com.sportfit.sync.domain.ActivityFingerprint
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.domain.SyncAbort
import com.sportfit.sync.domain.SyncDirection
import com.sportfit.sync.domain.SyncStatus
import com.sportfit.sync.domain.UploadOutcome
import com.sportfit.sync.platform.MfaProvider
import com.sportfit.sync.platform.PlatformAccount
import com.sportfit.sync.platform.PlatformContext
import com.sportfit.sync.platform.PlatformException
import com.sportfit.sync.platform.PlatformRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/** What to sync in one run. */
data class SyncRequest(
    val source: Platform,
    val target: Platform,
    /** `null` means "everything currently eligible". */
    val selectedIds: Set<String>? = null,
    /** Re-send activities the ledger already marks as synced. */
    val includeAlreadySynced: Boolean = false,
    /** Upper bound on how many source activities to pull down for the run. */
    val listLimit: Int = 200,
) {
    val direction: SyncDirection get() = SyncDirection(source, target)
}

/** Final tally of a run. */
data class SyncSummary(
    val jobId: Long,
    val direction: SyncDirection,
    val total: Int,
    val succeeded: Int,
    val duplicated: Int,
    val failed: Int,
    val status: String,
    val message: String? = null,
) {
    val processed: Int get() = succeeded + duplicated + failed
}

/** Everything the UI needs to render a running sync. */
sealed interface SyncEvent {
    data class Log(val level: String, val message: String) : SyncEvent

    data class Started(val jobId: Long, val direction: SyncDirection, val total: Int) : SyncEvent

    data class ActivityStarted(
        val index: Int,
        val total: Int,
        val activity: ActivityMeta,
    ) : SyncEvent

    data class ActivityFinished(
        val index: Int,
        val total: Int,
        val activity: ActivityMeta,
        val outcome: UploadOutcome,
    ) : SyncEvent

    data class Finished(val summary: SyncSummary) : SyncEvent
}

/**
 * Drives one `source → target` run.
 *
 * Design notes that differ from the upstream Python scripts:
 *
 *  - **Per-activity isolation.** A failure marks just that activity and the
 *    run continues; the original Garmin→Coros path `return`s on the first OSS
 *    error, abandoning the rest of the batch.
 *  - **Failures are retryable.** Failed activities stay eligible, so a later
 *    run (or the "retry failed" action) picks them up — upstream stores
 *    status 2 and then never selects it again.
 *  - **Temp files are per-run and disposable.** Payloads live under
 *    `cache/fit-cache/job-{id}` and are deleted once the run finishes, which
 *    is the behaviour mobile storage needs.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val accounts: AccountRepository,
    private val cache: CacheManager,
    private val mfa: MfaProvider,
) {

    companion object {
        /**
         * How many activities we are willing to pull in one go.
         *
         * Used for the one-off "pull the whole history" step that runs right
         * after an account is configured; later runs only fetch the newest
         * slice and merge it into the local table.
         */
        const val HISTORY_LIMIT = 5_000

        /**
         * 目标端最多取多少条已有活动用于比对。
         *
         * 只做「内容是否已经存在」的兜底检查，取最近的一批即可；取更多会把
         * 一次同步拖得很慢，而漏掉的比对只会退化成「照常同步」，不会误删。
         */
        const val TARGET_COMPARE_LIMIT = 500
    }

    fun run(request: SyncRequest): Flow<SyncEvent> = channelFlow {
        val direction = request.direction
        val sourceRow = accountOrThrow(request.source)
        val targetRow = accountOrThrow(request.target)

        val jobId = db.syncDao().insertJob(
            SyncJobEntity(
                sourcePlatform = request.source.key,
                targetPlatform = request.target.key,
            ),
        )
        val tempDir = cache.jobTempDir(jobId)

        suspend fun log(message: String, level: String = "INFO") {
            db.syncDao().insertLog(SyncLogEntity(jobId = jobId, level = level, message = message))
            send(SyncEvent.Log(level, message))
        }

        var total = 0
        var ok = 0
        var dup = 0
        var fail = 0
        var finished = false

        try {
            send(SyncEvent.Log("INFO", "开始同步：${direction.label}"))
            log("临时目录：${tempDir.name}")

            val contextFor: (Platform, com.sportfit.sync.platform.PlatformAccount) -> PlatformContext =
                { platform, account ->
                    PlatformContext(
                        account = account,
                        mfa = mfa,
                        persistToken = { blob -> accounts.persistToken(platform, blob) },
                        log = { message -> log(message) },
                    )
                }

            log("正在登录 ${request.source.displayName}…")
            val source = PlatformRegistry.gateway(request.source)
                .createSource(contextFor(request.source, sourceRow))
            log("正在登录 ${request.target.displayName}…")
            val target = PlatformRegistry.gateway(request.target)
                .createTarget(contextFor(request.target, targetRow))

            accounts.updateStatus(request.source, "CONNECTED")
            accounts.updateStatus(request.target, "CONNECTED")

            // 增量更新：网络上只取最近的一批并落库，活动的完整历史以本地库为准。
            // 首次配置账号时历史已经全量拉了下来（见 [warmUpPlatform]），之后
            // 每次同步只补最新的一批，历史活动不会被反复下载。
            log("读取 ${request.source.displayName} 活动列表（增量，本次最多 ${request.listLimit} 条）…")
            val fetched = source.listActivities(request.listLimit)
                .distinctBy { it.id }
            log("本次拉取 ${fetched.size} 条活动")

            cacheActivities(request.source, fetched)

            // 活动全集 = 本地库：新活动只有先落库，才可能进入本次同步。
            val activities = cachedActivities(request.source)
            log("本地共 ${activities.size} 条活动记录")
            // 账本只需要为「本次看到的」活动建行：没有行的活动按未同步处理，
            // 真正同步成功时 markSynced 会补上它自己的行。
            seedLedger(request.source, request.target, fetched)

            val records = db.syncDao()
                .recordsFor(request.source.key, request.target.key)
                .associateBy { it.activityId }
            val excluded = db.exclusionDao()
                .listFor(request.source.key, request.target.key)
                .map { it.activityId }
                .toSet()
            // 内容指纹：这个方向上已经同步过的「同样的数据」。
            val syncedFingerprints = db.syncDao()
                .syncedFingerprints(request.source.key, request.target.key)
                .toSet()

            // 目标端已有的活动：源端这条数据，目标端可能早就有了（之前用别的
            // 工具同步过、或者本地账本被清过）。目标平台不支持查询、或查询
            // 失败时，退化成「只和本地账本比对」——宁可多传一次，也不误跳。
            val targetActivities = try {
                target.listActivities(TARGET_COMPARE_LIMIT)
            } catch (error: Exception) {
                log(
                    "读取 ${request.target.displayName} 已有活动失败，本次只与本地账本比对：" +
                        (error.message ?: "未知错误"),
                    "WARN",
                )
                emptyList()
            }
            val targetFingerprints = targetActivities.mapNotNull { activity ->
                ActivityFingerprint.looseOf(activity).takeIf { it != ActivityFingerprint.NONE }
            }.toSet()

            // ===== 拉完列表立刻比对去重 =====
            val plan = SyncPlanner.plan(
                activities = activities,
                statusById = records.mapValues { SyncStatus.fromCode(it.value.status) },
                excludedIds = excluded,
                selectedIds = request.selectedIds,
                includeAlreadySynced = request.includeAlreadySynced,
                syncedFingerprints = syncedFingerprints,
                targetLooseFingerprints = targetFingerprints,
            )

            // 判定为「同样的数据」的活动，把结论写回账本：
            // 界面上的状态才和账本一致（显示已同步），下次也能按 id 直接跳过。
            markDuplicatesAsSynced(
                source = request.source.key,
                target = request.target.key,
                duplicates = plan.duplicatesByContent + plan.duplicatesOnTarget,
                existing = records,
            )

            log(
                "比对去重：已同步 ${plan.alreadySynced.size} 条 · " +
                    "内容相同 ${plan.duplicatesByContent.size} 条 · " +
                    "目标端已有 ${plan.duplicatesOnTarget.size} 条 · " +
                    "已排除 ${plan.excluded.size} 条 · " +
                    "待同步 ${plan.candidates.size} 条",
            )

            // 单次运行的处理量仍受「每次最多 N 条」限制；用户显式勾选的活动不受限制。
            val candidates = SyncPlanner.applyLimit(
                candidates = plan.candidates,
                limit = request.listLimit,
                explicitSelection = request.selectedIds != null,
            )
            if (candidates.size < plan.candidates.size) {
                log("单次上限 ${request.listLimit} 条，本次先处理前 ${candidates.size} 条")
            }
            total = candidates.size

            send(SyncEvent.Started(jobId, direction, total))
            if (total == 0) {
                log("没有需要同步的活动")
            } else {
                log("本次将同步 $total 条活动")
            }

            candidates.forEachIndexed { index, activity ->
                send(SyncEvent.ActivityStarted(index, total, activity))
                db.syncDao().bumpAttempt(request.source.key, request.target.key, activity.id)

                try {
                    log("[${index + 1}/$total] 下载 ${activity.name.ifBlank { activity.id }}…")
                    val payload = source.download(activity)

                    // Stage on disk so a failed transfer leaves something to
                    // diagnose, and so the cache screen reports real numbers
                    // instead of always showing an empty directory.
                    val staged = stagePayload(tempDir, activity.id, payload)
                    log(
                        "[${index + 1}/$total] 已暂存 ${staged.name}（${payload.sizeBytes} 字节），" +
                            "上传到 ${request.target.displayName}…",
                    )
                    val outcome = target.upload(payload)
                    if (outcome.isSuccess && cache.deleteTempAfterSuccess()) staged.delete()

                    when (outcome) {
                        is UploadOutcome.Success, is UploadOutcome.Duplicate -> {
                            if (outcome is UploadOutcome.Duplicate) dup++ else ok++
                            markSynced(
                                request.source.key,
                                request.target.key,
                                activity.id,
                                ActivityFingerprint.of(activity),
                            )
                            val label = if (outcome is UploadOutcome.Duplicate) "已存在（重复）" else "成功"
                            log("[${index + 1}/$total] ${activity.name.ifBlank { activity.id }}：$label", "SUCCESS")
                        }

                        is UploadOutcome.Failed -> {
                            fail++
                            markFailed(
                                request.source.key,
                                request.target.key,
                                activity.id,
                                outcome.reason,
                                ActivityFingerprint.of(activity),
                            )
                            log(
                                "[${index + 1}/$total] ${activity.name.ifBlank { activity.id }} 失败：${outcome.reason}",
                                "ERROR",
                            )
                        }
                    }
                    send(SyncEvent.ActivityFinished(index, total, activity, outcome))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fail++
                    val reason = error.message ?: error::class.simpleName ?: "未知错误"
                    markFailed(
                        request.source.key,
                        request.target.key,
                        activity.id,
                        reason,
                        ActivityFingerprint.of(activity),
                    )
                    log("[${index + 1}/$total] ${activity.name.ifBlank { activity.id }} 异常：$reason", "ERROR")
                    send(SyncEvent.ActivityFinished(index, total, activity, UploadOutcome.Failed(reason)))
                }
            }

            val status = when {
                fail == 0 -> SyncJobEntity.STATUS_SUCCESS
                ok + dup > 0 -> SyncJobEntity.STATUS_PARTIAL
                else -> SyncJobEntity.STATUS_FAILED
            }
            val message = "成功 $ok，重复 $dup，失败 $fail"
            db.syncDao().finishJob(
                jobId = jobId,
                status = status,
                total = total,
                ok = ok,
                dup = dup,
                fail = fail,
                finishedAt = System.currentTimeMillis(),
                message = message,
            )
            finished = true
            log("同步结束：$message", if (fail == 0) "SUCCESS" else "WARN")
            send(
                SyncEvent.Finished(
                    SyncSummary(jobId, direction, total, ok, dup, fail, status, message),
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                db.syncDao().finishJob(
                    jobId = jobId,
                    status = SyncJobEntity.STATUS_CANCELLED,
                    total = total,
                    ok = ok,
                    dup = dup,
                    fail = fail,
                    finishedAt = System.currentTimeMillis(),
                    message = "已取消",
                )
                db.syncDao().insertLog(
                    SyncLogEntity(jobId = jobId, level = "WARN", message = "同步已被用户取消"),
                )
            }
            throw cancelled
        } catch (error: Exception) {
            val reason = error.message ?: error::class.simpleName ?: "未知错误"
            withContext(NonCancellable) {
                db.syncDao().finishJob(
                    jobId = jobId,
                    status = SyncJobEntity.STATUS_FAILED,
                    total = total,
                    ok = ok,
                    dup = dup,
                    fail = fail,
                    finishedAt = System.currentTimeMillis(),
                    message = reason,
                )
                db.syncDao().insertLog(
                    SyncLogEntity(jobId = jobId, level = "ERROR", message = "同步中断：$reason"),
                )
            }
            throw error
        } finally {
            withContext(NonCancellable) {
                cache.finalizeJobTemp(jobId, keepEvidence = fail > 0)
                cache.applyRetention()
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun accountOrThrow(platform: Platform): PlatformAccount =
        if (platform == Platform.DEMO) {
            // Rehearsal mode has no credentials and no account row.
            com.sportfit.sync.platform.demo.DemoGateway.accountFor()
        } else {
            accounts.credentials(platform) ?: throw SyncAbort.MissingAccount(platform)
        }

    private fun stagePayload(dir: File, activityId: String, payload: FitPayload): File {
        if (!dir.exists()) dir.mkdirs()
        // Most platforms already name the file after the activity id; only
        // prefix when that would otherwise lose the id.
        val name = if (payload.fileName.startsWith(activityId)) {
            payload.fileName
        } else {
            "$activityId-${payload.fileName}"
        }
        val file = File(dir, name)
        file.writeBytes(payload.bytes)
        return file
    }

    private suspend fun cacheActivities(platform: Platform, activities: List<ActivityMeta>) {        if (activities.isEmpty()) return
        val now = System.currentTimeMillis()
        db.activityDao().upsertAll(
            activities.map { meta ->
                CachedActivityEntity(
                    platform = platform.key,
                    activityId = meta.id,
                    name = meta.name,
                    startTime = meta.startTime,
                    distanceMeters = meta.distanceMeters,
                    durationSec = meta.durationSec,
                    sportType = meta.sportType,
                    fetchedAt = now,
                )
            },
        )
    }

    /**
     * The full activity history we know about locally, newest first.
     *
     * The local table is the source of truth for "which activities exist":
     * the network only contributes the newest slice on every run, so a
     * history that was pulled once stays available offline.
     */
    private suspend fun cachedActivities(platform: Platform): List<ActivityMeta> =
        db.activityDao().listFor(platform.key, HISTORY_LIMIT).map { it.toActivityMeta() }

    /** Adds ledger rows for activities we have never seen before. */
    private suspend fun seedLedger(
        source: Platform,
        target: Platform,
        activities: List<ActivityMeta>,
    ) {
        if (activities.isEmpty()) return
        db.syncDao().insertIgnoringExisting(
            activities.map { meta ->
                SyncRecordEntity(
                    sourcePlatform = source.key,
                    targetPlatform = target.key,
                    activityId = meta.id,
                    status = SyncStatus.UNSYNCED.code,
                    fingerprint = ActivityFingerprint.of(meta),
                )
            },
        )
    }

    private suspend fun markSynced(
        source: String,
        target: String,
        activityId: String,
        fingerprint: String,
    ) {
        val attempts = db.syncDao().record(source, target, activityId)?.attempts ?: 0
        db.syncDao().upsertRecord(
            source = source,
            target = target,
            activityId = activityId,
            status = SyncStatus.SYNCED.code,
            attempts = attempts,
            error = null,
            syncedAt = System.currentTimeMillis(),
            fingerprint = fingerprint,
        )
    }

    private suspend fun markFailed(
        source: String,
        target: String,
        activityId: String,
        reason: String,
        fingerprint: String,
    ) {
        val attempts = db.syncDao().record(source, target, activityId)?.attempts ?: 0
        db.syncDao().upsertRecord(
            source = source,
            target = target,
            activityId = activityId,
            status = SyncStatus.FAILED.code,
            attempts = attempts,
            error = reason.take(500),
            syncedAt = null,
            fingerprint = fingerprint,
        )
    }

    /** Re-queues everything that failed in a direction. Returns rows touched. */
    suspend fun requeueFailed(source: Platform, target: Platform): Int =
        db.syncDao().requeueFailed(source.key, target.key)

    /**
     * 把「判定为同样的数据」的活动写回账本。
     *
     * 这些活动本次不会下载、不会上传，但账本必须记住这个结论：
     *  - 界面上的「已同步」才和账本一致（否则选择器里会一直显示「待同步」）；
     *  - 下次不用再比对内容，直接按 id 跳过。
     */
    private suspend fun markDuplicatesAsSynced(
        source: String,
        target: String,
        duplicates: List<ActivityMeta>,
        existing: Map<String, SyncRecordEntity>,
    ) {
        if (duplicates.isEmpty()) return
        val now = System.currentTimeMillis()
        db.syncDao().upsertRecords(
            duplicates.map { activity ->
                SyncRecordEntity(
                    sourcePlatform = source,
                    targetPlatform = target,
                    activityId = activity.id,
                    status = SyncStatus.SYNCED.code,
                    attempts = existing[activity.id]?.attempts ?: 0,
                    lastError = null,
                    syncedAt = now,
                    fingerprint = ActivityFingerprint.of(activity),
                    updatedAt = now,
                )
            },
        )
    }

    /**
     * Pulls the source's activity listing without syncing anything.
     *
     * Used by the picker's "refresh" action so the user can preview before
     * committing to a run. The whole history is pulled here, because the
     * local table is what the picker — and every later incremental run —
     * works from.
     *
     * The listing is compared against the ledger right away, so the picker
     * can show what is already synced before a run is even started.
     */
    suspend fun refreshSourceActivities(
        direction: SyncDirection,
        limit: Int = HISTORY_LIMIT,
    ): ActivityDedupSummary = withContext(Dispatchers.IO) {
        val fetched = pullHistory(direction.source, limit)
        seedLedger(direction.source, direction.target, fetched)

        val records = db.syncDao()
            .recordsFor(direction.source.key, direction.target.key)
            .associateBy { it.activityId }
        val excluded = db.exclusionDao()
            .listFor(direction.source.key, direction.target.key)
            .map { it.activityId }
            .toSet()
        val syncedFingerprints = db.syncDao()
            .syncedFingerprints(direction.source.key, direction.target.key)
            .toSet()

        val activities = cachedActivities(direction.source)
        val plan = SyncPlanner.plan(
            activities = activities,
            statusById = records.mapValues { SyncStatus.fromCode(it.value.status) },
            excludedIds = excluded,
            syncedFingerprints = syncedFingerprints,
        )
        markDuplicatesAsSynced(
            source = direction.source.key,
            target = direction.target.key,
            duplicates = plan.duplicatesByContent,
            existing = records,
        )
        ActivityDedupSummary(
            localTotal = activities.size,
            alreadySynced = plan.alreadySynced.size,
            duplicatesByContent = plan.duplicatesByContent.size,
            pending = plan.candidates.size,
        )
    }

    /**
     * Pulls the *whole* activity history of one account into the local table.
     *
     * Runs right after an account is configured: from then on the app knows
     * every activity the account owns, and each sync only has to fetch the
     * newest slice — an incremental update instead of a full re-read.
     */
    suspend fun warmUpPlatform(platform: Platform, limit: Int = HISTORY_LIMIT): Int =
        withContext(Dispatchers.IO) {
            pullHistory(platform, limit).size
        }

    /** Logs in and stores the account's activities locally. */
    private suspend fun pullHistory(platform: Platform, limit: Int): List<ActivityMeta> {
        // Must go through the same resolution as a real run, otherwise the
        // rehearsal platform is rejected as "no account configured" here while
        // working everywhere else.
        val account = accountOrThrow(platform)
        val log: suspend (String) -> Unit = {}
        val source = PlatformRegistry.gateway(platform).createSource(
            PlatformContext(
                account = account,
                mfa = mfa,
                persistToken = { blob -> accounts.persistToken(platform, blob) },
                log = log,
            ),
        )
        val activities = source.listActivities(limit).distinctBy { it.id }
        cacheActivities(platform, activities)
        accounts.updateStatus(platform, "CONNECTED")
        return activities
    }
}

/** 「拉取列表 + 比对去重」的结果，用于界面提示。 */
data class ActivityDedupSummary(
    /** 本地一共存了多少条活动。 */
    val localTotal: Int = 0,
    /** 按 id 命中账本、已经同步过的。 */
    val alreadySynced: Int = 0,
    /** 内容与已同步数据相同、这次不再同步的。 */
    val duplicatesByContent: Int = 0,
    /** 目标端已经有的（只有同步任务会查目标端，选择器刷新不查）。 */
    val duplicatesOnTarget: Int = 0,
    /** 被用户排除的。 */
    val excluded: Int = 0,
    /** 本次可以同步的。 */
    val pending: Int = 0,
)

/** Convenience for classifying a throwable into a user-facing message. */
fun Throwable.toUserMessage(): String = when (this) {
    is PlatformException.Auth -> message ?: "登录失效"
    is PlatformException.Network -> message ?: "网络错误"
    is PlatformException.Protocol -> message ?: "平台返回异常"
    is SyncAbort -> message ?: "无法开始同步"
    else -> message ?: this::class.simpleName ?: "未知错误"
}
