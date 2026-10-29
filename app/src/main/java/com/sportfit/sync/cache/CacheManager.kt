package com.sportfit.sync.cache

import android.content.Context
import com.sportfit.sync.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** One clearable cache category, as shown on the settings screen. */
data class CacheCategory(
    val key: String,
    val title: String,
    val description: String,
    val bytes: Long,
    val itemCount: Int,
    /** Dangerous categories are never touched by the one-tap or automatic paths. */
    val dangerous: Boolean = false,
)

data class CacheSnapshot(
    val categories: List<CacheCategory>,
    val capturedAt: Long = System.currentTimeMillis(),
) {
    val safeCategories: List<CacheCategory> get() = categories.filterNot { it.dangerous }
    val totalBytes: Long get() = categories.sumOf { it.bytes }
    val safeBytes: Long get() = safeCategories.sumOf { it.bytes }
}

/**
 * Retention policy, chosen by the user in settings.
 *
 * Defaults match the agreed interaction design: delete transfer files as soon
 * as the activity lands, keep the activity listing a week, keep logs a month.
 */
data class CachePolicy(
    val deleteTempAfterSuccess: Boolean = true,
    val activityRetentionDays: Int = 7,
    val logRetentionDays: Int = 30,
    val jobHistoryLimit: Int = 100,
)

/**
 * Measures and clears on-device caches.
 *
 * Deliberately splits "safe" caches (recreatable: temp files, listings, logs,
 * history) from "dangerous" ones (the de-duplication ledger and credentials),
 * because clearing the ledger makes every activity look unsynced again.
 */
