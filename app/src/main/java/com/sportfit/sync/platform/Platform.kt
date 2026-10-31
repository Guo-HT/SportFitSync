package com.sportfit.sync.platform

import com.sportfit.sync.core.format.FitPayload
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.domain.UploadOutcome

/**
 * Decrypted credentials handed to a platform client.
 *
 * The repository is responsible for decrypting blobs out of Room; platform
 * code never touches ciphertext.
 */
data class PlatformAccount(
    val platform: Platform,
    val email: String? = null,
    val password: String? = null,
    /** Garmin: `COM`/`CN`. Coros: `1`/`2`/`3`. iGPSPORT: `cn`/`global`. */
    val region: String? = null,
    /** Platform-specific token blob (Garmin OAuth JSON, Strava tokens, …). */
    val token: String? = null,
)

/** Asks the user for a verification code when a platform challenges the login. */
fun interface MfaProvider {
    suspend fun requestCode(platform: Platform): String?
}

/** Everything a platform client needs that is not part of its own account. */
class PlatformContext(
    val account: PlatformAccount,
    val mfa: MfaProvider = MfaProvider { null },
    /** Persists a refreshed token blob so the next run can skip the login. */
    val persistToken: suspend (String) -> Unit = {},
    /** Human-readable progress line appended to the running sync log. */
    val log: suspend (String) -> Unit = {},
)

/** Base type for every platform failure, so the engine can classify results. */
sealed class PlatformException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** Credentials rejected, or the session could not be established. */
    class Auth(message: String, cause: Throwable? = null) : PlatformException(message, cause)

    /** Transport-level failure (DNS, TLS, timeout). Retrying may help. */
    class Network(message: String, cause: Throwable? = null) : PlatformException(message, cause)

    /** The platform answered, but not in a shape we understand. */
    class Protocol(message: String, cause: Throwable? = null) : PlatformException(message, cause)
}

/** A platform that activities can be downloaded from. */
interface PlatformSource {
    val platform: Platform

    /** Newest-first listing, capped at [limit] entries. */
    suspend fun listActivities(limit: Int = 200): List<ActivityMeta>

    /** Fetches the original activity file, without normalising its container. */
    /**
     * Fetches the original file for one activity.
     *
     * Takes the full [ActivityMeta] rather than a bare id because some
     * platforms need more than the id: Coros rejects
     * `activity/detail/download` with `1031 Parameter input error` unless
     * `sportType` matches the activity (verified against the live API).
     */
    suspend fun download(activity: ActivityMeta): FitPayload
}

/** A platform that activities can be uploaded to. */
interface PlatformTarget {
    val platform: Platform

    /**
     * Uploads one activity.
     *
     * @param payload the activity file; targets wrap it if they need a ZIP.
     * @param activityName the source activity's human-readable title, when the
     *   source platform provided one. Targets that can show a title (iGPSPORT
     *   names the activity after the uploaded file) use it; the ones that read
     *   the title out of the FIT itself ignore it.
     */
    suspend fun upload(payload: FitPayload, activityName: String? = null): UploadOutcome

    /**
     * 目标端已有的活动列表，用于同步前比对去重。
     *
     * 默认返回空列表 = 该平台不支持查询目标端已有活动，比对自动退化为「只和本地账本比对」。
     */
    suspend fun listActivities(limit: Int = 500): List<ActivityMeta> = emptyList()
}

/**
 * Builds a logged-in [PlatformSource] / [PlatformTarget] for an account.
 *
 * Construction performs the login (or token refresh) so callers only ever see
 * ready-to-use objects; a failure surfaces as [PlatformException.Auth].
 */
interface PlatformGateway {
    suspend fun createSource(ctx: PlatformContext): PlatformSource
    suspend fun createTarget(ctx: PlatformContext): PlatformTarget
}
