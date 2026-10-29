package com.sportfit.sync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY platform")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY platform")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE platform = :platform")
    suspend fun get(platform: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE platform = :platform")
    suspend fun delete(platform: String)

    @Query(
        """
        UPDATE accounts
        SET status = :status, lastError = :error, lastVerifiedAt = :verifiedAt, updatedAt = :now
        WHERE platform = :platform
        """,
    )
    suspend fun updateStatus(
        platform: String,
        status: String,
        error: String?,
        verifiedAt: Long?,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE accounts SET tokenCipher = :token, updatedAt = :now WHERE platform = :platform")
    suspend fun updateToken(
        platform: String,
        token: String?,
        now: Long = System.currentTimeMillis(),
    )
}

@Dao
interface ActivityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(activities: List<CachedActivityEntity>)

    @Query(
        """
        SELECT * FROM cached_activities
        WHERE platform = :platform
        ORDER BY startTime DESC, activityId DESC
        LIMIT :limit
        """,
    )
    suspend fun listFor(platform: String, limit: Int = 500): List<CachedActivityEntity>

    @Query("SELECT * FROM cached_activities WHERE platform = :platform ORDER BY startTime DESC LIMIT :limit")
    fun observeFor(platform: String, limit: Int = 500): Flow<List<CachedActivityEntity>>

    /** 每个平台本地缓存的活动条数：一次拿到所有平台的计数。 */
    @Query("SELECT platform, COUNT(*) AS count FROM cached_activities GROUP BY platform")
    fun observeCounts(): Flow<List<PlatformActivityCount>>

    @Query("SELECT COUNT(*) FROM cached_activities")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM cached_activities WHERE platform = :platform")
    suspend fun countFor(platform: String): Int

    @Query("SELECT COALESCE(SUM(LENGTH(name) + LENGTH(COALESCE(sportType, '')) + 48), 0) FROM cached_activities")
    suspend fun approximateBytes(): Long

    @Query("DELETE FROM cached_activities WHERE fetchedAt < :cutoff")
    suspend fun deleteFetchedBefore(cutoff: Long): Int

    /**
     * Drops cached listings of platforms that no longer have an account.
     *
     * The listing of a *configured* account is kept until the user clears it
     * by hand: it is the local history the incremental sync works from, so
     * pruning it by age would make already-fetched activities unreachable.
     */
    @Query(
        """
        DELETE FROM cached_activities
        WHERE platform NOT IN (:platforms) AND fetchedAt < :cutoff
        """,
    )
    suspend fun deleteStaleForUnconfigured(platforms: List<String>, cutoff: Long): Int

    @Query("DELETE FROM cached_activities WHERE platform = :platform")
    suspend fun deleteFor(platform: String): Int

    @Query("DELETE FROM cached_activities")
    suspend fun deleteAll()
}

@Dao
interface SyncDao {

    @Query(
        """
        SELECT * FROM sync_records
        WHERE sourcePlatform = :source AND targetPlatform = :target
        """,
    )
    suspend fun recordsFor(source: String, target: String): List<SyncRecordEntity>

    @Query(
        """
        SELECT * FROM sync_records
        WHERE sourcePlatform = :source AND targetPlatform = :target AND activityId = :activityId
        """,
    )
    suspend fun record(source: String, target: String, activityId: String): SyncRecordEntity?

    @Query(
        """
        SELECT * FROM sync_records
        WHERE sourcePlatform = :source AND targetPlatform = :target
        """,
    )
    fun observeRecordsFor(source: String, target: String): Flow<List<SyncRecordEntity>>

