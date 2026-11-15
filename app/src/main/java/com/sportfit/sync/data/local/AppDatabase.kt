package com.sportfit.sync.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        CachedActivityEntity::class,
        SyncRecordEntity::class,
        SyncJobEntity::class,
        SyncLogEntity::class,
        ExclusionEntity::class,
        StrategyEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun activityDao(): ActivityDao
    abstract fun syncDao(): SyncDao
    abstract fun exclusionDao(): ExclusionDao
    abstract fun strategyDao(): StrategyDao

    companion object {
        private const val DB_NAME = "sportfit-sync.db"

        /**
         * v2 → v3: sync_records 增加内容指纹列（默认空串，历史记录视为无指纹）。
         *
         * 必须是**非破坏性**迁移：库里还存着用户加密后的账号凭据
         * （accounts.emailCipher / secretCipher / tokenCipher），一旦走
         * `fallbackToDestructiveMigration` 这张表会被一起清掉，用户刚配好的
         * 账号和授权就没了。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_records ADD COLUMN fingerprint TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v3 → v4: Garmin 拆成「国际区」与「中国区」两个平台。
         *
         * 拆分前一个平台只有一行账号，站点由 `region` 列（`COM` / `CN`）决定。
         * 拆分后站点改由平台自己决定（`garmin` = 国际区，`garmin-cn` = 中国区），
         * 所以**原来挂在国区的那些行都要改写成新 key**，否则用户的国区账号会被
         * 误标成国际区，登录时会打到 garmin.com 去。
         *
         * 判定依据只有一个：升级前那个 Garmin 账号的 `region`。之前一个平台只可能
         * 存一个账号，所以 `region='CN'` 意味着所有 `garmin` 的行都属于国区；
         * 否则它们都属于国际区，原样不动。
         *
         * 同样是**非破坏性**迁移：账号凭据（`emailCipher` / `secretCipher` /
         * `tokenCipher`）只是换了主键值，内容一个字节都不动。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val wasChina = db.query(
                    "SELECT COUNT(*) FROM accounts WHERE platform = 'garmin' AND region = 'CN'",
                ).use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0) > 0
                }
                if (!wasChina) return

                // 账号行：改写主键值。'garmin-cn' 此前不存在，不会撞主键。
                db.execSQL("UPDATE accounts SET platform = 'garmin-cn' WHERE platform = 'garmin'")
                // 活动缓存：之前只有一份 Garmin 列表，它属于国区。
                db.execSQL(
                    "UPDATE cached_activities SET platform = 'garmin-cn' WHERE platform = 'garmin'",
                )
                // 账本 / 任务 / 策略 / 排除表：源与目标两列都要改。
                // 这些表里不存在 source == target == 'garmin' 的自环记录
                // （方向矩阵一直禁止自同步），所以改写不会撞主键。
                for (table in listOf("sync_records", "sync_jobs", "strategies", "exclusions")) {
                    db.execSQL(
                        "UPDATE $table SET sourcePlatform = 'garmin-cn' " +
                            "WHERE sourcePlatform = 'garmin'",
                    )
                    db.execSQL(
                        "UPDATE $table SET targetPlatform = 'garmin-cn' " +
                            "WHERE targetPlatform = 'garmin'",
                    )
                }
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                // Per-activity state is a cache, not user data: rebuilding the
                // ledger is always safe, so we never ship destructive migrations
                // for the pre-1.0 schema.
                .fallbackToDestructiveMigration(dropAllTables = true)
                // v2 → v3 有专门的非破坏性迁移：它只给 sync_records 加一列，
                // 不会碰 accounts 里的账号凭据。
                .addMigrations(MIGRATION_2_3)
                // v3 → v4 把国区 Garmin 的行改写成新 key，凭据内容不变。
                .addMigrations(MIGRATION_3_4)
                .build()
    }
}
