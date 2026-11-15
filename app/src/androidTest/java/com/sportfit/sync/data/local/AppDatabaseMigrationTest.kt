package com.sportfit.sync.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * The 3 → 4 migration moves a China-region Garmin account to its own key.
     *
     * Before v4 a Garmin account was one row whose `region` column picked the
     * site. v4 turns the site into the platform itself (`garmin` = 国际区,
     * `garmin-cn` = 中国区), so the old China row has to be relabelled —
     * otherwise the user's China credentials would be shown as an
     * international account and the login would hit garmin.com.
     *
     * The ciphertexts are only carried over, never rewritten, so the account
     * keeps working without the user touching the password.
     */
    @Test
    fun migrate_3_to_4_moves_a_china_garmin_account_to_its_own_key() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                INSERT INTO accounts
                    (platform, displayName, emailCipher, region, secretCipher, tokenCipher,
                     status, createdAt, updatedAt)
                VALUES ('garmin', 'Garmin', 'enc-email', 'CN', 'enc-secret', 'enc-token',
                        'CONNECTED', 1, 1)
                """,
            )
            execSQL(
                """
                INSERT INTO cached_activities
                    (platform, activityId, name, startTime, distanceMeters, durationSec,
                     sportType, fetchedAt)
                VALUES ('garmin', '481335', '晨跑', 1000, 5000.0, 600, 'running', 1)
                """,
            )
            execSQL(
                """
                INSERT INTO sync_records
                    (sourcePlatform, targetPlatform, activityId, status, attempts, syncedAt,
                     fingerprint, updatedAt)
                VALUES ('garmin', 'coros', '481335', 1, 1, 2, 'fp-1', 2)
                """,
            )
            execSQL(
                """
                INSERT INTO exclusions (sourcePlatform, targetPlatform, activityId, createdAt)
                VALUES ('garmin', 'strava', '481335', 1)
                """,
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4)

        // 账号搬到新 key，凭据一个字节都没动。
        db.query(
            "SELECT platform, emailCipher, region, secretCipher, tokenCipher FROM accounts",
        ).use { cursor ->
            assertTrue("账号行必须在迁移后保留", cursor.moveToFirst())
            assertEquals("garmin-cn", cursor.getString(0))
            assertEquals("enc-email", cursor.getString(1))
            assertEquals("CN", cursor.getString(2))
            assertEquals("enc-secret", cursor.getString(3))
            assertEquals("enc-token", cursor.getString(4))
            assertFalse("迁移后不应再有旧的 garmin 账号行", cursor.moveToNext())
        }

        // 活动缓存跟着账号走。
        db.query("SELECT platform, activityId FROM cached_activities").use { cursor ->
            assertTrue("活动缓存必须在迁移后保留", cursor.moveToFirst())
            assertEquals("garmin-cn", cursor.getString(0))
            assertEquals("481335", cursor.getString(1))
        }

        // 去重账本的源端改写。
        db.query(
            "SELECT sourcePlatform, targetPlatform FROM sync_records WHERE activityId = '481335'",
        ).use { cursor ->
            assertTrue("去重记录必须在迁移后保留", cursor.moveToFirst())
            assertEquals("garmin-cn", cursor.getString(0))
            assertEquals("coros", cursor.getString(1))
        }

        // 排除表同样改写。
        db.query(
            "SELECT sourcePlatform, targetPlatform FROM exclusions WHERE activityId = '481335'",
        ).use { cursor ->
            assertTrue("排除记录必须在迁移后保留", cursor.moveToFirst())
            assertEquals("garmin-cn", cursor.getString(0))
            assertEquals("strava", cursor.getString(1))
        }
    }

    /**
     * An international Garmin account must stay exactly where it was.
     *
     * The migration only moves rows when the old account was the China one;
     * otherwise every `garmin` row would be relabelled for nothing.
     */
    @Test
    fun migrate_3_to_4_leaves_an_international_garmin_account_alone() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                INSERT INTO accounts
                    (platform, displayName, emailCipher, region, secretCipher, tokenCipher,
                     status, createdAt, updatedAt)
                VALUES ('garmin', 'Garmin', 'enc-email', 'COM', 'enc-secret', 'enc-token',
                        'CONNECTED', 1, 1)
                """,
            )
            execSQL(
                """
                INSERT INTO sync_records
                    (sourcePlatform, targetPlatform, activityId, status, attempts, syncedAt,
                     fingerprint, updatedAt)
                VALUES ('garmin', 'coros', '481335', 1, 1, 2, 'fp-1', 2)
                """,
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4)

        db.query("SELECT platform, region FROM accounts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("garmin", cursor.getString(0))
            assertEquals("COM", cursor.getString(1))
        }

        db.query("SELECT sourcePlatform FROM sync_records").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("garmin", cursor.getString(0))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
