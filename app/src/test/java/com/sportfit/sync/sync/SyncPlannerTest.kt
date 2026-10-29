package com.sportfit.sync.sync

import com.sportfit.sync.domain.ActivityFingerprint
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {

    private fun activity(id: String) = ActivityMeta(
        id = id,
        name = "activity $id",
        startTime = null,
        distanceMeters = null,
        durationSec = null,
    )

    private val activities = listOf(activity("1"), activity("2"), activity("3"), activity("4"))

    @Test
    fun `selects everything that is not yet synced`() {
        val selected = SyncPlanner.selectCandidates(
            activities = activities,
            statusById = mapOf("1" to SyncStatus.SYNCED, "2" to SyncStatus.UNSYNCED),
            excludedIds = emptySet(),
        )
        assertEquals(listOf("2", "3", "4"), selected.map { it.id })
    }

    @Test
    fun `previously failed activities are retried automatically`() {
        // The upstream project left status=2 out of its query, so these were
        // never attempted again. That is the regression this guards.
        val selected = SyncPlanner.selectCandidates(
            activities = activities,
            statusById = mapOf("1" to SyncStatus.FAILED, "2" to SyncStatus.SYNCED),
            excludedIds = emptySet(),
        )
        assertEquals(listOf("1", "3", "4"), selected.map { it.id })
    }

    @Test
    fun `exclusions are honoured even for an explicit selection`() {
        val selected = SyncPlanner.selectCandidates(
            activities = activities,
            statusById = emptyMap(),
            excludedIds = setOf("2", "4"),
            selectedIds = setOf("2", "3", "4"),
        )
        assertEquals(listOf("3"), selected.map { it.id })
    }

    @Test
    fun `an explicit selection ignores previous sync state`() {
        val selected = SyncPlanner.selectCandidates(
            activities = activities,
            statusById = mapOf("1" to SyncStatus.SYNCED),
            excludedIds = emptySet(),
            selectedIds = setOf("1"),
        )
        assertEquals(listOf("1"), selected.map { it.id })
    }

    @Test
    fun `includeAlreadySynced forces a full re-sync`() {
        val selected = SyncPlanner.selectCandidates(
            activities = activities,
            statusById = activities.associate { it.id to SyncStatus.SYNCED },
            excludedIds = emptySet(),
            includeAlreadySynced = true,
        )
        assertEquals(4, selected.size)
    }

    @Test
    fun `duplicate ids from paging are collapsed`() {
        val duplicated = activities + activities
        val selected = SyncPlanner.selectCandidates(
            activities = duplicated,
            statusById = emptyMap(),
            excludedIds = emptySet(),
        )
        assertEquals(listOf("1", "2", "3", "4"), selected.map { it.id })
    }

    @Test
    fun `an explicit selection of nothing selects nothing`() {
        val selected = SyncPlanner.selectCandidates(
            activities = activities,
            statusById = emptyMap(),
            excludedIds = emptySet(),
            selectedIds = emptySet(),
        )
        assertEquals(emptyList<String>(), selected.map { it.id })
    }

    // ---- 内容指纹去重 ----------------------------------------------------

    /** 有开始时间的活动：只有这类活动才有可用的内容指纹。 */
    private fun timedActivity(
        id: String,
        startTime: Long,
        name: String = "activity",
        distanceMeters: Double = 10_000.0,
        durationSec: Long = 1800L,
    ) = ActivityMeta(
        id = id,
        name = name,
        startTime = startTime,
        distanceMeters = distanceMeters,
        durationSec = durationSec,
    )

    @Test
    fun `指纹命中的活动不再作为候选`() {
        // 同样的数据已经在这个方向上同步过，就不要再传一遍制造副本。
        val first = timedActivity("1", 1_000L)
        val second = timedActivity("2", 2_000L)

        val selected = SyncPlanner.selectCandidates(
            activities = listOf(first, second),
            statusById = emptyMap(),
            excludedIds = emptySet(),
            syncedFingerprints = setOf(ActivityFingerprint.of(first)),
        )
        assertEquals(listOf("2"), selected.map { it.id })
    }

    @Test
    fun `换了 id 的同一份数据也会被指纹拦下`() {
        // 源平台重新导入 FIT 会给出新的 activityId，但内容没变 —— 必须跳过。
        val original = timedActivity("1", 1_000L)
        val reimported = timedActivity("999", 1_000L)
        assertEquals(ActivityFingerprint.of(original), ActivityFingerprint.of(reimported))

        val selected = SyncPlanner.selectCandidates(
            activities = listOf(reimported),
            statusById = emptyMap(),
            excludedIds = emptySet(),
            syncedFingerprints = setOf(ActivityFingerprint.of(original)),
        )
        assertEquals(emptyList<String>(), selected.map { it.id })
    }

    @Test
    fun `includeAlreadySynced 为真时不套用指纹规则`() {
        // 用户明确要求「重新同步已同步项」，必须尊重，不能被指纹悄悄拦下。
        val first = timedActivity("1", 1_000L)
        val second = timedActivity("2", 2_000L)

        val selected = SyncPlanner.selectCandidates(
            activities = listOf(first, second),
            statusById = emptyMap(),
            excludedIds = emptySet(),
            includeAlreadySynced = true,
            syncedFingerprints = setOf(ActivityFingerprint.of(first)),
        )
        assertEquals(listOf("1", "2"), selected.map { it.id })
    }

    @Test
    fun `没有开始时间的活动不参与指纹判定`() {
        // 指纹为空串 = 没有可用指纹，回退到按 id 判断，不能被误伤。
        val withoutStartTime = activity("3")
        assertEquals(ActivityFingerprint.NONE, ActivityFingerprint.of(withoutStartTime))

        val selected = SyncPlanner.selectCandidates(
            activities = listOf(withoutStartTime),
            statusById = emptyMap(),
            excludedIds = emptySet(),
            syncedFingerprints = setOf("some-fingerprint"),
        )
        assertEquals(listOf("3"), selected.map { it.id })
    }

    @Test
    fun `显式选择也会套用指纹规则`() {
        // 取舍：用户点名的活动如果内容完全重复，再传一遍只会产生副本，
        // 所以照样跳过；真要重复同步请用 includeAlreadySynced。
        val first = timedActivity("1", 1_000L)
        val second = timedActivity("2", 2_000L)

        val selected = SyncPlanner.selectCandidates(
            activities = listOf(first, second),
            statusById = emptyMap(),
            excludedIds = emptySet(),
            selectedIds = setOf("1", "2"),
            syncedFingerprints = setOf(ActivityFingerprint.of(first)),
        )
        assertEquals(listOf("2"), selected.map { it.id })
    }

    // ---- 分类明细（plan） ------------------------------------------------

    @Test
    fun `按 id 命中的已同步活动进 alreadySynced`() {
        val plan = SyncPlanner.plan(
            activities = activities,
            statusById = mapOf("1" to SyncStatus.SYNCED, "2" to SyncStatus.UNSYNCED),
            excludedIds = emptySet(),
        )
        assertEquals(listOf("1"), plan.alreadySynced.map { it.id })
        assertEquals(listOf("2", "3", "4"), plan.candidates.map { it.id })
        // 已同步的活动不会再出现在候选集里。
        assertTrue(plan.alreadySynced.none { it in plan.candidates })
    }

    @Test
    fun `严格指纹命中的活动进 duplicatesByContent`() {
        val first = timedActivity("1", 1_000L)
        val second = timedActivity("2", 2_000L)

        val plan = SyncPlanner.plan(
            activities = listOf(first, second),
            statusById = emptyMap(),
            excludedIds = emptySet(),
            syncedFingerprints = setOf(ActivityFingerprint.of(first)),
        )
        assertEquals(listOf("1"), plan.duplicatesByContent.map { it.id })
        assertEquals(listOf("2"), plan.candidates.map { it.id })
        assertTrue(plan.alreadySynced.isEmpty())
        assertTrue(plan.duplicatesOnTarget.isEmpty())
    }

    @Test
    fun `宽松指纹命中的活动进 duplicatesOnTarget`() {
        // 目标端已经有同样的一条了，再传一遍就多一份副本。
        val first = timedActivity("1", 1_000L)
        val second = timedActivity("2", 2_000L)

        val plan = SyncPlanner.plan(
            activities = listOf(first, second),
            statusById = emptyMap(),
            excludedIds = emptySet(),
            targetLooseFingerprints = setOf(ActivityFingerprint.looseOf(first)),
        )
        assertEquals(listOf("1"), plan.duplicatesOnTarget.map { it.id })
        assertEquals(listOf("2"), plan.candidates.map { it.id })
        assertTrue(plan.duplicatesByContent.isEmpty())
    }

    @Test
    fun `includeAlreadySynced 为真时两条指纹规则都不生效`() {
        // 用户明确要求「重新同步已同步项」，严格指纹和宽松指纹都不能拦下它。
        val first = timedActivity("1", 1_000L)
        val second = timedActivity("2", 2_000L)

        val plan = SyncPlanner.plan(
            activities = listOf(first, second),
            statusById = emptyMap(),
            excludedIds = emptySet(),
            includeAlreadySynced = true,
            syncedFingerprints = setOf(ActivityFingerprint.of(first)),
            targetLooseFingerprints = setOf(ActivityFingerprint.looseOf(first)),
        )
        assertEquals(listOf("1", "2"), plan.candidates.map { it.id })
        assertTrue(plan.duplicatesByContent.isEmpty())
        assertTrue(plan.duplicatesOnTarget.isEmpty())
    }

    @Test
    fun `显式勾选也绕不过指纹规则`() {
        // 取舍：用户点名的活动如果内容完全重复，再传一遍只会产生副本，
        // 所以照样归入 duplicatesByContent；真要重复同步请用 includeAlreadySynced。
        val first = timedActivity("1", 1_000L)
        val second = timedActivity("2", 2_000L)

        val plan = SyncPlanner.plan(
            activities = listOf(first, second),
            statusById = emptyMap(),
            excludedIds = emptySet(),
            selectedIds = setOf("1", "2"),
            syncedFingerprints = setOf(ActivityFingerprint.of(first)),
        )
        assertEquals(listOf("1"), plan.duplicatesByContent.map { it.id })
        assertEquals(listOf("2"), plan.candidates.map { it.id })
    }

    @Test
    fun `被排除的活动进 excluded 且不参与其他分类`() {
        val plan = SyncPlanner.plan(
            activities = activities,
            statusById = mapOf("2" to SyncStatus.SYNCED),
            excludedIds = setOf("2"),
            selectedIds = setOf("2", "3"),
        )
        assertEquals(listOf("2"), plan.excluded.map { it.id })
        assertEquals(listOf("3"), plan.candidates.map { it.id })
        assertTrue(plan.alreadySynced.isEmpty())
        assertTrue(plan.duplicatesByContent.isEmpty())
        assertTrue(plan.duplicatesOnTarget.isEmpty())
    }

    @Test
    fun `没有开始时间的活动不参与任何指纹判定`() {
        // 指纹为空串 = 没有可用指纹，回退到按 id 判断，不能被误伤。
        val withoutStartTime = activity("3")
        assertEquals(ActivityFingerprint.NONE, ActivityFingerprint.of(withoutStartTime))

        val plan = SyncPlanner.plan(
            activities = listOf(withoutStartTime),
            statusById = emptyMap(),
            excludedIds = emptySet(),
            syncedFingerprints = setOf(ActivityFingerprint.NONE),
            targetLooseFingerprints = setOf(ActivityFingerprint.NONE),
        )
        assertEquals(listOf("3"), plan.candidates.map { it.id })
        assertTrue(plan.duplicatesByContent.isEmpty())
        assertTrue(plan.duplicatesOnTarget.isEmpty())
    }

    @Test
    fun `plan 的候选集与旧的 selectCandidates 一致`() {
        // 同一个场景，两种调用方式必须给出同样的候选集。
        val first = timedActivity("1", 1_000L)
        val mixed = listOf(first, activity("2"), timedActivity("3", 3_000L), activity("4"))
        val statusById = mapOf("1" to SyncStatus.SYNCED, "3" to SyncStatus.FAILED)
        val syncedFingerprints = setOf(ActivityFingerprint.of(timedActivity("9", 9_000L)))

        val plan = SyncPlanner.plan(
            activities = mixed,
            statusById = statusById,
            excludedIds = setOf("4"),
            syncedFingerprints = syncedFingerprints,
        )
        val selected = SyncPlanner.selectCandidates(
            activities = mixed,
            statusById = statusById,
            excludedIds = setOf("4"),
            syncedFingerprints = syncedFingerprints,
        )

        assertEquals(listOf("2", "3"), plan.candidates.map { it.id })
        assertEquals(selected.map { it.id }, plan.candidates.map { it.id })
    }

    @Test
    fun `plan 的分类明细互不重叠`() {
        // 每条活动只落在一个分类里，方便界面上直接报数。
        val first = timedActivity("1", 1_000L)
        val mixed = listOf(first, activity("2"), timedActivity("3", 3_000L), activity("4"))

        val plan = SyncPlanner.plan(
            activities = mixed,
            statusById = mapOf("1" to SyncStatus.SYNCED, "3" to SyncStatus.FAILED),
            excludedIds = setOf("4"),
            syncedFingerprints = setOf(ActivityFingerprint.of(first)),
        )

        val all = plan.candidates + plan.alreadySynced + plan.duplicatesByContent +
            plan.duplicatesOnTarget + plan.excluded
        assertEquals(4, all.size)
        assertEquals(4, all.map { it.id }.distinct().size)
        // "1" 的 id 本身就同步过 → 记「已同步」；它的指纹也命中，但那只是
        // 因为指纹就是它自己的，两件事要分开报。
        assertEquals(listOf("1"), plan.alreadySynced.map { it.id })
        assertEquals(listOf("2", "3"), plan.candidates.map { it.id })
        assertEquals(listOf("4"), plan.excluded.map { it.id })
    }

    @Test
    fun `已同步和内容相同是两回事`() {
        // 「已同步」：这个 id 我们同步过（最常见的情况）。
        // 「内容相同」：id 是新的，但数据和已同步的完全一样 —— 这才是真正的重复。
        // 判定顺序必须先看 id 再看指纹，否则「内容相同」会吞掉所有已同步的活动，
        // 报给用户的数字就没有意义了。
        val original = timedActivity("1", 1_000L)
        val reimported = timedActivity("2", 1_000L) // 同样的数据，换了个 id
        assertEquals(ActivityFingerprint.of(original), ActivityFingerprint.of(reimported))

        val plan = SyncPlanner.plan(
            activities = listOf(original, reimported),
            statusById = mapOf("1" to SyncStatus.SYNCED),
            excludedIds = emptySet(),
            syncedFingerprints = setOf(ActivityFingerprint.of(original)),
        )

        assertEquals(listOf("1"), plan.alreadySynced.map { it.id })
        assertEquals(listOf("2"), plan.duplicatesByContent.map { it.id })
        assertTrue(plan.candidates.isEmpty())
    }

    // ---- 单次运行的处理量上限 --------------------------------------------

    @Test
    fun `默认只处理最新的一批活动`() {
        // 活动按时间倒序排列，上限保留的就是最新的那批 —— 和旧版
        // 「只拉最新 N 条」的行为一致。
        val limited = SyncPlanner.applyLimit(
            candidates = activities,
            limit = 2,
            explicitSelection = false,
        )
        assertEquals(listOf("1", "2"), limited.map { it.id })
    }

    @Test
    fun `显式勾选的活动不受上限限制`() {
        // 用户点名了哪几条，就跑哪几条，不能被「每次最多 N 条」悄悄砍掉。
        val limited = SyncPlanner.applyLimit(
            candidates = activities,
            limit = 1,
            explicitSelection = true,
        )
        assertEquals(listOf("1", "2", "3", "4"), limited.map { it.id })
    }

    @Test
    fun `上限为 0 表示不限制`() {
        val limited = SyncPlanner.applyLimit(
            candidates = activities,
            limit = 0,
            explicitSelection = false,
        )
        assertEquals(4, limited.size)
    }
}
