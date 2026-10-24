package sh.zeron.android.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import uniffi.zeron_core.AuthOrg
import uniffi.zeron_core.AuthTokens
import uniffi.zeron_core.ClientEvent
import uniffi.zeron_core.ClientListener
import uniffi.zeron_core.Connectivity
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.CoreConfig
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.Credentials
import uniffi.zeron_core.SessionRow
import uniffi.zeron_core.authExchangeCode
import uniffi.zeron_core.authListOrgs
import uniffi.zeron_core.authProductionEdgeUrl
import uniffi.zeron_core.authRefresh
import java.io.File
import java.util.UUID

/**
 * App-wide state owner: holds the Rust [CoreClient] and fans its change
 * notifications out to Compose through revision counters. Main-thread only.
 */
/** How long the app can sit in the background before it asks again. */
private const val LOCK_AFTER_MS = 30_000L

class AppModel(private val context: Context) : ClientListener {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("zeron", Context.MODE_PRIVATE)
    private val secure by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "zeron-credentials", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val notifier = sh.zeron.android.notify.SessionNotifier(context)

    /** The voice orchestrator call (audio on this phone, Codex on a host). */
    val voice = sh.zeron.android.voice.VoiceController(context, this)

    /** Whether the app is on screen — session alerts stay quiet while it is. */
    var inForeground = true
        private set

    var client: CoreClient? by mutableStateOf(null)
        private set

    /** Bumps whenever `client.workspace()` advanced. */
    var workspaceRevision by mutableLongStateOf(0)
        private set

    /** Bumps whenever any session's composer state advanced. */
    var composerRevision by mutableLongStateOf(0)
        private set

    var connectivity: Connectivity? by mutableStateOf(null)
        private set

    /** What was typed per session / on the new-session page when it was closed unsent. */
    private val drafts = HashMap<String, String>()
    fun draft(chatId: String) = drafts[chatId].orEmpty()
    fun setDraft(chatId: String, text: String) { if (text.isEmpty()) drafts.remove(chatId) else drafts[chatId] = text }
    var newSessionText: String = ""

