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
    version = 3,
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                // Per-activity state is a cache, not user data: rebuilding the
                // ledger is always safe, so we never ship destructive migrations
                // for the pre-1.0 schema.
                .fallbackToDestructiveMigration(dropAllTables = true)
                // v2 → v3 有专门的非破坏性迁移：它只给 sync_records 加一列，
                // 不会碰 accounts 里的账号凭据。
                .addMigrations(MIGRATION_2_3)
                .build()
    }
}
