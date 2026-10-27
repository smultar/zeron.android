package sh.zeron.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import sh.zeron.android.ui.AppDrawerContent
import sh.zeron.android.ui.ArchivedScreen
import sh.zeron.android.ui.HomeScreen
import sh.zeron.android.ui.SettingsScreen
import sh.zeron.android.ui.SessionScreen
import sh.zeron.android.ui.SignInScreen
import sh.zeron.android.ui.Z
import sh.zeron.android.ui.ZeronTheme

/** Where the user is. Sessions stack above the home tabs. */
private sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data object Archived : Route
    data class Session(val chatId: String) : Route
}

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private val authCallbacks = MutableSharedFlow<Uri>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val model get() = (application as ZeronApplication).model

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        debugLaunch(intent)
        setContent {
            ZeronTheme {
                Box(Modifier.fillMaxSize().background(Z.background)) { Root() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        debugLaunch(intent)
    }

    /** `--es chat <id>` opens that chat on launch; session alerts use it too. */
    private fun debugLaunch(intent: Intent?) {
        if (intent == null) return
        // A tapped session alert (or a debug launch) opens that chat.
        intent.getStringExtra("chat")?.let { pendingChat = it }
        // Debug: `--ez voicePreview true` shows a scripted call (no audio, host or relay).
        if (BuildConfig.DEBUG && intent.getBooleanExtra("voicePreview", false)) previewVoice = true
    }

    private var pendingChat by androidx.compose.runtime.mutableStateOf<String?>(null)
    private var previewVoice by androidx.compose.runtime.mutableStateOf(false)

    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "zeron") authCallbacks.tryEmit(data)
    }

    override fun onStart() {
        super.onStart()
        model.onForeground()
        sh.zeron.android.notify.KeepAliveService.sync(this)
    }

    override fun onStop() {
        super.onStop()
        model.onBackground()
    }

    @Composable
    private fun Root() {
        val app = model
        if (app.client == null) {
            SignInScreen(app, authCallbacks)
            return
        }
        // Ask once, after sign-in, for permission to show session alerts.
        val askPermission = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { sh.zeron.android.notify.KeepAliveService.sync(this@MainActivity) }
        androidx.compose.runtime.LaunchedEffect(app.client) {
            val n = sh.zeron.android.notify.Notifications
            if (android.os.Build.VERSION.SDK_INT >= 33 && !n.asked(this@MainActivity) && !n.permissionGranted(this@MainActivity)) {
                n.markAsked(this@MainActivity)
                askPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            sh.zeron.android.notify.KeepAliveService.sync(this@MainActivity)
        }
        // Voice calls ask for the microphone when one is placed.
        var micAnswer by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
        val askMic = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { granted -> micAnswer?.invoke(granted); micAnswer = null }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            app.voice.requestMicrophone = { answer -> micAnswer = answer; askMic.launch(android.Manifest.permission.RECORD_AUDIO) }
        }
        androidx.compose.runtime.LaunchedEffect(previewVoice) {
            if (previewVoice) { app.voice.preview(); previewVoice = false }
        }
        val stack = remember { mutableStateListOf<Route>(Route.Home) }
        androidx.compose.runtime.LaunchedEffect(pendingChat) {
            pendingChat?.let { stack += Route.Session(it); pendingChat = null }
        }
        fun push(r: Route) { stack += r }
        fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
        /** Open a session from the menu: replace a chat already on top instead of stacking chats. */
        fun openSession(id: String) {
            if (stack.last() is Route.Session) stack.removeAt(stack.lastIndex)
            push(Route.Session(id))
        }
        val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val route = stack.last()
        androidx.activity.compose.BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

        val lockOn = app.lockEnabled
        // A locked app also hides itself in the recent-apps switcher and screenshots.
        androidx.compose.runtime.LaunchedEffect(lockOn) {
            if (lockOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        Box(Modifier.fillMaxSize()) {
        androidx.compose.material3.ModalNavigationDrawer(
            drawerState = drawer,
            // The menu belongs to the home and chat screens; settings and archive are plain pages.
            gesturesEnabled = route is Route.Home || route is Route.Session,
            drawerContent = {
                AppDrawerContent(
                    app,
                    close = { then -> scope.launch { drawer.close(); then() } },
                    onOpen = ::openSession,
                    onNew = { while (stack.size > 1) stack.removeAt(stack.lastIndex) },
                    onSettings = { push(Route.Settings) },
                    onArchived = { push(Route.Archived) },
                )
            },
        ) {
            when (route) {
                Route.Home -> HomeScreen(
                    app, onMenu = { scope.launch { drawer.open() } }, onSettings = { push(Route.Settings) },
                    onCreated = { push(Route.Session(it)) },
                )
                Route.Settings -> SettingsScreen(app, onBack = ::pop, onArchived = { push(Route.Archived) })
                Route.Archived -> ArchivedScreen(app, onBack = ::pop, onOpen = { push(Route.Session(it)) })
                is Route.Session -> androidx.compose.runtime.key(route.chatId) {
                    SessionScreen(app, route.chatId, onMenu = { scope.launch { drawer.open() } }, onBack = ::pop)
                }
            }
        }
        sh.zeron.android.ui.VoiceBar(app, Modifier.align(androidx.compose.ui.Alignment.TopCenter))
        if (app.voice.showStage) sh.zeron.android.ui.VoiceStage(app, onOpenChat = { id -> openSession(id) })
        if (app.lockEnabled && app.locked) sh.zeron.android.ui.LockScreen(this@MainActivity) { app.locked = false }
        }
    }
}
