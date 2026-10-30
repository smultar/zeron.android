package sh.zeron.android.auto

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
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
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import sh.zeron.android.ZeronApplication
import sh.zeron.android.design.Icons
import sh.zeron.android.ui.agentMarkAsset
import sh.zeron.android.ui.agentMarkTintArgb
import uniffi.zeron_core.BusyPolicy
import uniffi.zeron_core.SendRequest
import uniffi.zeron_core.SessionRow

/** How many chats the car lists: Google asks for a handful so a driver can scan them quickly. */
private const val MAX_CHATS = 8

private data class CarChat(
    val id: String,
    val title: String,
    val agent: String,
    val harness: String,
    val lines: MutableList<Pair<Boolean, String>>,
    val lastActivityMs: Long,
    var unseen: Boolean,
)

/**
 * The car's list of recent chats. Each is a conversation: the agent's latest message (read aloud on
 * tap), Mark as read, and a spoken Reply that is queued into the chat, so it never interrupts a run
 * that is still going. Signed-out debug builds show a handful of example sessions so the car UI can
 * be reviewed without a live account; release builds never show them.
 */
class ConversationsScreen(carContext: CarContext) : Screen(carContext) {
    private val app get() = (carContext.applicationContext as ZeronApplication).model
    private val main = Handler(Looper.getMainLooper())
    private var shownRevision = -1L
    private val demo = exampleChats()
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

    private fun liveRows(): List<SessionRow> {
        val client = app.client ?: return emptyList()
        val front = client.frontPage()
        return (front.pinned + front.sections.flatMap { it.sessions } + front.recent)
            .filter { it.parentChatId == null && !it.archived }
            .sortedByDescending { it.lastActivityMs }
            .take(MAX_CHATS)
    }

    private fun chats(): List<CarChat> {
        val live = liveRows()
        if (live.isNotEmpty()) {
            return live.map { row ->
                CarChat(
                    id = row.id,
                    title = row.title,
                    agent = row.harnessLabel ?: "Agent",
                    harness = row.harness ?: "claude-code",
                    lines = mutableListOf(false to (row.preview?.replace(Regex("""\s+"""), " ")?.trim().orEmpty().ifEmpty { "No messages yet" })),
                    lastActivityMs = row.lastActivityMs,
                    unseen = row.unseen,
                )
            }
        }
        // Example chats only for reviewing the car UI in a signed-out debug build, never in the car for real.
        return if (sh.zeron.android.BuildConfig.DEBUG && app.client == null) demo else emptyList()
    }

    override fun onGetTemplate(): Template {
        shownRevision = app.workspaceRevision
        val header = Header.Builder().setStartHeaderAction(Action.APP_ICON).setTitle("Zeron").build()
        val chats = chats()
        if (chats.isEmpty() && app.client == null) {
            return MessageTemplate.Builder("Sign in to Zeron on your phone first.").setHeader(header).build()
        }
        if (chats.isEmpty()) {
            return MessageTemplate.Builder("No sessions yet.").setHeader(header).build()
        }
        val me = Person.Builder().setName("You").setKey("self").build()
        val list = ItemList.Builder()
        for (chat in chats) {
            val mark = agentIcon(chat.harness)
            val agent = Person.Builder().setName(chat.agent).setKey("agent:${chat.id}").setImportant(true)
                .also { if (mark != null) it.setIcon(mark) }
                .build()
            val messages = chat.lines.mapIndexed { i, (fromYou, body) ->
                CarMessage.Builder()
                    .setSender(if (fromYou) me else agent)
                    .setBody(CarText.create(body.take(400)))
                    .setReceivedTimeEpochMillis(chat.lastActivityMs - (chat.lines.size - 1 - i) * 60_000L)
                    .setRead(!chat.unseen || i < chat.lines.lastIndex)
                    .build()
            }
            val item = ConversationItem.Builder(chat.id, CarText.create(chat.title), me, messages, callback(chat.id))
            if (carContext.carAppApiLevel >= 7 && mark != null) {
                item.setIcon(CarIcon.Builder(mark).build())
            }
            list.addItem(item.build())
        }
        return ListTemplate.Builder().setHeader(header).setSingleList(list.build()).build()
    }

    private fun callback(chatId: String) = object : ConversationCallback {
        override fun onMarkAsRead() {
            demo.find { it.id == chatId }?.unseen = false
            try {
                app.client?.openSession(chatId)?.markSeen()
            } catch (_: Exception) {
            }
            invalidate()
        }

        override fun onTextReply(replyText: String) {
            val text = replyText.trim()
            if (text.isEmpty()) return
            demo.find { it.id == chatId }?.let { chat ->
                chat.lines.add(true to text)
                chat.unseen = false
            }
            try {
                app.client?.openSession(chatId)?.send(SendRequest(text, emptyList(), null, BusyPolicy.QUEUE))
            } catch (e: Exception) {
                android.util.Log.w("zeron", "car reply failed", e)
            }
            invalidate()
        }
    }
}

private fun exampleChats(): MutableList<CarChat> {
    val now = System.currentTimeMillis()
    return mutableListOf(
        CarChat(
            id = "demo-android",
            title = "zeron.android",
            agent = "Claude Code",
            harness = "claude-code",
            lines = mutableListOf(
                true to "The overlay is covering the back button. Can we match Claude and Gemini instead?",
                false to "Moved the session chrome into a slide-over and inset the header for the system bars. New chat is a composer on the wallpaper.",
            ),
            lastActivityMs = now - 4 * 60_000L,
            unseen = true,
        ),
        CarChat(
            id = "demo-saturn",
            title = "saturn.os",
            agent = "Codex",
            harness = "codex",
            lines = mutableListOf(
                true to "Push the android-app branch to the internal remote.",
                false to "Pushed android-app to saturn. Three checks green. Default branch is still main.",
            ),
            lastActivityMs = now - 18 * 60_000L,
            unseen = false,
        ),
        CarChat(
            id = "demo-voice",
            title = "voice call",
            agent = "Claude Code",
            harness = "claude-code",
            lines = mutableListOf(
                true to "Do I need ChatGPT to use voice, or is there a self-hosted option?",
                false to "Calls use the host's Codex voice session over WebRTC. A self-hosted STT would be a separate backend.",
            ),
            lastActivityMs = now - 55 * 60_000L,
            unseen = true,
        ),
        CarChat(
            id = "demo-cursor",
            title = "cursor usage",
            agent = "Cursor",
            harness = "cursor",
            lines = mutableListOf(
                true to "Plan usage is stuck at 100%. That's not right.",
                false to "The phone was showing totalSpend over limit as one Month window. Auto and API are separate; the host build needs that split.",
            ),
            lastActivityMs = now - 3 * 60 * 60_000L,
            unseen = false,
        ),
    )
}

/** The same brand mark the phone app draws, as a car-list avatar. */
private fun agentIcon(harness: String): IconCompat? {
    val tint = agentMarkTintArgb(harness, Color.WHITE)
    val bmp = Icons.tintedBitmap(agentMarkAsset(harness), 128, tint) ?: return null
    return IconCompat.createWithBitmap(bmp)
}
