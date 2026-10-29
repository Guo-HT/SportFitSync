package com.sportfit.sync.sync

import com.sportfit.sync.core.format.FitPackaging
import com.sportfit.sync.data.local.SyncJobEntity
import com.sportfit.sync.data.local.SyncLogEntity
import com.sportfit.sync.data.local.toActivityMeta
import com.sportfit.sync.data.repo.AccountRepository
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.SyncAbort
import com.sportfit.sync.domain.SyncDirection
import com.sportfit.sync.domain.SyncStatus
import com.sportfit.sync.domain.UploadOutcome
import com.sportfit.sync.export.ActivityExporter
import com.sportfit.sync.platform.MfaProvider
import com.sportfit.sync.platform.PlatformAccount
import com.sportfit.sync.platform.PlatformContext
import com.sportfit.sync.platform.PlatformRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which activities to write to disk. */
data class ExportRequest(
    val source: com.sportfit.sync.domain.Platform,
    val selectedIds: Set<String>,
    val listLimit: Int = 200,
)

/**
 * Downloads activities from a source platform and writes them to the user's
 * Downloads folder, without involving any target platform.
 *
 * Two reasons this exists:
 *  1. **Escape hatch.** If a target platform changes or breaks its import
 *     path, the user still needs a way to get their data out.
 *  2. **Backup.** A plain `.fit` file is readable by every tool in this space,
 *     so a periodic export is a reasonable safety net.
 *
 * Emits the same [SyncEvent] stream as [SyncEngine] so the existing progress
 * card, log view and history list work unchanged; the "target" is recorded as
 * [LOCAL_FILES_KEY].
 */
class ExportEngine(
    private val db: com.sportfit.sync.data.local.AppDatabase,
    private val accounts: AccountRepository,
    private val exporter: ActivityExporter,
    private val mfa: MfaProvider,
) {

    fun run(request: ExportRequest): Flow<SyncEvent> = channelFlow {
        val sourceRow = accountOrThrow(request.source)
        val direction = SyncDirection(request.source, com.sportfit.sync.domain.Platform.LOCAL_FILES)

        val jobId = db.syncDao().insertJob(
            SyncJobEntity(
                sourcePlatform = request.source.key,
                targetPlatform = LOCAL_FILES_KEY,
            ),
        )

        suspend fun log(message: String, level: String = "INFO") {
            db.syncDao().insertLog(SyncLogEntity(jobId = jobId, level = level, message = message))
            send(SyncEvent.Log(level, message))
        }

        var total = 0
        var ok = 0
        var fail = 0

        try {
            log("开始导出：${request.source.displayName} → 本地文件")

            val source = PlatformRegistry.gateway(request.source).createSource(
                PlatformContext(
                    account = sourceRow,
                    mfa = mfa,
                    persistToken = { blob -> accounts.persistToken(request.source, blob) },
                    log = { message -> log(message) },
                ),
            )

            // 活动全集以本地库为准：配置账号时历史已经全量落库，
            // 「选择活动同步」里选中的老活动不能因为「只取最新 N 条」被悄悄丢掉。
            val cached = db.activityDao()
                .listFor(request.source.key, SyncEngine.HISTORY_LIMIT)
                .map { it.toActivityMeta() }
            val activities = if (cached.isNotEmpty()) {
                cached
            } else {
                // 本地还没有缓存（例如刚配好账号、历史还没拉完）时回退到网络列表。
                source.listActivities(request.listLimit).distinctBy { it.id }
            }
            val selected = activities.filter { it.id in request.selectedIds }
            val missing = request.selectedIds - selected.map { it.id }.toSet()
            if (missing.isNotEmpty()) {
                log("有 ${missing.size} 条选中的活动本地没有缓存，已跳过", "WARN")
            }
            total = selected.size
            log("已选 ${total} 条活动")

            send(SyncEvent.Started(jobId, direction, total))

            selected.forEachIndexed { index, activity ->
                send(SyncEvent.ActivityStarted(index, total, activity))
                try {
                    val payload = source.download(activity)
                    val raw = FitPackaging.ensureRawFit(payload)
                    val fileName = ActivityExporter.buildFileName(
                        datePrefix = activity.startTime?.let(::formatDate),
                        name = activity.name,
                        activityId = activity.id,
                    )
                    val written = exporter.write(fileName, raw.bytes)

                    ok++
                    db.syncDao().upsertRecord(
                        source = request.source.key,
                        target = LOCAL_FILES_KEY,
                        activityId = activity.id,
                        status = SyncStatus.SYNCED.code,
                        attempts = 1,
                        error = null,
                        syncedAt = System.currentTimeMillis(),
                        fingerprint = com.sportfit.sync.domain.ActivityFingerprint.of(activity),
                    )
                    log(
                        "[${index + 1}/$total] 已导出 ${written.fileName}（${written.bytes} 字节）→ ${written.displayPath}",
                        "SUCCESS",
                    )
                    send(SyncEvent.ActivityFinished(index, total, activity, UploadOutcome.Success))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fail++
                    val reason = error.message ?: error::class.simpleName ?: "未知错误"
                    log("[${index + 1}/$total] 导出失败：$reason", "ERROR")
                    send(SyncEvent.ActivityFinished(index, total, activity, UploadOutcome.Failed(reason)))
                }
            }

            val status = when {
                fail == 0 -> SyncJobEntity.STATUS_SUCCESS
                ok > 0 -> SyncJobEntity.STATUS_PARTIAL
                else -> SyncJobEntity.STATUS_FAILED
            }
            val message = "已导出 $ok 个文件，失败 $fail"
            db.syncDao().finishJob(
                jobId = jobId,
                status = status,
                total = total,
                ok = ok,
                dup = 0,
                fail = fail,
                finishedAt = System.currentTimeMillis(),
                message = message,
            )
            log("导出结束：$message", if (fail == 0) "SUCCESS" else "WARN")
            send(
                SyncEvent.Finished(
                    SyncSummary(jobId, direction, total, ok, 0, fail, status, message),
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                db.syncDao().finishJob(
                    jobId, SyncJobEntity.STATUS_CANCELLED, total, ok, 0, fail,
                    System.currentTimeMillis(), "已取消",
                )
            }
            throw cancelled
        } catch (error: Exception) {
            val reason = error.message ?: error::class.simpleName ?: "未知错误"
            withContext(NonCancellable) {
                db.syncDao().finishJob(
                    jobId, SyncJobEntity.STATUS_FAILED, total, ok, 0, fail,
                    System.currentTimeMillis(), reason,
                )
                db.syncDao().insertLog(
                    SyncLogEntity(jobId = jobId, level = "ERROR", message = "导出中断：$reason"),
                )
            }
            throw error
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun accountOrThrow(
        platform: com.sportfit.sync.domain.Platform,
    ): PlatformAccount =
        if (platform == com.sportfit.sync.domain.Platform.DEMO) {
            com.sportfit.sync.platform.demo.DemoGateway.accountFor()
        } else {
            accounts.credentials(platform) ?: throw SyncAbort.MissingAccount(platform)
        }

    private fun formatDate(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMillis))

    companion object {
        const val LOCAL_FILES_KEY = "local-files"
    }
}
