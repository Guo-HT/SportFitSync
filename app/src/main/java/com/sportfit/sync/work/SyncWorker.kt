package com.sportfit.sync.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.sportfit.sync.AppContainer
import com.sportfit.sync.R
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.sync.SyncProgress
import com.sportfit.sync.sync.SyncProgressBus
import com.sportfit.sync.sync.SyncRequest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

/**
 * Runs the default (or strategy-designated) sync direction in the background.
 *
 * Skips silently when the direction's accounts are missing, which is the
 * normal state right after installation and must not surface as a worker
 * failure (that would trigger WorkManager retries).
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.from(applicationContext)
        val settings = container.settingsStore.settings.first()

        // A strategy explicitly marked for background use wins over the
        // ad-hoc defaults chosen on the home screen.
        val strategy = container.database.strategyDao().backgroundStrategy()
            ?.takeIf { it.enabled }
        val source = strategy?.sourcePlatform?.let(Platform::fromKey) ?: settings.defaultSource
        val target = strategy?.targetPlatform?.let(Platform::fromKey) ?: settings.defaultTarget
        if (!container.accountRepository.has(source) || !container.accountRepository.has(target)) {
            return Result.success()
        }

        // WorkManager caps plain workers at ~10 minutes and a real history sync
        // can exceed that, so promote to a foreground worker.
        runCatching { setForeground(foregroundInfo(source, target)) }

        SyncProgressBus.publish(
            SyncProgress(
                directionLabel = "${source.displayName} → ${target.displayName}",
                index = 0,
                total = 0,
                currentName = null,
                running = true,
            ),
        )

        return try {
            container.syncEngine
                .run(
                    SyncRequest(
                        source = source,
                        target = target,
                        listLimit = settings.listLimit,
                    ),
                )
                .collect { /* progress is persisted to the DB as it happens */ }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A missing or expired credential is not retryable without the
            // user, so do not ask WorkManager to back off and retry.
            Result.failure()
        } finally {
            SyncProgressBus.clear()
        }
    }

    private fun foregroundInfo(source: Platform, target: Platform): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, SyncScheduler.CHANNEL_ID)
            .setContentTitle("后台同步 ${source.displayName} → ${target.displayName}")
            .setContentText("正在运行…")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 42
    }
}
