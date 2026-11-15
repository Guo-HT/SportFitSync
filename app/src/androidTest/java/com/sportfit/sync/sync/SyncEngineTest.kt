package com.sportfit.sync.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sportfit.sync.cache.CacheManager
import com.sportfit.sync.data.local.AppDatabase
import com.sportfit.sync.data.local.CachedActivityEntity
import com.sportfit.sync.data.local.SyncJobEntity
import com.sportfit.sync.data.repo.AccountRepository
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.domain.SyncStatus
import com.sportfit.sync.platform.MfaProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end run of [SyncEngine] against the rehearsal platform.
 *
 * The important property under test is not the arithmetic — it is that the
 * engine survives **platforms logging from their own `Dispatchers.IO`
 * context**. The real clients all do `withContext(Dispatchers.IO) { …; log(…) }`,
 * which lands the emission in an undispatched coroutine and, with a plain
 * `flow {}`, makes the run die with:
 *
 * ```
 * Flow invariant is violated: Flow was collected in [ProducerCoroutine, Dispatchers.IO],
 * but emission happened in [UndispatchedCoroutine, Dispatchers.IO]
 * ```
 *
 * This test exists because the rehearsal platform originally did *not* log, so
 * the bug shipped and only appeared on the first run against real accounts.
 */
