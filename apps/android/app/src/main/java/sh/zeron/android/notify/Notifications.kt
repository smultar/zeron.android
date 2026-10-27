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
import androidx.core.app.Person
import androidx.core.app.RemoteInput
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

    const val EXTRA_REPLY = "reply"

    /** A broadcast to [CarActionReceiver] for one chat; `mutable` for the reply (its text is filled in). */
    private fun actionIntent(context: Context, action: String, chatId: String, mutable: Boolean): PendingIntent {
        val intent = Intent(context, CarActionReceiver::class.java).setAction(action).putExtra("chat", chatId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, chatId.hashCode() xor action.hashCode(), intent, flags)
    }

    /** What a car reads aloud: the event, then the start of the agent's last message. */
    private fun spoken(category: Category, preview: String?): String {
        val tail = preview?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
        return if (tail.isEmpty()) category.body else "${category.body}. ${tail.take(280)}"
    }

    /**
     * Post a session alert; tapping it opens that chat. It is a messaging notification — the agent
     * "says" the event, with Reply and Mark as read actions — so Android Auto reads it aloud and takes
     * a spoken reply, which is queued into the chat (never run as a command while the agent is busy).
     */
    fun show(context: Context, chatId: String, title: String, category: Category, agent: String? = null, preview: String? = null) {
        if (!active(context) || !wants(context, category)) return
        ensureChannels(context)
        val me = Person.Builder().setName("You").build()
        val from = Person.Builder().setName(agent ?: "Zeron").setKey("agent").setImportant(true).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(title)
            .addMessage(spoken(category, preview), System.currentTimeMillis(), from)
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_stat_zeron, "Reply", actionIntent(context, CarActionReceiver.ACTION_REPLY, chatId, mutable = true))
            .addRemoteInput(RemoteInput.Builder(EXTRA_REPLY).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val read = NotificationCompat.Action.Builder(R.drawable.ic_stat_zeron, "Mark as read", actionIntent(context, CarActionReceiver.ACTION_READ, chatId, mutable = false))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val n = NotificationCompat.Builder(context, category.channel)
            .setSmallIcon(R.drawable.ic_stat_zeron)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(reply)
            .addAction(read)
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
