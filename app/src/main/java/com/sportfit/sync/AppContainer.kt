package com.sportfit.sync

import android.content.Context
import com.sportfit.sync.cache.CacheManager
import com.sportfit.sync.data.local.AppDatabase
import com.sportfit.sync.data.prefs.SettingsStore
import com.sportfit.sync.data.repo.AccountRepository
import com.sportfit.sync.export.ActivityExporter
import com.sportfit.sync.sync.ExportEngine
import com.sportfit.sync.platform.MfaCoordinator
import com.sportfit.sync.sync.SyncEngine
import com.sportfit.sync.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Process-wide dependency container.
 *
 * Hand-rolled on purpose: the graph is a dozen objects deep and adding Hilt
 * would cost annotation processing on every build for no functional gain.
 */
class AppContainer(private val appContext: Context) {

    /** Application context, for components that must outlive an Activity. */
    val context: Context get() = appContext


    val applicationScope = CoroutineScope(SupervisorJob())

    val database: AppDatabase = AppDatabase.build(appContext)

    val settingsStore = SettingsStore(appContext)

    val accountRepository = AccountRepository(database.accountDao())

    val mfaCoordinator = MfaCoordinator()

    val cacheManager = CacheManager(
        context = appContext,
        db = database,
        policyProvider = {
            // CachePolicy is only read on background threads; blocking here is
            // cheaper than threading a coroutine through every DAO call site.
            runBlocking { settingsStore.current().cachePolicy }
        },
    )

    val syncEngine = SyncEngine(
        db = database,
        accounts = accountRepository,
        cache = cacheManager,
        mfa = mfaCoordinator,
    )

    val activityExporter = ActivityExporter(appContext)

    val exportEngine = ExportEngine(
        db = database,
        accounts = accountRepository,
        exporter = activityExporter,
        mfa = mfaCoordinator,
    )

    /** Re-applies the persisted background-sync preference to WorkManager. */
    suspend fun applyBackgroundSchedule() = SyncScheduler.syncWithSettings(appContext)

    companion object {
        fun from(context: Context): AppContainer =
            (context.applicationContext as SportFitApp).container
    }
}
