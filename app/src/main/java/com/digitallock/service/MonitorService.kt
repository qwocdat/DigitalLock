package com.digitallock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.lock.LockActivity
import com.digitallock.lock.LockController
import com.digitallock.receiver.ScreenStateReceiver

/**
 * Event-driven Foreground MonitorService for DigitalLock.
 *
 * Responsibilities:
 * - Registers [ScreenStateReceiver] dynamically for `ACTION_SCREEN_OFF`, `ACTION_SCREEN_ON`,
 *   and `ACTION_USER_PRESENT` (Android does not allow manifest receivers for `SCREEN_ON`/`OFF`).
 * - Coordinates session lock state with [LockController] without any battery-draining polling loop.
 * - Restores state cleanly (`START_STICKY`) if the OS reclaims and restarts the process.
 */
class MonitorService : Service() {

    private var screenStateReceiver: ScreenStateReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildStatusNotification())
        registerScreenStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settingsRepo = SettingsRepository(this)
        val settings = settingsRepo.currentSettings()
        if (!settings.setupCompleted || !settings.lockEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_VERIFY_LOCK_STATE) {
            val lockRepo = LockRepository(this)
            val controller = LockController(this, settingsRepo, lockRepo)
            controller.requestLockScreen("MonitorServiceVerify")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterScreenStateReceiver()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val receiver = ScreenStateReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        screenStateReceiver = receiver
    }

    private fun unregisterScreenStateReceiver() {
        screenStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
            screenStateReceiver = null
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DigitalLock Protection Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Maintains event-driven screen state monitoring for DigitalLock."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildStatusNotification(): Notification {
        val lockIntent = Intent(this, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            lockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("DigitalLock Active")
            .setContentText("Event-driven screen state protection is running. Tap to open lock screen.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "digitallock_monitor_channel"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_VERIFY_LOCK_STATE = "com.digitallock.action.VERIFY_LOCK_STATE"

        fun startIfEnabled(context: Context) {
            val settings = SettingsRepository(context).currentSettings()
            if (!settings.setupCompleted || !settings.lockEnabled) return
            val intent = Intent(context, MonitorService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (_: Throwable) {
                // On Android 12+, ForegroundServiceStartNotAllowedException can occur if started
                // from restricted background contexts; handled cleanly without crashing.
            }
        }
    }
}
