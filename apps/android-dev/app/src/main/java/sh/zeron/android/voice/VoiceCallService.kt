package sh.zeron.android.voice

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
import androidx.core.content.ContextCompat
import sh.zeron.android.MainActivity
import sh.zeron.android.R
import sh.zeron.android.ZeronApplication

/**
 * Holds a voice call open (microphone foreground service) while the screen is
 * off or another app is in front, with a Hang up action on its notification.
 * It lives exactly as long as the call.
 */
class VoiceCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            (application as? ZeronApplication)?.model?.voice?.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val host = intent?.getStringExtra(EXTRA_HOST).orEmpty()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUp = PendingIntent.getService(
            this, 1, Intent(this, VoiceCallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zeron)
            .setContentTitle("Voice call")
            .setContentText(if (host.isEmpty()) "Talking with Codex" else "Talking with Codex on $host")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .addAction(0, "Hang up", hangUp)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "voice_call"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_HANGUP = "sh.zeron.android.voice.HANGUP"
        private const val EXTRA_HOST = "host"

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Voice calls", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a voice call is open"
                    setShowBadge(false)
                },
            )
        }

        fun start(context: Context, host: String) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, VoiceCallService::class.java).putExtra(EXTRA_HOST, host))
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceCallService::class.java))
        }
    }
}
