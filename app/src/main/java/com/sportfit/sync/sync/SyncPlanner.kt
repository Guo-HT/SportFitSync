package com.sportfit.sync.sync

import com.sportfit.sync.domain.ActivityFingerprint
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.SyncStatus

/**
 * Decides which activities a run should attempt.
 *
 * Extracted from [SyncEngine] as a pure function because this is the part of
 * the pipeline where the upstream Python project had its worst bug: its query
 * was `WHERE is_sync_x = 0`, so anything that failed (status 2) was never
 * selected again and the only fix was editing SQLite by hand.
 *
 * The rules here are:
 *  - an explicit selection wins, but exclusions still apply;
 *  - otherwise every activity that is not already synced is eligible, which
 *    includes previously failed ones (they are retried automatically);
 *  - already-synced activities are included only when the caller asks for a
 *    deliberate re-sync.
 *
 * ## Why a content fingerprint
 *
 * The ledger is keyed by the *source platform's own activity id*, but that id
 * is not a stable name for "this activity". The very same ride can come back
 * under a brand new id:
 *
 *  - re-importing the same FIT file into the source platform creates a **new
 *    activity** (new id) with identical content;
 *  - switching to a second account of the *same* platform yields ids from a
 *    different namespace — so `activityId` differs even though the session is
 *    the same one;
 *  - some platforms occasionally **re-issue an id** after their own re-sync.
 *
 * In all of those cases the id says "new activity" while the data says
 * "already synced", and syncing it again produces a duplicate on the target.
 * [ActivityFingerprint] hashes the activity *content* (start time plus the
 * coarse metadata), so identical data is recognised as identical regardless of
 * its id. The caller passes the fingerprints already synced in this direction
 * as [selectCandidates]'s `syncedFingerprints` argument.
 *
 * Notes on the trade-offs:
 *
 *  - **Time is mandatory for a fingerprint.** An activity without a start
 *    time has no usable fingerprint ([ActivityFingerprint.NONE]) and falls
 *    back to plain id-based matching.
 *  - **`includeAlreadySynced` wins over the fingerprint rule.** That flag
 *    means "the user explicitly asked to sync these again", so it must not be
 *    silently narrowed. Without the flag, an activity whose fingerprint is
 *    already synced is treated as *already synced* and is not a candidate.
 *  - **An explicit selection does *not* win over the fingerprint rule.** If the
 *    user taps an activity whose content is byte-for-byte the same as one
 *    that was already synced in this direction, re-uploading it would only
 *    create a duplicate on the target, so it is dropped even though the user
 *    named it. This is a deliberate trade-off: the ledger's promise is "one
 *    activity per target", and the alternative — silently producing copies —
 *    is worse. Users who really want a second copy can use
 *    `includeAlreadySynced`, which bypasses the rule entirely.
 *
 * This object stays a pure function: it takes its inputs as arguments and
 * touches no database, no Android API and no clock.
 */
object SyncPlanner {

    /**
     * @param syncedFingerprints fingerprints of the activities that were
     *   already synced in this direction. An activity whose fingerprint is in
     *   this set is considered *already synced* and is not selected again
     *   (unless [includeAlreadySynced] is `true`).
     */
    fun selectCandidates(
        activities: List<ActivityMeta>,
        statusById: Map<String, SyncStatus>,
        excludedIds: Set<String>,
        selectedIds: Set<String>? = null,
        includeAlreadySynced: Boolean = false,
        syncedFingerprints: Set<String> = emptySet(),
    ): List<ActivityMeta> = activities
        .distinctBy { it.id }
        .filter { activity ->
            if (activity.id in excludedIds) return@filter false

            // 内容指纹命中 = 同样的数据在这个方向上已经同步过。
            // includeAlreadySynced 是用户明确要求「重新同步已同步项」，必须尊重，
            // 所以这条规则在它为 true 时不生效。
            if (!includeAlreadySynced && isAlreadySyncedByFingerprint(activity, syncedFingerprints)) {
                return@filter false
            }

            if (selectedIds != null) {
                return@filter activity.id in selectedIds
            }

            val status = statusById[activity.id] ?: SyncStatus.UNSYNCED
            when (status) {
                SyncStatus.SYNCED -> includeAlreadySynced
                // Unsynced and Failed are both eligible: failed runs retry.
                SyncStatus.UNSYNCED, SyncStatus.FAILED -> true
            }
        }

    /** 空指纹（没有开始时间）不参与指纹判定，回退到按 id 判断。 */
    private fun isAlreadySyncedByFingerprint(
        activity: ActivityMeta,
        syncedFingerprints: Set<String>,
    ): Boolean {
        if (syncedFingerprints.isEmpty()) return false

        val fingerprint = ActivityFingerprint.of(activity)
        if (fingerprint == ActivityFingerprint.NONE) return false

        return fingerprint in syncedFingerprints
    }

    /**
     * Caps how many activities one run may process.
     *
     * The activity list is ordered newest first, so the cap keeps the most
     * recent ones — the same ones the old "fetch the newest N" behaviour
     * would have offered.
     *
     * An explicit selection is never narrowed: the user named those
     * activities, so the run does exactly that even when the batch is larger
     * than the usual limit.
     */
    fun applyLimit(
        candidates: List<ActivityMeta>,
        limit: Int,
        explicitSelection: Boolean,
    ): List<ActivityMeta> = when {
        explicitSelection -> candidates
        limit <= 0 -> candidates
        else -> candidates.take(limit)
    }
}
