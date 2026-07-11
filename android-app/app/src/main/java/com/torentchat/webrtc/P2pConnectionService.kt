package com.torentchat.webrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps P2P connections alive while the app is backgrounded.
 * ─────────────────────────────────────────────────────────────────────────────
 * Android aggressively kills background sockets to save battery. To ensure
 * incoming messages are received promptly, this service holds a foreground
 * notification (required by Android for long-running background work) and
 * maintains the WebRTC peer connections & signaling poll loop.
 *
 * The notification is intentionally minimal ("TorentChat aktif") and doesn't
 * reveal any message content — privacy-preserving even in the notification shade.
 *
 * TODO(Phase 3): wire up the actual connection lifecycle (start/stop polling,
 *   reconnect on network change, drain pending on reconnect).
 */
class P2pConnectionService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // TODO: acquire wakelock, start signaling poll, maintain peer connections.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TorentChat aktif")
            .setContentText("Koneksi terenkripsi aktif")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Koneksi P2P",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Menjaga koneksi chat terenkripsi tetap aktif"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "torentchat_p2p"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, P2pConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, P2pConnectionService::class.java))
        }
    }
}
