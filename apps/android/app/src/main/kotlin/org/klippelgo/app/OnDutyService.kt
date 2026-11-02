package org.klippelgo.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the app's process and CPU running off screen while a driver is online or a ride is under way, so gossip,
 * the refresh loop and the driver's position do not stop when Android suspends the app. It holds no logic of its
 * own: the work stays in [AppViewModel], and this only says the app is in use, with a notification.
 */
class OnDutyService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Location needs its permission; without it, a rider who refused still has a ride to follow.
        val located = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val shown = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (located) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIFICATION, notification(), type)
            } else {
                startForeground(NOTIFICATION, notification())
            }
        }
        if (shown.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }
        val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "klippelgo:on-duty").apply {
            setReferenceCounted(false)
            wakeLock = this
        }
        // Each time the app comes back on screen, which is what makes a shift longer than one lock possible.
        lock.acquire(WAKE_LOCK_MS)
        // Started again by nobody: the work it stands for lives in the view model, which a killed process lost.
        return START_NOT_STICKY
    }

    /** Android ends a data-sync service after six hours a day. */
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.on_duty_channel), NotificationManager.IMPORTANCE_LOW))
        }
        // The activity already running, not a second one: that would be a second account's worth of gossip and polling.
        val app = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(this, 0, app, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.on_duty_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "on-duty"
        private const val NOTIFICATION = 1

        /** A day's shift, at most. The lock must not outlive a process that failed to release it. */
        private const val WAKE_LOCK_MS = 12 * 60 * 60 * 1000L

        /** Starts or stops the service. Off screen Android may refuse a start: the next time on screen tries again. */
        fun keep(context: Context, on: Boolean) {
            val intent = Intent(context, OnDutyService::class.java)
            if (on) runCatching { context.startForegroundService(intent) } else context.stopService(intent)
        }
    }
}
