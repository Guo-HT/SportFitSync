package com.sportfit.sync.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 2 → 3 migration must not cost the user their accounts.
 *
 * The database holds more than a cache: `accounts` carries the encrypted
 * credentials, and `sync_records` carries the de-duplication ledger. Both are
 * irreplaceable from the user's point of view — losing the credentials means
 * typing the password again, losing the ledger means every activity looks
 * unsynced and gets uploaded a second time.
 *
 * That is exactly why the schema bump adds a *non-destructive* migration
 * instead of relying on `fallbackToDestructiveMigration`. This test pins that
 * promise: after the upgrade the account row is still there, the old ledger
 * row is still there, and the new column exists with an empty fingerprint
 * (meaning "no fingerprint known", so the id-based matching takes over).
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_2_to_3_keeps_accounts_and_ledger() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                """
                INSERT INTO accounts (platform, displayName, status, createdAt, updatedAt)
                VALUES ('coros', 'Coros', 'CONNECTED', 1, 1)
                """,
            )
            execSQL(
                """
                INSERT INTO sync_records
                    (sourcePlatform, targetPlatform, activityId, status, attempts, syncedAt, updatedAt)
                VALUES ('garmin', 'coros', '481335', 1, 1, 2, 2)
                """,
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3)

        db.query("SELECT platform, displayName FROM accounts").use { cursor ->
            assertTrue("账号行必须在迁移后保留", cursor.moveToFirst())
            assertEquals("coros", cursor.getString(0))
            assertEquals("Coros", cursor.getString(1))
        }

        db.query(
            "SELECT activityId, status, fingerprint FROM sync_records WHERE activityId = '481335'",
        ).use { cursor ->
            assertTrue("去重记录必须在迁移后保留", cursor.moveToFirst())
            assertEquals("481335", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            // 历史记录没有指纹：空串表示「未知」，去重退回按 activityId 判断。
            assertEquals("", cursor.getString(2))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
