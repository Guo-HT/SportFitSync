package com.sportfit.sync.domain

/**
 * A fitness platform. `canBeTarget` mirrors the upstream Python project:
 * only Garmin, Coros and Strava accept uploads — iGPSPORT and OneLap are
 * export-only sources.
 */
enum class Platform(
    val key: String,
    val displayName: String,
    val canBeSource: Boolean,
    val canBeTarget: Boolean,
) {
    GARMIN("garmin", "Garmin", canBeSource = true, canBeTarget = true),
    COROS("coros", "Coros", canBeSource = true, canBeTarget = true),
    STRAVA("strava", "Strava", canBeSource = true, canBeTarget = true),
    IGPSPORT("igpsport", "iGPSPORT", canBeSource = true, canBeTarget = false),
    ONELAP("onelap", "OneLap", canBeSource = true, canBeTarget = false),

    /**
     * Debug-only rehearsal platform: produces synthetic activities and never
     * touches the network, so the full pipeline can be exercised on a device
     * without real credentials. Hidden from release builds in the UI.
     */
    DEMO("demo", "演练模式", canBeSource = true, canBeTarget = true),

    /**
     * Not a platform: the pseudo-target recorded when activities are exported
     * as files. Neither a source nor a target, so it never appears in the
     * direction pickers — it only labels history entries.
     */
    LOCAL_FILES("local-files", "本地文件", canBeSource = false, canBeTarget = false),
    ;

    companion object {
        fun fromKey(key: String?): Platform? = entries.firstOrNull { it.key == key }

        fun requireFromKey(key: String?): Platform =
            fromKey(key) ?: error("Unknown platform: $key")

        val sources: List<Platform> get() = entries.filter { it.canBeSource }
        val targets: List<Platform> get() = entries.filter { it.canBeTarget }

        /**
         * Platforms the direction picker may offer. Excludes the pseudo
         * `LOCAL_FILES` target (it is not a platform) and, in release builds,
         * the rehearsal platform.
         */
        fun pickable(includeDemo: Boolean): List<Platform> =
            entries.filter { (it.canBeSource || it.canBeTarget) && (includeDemo || it != DEMO) }

        /** Platforms the user can bind credentials for. */
        val accountPlatforms: List<Platform>
            get() = entries.filter { it != DEMO && it != LOCAL_FILES }
    }
}

/** One directed sync relationship, e.g. `garmin → coros`. */
data class SyncDirection(val source: Platform, val target: Platform) {
    val key: String get() = "${source.key}->${target.key}"
    val label: String get() = "${source.displayName} → ${target.displayName}"

    companion object {
        fun fromKey(key: String): SyncDirection? {
            val (src, dst) = key.split("->").takeIf { it.size == 2 } ?: return null
            val source = Platform.fromKey(src) ?: return null
            val target = Platform.fromKey(dst) ?: return null
            return SyncDirection(source, target)
        }
    }
}

/** Uniform activity metadata, mapped from each platform's own shape. */
data class ActivityMeta(
    val id: String,
    val name: String,
    val startTime: Long?,
    val distanceMeters: Double?,
    val durationSec: Long?,
    val sportType: String? = null,
)

/** Per-direction sync state, replacing the upstream `is_sync_*` columns. */
enum class SyncStatus(val code: Int) {
    UNSYNCED(0),
    SYNCED(1),
    FAILED(2),
    ;

    companion object {
        fun fromCode(code: Int): SyncStatus = entries.firstOrNull { it.code == code } ?: UNSYNCED
    }
}

sealed interface UploadOutcome {
    /** The target accepted the file. */
    data object Success : UploadOutcome

    /** The target already has this activity — treated as a success everywhere. */
    data object Duplicate : UploadOutcome

    /** Recoverable failure; the message is surfaced in the UI and the log. */
    data class Failed(val reason: String) : UploadOutcome

    val isSuccess: Boolean get() = this is Success || this is Duplicate
}

/** Reasons a sync run can stop early. */
sealed class SyncAbort(message: String) : Exception(message) {
    class MissingAccount(val platform: Platform) :
        SyncAbort("未配置 ${platform.displayName} 账号")

    class Authentication(val platform: Platform, detail: String?) :
        SyncAbort("${platform.displayName} 登录失效${detail?.let { "：$it" } ?: ""}")
}
