package com.sportfit.sync.domain

import com.sportfit.sync.core.net.Hashing

/**
 * A stable fingerprint of an activity's *content*.
 *
 * The sync ledger is keyed by the platform's own activity id, which is not
 * enough to recognise "the same activity" in every situation the user cares
 * about:
 *
 *  - re-importing the same FIT into the source platform produces a **new id**
 *    for the very same ride;
 *  - switching to a second account of the same platform yields ids from a
 *    different namespace;
 *  - some platforms occasionally re-issue an id after a re-sync.
 *
 * In all of those cases the id says "new activity" while the *data* says
 * "already synced". The fingerprint is therefore computed from the content
 * itself — the start time plus the metadata that identifies the session —
 * so identical data is recognised as identical regardless of its id.
 *
 * Rules:
 *
 *  - **Time is mandatory.** Without a start time there is nothing reliable to
 *    compare, so the fingerprint is empty and the caller must fall back to
 *    id-based matching.
 *  - **Only coarse fields are used.** The fingerprint is derived from the
 *    listing metadata (name, distance, duration, sport type) — never from the
 *    FIT bytes. Hashing the payload would mean downloading every activity
 *    before deciding it was already synced, which defeats the purpose.
 *  - **Values are normalised** (trimmed, lower-cased, distance rounded to
 *    whole metres) so that cosmetic differences between platforms do not
 *    produce different fingerprints for the same session.
 *
 * ## Strict vs. loose fingerprint
 *
 * There are two flavours, and they are used for different comparisons:
 *
 *  - [of] — the **strict** fingerprint (start time + sport type + distance +
 *    duration + name). It is used when both sides come from the **same**
 *    platform, where sport type and name are spelled the same way.
 *  - [looseOf] — the **loose** fingerprint (start time + distance + duration
 *    only). It is used for **cross-platform** comparison: Garmin writes the
 *    sport as `"running"`, Coros as `"8"` and Strava as `"run"`, and the same
 *    ride is called "Morning Ride" on one platform and "晨骑" on another, so
 *    the strict fingerprint would never match across platforms. Distance and
 *    duration are the only coarse fields every platform reports.
 *
 * The loose fingerprint requires **distance or duration to be present** (in
 * addition to the start time). With nothing but a start time the match would
 * be based on a single number, and a false positive silently drops a
 * legitimate activity from the sync — worse than not comparing at all. When
 * the data is too thin, the loose fingerprint is [NONE] and the caller falls
 * back to id-based matching.
 */
object ActivityFingerprint {

    /** Empty means "no usable fingerprint" — fall back to id-based matching. */
    const val NONE = ""

    fun of(activity: ActivityMeta): String = of(
        startTime = activity.startTime,
        sportType = activity.sportType,
        distanceMeters = activity.distanceMeters,
        durationSec = activity.durationSec,
        name = activity.name,
    )

    fun of(
        startTime: Long?,
        sportType: String?,
        distanceMeters: Double?,
        durationSec: Long?,
        name: String,
    ): String {
        if (startTime == null) return NONE

        val payload = buildString {
            append(startTime).append('|')
            append(sportType?.trim()?.lowercase().orEmpty()).append('|')
            append(distanceMeters?.let { Math.round(it) }?.toString().orEmpty()).append('|')
            append(durationSec?.toString().orEmpty()).append('|')
            append(name.trim().lowercase())
        }
        return Hashing.sha256Hex(payload)
    }

    /**
     * 跨平台比对用的宽松指纹：只看「开始时间 + 距离(米) + 时长(秒)」。
     *
     * 运动类型和名字在不同平台表示不同，跨平台比较只能用这几个所有平台都会给出的字段。
     *
     * 规则：
     *  - 没有开始时间 → [NONE]（无从比较）；
     *  - 距离和时长同时没有 → [NONE]（数据太少，误判风险高，宁可不比）。
     */
    fun looseOf(activity: ActivityMeta): String = looseOf(
        startTime = activity.startTime,
        distanceMeters = activity.distanceMeters,
        durationSec = activity.durationSec,
    )

    /**
     * 跨平台比对用的宽松指纹：只看「开始时间 + 距离(米) + 时长(秒)」。
     *
     * 运动类型和名字在不同平台表示不同，跨平台比较只能用这几个所有平台都会给出的字段。
     *
     * 规则：
     *  - 没有开始时间 → [NONE]（无从比较）；
     *  - 距离和时长同时没有 → [NONE]（数据太少，误判风险高，宁可不比）；
     *  - 距离用 `Math.round` 取整到米，时长用秒。
     */
    fun looseOf(startTime: Long?, distanceMeters: Double?, durationSec: Long?): String {
        if (startTime == null) return NONE
        if (distanceMeters == null && durationSec == null) return NONE

        val payload = buildString {
            append(startTime).append('|')
            append(distanceMeters?.let { Math.round(it) }?.toString().orEmpty()).append('|')
            append(durationSec?.toString().orEmpty())
        }
        return Hashing.sha256Hex(payload)
    }
}
