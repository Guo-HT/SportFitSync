package com.sportfit.sync

import android.app.Application
import com.sportfit.sync.work.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * Owns the process-wide dependency container and registers background work.
 * Startup work is limited to retention housekeeping, which is cheap and keeps
 * the cache screen accurate from the first launch.
 */
class SportFitApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        SyncScheduler.createNotificationChannel(this)

        container.applicationScope.launch(Dispatchers.IO) {
            runCatching { container.cacheManager.applyRetention() }
            runCatching { SyncScheduler.syncWithSettings(this@SportFitApp) }
        }
    }
}
