package com.kanealex.obdauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps the app alive with a notification while connected, so polling continues with the phone locked. */
class ObdService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW))
        try {
            startForeground(1, build("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            ObdManager.log("Background service not started: ${e.message}")
            ObdManager.connect(this)
            stopSelf()
            return
        }
        // Connect before watching state, so the initial "disconnected" state doesn't stop the service.
        ObdManager.connect(this)
        scope.launch {
            ObdManager.state.collectLatest { s ->
                if (s.conn == Conn.DISCONNECTED && !ObdManager.isRunning) {
                    stopSelf()
                } else {
                    nm.notify(1, build(s.status))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ObdManager.connect(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "obd"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, ObdService::class.java))
            } catch (e: Exception) {
                // Android blocks this from the background (e.g. started from the car only).
                // The connection still runs while the car screen is open.
                ObdManager.log("Background service not started: ${e.message}")
                ObdManager.connect(ctx)
            }
        }
    }
}
