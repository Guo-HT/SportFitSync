package com.sportfit.sync.sync

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sportfit.sync.MainActivity
import com.sportfit.sync.R
import com.sportfit.sync.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a running sync alive and visible.
 *
 * A full history sync can take minutes; without a foreground service Android
 * is free to kill the process the moment the user switches away, which would
 * silently truncate the run. The service owns the notification and stops itself
 * as soon as [SyncProgressBus] reports that nothing is running.
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(buildNotification(SyncProgressBus.progress.value))

        scope.launch {
            SyncProgressBus.progress.collectLatest { progress ->
                if (progress == null || !progress.running) {
                    stopSelf()
                    return@collectLatest
                }
                notify(buildNotification(progress))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Not sticky: if the process dies mid-sync the run is aborted anyway,
        // and restarting without state would show a bogus notification.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(progress: SyncProgress?): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = progress?.let { "正在同步 ${it.directionLabel}" } ?: "正在同步…"
        val text = progress?.let { p ->
            if (p.total > 0) {
                "${p.index}/${p.total} · ${p.currentName ?: "准备中"}"
            } else {
                "正在获取活动列表…"
            }
        } ?: "正在准备…"

        return NotificationCompat.Builder(this, SyncScheduler.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(progress?.total ?: 0, progress?.index ?: 0, progress == null || progress.total <= 0)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            SyncProgressBus.clear()
            runCatching { context.stopService(Intent(context, SyncService::class.java)) }
        }
    }
}
