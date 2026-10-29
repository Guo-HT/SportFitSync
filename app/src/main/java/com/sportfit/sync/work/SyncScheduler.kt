package com.sportfit.sync.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sportfit.sync.AppContainer
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Registers the optional background sync.
 *
 * Background syncing is opt-in (off by default) because a scheduled run can
 * silently invalidate a Coros session on the user's phone, and because keeping
 * credentials usable in the background is a privacy decision the user should
 * make explicitly.
 */
object SyncScheduler {

    const val CHANNEL_ID = "sync-status"
    private const val UNIQUE_WORK_NAME = "sportfit-periodic-sync"

    fun createNotificationChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "同步状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "后台同步的进度与结果通知"
        }
        manager.createNotificationChannel(channel)
    }

    /** Applies the user's current preference; safe to call on every launch. */
    suspend fun syncWithSettings(context: Context) {
        val container = AppContainer.from(context)
        val settings = container.settingsStore.settings.first()

        if (!settings.backgroundSyncEnabled) {
            cancel(context)
        } else {
            schedule(context, settings.backgroundIntervalHours)
        }
    }

    fun schedule(context: Context, intervalHours: Int) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            intervalHours.coerceIn(1, 24).toLong(),
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /** Kicks off a one-off run; used by "sync now" from the notification path. */
    fun runOnce(context: Context) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }

    internal fun container(context: Context): AppContainer = AppContainer.from(context)
}
