package com.sportfit.sync.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sportfit.sync.cache.CacheManager
import com.sportfit.sync.data.local.AppDatabase
import com.sportfit.sync.data.local.SyncJobEntity
import com.sportfit.sync.data.repo.AccountRepository
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.domain.SyncStatus
import com.sportfit.sync.platform.MfaProvider
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
}
