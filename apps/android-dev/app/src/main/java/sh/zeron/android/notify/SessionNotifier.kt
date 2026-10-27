package sh.zeron.android.notify

import android.content.Context
import uniffi.zeron_core.ChatIndicator
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.SessionRow

/**
 * Decides when a workspace change deserves a phone alert — the desktop's rule
 * (`SessionNotificationState::sound_since`) applied to the host-reported status
 * of each session:
 * - failed: the run becomes Errored;
 * - input: it becomes AwaitingInput (a question or permission prompt);
 * - done: a working run completes and hasn't been seen yet.
 * A session seen for the first time only sets its baseline, so launches and
 * re-syncs never replay old events. Side chats and archived chats stay quiet,
 * and nothing sounds while the app is on screen.
 */
class SessionNotifier(private val context: Context) {
    private val baseline = HashMap<String, ChatIndicator>()
    private var seeded = false

    fun reset() {
        baseline.clear()
        seeded = false
    }

    fun onWorkspace(client: CoreClient, appInForeground: Boolean) {
        val front = client.workspace().front
        val rows = ArrayList<SessionRow>()
        rows += front.pinned
        for (s in front.sections) rows += s.sessions
        rows += front.recent

        val seen = HashSet<String>()
        for (row in rows) {
            if (row.parentChatId != null || row.archived || !seen.add(row.id)) continue
            val now = row.hostIndicator
            val before = baseline.put(row.id, now)
            if (!seeded || before == null || appInForeground) continue
            val category = when {
                now == ChatIndicator.ERRORED && before != ChatIndicator.ERRORED -> Category.FAILED
                now == ChatIndicator.AWAITING_INPUT && before != ChatIndicator.AWAITING_INPUT -> Category.INPUT
                before == ChatIndicator.WORKING && now == ChatIndicator.COMPLETED && row.unseen -> Category.DONE
                else -> null
            } ?: continue
            Notifications.show(context, row.id, row.title, category, row.harnessLabel, row.preview)
        }
        baseline.keys.retainAll(seen)
        seeded = true
    }
}
