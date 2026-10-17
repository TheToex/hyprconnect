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
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = Hub.sendBattery(i)
    }

    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("link", "Connection", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "link")
            .setContentTitle("HyprConnect")
            .setContentText("Linked to ${Hub.store.pcName}")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            Hub.stop(); stopSelf(); return START_NOT_STICKY
        }
        Hub.start()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(battery) }
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
