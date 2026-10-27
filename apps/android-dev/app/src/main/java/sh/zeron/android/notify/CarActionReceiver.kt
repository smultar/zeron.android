package sh.zeron.android.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import sh.zeron.android.ZeronApplication
import uniffi.zeron_core.BusyPolicy
import uniffi.zeron_core.SendRequest

/**
 * The Reply and Mark as read buttons of a session alert, which is also what Android Auto runs when the
 * driver answers by voice. A reply is sent with the queue policy: it goes straight in when the agent is
 * idle and waits its turn when it is busy, so a spoken sentence never interrupts or steers a live run.
 */
class CarActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra("chat") ?: return
        val app = (context.applicationContext as? ZeronApplication)?.model
        val client = app?.client
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifications.EXTRA_REPLY)?.toString()?.trim().orEmpty()
                if (text.isNotEmpty() && client != null) {
                    try {
                        client.openSession(chatId).send(SendRequest(text, emptyList(), null, BusyPolicy.QUEUE))
                    } catch (e: Exception) {
                        android.util.Log.w("zeron", "car reply failed", e)
                    }
                }
            }
            ACTION_READ -> try {
                client?.openSession(chatId)?.markSeen()
            } catch (_: Exception) {
            }
            else -> return
        }
        context.getSystemService(NotificationManager::class.java).cancel(chatId.hashCode())
    }

    companion object {
        const val ACTION_REPLY = "sh.zeron.android.action.REPLY"
        const val ACTION_READ = "sh.zeron.android.action.MARK_READ"
    }
}
