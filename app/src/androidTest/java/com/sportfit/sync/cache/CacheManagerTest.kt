package com.sportfit.sync.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sportfit.sync.data.local.AccountEntity
import com.sportfit.sync.data.local.AppDatabase
import com.sportfit.sync.data.local.CachedActivityEntity
import com.sportfit.sync.data.local.SyncJobEntity
import com.sportfit.sync.data.local.SyncLogEntity
import com.sportfit.sync.data.local.SyncRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Verifies the cache requirements against the real Room schema on a device:
 * the 7-day activity retention, the 30-day log retention, and the rule that
 * the de-duplication ledger is never cleared without an explicit confirmation.
 */
@RunWith(AndroidJUnit4::class)
class CacheManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private var policy = CachePolicy()
    private lateinit var cache: CacheManager

    private val now = System.currentTimeMillis()
    private fun daysAgo(days: Long) = now - TimeUnit.DAYS.toMillis(days)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        policy = CachePolicy()
        cache = CacheManager(context, db) { policy }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedActivities() {
        db.activityDao().upsertAll(
            listOf(
                CachedActivityEntity(
                    platform = "garmin", activityId = "fresh", name = "fresh",
                    fetchedAt = daysAgo(1),
                ),
                CachedActivityEntity(
                    platform = "garmin", activityId = "stale", name = "stale",
                    fetchedAt = daysAgo(8),
                ),
            ),
        )
    }

    private suspend fun seedLogs() {
        val jobId = db.syncDao().insertJob(
            SyncJobEntity(sourcePlatform = "garmin", targetPlatform = "coros"),
        )
        db.syncDao().insertLogs(
            listOf(
                SyncLogEntity(jobId = jobId, ts = daysAgo(1), message = "recent"),
                SyncLogEntity(jobId = jobId, ts = daysAgo(31), message = "old"),
            ),
        )
    }

    @Test
    fun retention_keeps_recent_activities_and_drops_ones_past_seven_days() = runTest {
        seedActivities()

        cache.applyRetention()

        val remaining = db.activityDao().listFor("garmin")
        assertEquals(listOf("fresh"), remaining.map { it.activityId })
    }

    @Test
    fun retention_never_drops_the_listing_of_a_configured_account() = runTest {
        // 配置中的账号，它的活动列表是「增量同步的本地历史」：
        // 按时间清掉会让还没同步过的老活动永远失去被同步的机会。
        db.accountDao().upsert(
            AccountEntity(platform = "garmin", displayName = "Garmin"),
        )
        seedActivities()

        cache.applyRetention()

        assertEquals(
            "配置中的账号，其活动列表必须完整保留",
            2,
            db.activityDao().countFor("garmin"),
        )
    }

    @Test
    fun retention_drops_the_listing_of_a_removed_account() = runTest {
        // 账号已经删掉 → 它的列表没有主人了，按保留天数清理。
        db.accountDao().upsert(
            AccountEntity(platform = "coros", displayName = "Coros"),
        )
        seedActivities()

        cache.applyRetention()

        val remaining = db.activityDao().listFor("garmin")
        assertEquals(listOf("fresh"), remaining.map { it.activityId })
    }

    @Test
    fun retention_keeps_recent_logs_and_drops_ones_past_thirty_days() = runTest {
        seedLogs()

        cache.applyRetention()

        assertEquals(1, db.syncDao().logCount())
    }

    @Test
    fun retention_honours_a_changed_policy() = runTest {
        seedActivities()
        // Nothing is old enough once the window is widened.
        policy = policy.copy(activityRetentionDays = 365)

        cache.applyRetention()

        assertEquals(2, db.activityDao().count())
    }

    @Test
    fun retention_prunes_job_history_to_the_configured_limit() = runTest {
        repeat(5) {
            db.syncDao().insertJob(
                SyncJobEntity(
                    sourcePlatform = "garmin",
                    targetPlatform = "coros",
                    startedAt = now - it * 1_000L,
                ),
            )
        }
        policy = policy.copy(jobHistoryLimit = 2)

        cache.applyRetention()

        assertEquals(2, db.syncDao().jobCount())
    }

    @Test
    fun clearing_the_ledger_requires_explicit_confirmation() = runTest {
        db.syncDao().upsertRecord(
            source = "garmin", target = "coros", activityId = "1",
            status = 1, attempts = 1, error = null, syncedAt = now,
        )

        // Without `force` the dangerous category must be a no-op.
        assertFalse(cache.clear(CacheManager.KEY_SYNC_RECORDS))
        assertEquals(1, db.syncDao().totalRecordCount())

        assertTrue(cache.clear(CacheManager.KEY_SYNC_RECORDS, force = true))
        assertEquals(0, db.syncDao().totalRecordCount())
    }

    @Test
    fun clearing_all_safe_categories_leaves_the_ledger_intact() = runTest {
        seedActivities()
        seedLogs()
        db.syncDao().upsertRecord(
            source = "garmin", target = "coros", activityId = "1",
            status = 1, attempts = 1, error = null, syncedAt = now,
        )

        cache.clearAllSafe()

        assertEquals(0, db.activityDao().count())
        assertEquals(0, db.syncDao().logCount())
        assertEquals(0, db.syncDao().jobCount())
        // The whole point: the ledger survives, so nothing is re-uploaded.
        assertEquals(1, db.syncDao().totalRecordCount())
    }

    @Test
    fun snapshot_reports_the_ledger_as_dangerous() = runTest {
        db.syncDao().upsertRecord(
            source = "garmin", target = "coros", activityId = "1",
            status = 2, attempts = 3, error = "boom", syncedAt = null,
        )

        val snapshot = cache.snapshot()

        val ledger = snapshot.categories.single { it.key == CacheManager.KEY_SYNC_RECORDS }
        assertTrue("ledger must be flagged dangerous", ledger.dangerous)
        assertEquals(1, ledger.itemCount)
        assertTrue(snapshot.safeCategories.none { it.key == CacheManager.KEY_SYNC_RECORDS })
    }

    @Test
    fun job_temp_directories_are_removed_when_nothing_failed() = runTest {
        val dir = cache.jobTempDir(7L)
        java.io.File(dir, "payload.fit").writeBytes(ByteArray(16))
        assertTrue(dir.exists())

        cache.finalizeJobTemp(7L, keepEvidence = true)
        assertTrue("failed runs keep their payloads", dir.exists())

        cache.finalizeJobTemp(7L, keepEvidence = false)
        assertFalse("clean runs release the directory", dir.exists())
    }
}
