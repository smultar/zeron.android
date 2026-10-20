package sh.zeron.android.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import sh.zeron.android.MainActivity
import sh.zeron.android.R

/** Which session event a notification is for — also the per-kind preference. */
enum class Category(val key: String, val channel: String, val title: String, val body: String, val sound: Int) {
    DONE("done", "session_done", "Run finished", "Run finished", R.raw.session_done),
    INPUT("input", "session_input", "Waiting on your input", "Waiting on your input", R.raw.session_input),
    FAILED("failed", "session_failed", "Run failed", "Run failed", R.raw.session_failed),
}

/**
 * Session alerts on this phone. The desktop's three chimes ride the
 * notification channels (done / request / attention), so each kind sounds the
 * same here as it does there.
 */
object Notifications {
    const val KEEP_ALIVE_CHANNEL = "keep_alive"
    private const val KEEP_ALIVE_ID = 1

    private fun prefs(context: Context) = context.getSharedPreferences("zeron", Context.MODE_PRIVATE)

    /** Master switch (default on); the system permission is separate. */
    fun enabled(context: Context) = prefs(context).getBoolean("notifyEnabled", true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("notifyEnabled", on).apply()

    fun wants(context: Context, c: Category) = prefs(context).getBoolean("notify_${c.key}", true)
    fun setWants(context: Context, c: Category, on: Boolean) = prefs(context).edit().putBoolean("notify_${c.key}", on).apply()

    fun asked(context: Context) = prefs(context).getBoolean("notifyAsked", false)
    fun markAsked(context: Context) = prefs(context).edit().putBoolean("notifyAsked", true).apply()

    fun permissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Alerts are on and allowed: worth keeping the connection alive for. */
    fun active(context: Context) = enabled(context) && permissionGranted(context)

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        for (c in Category.entries) {
            val channel = NotificationChannel(c.channel, c.title, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When a session's ${c.key} state changes"
                setSound(Uri.parse("android.resource://${context.packageName}/${c.sound}"), audio)
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
        nm.createNotificationChannel(
            NotificationChannel(KEEP_ALIVE_CHANNEL, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps Zeron connected so alerts arrive when the app is closed"
                setShowBadge(false)
            },
        )
    }

    private fun openIntent(context: Context, chatId: String?, request: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            chatId?.let { putExtra("chat", it) }
        }
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Post a session alert; tapping it opens that chat. */
    fun show(context: Context, chatId: String, title: String, category: Category) {
        if (!active(context) || !wants(context, category)) return
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, category.channel)
            .setSmallIcon(R.drawable.ic_stat_zeron)
            .setContentTitle(title)
            .setContentText(category.body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, chatId, chatId.hashCode()))
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(chatId.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }

    /** The quiet ongoing notification the foreground service needs. */
    fun keepAliveNotification(context: Context) = NotificationCompat.Builder(context, KEEP_ALIVE_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_zeron)
        .setContentTitle("Zeron is connected")
        .setContentText("Session alerts are on")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(openIntent(context, null, 0))
        .build()

    const val KEEP_ALIVE_NOTIFICATION_ID = KEEP_ALIVE_ID
}