class CacheManager(
    private val context: Context,
    private val db: AppDatabase,
    private val policyProvider: () -> CachePolicy = { CachePolicy() },
) {

    private val tempRoot: File
        get() = File(context.cacheDir, TEMP_DIR_NAME)

    /** Per-run scratch directory for downloaded FIT/ZIP payloads. */
    fun jobTempDir(jobId: Long): File =
        File(tempRoot, "job-$jobId").apply { mkdirs() }

    suspend fun snapshot(): CacheSnapshot = withContext(Dispatchers.IO) {
        val activityDao = db.activityDao()
        val syncDao = db.syncDao()
        val exclusionDao = db.exclusionDao()
        val activityBytes = activityDao.approximateBytes()
        val activityCount = activityDao.count()
        val logBytes = syncDao.approximateLogBytes()
        val logCount = syncDao.logCount()
        val jobBytes = syncDao.approximateJobBytes()
        val jobCount = syncDao.jobCount()
        val recordBytes = syncDao.approximateBytes()
        val recordCount = syncDao.totalRecordCount()

        CacheSnapshot(
            categories = listOf(
                CacheCategory(
                    key = KEY_TEMP_FILES,
                    title = "临时 FIT / ZIP 文件",
                    description = "同步过程中的中转文件；同步成功后默认立即删除",
                    bytes = directorySize(tempRoot),
                    itemCount = countFiles(tempRoot),
                ),
                CacheCategory(
                    key = KEY_ACTIVITIES,
                    title = "活动列表缓存",
                    description = "已拉取的活动元数据（本地历史），清除后重新联网拉取",
                    bytes = activityBytes,
                    itemCount = activityCount,
                ),
                CacheCategory(
                    key = KEY_LOGS,
                    title = "同步日志",
                    description = "每次同步的逐条日志，保留 ${policyProvider().logRetentionDays} 天",
                    bytes = logBytes,
                    itemCount = logCount,
                ),
                CacheCategory(
                    key = KEY_HISTORY,
                    title = "同步历史",
                    description = "保留最近 ${policyProvider().jobHistoryLimit} 次运行记录",
                    bytes = jobBytes,
                    itemCount = jobCount,
                ),
                CacheCategory(
                    key = KEY_EXCLUSIONS,
                    title = "排除名单",
                    description = "被标记为「不再同步」的活动；清除后它们会重新出现",
                    bytes = exclusionDao.count() * 96L,
                    itemCount = exclusionDao.count(),
                ),
                CacheCategory(
                    key = KEY_SYNC_RECORDS,
                    title = "同步去重记录",
                    description = "决定哪些活动已同步过；清除后所有活动会被视为未同步",
                    bytes = recordBytes,
                    itemCount = recordCount,
                    dangerous = true,
                ),
            ),
        )
    }

    /** Clears one category. Dangerous keys require [force] to be set. */
    suspend fun clear(key: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val danger = key == KEY_SYNC_RECORDS || key == KEY_ACCOUNTS
        if (danger && !force) return@withContext false

        when (key) {
            KEY_TEMP_FILES -> tempRoot.deleteRecursively()
            KEY_ACTIVITIES -> db.activityDao().deleteAll()
            KEY_LOGS -> db.syncDao().deleteAllLogs()
            KEY_HISTORY -> db.syncDao().deleteAllJobs()
            KEY_EXCLUSIONS -> db.exclusionDao().deleteAll()
            KEY_SYNC_RECORDS -> db.syncDao().deleteAllRecords()
            else -> return@withContext false
        }
        tempRoot.mkdirs()
        true
    }

    /** One-tap "clear everything that is safe". */
    suspend fun clearAllSafe(): Unit = withContext(Dispatchers.IO) {
        tempRoot.deleteRecursively()
        db.activityDao().deleteAll()
        db.syncDao().deleteAllLogs()
        db.syncDao().deleteAllJobs()
        tempRoot.mkdirs()
    }

    /**
     * Applies the retention policy. Call after every sync run and on app start.
     */
    suspend fun applyRetention(): Unit = withContext(Dispatchers.IO) {
        val policy = policyProvider()
        val now = System.currentTimeMillis()

        // 活动列表不再是「用完即弃」的缓存：配置账号时会把历史全量拉下来，
        // 之后的每次同步都靠它做增量。自动按时间清掉，会让那些还没来得及
        // 同步的老活动永远失去被同步的机会。因此只清理「账号已经删掉」的
        // 平台的列表——那种数据已经没有主人了。
        val configured = db.accountDao().getAll().map { it.platform }.toSet()
        val activityCutoff = now - TimeUnit.DAYS.toMillis(policy.activityRetentionDays.toLong())
        if (configured.isEmpty()) {
            // 一个账号都没配：所有列表都是无主数据，按保留天数清理。
            db.activityDao().deleteFetchedBefore(activityCutoff)
        } else {
            db.activityDao().deleteStaleForUnconfigured(configured.toList(), activityCutoff)
        }

        val logCutoff = now - TimeUnit.DAYS.toMillis(policy.logRetentionDays.toLong())
        db.syncDao().deleteLogsBefore(logCutoff)

        db.syncDao().pruneJobs(policy.jobHistoryLimit)
    }

    /** Whether a payload may be dropped the moment its upload succeeds. */
    fun deleteTempAfterSuccess(): Boolean = policyProvider().deleteTempAfterSuccess

    /**
     * Finalises a run's scratch directory.
     *
     * [keepEvidence] is set when anything failed: the staged payloads are then
     * deliberately left on disk so the user can inspect them (and clear them
     * from the cache screen). A clean run always releases its directory.
     */
    suspend fun finalizeJobTemp(jobId: Long, keepEvidence: Boolean): Unit =
        withContext(Dispatchers.IO) {
            val dir = File(tempRoot, "job-$jobId")
            if (!dir.exists()) return@withContext
            if (!keepEvidence) dir.deleteRecursively()
        }

    private fun directorySize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun countFiles(dir: File): Int =
        if (!dir.exists()) 0 else dir.walkTopDown().count { it.isFile }

    companion object {
        const val TEMP_DIR_NAME = "fit-cache"
        const val KEY_TEMP_FILES = "temp_files"
        const val KEY_ACTIVITIES = "activities"
        const val KEY_LOGS = "logs"
        const val KEY_HISTORY = "history"
        const val KEY_EXCLUSIONS = "exclusions"
        const val KEY_SYNC_RECORDS = "sync_records"
        const val KEY_ACCOUNTS = "accounts"

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
            else -> "$bytes B"
        }
    }
}
