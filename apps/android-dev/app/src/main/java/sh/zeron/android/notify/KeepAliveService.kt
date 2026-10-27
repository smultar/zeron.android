package sh.zeron.android.notify

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import sh.zeron.android.ZeronApplication

/**
 * Keeps the app process — and with it the Rust client's connection — alive in
 * the background so session alerts arrive with the app closed. It shows one
 * quiet, minimized notification while running.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.KEEP_ALIVE_NOTIFICATION_ID, Notifications.keepAliveNotification(this), type)
        return START_STICKY
    }

    companion object {
        /** Start or stop the service to match the current state (signed in, alerts on, permission granted). */
        fun sync(context: Context) {
            val app = context.applicationContext as? ZeronApplication ?: return
            val intent = Intent(context, KeepAliveService::class.java)
            if (app.model.client != null && Notifications.active(context)) {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (_: Exception) {
                    // Not allowed from the background right now; the next foreground start retries.
                }
            } else {
                context.stopService(intent)
            }
        }
    }
}

/** Restart the connection after a reboot when alerts are on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) KeepAliveService.sync(context)
    }
}
