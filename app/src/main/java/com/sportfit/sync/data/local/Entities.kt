package com.sportfit.sync.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sportfit.sync.domain.ActivityMeta

/**
 * A bound platform account.
 *
 * `emailCipher` / `secretCipher` / `tokenCipher` are blobs produced by
 * `CryptoBox` (Android Keystore, AES-GCM). Nothing sensitive is stored in the
 * clear, and `android:allowBackup="false"` keeps them off cloud backups.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val platform: String,
    val displayName: String,
    val emailCipher: String? = null,
    /** Region/auth-domain selector; meaning depends on the platform. */
    val region: String? = null,
    val secretCipher: String? = null,
    /** Serialised OAuth/token blob (Garmin OAuth1+OAuth2, Strava tokens, …). */
    val tokenCipher: String? = null,
    /** CONNECTED | NEEDS_LOGIN | ERROR */
    val status: String = STATUS_NEEDS_LOGIN,
    val lastVerifiedAt: Long? = null,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_CONNECTED = "CONNECTED"
        const val STATUS_NEEDS_LOGIN = "NEEDS_LOGIN"
        const val STATUS_ERROR = "ERROR"
    }
}

/** Cached activity listing, so the picker works offline and instantly. */
@Entity(
    tableName = "cached_activities",
    primaryKeys = ["platform", "activityId"],
    indices = [Index("platform", "fetchedAt"), Index("fetchedAt")],
)
data class CachedActivityEntity(
    val platform: String,
    val activityId: String,
    val name: String,
    val startTime: Long? = null,
    val distanceMeters: Double? = null,
    val durationSec: Long? = null,
    val sportType: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
)

/**
 * The cached row as the uniform activity metadata the pipeline works with.
 *
 * The local table is the source of truth for "which activities exist", so the
 * sync and export paths must be able to read it without going back to the
 * network.
 */
fun CachedActivityEntity.toActivityMeta(): ActivityMeta = ActivityMeta(
    id = activityId,
    name = name,
    startTime = startTime,
    distanceMeters = distanceMeters,
    durationSec = durationSec,
    sportType = sportType,
)

/** 每个平台本地缓存的活动条数。 */
data class PlatformActivityCount(
    val platform: String,
    val count: Int,
)

/**
 * De-duplication ledger, keyed by direction.
 *
 * The upstream project needs one SQLite file *and* one status column per
 * target (`is_sync_coros`, `is_sync_garmin`, `is_sync_strava`). Keying on
 * (source, target, activityId) expresses the same thing while allowing one
 * source activity to be tracked independently for several targets.
 */
@Entity(
    tableName = "sync_records",
    primaryKeys = ["sourcePlatform", "targetPlatform", "activityId"],
    indices = [
        Index("sourcePlatform", "targetPlatform", "status"),
        Index("sourcePlatform", "activityId"),
    ],
)
data class SyncRecordEntity(
    val sourcePlatform: String,
    val targetPlatform: String,
    val activityId: String,
    /** 0 = unsynced, 1 = synced, 2 = failed. See [com.sportfit.sync.domain.SyncStatus]. */
    val status: Int = 0,
    val attempts: Int = 0,
    val lastError: String? = null,
    val syncedAt: Long? = null,
    /** 内容指纹（见 [com.sportfit.sync.domain.ActivityFingerprint]）；空串表示未记录。 */
    val fingerprint: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

/** One run of the sync engine. */
@Entity(tableName = "sync_jobs", indices = [Index("startedAt")])
data class SyncJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePlatform: String,
    val targetPlatform: String,
    /** RUNNING | SUCCESS | PARTIAL | FAILED | CANCELLED */
    val status: String = STATUS_RUNNING,
    val total: Int = 0,
    val okCount: Int = 0,
    val dupCount: Int = 0,
    val failCount: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val message: String? = null,
) {
    companion object {
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_PARTIAL = "PARTIAL"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}

/** Append-only sync log line, streamed to the UI and pruned by the cache policy. */
@Entity(tableName = "sync_logs", indices = [Index("jobId"), Index("ts")])
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val ts: Long = System.currentTimeMillis(),
    /** INFO | WARN | ERROR | SUCCESS */
    val level: String = "INFO",
    val message: String,
)

/** Per-direction permanent exclusion ("never sync this activity again"). */
@Entity(
    tableName = "exclusions",
    primaryKeys = ["sourcePlatform", "targetPlatform", "activityId"],
)
data class ExclusionEntity(
    val sourcePlatform: String,
    val targetPlatform: String,
    val activityId: String,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A saved, reusable sync direction.
 *
 * The design review asked for both interaction shapes: an ad-hoc direction
 * picker for one-off syncs, and this table for the handful of directions a
 * user actually repeats (typically "Garmin → Coros" plus a backup target).
 * At most one strategy is marked for background runs.
 */
@Entity(tableName = "strategies", indices = [Index("updatedAt")])
data class StrategyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourcePlatform: String,
    val targetPlatform: String,
    val enabled: Boolean = true,
    /** Use this direction for the scheduled background run. */
    val useForBackground: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