    /** The new-session page's last options, kept across launches. */
    var lastTargetKey: String?
        get() = prefs.getString("lastTarget", null)
        set(v) { prefs.edit().putString("lastTarget", v).apply() }
    var lastHarness: String?
        get() = prefs.getString("lastHarness", null)
        set(v) { prefs.edit().putString("lastHarness", v).apply() }
    var lastModel: String?
        get() = prefs.getString("lastModel", null)
        set(v) { prefs.edit().putString("lastModel", v).apply() }
    var lastEffort: String?
        get() = prefs.getString("lastEffort", null)
        set(v) { prefs.edit().putString("lastEffort", v).apply() }
    /** Per-model option picks (speed, context window…) from the last selection. */
    var lastOptions: Map<String, String>
        get() = try {
            val o = JSONObject(prefs.getString("lastOptions", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (_: Exception) {
            emptyMap()
        }
        set(v) { prefs.edit().putString("lastOptions", JSONObject(v).toString()).apply() }
    /** The branch and worktree choice last made in a project (keyed by its checkout). */
    fun branchFor(key: String): String? = prefs.getString("branch.$key", null)
    fun setBranchFor(key: String, branch: String) { prefs.edit().putString("branch.$key", branch).apply() }
    fun worktreeFor(key: String): Boolean = prefs.getBoolean("worktree.$key", false)
    fun setWorktreeFor(key: String, on: Boolean) { prefs.edit().putBoolean("worktree.$key", on).apply() }

    /** Ask for the phone's fingerprint, face or screen lock when the app opens or returns after a while. */
    var lockEnabled: Boolean
        get() = prefs.getBoolean("appLock", false)
        set(v) { prefs.edit().putBoolean("appLock", v).apply(); if (!v) locked = false }

    /** Whether the lock screen is up; starts up so a cold start is gated. */
    var locked by mutableStateOf(prefs.getBoolean("appLock", false))
    private var backgroundedAt = 0L

    val isSignedIn get() = client != null

    /** Edge override for local `wrangler dev` stacks (debug builds only). */
    val edgeUrl: String
        get() = prefs.getString("edgeOverride", null)?.takeIf { it.isNotBlank() } ?: authProductionEdgeUrl()

    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: ("android-" + UUID.randomUUID().toString().take(8)).also {
            prefs.edit().putString("deviceId", it).apply()
        }

    private val coreDir get() = File(context.filesDir, "core")

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { client?.setNetworkOnline(true) } }
        override fun onLost(network: Network) { main.post { client?.setNetworkOnline(false) } }
    }

    init {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(network)
        stored()?.let { start(it) }
    }

    // ---- credentials ------------------------------------------------------------

    private fun stored(): Credentials? {
        val raw = secure.getString("credentials", null) ?: return null
        return try {
            val j = JSONObject(raw)
            Credentials.WorkOs(
                j.getString("userId"), j.getString("orgId"),
                AuthTokens(j.getString("accessToken"), j.getString("refreshToken")),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun store(c: Credentials.WorkOs) {
        secure.edit().putString(
            "credentials",
            JSONObject()
                .put("userId", c.userId).put("orgId", c.orgId)
                .put("accessToken", c.tokens.accessToken).put("refreshToken", c.tokens.refreshToken)
                .toString(),
        ).apply()
    }

    // ---- lifecycle ----------------------------------------------------------------

    /** Local docs belong to one identity: another account starts from an empty store. */
    private fun claimCoreDir(c: Credentials) {
        val owner = when (c) {
            is Credentials.WorkOs -> "${c.userId}/${c.orgId}"
            is Credentials.Dev -> "${c.userId}/${c.orgId}"
            is Credentials.Demo -> return
        }
        val marker = File(coreDir, ".owner")
        if (!marker.exists() || marker.readText() != owner) coreDir.deleteRecursively()
        coreDir.mkdirs()
        marker.writeText(owner)
    }














    fun startDev(userId: String, orgId: String) = start(Credentials.Dev(userId, orgId))

    private fun start(credentials: Credentials): Boolean {
        client?.shutdown()
        claimCoreDir(credentials)
        val dir = coreDir
        dir.mkdirs()
        val config = CoreConfig(
            edgeUrl = edgeUrl,
            dataDir = dir.path,
            deviceId = deviceId,
            deviceName = Build.MODEL ?: "Android",
            platform = "android",
            appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0",
        )
        return try {
            client = CoreClient(config, credentials, this)
            notifier.reset()
            main.post { sh.zeron.android.notify.KeepAliveService.sync(context) }
            if (credentials is Credentials.WorkOs) store(credentials)
            refresh()
            client?.preloadSessions()
            true
        } catch (e: Exception) {
            android.util.Log.e("zeron", "core start failed", e)
            client = null
            false
        }
    }

    /** WorkOS code → tokens → org (the first, or the only one) → client. */
    suspend fun signIn(code: String, chooseOrg: suspend (List<AuthOrg>) -> AuthOrg?) {
        val edge = edgeUrl
        val exchange = authExchangeCode(edge, code)
        val orgs = authListOrgs(edge, exchange.tokens.accessToken)
        if (orgs.isEmpty()) throw CoreException.Auth("This account isn't in an organization yet.")
        val org = if (orgs.size == 1) orgs[0] else chooseOrg(orgs) ?: return
        val tokens = authRefresh(edge, exchange.tokens.refreshToken, org.organizationId)
        val name = listOfNotNull(exchange.user.firstName, exchange.user.lastName).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        prefs.edit()
            .putString("accountName", name)
            .putString("accountEmail", exchange.user.email.orEmpty())
            .putString("accountOrg", org.name)
            .apply()
        val ok = start(Credentials.WorkOs(exchange.user.id, org.organizationId, tokens))
        if (!ok) throw CoreException.Auth("Couldn't open your workspace. Try signing in again.")
    }

    val accountName get() = prefs.getString("accountName", "").orEmpty()
    val accountEmail get() = prefs.getString("accountEmail", "").orEmpty()
    val accountOrg get() = prefs.getString("accountOrg", "").orEmpty()

    /** The user signing out: this device forgets the account's local docs. */
    fun signOut(forget: Boolean = true) {
        voice.stop()
        client?.shutdown()
        client = null
        secure.edit().remove("credentials").apply()
        prefs.edit().remove("accountName").remove("accountEmail").remove("accountOrg").apply()
        if (forget) coreDir.deleteRecursively()
        connectivity = null
        notifier.reset()
        workspaceRevision++
        main.post { sh.zeron.android.notify.KeepAliveService.sync(context) }
    }

    fun onForeground() {
        inForeground = true
        if (lockEnabled && backgroundedAt != 0L && System.currentTimeMillis() - backgroundedAt > LOCK_AFTER_MS) locked = true
        client?.onForeground()
        refresh()
    }

    fun onBackground() {
        inForeground = false
        backgroundedAt = System.currentTimeMillis()
        // With alerts on, the background service keeps the connection live; otherwise pause it.
        if (!sh.zeron.android.notify.Notifications.active(context)) client?.onBackground()
    }

    fun refresh() {
        workspaceRevision++
        composerRevision++
    }

    /** Look up a row by chat id from the current workspace. */
    fun row(chatId: String): SessionRow? = client?.sessionRow(chatId)

    // ---- core events ---------------------------------------------------------------

    override fun onEvent(event: ClientEvent) {
        main.post {
            when (event) {
                is ClientEvent.WorkspaceChanged -> {
                    client?.let { notifier.onWorkspace(it, inForeground) }
                    workspaceRevision++
                }
                is ClientEvent.SessionChanged -> composerRevision++
                is ClientEvent.ComposerChanged -> composerRevision++
                is ClientEvent.ConnectivityChanged -> connectivity = event.connectivity
                is ClientEvent.AuthRefreshed -> {
                    val c = stored() as? Credentials.WorkOs
                    if (c != null) store(Credentials.WorkOs(c.userId, c.orgId, event.tokens))
                }
                is ClientEvent.AuthExpired -> signOut(forget = false)
            }
        }
    }
}