@RunWith(AndroidJUnit4::class)
class SyncEngineTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        engine = SyncEngine(
            db = db,
            accounts = AccountRepository(db.accountDao()),
            cache = CacheManager(context, db),
            mfa = MfaProvider { null },
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun run_completes_when_platforms_log_from_their_own_io_context() = runTest {
        val events = engine.run(
            SyncRequest(
                source = Platform.DEMO,
                target = Platform.DEMO,
                selectedIds = setOf("demo-1", "demo-2", "demo-5"),
            ),
        ).toList()

        // demo-1 and demo-2 succeed, demo-5 is the deterministic failure.
        val summary = events.filterIsInstance<SyncEvent.Finished>().single().summary
        assertEquals(3, summary.total)
        assertEquals(2, summary.succeeded)
        assertEquals(0, summary.duplicated)
        assertEquals(1, summary.failed)
    }

    @Test
    fun platform_logs_reach_the_collector() = runTest {
        val events = engine.run(
            SyncRequest(
                source = Platform.DEMO,
                target = Platform.DEMO,
                selectedIds = setOf("demo-1"),
            ),
        ).toList()

        val platformLogs = events.filterIsInstance<SyncEvent.Log>()
            .filter { it.message.contains("演练：") }

        assertTrue(
            "expected log lines emitted from inside the platform's IO context",
            platformLogs.isNotEmpty(),
        )
    }

    @Test
    fun a_run_records_a_finished_job_and_a_ledger() = runTest {
        engine.run(
            SyncRequest(
                source = Platform.DEMO,
                target = Platform.DEMO,
                selectedIds = setOf("demo-1", "demo-5"),
            ),
        ).toList()

        val job = db.syncDao().getJob(1L)
        assertEquals(SyncJobEntity.STATUS_PARTIAL, job?.status)
        assertEquals(1, job?.okCount)
        assertEquals(1, job?.failCount)

        val records = db.syncDao().recordsFor("demo", "demo")
            .associate { it.activityId to SyncStatus.fromCode(it.status) }
        assertEquals(SyncStatus.SYNCED, records["demo-1"])
        assertEquals(SyncStatus.FAILED, records["demo-5"])
    }

    @Test
    fun a_second_run_does_not_upload_the_same_activities_again() = runTest {
        // 第一次：全量同步
        val first = engine.run(SyncRequest(Platform.DEMO, Platform.DEMO)).toList()
        val firstSummary = first.filterIsInstance<SyncEvent.Finished>().single().summary
        assertTrue("第一次应该有活动被同步", firstSummary.total > 0)

        // 第二次：活动仍然在本地库里（增量拉取只是覆盖它们），
        // 但账本已经标成「已同步」，所以一条都不该再传 —— 否则目标端会出现副本。
        // 只有上次失败的那条（演练平台固定让 demo-5 失败）会被再次尝试。
        val second = engine.run(SyncRequest(Platform.DEMO, Platform.DEMO)).toList()
        val processed = second.filterIsInstance<SyncEvent.ActivityFinished>().map { it.activity.id }
        assertEquals(
            "已经同步成功的活动不能被再传一遍",
            listOf("demo-5"),
            processed,
        )
    }

    @Test
    fun synced_activities_store_their_content_fingerprint() = runTest {
        // 账本必须记住「这份数据的指纹」：下次源平台换个 activityId 把同一条
        // 运动记录再发一次时，只有指纹能认出它是同一份数据。
        engine.run(
            SyncRequest(
                source = Platform.DEMO,
                target = Platform.DEMO,
                selectedIds = setOf("demo-1"),
            ),
        ).toList()

        val record = db.syncDao().record("demo", "demo", "demo-1")
        assertEquals(SyncStatus.SYNCED.code, record?.status)
        assertTrue(
            "同步成功的活动必须写入内容指纹",
            !record?.fingerprint.isNullOrEmpty(),
        )
    }

    @Test
    fun the_run_log_reports_the_dedup_breakdown() = runTest {
        // 拉完列表立刻比对去重，并且把各分类的条数报出来 —— 用户要能在
        // 同步日志里看见「哪些被去重了」。
        val events = engine.run(SyncRequest(Platform.DEMO, Platform.DEMO)).toList()

        val breakdown = events.filterIsInstance<SyncEvent.Log>()
            .map { it.message }
            .firstOrNull { it.startsWith("比对去重") }

        assertTrue(
            "同步日志里必须有比对去重的分类明细，实际日志：" +
                events.filterIsInstance<SyncEvent.Log>().map { it.message },
            breakdown != null,
        )
        assertTrue(
            "分类明细里要报出「已同步」的条数：$breakdown",
            breakdown!!.contains("已同步"),
        )
    }

    @Test
    fun a_failed_activity_stays_eligible_for_the_next_run() = runTest {
        engine.run(
            SyncRequest(
                source = Platform.DEMO,
                target = Platform.DEMO,
                selectedIds = setOf("demo-5"),
            ),
        ).toList()

        // No explicit selection this time: an unqualified run must still pick
        // the failed activity up, which the upstream Python project did not do.
        val second = engine.run(SyncRequest(Platform.DEMO, Platform.DEMO)).toList()
        val summary = second.filterIsInstance<SyncEvent.Finished>().single().summary

        // The point of this test is that demo-5 is *attempted again*, not that
        // it is the only candidate: an unqualified run also picks up every
        // activity that was never synced.
        val processed = second.filterIsInstance<SyncEvent.ActivityFinished>().map { it.activity.id }
        assertTrue(
            "失败的活动必须在下一轮被再次尝试",
            processed.contains("demo-5"),
        )
        assertEquals(1, summary.failed)
        assertEquals(
            2,
            db.syncDao().record("demo", "demo", "demo-5")?.attempts,
        )
    }

    /**
     * 「一键同步全部」必须把积压一次推完，不能停在固定条数上。
     *
     * 这条用例钉的是一个真实缺陷：手动同步曾经被「每次最多 N 条」截断，
     * 而那个 N（默认 200）管的是**这一轮从平台补拉多少条新的**，
     * 不是「本地已经存着的活动最多推多少条」。
     *
     * 后果很直观：账号配好后本地已经存了最多 5000 条历史，用户点一次
     * 「一键同步全部」却只走 200 条，剩下的还得按十几次才推得完 ——
     * 按钮写着「全部」，实际只同步了 200 条。
     */
    @Test
    fun sync_all_processes_the_whole_backlog_not_just_two_hundred() = runTest {
        // 模拟「配置账号时历史活动已全量入库」：往本地缓存里塞 250 条。
        val backlog = (1..250).map { index ->
            CachedActivityEntity(
                platform = Platform.DEMO.key,
                activityId = "backlog-$index",
                name = "积压活动 $index",
                // 每条的时间、距离、时长都不同：内容指纹不能把它们判成同一份数据，
                // 否则会被去重掉，测的就不是「条数」了。
                startTime = 1_700_000_000_000L + index * 60_000L,
                distanceMeters = 5_000.0 + index * 137.0,
                durationSec = 1_500L + index * 61L,
                sportType = "running",
            )
        }
        db.activityDao().upsertAll(backlog)

        // Started 事件带的就是本次要处理的条数，在真正开始上传之前就发出来了。
        val started = engine.run(
            SyncRequest(source = Platform.DEMO, target = Platform.DEMO),
        ).first { it is SyncEvent.Started }

        val total = (started as SyncEvent.Started).total
        assertTrue(
            "「一键同步全部」被截断了：只处理了 $total 条，积压有 250 条以上",
            total > 200,
        )
        assertTrue("应把本地缓存里的积压全部纳入本次", total >= backlog.size)
    }

    /**
     * 后台定时同步是无人值守的作业：这一轮必须有界。
     *
     * 手动同步不限，但后台作业不能霸着设备跑几小时 —— 剩下的交给下个周期，
     * 账本记着推到哪里了。
     */
    @Test
    fun the_background_run_stays_within_its_upload_limit() = runTest {
        val backlog = (1..20).map { index ->
            CachedActivityEntity(
                platform = Platform.DEMO.key,
                activityId = "bg-$index",
                name = "后台积压 $index",
                startTime = 1_700_000_000_000L + index * 60_000L,
                distanceMeters = 5_000.0 + index * 137.0,
                durationSec = 1_500L + index * 61L,
                sportType = "running",
            )
        }
        db.activityDao().upsertAll(backlog)

        val started = engine.run(
            SyncRequest(
                source = Platform.DEMO,
                target = Platform.DEMO,
                uploadLimit = 3,
            ),
        ).first { it is SyncEvent.Started }

        assertEquals(3, (started as SyncEvent.Started).total)
    }
}
