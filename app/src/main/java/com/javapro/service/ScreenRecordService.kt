package com.javapro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.javapro.R
import com.javapro.MainActivity

class ScreenRecordService : Service() {

    companion object {
        const val CHANNEL_ID   = "screen_record_channel"
        const val NOTIF_ID     = 7001
        const val ACTION_START = "com.javapro.SCREEN_RECORD_START"
        const val ACTION_STOP  = "com.javapro.SCREEN_RECORD_STOP"

        fun startIntent(context: Context) =
            Intent(context, ScreenRecordService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context) =
            Intent(context, ScreenRecordService::class.java).apply { action = ACTION_STOP }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // FIX: Gunakan startForeground langsung (bukan ServiceCompat) untuk Android 13+
                // ServiceCompat.startForeground bisa gagal silent di beberapa vendor Android 13.
                // Panggil langsung dengan tipe MEDIA_PROJECTION agar tidak SecurityException.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIF_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIF_ID, buildNotification())
                }
            }
            ACTION_STOP -> {
                // FIX: stopForeground harus pakai flag yang benar di semua API level
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        // FIX: START_STICKY agar service restart jika di-kill OS saat sedang record
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_record_title))
            .setContentText(getString(R.string.screen_record_status_recording))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.screen_record_btn_stop), stopPi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.screen_record_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }
}
