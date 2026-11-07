package com.digitallock.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.graphics.BackdropCaptureStore
import com.digitallock.graphics.LockBackgroundRenderer
import com.digitallock.lock.LockActivity
import com.digitallock.lock.LockController
import com.digitallock.receiver.ScreenStateReceiver

class MonitorService : Service() {

    private var screenStateReceiver: ScreenStateReceiver? = null
    private var mediaProjection: MediaProjection? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildStatusNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        registerScreenStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settingsRepo = SettingsRepository(this)
        val settings = settingsRepo.currentSettings()

        when (intent?.action) {
            ACTION_INIT_MEDIA_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    setupMediaProjection(resultCode, resultData)
                }
            }

            ACTION_CAPTURE_BACKDROP_NOW -> {
                val delayMs = intent.getLongExtra(EXTRA_CAPTURE_DELAY_MS, 250L)
                mainHandler.postDelayed({
                    captureCurrentScreenToBackdrop()
                }, delayMs)
            }

            ACTION_VERIFY_LOCK_STATE -> {
                if (settings.setupCompleted && settings.lockEnabled) {
                    val lockRepo = LockRepository(this)
                    LockController(this, settingsRepo, lockRepo)
                        .requestLockScreen("MonitorServiceVerify")
                }
            }
        }

        if (!settings.setupCompleted && intent?.action != ACTION_INIT_MEDIA_PROJECTION) {
            return START_STICKY
        }
        return START_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildStatusNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            }
            val mpm = getSystemService(MediaProjectionManager::class.java) ?: return
            mediaProjection?.stop()
            val proj = mpm.getMediaProjection(resultCode, resultData) ?: return
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (mediaProjection === proj) {
                        mediaProjection = null
                        BackdropCaptureStore.setMediaProjectionActive(false)
                    }
                }
            }, mainHandler)
            mediaProjection = proj
            BackdropCaptureStore.setMediaProjectionActive(true)
            // Capture initial wallpaper frame immediately
            mainHandler.postDelayed({ captureCurrentScreenToBackdrop() }, 300L)
        } catch (_: Throwable) {
            BackdropCaptureStore.setMediaProjectionActive(false)
        }
    }

    private fun captureCurrentScreenToBackdrop() {
        val proj = mediaProjection ?: return
        val metrics = resources.displayMetrics
        LockBackgroundRenderer.captureSingleFrameFromMediaProjection(
            mediaProjection = proj,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            stopProjectionAfterCapture = false,
        ) { bmp ->
            if (bmp != null) {
                BackdropCaptureStore.updateCapturedBitmap(applicationContext, bmp)
            }
        }
    }

    override fun onDestroy() {
        unregisterScreenStateReceiver()
        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        mediaProjection = null
        BackdropCaptureStore.setMediaProjectionActive(false)
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
            "Dịch vụ bảo vệ DigitalLock",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Duy trì theo dõi sự kiện tắt/bật màn hình cho DigitalLock."
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
            .setContentTitle("DigitalLock đang bảo vệ")
            .setContentText("Đang theo dõi trạng thái khóa màn hình. Chạm để mở màn hình khóa.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "digitallock_monitor_channel"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_VERIFY_LOCK_STATE = "com.digitallock.action.VERIFY_LOCK_STATE"
        const val ACTION_INIT_MEDIA_PROJECTION = "com.digitallock.action.INIT_MEDIA_PROJECTION"
        const val ACTION_CAPTURE_BACKDROP_NOW = "com.digitallock.action.CAPTURE_BACKDROP_NOW"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_CAPTURE_DELAY_MS = "extra_capture_delay_ms"

        fun startIfEnabled(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (_: Throwable) {
            }
        }

        fun initMediaProjectionSession(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MonitorService::class.java).apply {
                action = ACTION_INIT_MEDIA_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            try {
                context.startForegroundService(intent)
            } catch (_: Throwable) {
            }
        }

        fun requestBackdropCapture(context: Context, delayMs: Long = 350L) {
            val intent = Intent(context, MonitorService::class.java).apply {
                action = ACTION_CAPTURE_BACKDROP_NOW
                putExtra(EXTRA_CAPTURE_DELAY_MS, delayMs)
            }
            try {
                context.startService(intent)
            } catch (_: Throwable) {
            }
        }
    }
}
