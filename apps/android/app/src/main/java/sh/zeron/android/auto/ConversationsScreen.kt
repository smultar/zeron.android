package sh.zeron.android.auto

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.messaging.model.CarMessage
import androidx.car.app.messaging.model.ConversationCallback
import androidx.car.app.messaging.model.ConversationItem
import androidx.core.app.Person
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import sh.zeron.android.ZeronApplication
import uniffi.zeron_core.BusyPolicy
import uniffi.zeron_core.SendRequest
import uniffi.zeron_core.SessionRow

/** How many chats the car lists: Google asks for a handful so a driver can scan them quickly. */
private const val MAX_CHATS = 8

/**
 * The car's list of recent chats. Each is a conversation: the agent's latest message (read aloud on
 * tap), Mark as read, and a spoken Reply that is queued into the chat, so it never interrupts a run
 * that is still going. The list follows the phone's workspace while the screen is showing.
 */
class ConversationsScreen(carContext: CarContext) : Screen(carContext) {
    private val app get() = (carContext.applicationContext as ZeronApplication).model
    private val main = Handler(Looper.getMainLooper())
    private var shownRevision = -1L
    private val poll = object : Runnable {
        override fun run() {
            if (app.workspaceRevision != shownRevision) invalidate()
            main.postDelayed(this, 2_000)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { main.post(poll) }
            override fun onStop(owner: LifecycleOwner) { main.removeCallbacks(poll) }
        })
    }

    private fun rows(): List<SessionRow> {
        val client = app.client ?: return emptyList()
        val front = client.frontPage()
        return (front.pinned + front.sections.flatMap { it.sessions } + front.recent)
            .filter { it.parentChatId == null && !it.archived }
            .sortedByDescending { it.lastActivityMs }
            .take(MAX_CHATS)
    }

    override fun onGetTemplate(): Template {
        shownRevision = app.workspaceRevision
        val header = Header.Builder().setStartHeaderAction(Action.APP_ICON).setTitle("Zeron").build()
        if (app.client == null) {
            return MessageTemplate.Builder("Sign in to Zeron on your phone first.").setHeader(header).build()
        }
        val chats = rows()
        if (chats.isEmpty()) {
            return MessageTemplate.Builder("No sessions yet.").setHeader(header).build()
        }
        val me = Person.Builder().setName("You").setKey("self").build()
        val list = ItemList.Builder()
        for (row in chats) {
            val agent = Person.Builder().setName(row.harnessLabel ?: "Agent").setKey("agent").build()
            val body = row.preview?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: "No messages yet"
            val message = CarMessage.Builder()
                .setSender(agent)
                .setBody(CarText.create(body.take(400)))
                .setReceivedTimeEpochMillis(row.lastActivityMs)
                .setRead(!row.unseen)
                .build()
            list.addItem(
                ConversationItem.Builder(row.id, CarText.create(row.title), me, listOf(message), callback(row.id)).build(),
            )
        }
        return ListTemplate.Builder().setHeader(header).setSingleList(list.build()).build()
    }

    private fun callback(chatId: String) = object : ConversationCallback {
        override fun onMarkAsRead() {
            try {
                app.client?.openSession(chatId)?.markSeen()
            } catch (_: Exception) {
            }
            invalidate()
        }

        override fun onTextReply(replyText: String) {
            val text = replyText.trim()
            if (text.isEmpty()) return
            try {
                app.client?.openSession(chatId)?.send(SendRequest(text, emptyList(), null, BusyPolicy.QUEUE))
            } catch (e: Exception) {
                android.util.Log.w("zeron", "car reply failed", e)
            }
        }
    }
}
