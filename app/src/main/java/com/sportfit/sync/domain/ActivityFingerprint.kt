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
}
