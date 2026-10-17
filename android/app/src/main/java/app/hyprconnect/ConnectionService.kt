package app.hyprconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Keeps the process (and so the link to the PC) alive while the screen is off. */
class ConnectionService : Service() {
    companion object {
        var instance: ConnectionService? = null
        const val ACTION_START_MIRROR = "app.hyprconnect.action.START_MIRROR"
        const val ACTION_STOP_MIRROR = "app.hyprconnect.action.STOP_MIRROR"
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = Hub.sendBattery(i)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Hub.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("link", "Connection", NotificationManager.IMPORTANCE_MIN)
        )
        updateForeground(false)
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MIRROR -> updateForeground(true)
            ACTION_STOP_MIRROR -> updateForeground(false)
            "stop" -> {
                Hub.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> Hub.start()
        }
        return START_STICKY
    }

    fun updateForeground(mirroring: Boolean) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (mirroring) "Sharing screen with ${Hub.store.pcName}" else "Linked to ${Hub.store.pcName}"
        val n = Notification.Builder(this, "link")
            .setContentTitle("HyprConnect")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = if (mirroring) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        startForeground(1, n, type)
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        runCatching { unregisterReceiver(battery) }
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