    @Query(
        """
        SELECT activityId FROM sync_records
        WHERE sourcePlatform = :source AND targetPlatform = :target AND status = :status
        """,
    )
    suspend fun idsWithStatus(source: String, target: String, status: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringExisting(records: List<SyncRecordEntity>)

    @Query(
        """
        INSERT INTO sync_records
            (sourcePlatform, targetPlatform, activityId, status, attempts, lastError, syncedAt, fingerprint, updatedAt)
        VALUES (:source, :target, :activityId, :status, :attempts, :error, :syncedAt, :fingerprint, :now)
        ON CONFLICT(sourcePlatform, targetPlatform, activityId) DO UPDATE SET
            status = :status,
            attempts = :attempts,
            lastError = :error,
            syncedAt = :syncedAt,
            fingerprint = :fingerprint,
            updatedAt = :now
        """,
    )
    suspend fun upsertRecord(
        source: String,
        target: String,
        activityId: String,
        status: Int,
        attempts: Int,
        error: String?,
        syncedAt: Long?,
        fingerprint: String = "",
        now: Long = System.currentTimeMillis(),
    )

    /**
     * 批量写入账本行，同 (source, target, activityId) 覆盖。
     *
     * 去重结论可能一次涉及成百上千条活动（内容相同、目标端已有），
     * 逐条 upsert 会把一次同步拖成几千次 SQL；这里一次事务写完。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecords(records: List<SyncRecordEntity>)

    /**
     * 内容指纹集合：该方向下已经同步过的活动。
     *
     * 按 id 去重挡不住「同一份数据换了新 id」的情况（重新导入 FIT、切换同平台
     * 另一个账号、平台自身重发 id），所以额外用内容指纹判断。空串表示这条
     * 历史记录没有指纹（v2 之前的数据），不参与指纹去重。
     */
    @Query(
        """
        SELECT fingerprint FROM sync_records
        WHERE sourcePlatform = :source AND targetPlatform = :target
          AND status = 1 AND fingerprint != ''
        """,
    )
    suspend fun syncedFingerprints(source: String, target: String): List<String>

    @Query(
        """
        UPDATE sync_records
        SET attempts = attempts + 1, updatedAt = :now
        WHERE sourcePlatform = :source AND targetPlatform = :target AND activityId = :activityId
        """,
    )
    suspend fun bumpAttempt(
        source: String,
        target: String,
        activityId: String,
        now: Long = System.currentTimeMillis(),
    )

    /** Requeue every failed activity in a direction; used by "retry failed items". */
    @Query(
        """
        UPDATE sync_records
        SET status = 0, lastError = NULL, updatedAt = :now
        WHERE sourcePlatform = :source AND targetPlatform = :target AND status = 2
        """,
    )
    suspend fun requeueFailed(
        source: String,
        target: String,
        now: Long = System.currentTimeMillis(),
    ): Int

    @Query("SELECT COUNT(*) FROM sync_records WHERE sourcePlatform = :source AND targetPlatform = :target")
    suspend fun recordCount(source: String, target: String): Int

    @Query("SELECT COUNT(*) FROM sync_records")
    suspend fun totalRecordCount(): Int

    @Query("SELECT COALESCE(SUM(LENGTH(COALESCE(lastError, '')) + 80), 0) FROM sync_records")
    suspend fun approximateBytes(): Long

    @Query("DELETE FROM sync_records WHERE sourcePlatform = :source AND targetPlatform = :target")
    suspend fun deleteRecords(source: String, target: String): Int

    @Query("DELETE FROM sync_records")
    suspend fun deleteAllRecords()

    // ---- jobs -------------------------------------------------------------

    @Insert
    suspend fun insertJob(job: SyncJobEntity): Long

    @Query(
        """
        UPDATE sync_jobs SET
            status = :status, total = :total, okCount = :ok, dupCount = :dup,
            failCount = :fail, finishedAt = :finishedAt, message = :message
        WHERE id = :jobId
        """,
    )
    suspend fun finishJob(
        jobId: Long,
        status: String,
        total: Int,
        ok: Int,
        dup: Int,
        fail: Int,
        finishedAt: Long?,
        message: String?,
    )

    @Query("SELECT * FROM sync_jobs ORDER BY startedAt DESC LIMIT :limit")
    fun observeJobs(limit: Int = 100): Flow<List<SyncJobEntity>>

    @Query("SELECT * FROM sync_jobs WHERE id = :jobId")
    fun observeJob(jobId: Long): Flow<SyncJobEntity?>

    @Query("SELECT * FROM sync_jobs WHERE id = :jobId")
    suspend fun getJob(jobId: Long): SyncJobEntity?

    @Query("SELECT COUNT(*) FROM sync_jobs")
    suspend fun jobCount(): Int

    @Query("SELECT COALESCE(SUM(LENGTH(COALESCE(message, '')) + 96), 0) FROM sync_jobs")
    suspend fun approximateJobBytes(): Long

    /** Keeps the newest [keep] runs and drops the rest. Returns rows deleted. */
    @Query(
        """
        DELETE FROM sync_jobs WHERE id NOT IN (
            SELECT id FROM sync_jobs ORDER BY startedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun pruneJobs(keep: Int): Int

    @Query("DELETE FROM sync_jobs")
    suspend fun deleteAllJobs()

    // ---- logs -------------------------------------------------------------

    @Insert
    suspend fun insertLog(log: SyncLogEntity): Long

    @Insert
    suspend fun insertLogs(logs: List<SyncLogEntity>)

    @Query("SELECT * FROM sync_logs WHERE jobId = :jobId ORDER BY id ASC LIMIT :limit")
    suspend fun logsFor(jobId: Long, limit: Int = 2000): List<SyncLogEntity>

    @Query("SELECT * FROM sync_logs WHERE jobId = :jobId ORDER BY id ASC LIMIT :limit")
    fun observeLogs(jobId: Long, limit: Int = 2000): Flow<List<SyncLogEntity>>

    @Query("DELETE FROM sync_logs WHERE ts < :cutoff")
    suspend fun deleteLogsBefore(cutoff: Long): Int

    @Query("DELETE FROM sync_logs WHERE jobId = :jobId")
    suspend fun deleteLogsForJob(jobId: Long): Int

    @Query("DELETE FROM sync_logs")
    suspend fun deleteAllLogs()

    @Query("SELECT COUNT(*) FROM sync_logs")
    suspend fun logCount(): Int

    @Query("SELECT COALESCE(SUM(LENGTH(message) + 40), 0) FROM sync_logs")
    suspend fun approximateLogBytes(): Long
}

@Dao
interface ExclusionDao {

    @Query(
        """
        SELECT * FROM exclusions
        WHERE sourcePlatform = :source AND targetPlatform = :target
        ORDER BY createdAt DESC
        """,
    )
    suspend fun listFor(source: String, target: String): List<ExclusionEntity>

    @Query(
        """
        SELECT * FROM exclusions
        WHERE sourcePlatform = :source AND targetPlatform = :target
        """,
    )
    fun observeFor(source: String, target: String): Flow<List<ExclusionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exclusion: ExclusionEntity)

    @Query(
        """
        DELETE FROM exclusions
        WHERE sourcePlatform = :source AND targetPlatform = :target AND activityId = :activityId
        """,
    )
    suspend fun remove(source: String, target: String, activityId: String): Int

    @Query("SELECT COUNT(*) FROM exclusions")
    suspend fun count(): Int

    @Query("DELETE FROM exclusions")
    suspend fun deleteAll()
}

@Dao
interface StrategyDao {

    @Query("SELECT * FROM strategies ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<StrategyEntity>>

    @Query("SELECT * FROM strategies ORDER BY updatedAt DESC")
    suspend fun getAll(): List<StrategyEntity>

    @Query("SELECT * FROM strategies WHERE useForBackground = 1 LIMIT 1")
    suspend fun backgroundStrategy(): StrategyEntity?

    @Query("SELECT * FROM strategies WHERE sourcePlatform = :source AND targetPlatform = :target LIMIT 1")
    suspend fun find(source: String, target: String): StrategyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(strategy: StrategyEntity): Long

    @Query(
        """
        UPDATE strategies
        SET name = :name, enabled = :enabled, updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun update(
        id: Long,
        name: String,
        enabled: Boolean,
        now: Long = System.currentTimeMillis(),
    )

    /** Only one direction may drive the background worker. */
    @Query("UPDATE strategies SET useForBackground = 0 WHERE id != :keepId")
    suspend fun clearBackgroundExcept(keepId: Long)

    @Query("UPDATE strategies SET useForBackground = :flag, updatedAt = :now WHERE id = :id")
    suspend fun setBackground(
        id: Long,
        flag: Boolean,
        now: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM strategies WHERE id = :id")
    suspend fun delete(id: Long)
}
