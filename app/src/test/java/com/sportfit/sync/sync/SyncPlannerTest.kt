package com.sportfit.sync.sync

import com.sportfit.sync.domain.ActivityFingerprint
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.SyncStatus
import org.junit.Assert.assertEquals
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
