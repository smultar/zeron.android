package sh.zeron.android.voice

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import sh.zeron.android.R
import sh.zeron.android.app.AppModel
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.VoiceCall
import uniffi.zeron_core.VoiceCallPhase
import uniffi.zeron_core.VoiceCallState
import uniffi.zeron_core.VoiceCallWork
import uniffi.zeron_core.VoiceEndReason
import uniffi.zeron_core.VoiceMediaFailure
import uniffi.zeron_core.VoiceMediaListener
import uniffi.zeron_core.VoiceMediaOperation
import uniffi.zeron_core.VoiceMediaRequest
import uniffi.zeron_core.VoiceOrb
import uniffi.zeron_core.VoiceSessionListener
import uniffi.zeron_core.VoiceSpeaker
import uniffi.zeron_core.defaultVoiceStyles
import uniffi.zeron_core.voiceHostCapability

/**
 * The app-wide voice orchestrator call. Audio runs on this phone; Codex, its
 * credentials and the orchestrator chat run on a registered execution host
 * (any device advertising `voice-client-media-v1`). One call at a time, no
 * automatic reconnect: a dropped call explains itself and waits for a tap.
 * Main-thread only; core callbacks hop here.
 */
class VoiceController(private val context: Context, private val app: AppModel) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("zeron", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var call: VoiceCall? = null
    private var media: NativeVoiceMedia? = null
    private var generation = 0L
    private var proximity: SensorEventListener? = null

    var state by mutableStateOf<VoiceCallState?>(null)
        private set
    /** Why the last call ended on its own; cleared by the next start. */
    var endReason by mutableStateOf<VoiceEndReason?>(null)
        private set
    var muted by mutableStateOf(false)
        private set
    var hostName by mutableStateOf("")
        private set
    var activeSinceMs by mutableStateOf<Long?>(null)
        private set
    /** Styles the last host offered. */
    var styles by mutableStateOf(defaultVoiceStyles())
        private set
    /** The full-screen call stage is showing (a live call can be minimized to a bar). */
    var showStage by mutableStateOf(false)

    private var previewing = false
    private var previewJob: Job? = null

    /** Set by the activity: asks for the microphone permission, then reports the answer. */
    var requestMicrophone: ((onResult: (Boolean) -> Unit) -> Unit)? = null

    val live: Boolean get() = call != null || previewing
    val active: Boolean get() = state?.phase == VoiceCallPhase.ACTIVE
    val orb: VoiceOrb get() = state?.orb ?: if (live) VoiceOrb.CONNECTING else VoiceOrb.IDLE

    // ---- hosts and preferences ------------------------------------------------

    private val capability = voiceHostCapability()
    private fun compatible(d: DeviceView) = d.isExecutionHost && capability in d.capabilities

    /** Registered hosts that can take a call right now. */
    val hosts: List<DeviceView> get() = app.client?.devices()?.filter { it.online && compatible(it) }.orEmpty()

    /** Voice controls appear once any registered host can run them. */
    val available: Boolean get() = app.client?.devices()?.any(::compatible) == true

    private val preferenceKey get() = "remoteVoice.${app.client?.userId() ?: "none"}.${app.client?.orgId() ?: "none"}"

    var selectedHost: String?
        get() = prefs.getString("$preferenceKey.host", null)
        set(v) { if (!live) prefs.edit().putString("$preferenceKey.host", v).apply() }
    var selectedStyle: String?
        get() = prefs.getString("$preferenceKey.style", null)
        set(v) { prefs.edit().putString("$preferenceKey.style", v).apply() }

    /** The host a plain tap calls: the chosen one, or the only one online. */
    val startHost: DeviceView?
        get() {
            val list = hosts
            selectedHost?.let { id -> list.firstOrNull { it.id == id }?.let { return it } }
            return if (list.size == 1) list.first() else null
        }

    // ---- call ----------------------------------------------------------------------

    /** Place a call to `host` (remembered for next time) or the default host. False when there is none to call. */
    fun start(host: DeviceView? = null): Boolean {
        if (live) return false
        val device = host ?: startHost ?: return false
        val client = app.client ?: return false
        if (host != null) selectedHost = device.id
        // The permission prompt needs the activity; ask first, place the call on the answer.
        val ask = requestMicrophone
        if (!sh.zeron.android.voice.hasMicrophone(context) && ask != null) {
            ask { granted ->
                if (granted) place(client, device) else fail(device, VoiceEndReason.MICROPHONE_DENIED)
            }
            return true
        }
        place(client, device)
        return true
    }

    private fun fail(device: DeviceView, reason: VoiceEndReason) {
        hostName = device.name
        endReason = reason
        showStage = true
    }

    private fun place(client: uniffi.zeron_core.CoreClient, device: DeviceView) {
        generation++
        state = null; endReason = null; muted = false; activeSinceMs = null
        hostName = device.name
        val media = NativeVoiceMedia(context, scope) { main.post { finish(VoiceEndReason.AUDIO_UNAVAILABLE) } }
        this.media = media
        val mine = generation
        val listener = object : VoiceSessionListener {
            override fun onVoiceState(state: VoiceCallState) { main.post { receive(state, mine) } }
            override fun onVoiceClosed(reason: VoiceEndReason?) { main.post { closed(reason, mine) } }
        }
        val voiceCall = client.startVoice(device.id, selectedStyle, media, listener)
        call = voiceCall
        media.call = voiceCall
        showStage = true
        VoiceCallService.start(context, device.name)
        buzz(30)
        playSound(R.raw.voice_start)
    }

    fun toggleMute() {
        if (!live) return
        muted = !muted
        media?.muteLocally(muted)
        call?.setMuted(muted)
        buzz(15)
    }

    /** The user hangs up. Local capture stops before any remote control. */
    fun stop() {
        if (!live) return
        buzz(40)
        finish(null)
    }

    fun dismissEndReason() {
        if (endReason == null) return
        endReason = null
        showStage = false
    }

    private fun finish(reason: VoiceEndReason?) {
        previewing = false
        previewJob?.cancel()
        generation++
        // A hang-up or a connected call ending sounds; a failure to connect only explains itself.
        val sounds = call != null && (activeSinceMs != null || reason == null)
        media?.close()
        call?.stop()
        if (sounds) playSound(R.raw.voice_end)
        call = null; media = null; state = null; activeSinceMs = null
        endReason = reason
        if (reason == null) showStage = false
        setProximity(false)
        VoiceCallService.stop(context)
    }

    private fun receive(next: VoiceCallState, mine: Long) {
        if (mine != generation || !live) return
        val becameActive = next.phase == VoiceCallPhase.ACTIVE && state?.phase != VoiceCallPhase.ACTIVE
        state = next
        if (next.voices.isNotEmpty()) styles = next.voices
        if (becameActive) {
            activeSinceMs = System.currentTimeMillis()
            setProximity(true)
            buzz(25)
        }
    }

    private fun closed(reason: VoiceEndReason?, mine: Long) {
        if (mine != generation || !live) return
        finish(reason)
    }

    /** Held to the ear audio moves to the earpiece; in hand it plays on the loudspeaker. */
    private fun setProximity(on: Boolean) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (on && proximity == null) {
            val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) { media?.setNearEar(e.values[0] < sensor.maximumRange.coerceAtMost(5f)) }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            proximity = l
            sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else if (!on) {
            proximity?.let { sm.unregisterListener(it) }
            proximity = null
        }
    }

    private fun buzz(ms: Long) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun playSound(res: Int) {
        try {
            MediaPlayer.create(context, res)?.apply { setOnCompletionListener { it.release() }; start() }
        } catch (_: Exception) {
        }
    }

    // ---- presentation ----------------------------------------------------------------

    /** `m:ss`, or `h:mm:ss` past the hour (desktop `format_elapsed`). */
    fun elapsedText(now: Long): String? {
        val since = activeSinceMs ?: return null
        val s = maxOf(0L, (now - since) / 1000)
        val (h, m, sec) = Triple(s / 3600, s / 60 % 60, s % 60)
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** One line for what the call is doing now. */
    val statusText: String
        get() {
            val s = state ?: return if (live) "Calling $hostName…" else "Codex voice"
            if (s.phase == VoiceCallPhase.CONNECTING) return "Connecting to $hostName…"
            if (s.phase == VoiceCallPhase.ENDING) return "Ending…"
            return when (s.orb) {
                VoiceOrb.SPEAKING -> "Speaking"
                VoiceOrb.AWAITING_INPUT -> "Codex needs your answer"
                VoiceOrb.WORKING -> "Working on it…"
                VoiceOrb.MUTED -> "Muted"
                else -> "Listening"
            }
        }

    /** The host's own refusal, so "update Zeron" isn't the answer to everything. */
    private fun incompatible(host: String): String {
        val why = uniffi.zeron_core.voiceLastRejection()
        android.util.Log.w("zeron-voice", "host refused voice: $why")
        return when (why) {
            "NativeRuntimeUnavailable" ->
                "Codex on $host has no voice runtime. Install or update Codex there with its official installer (npm installs lack it), then restart Zeron on $host."
            "Disabled" -> "Remote voice is turned off on $host. Enable it in Zeron's voice settings there."
            "WrongHarness" -> "Voice needs Codex on $host."
            "AudioFormatUnverified", "DuplexUnverified" -> "$host's Codex voice format isn't verified for phone calls yet. Update Codex and Zeron there."
            "Unsupported" -> "$host couldn't start its voice session (code: Unsupported). Restart Zeron on $host and try again."
            else -> "Update Zeron on $host and enable remote voice there." + (why?.let { " ($it)" } ?: "")
        }
    }

    fun message(reason: VoiceEndReason): String {
        val host = hostName.ifEmpty { "the selected device" }
        return when (reason) {
            VoiceEndReason.MICROPHONE_DENIED -> "Zeron needs microphone access to talk with Codex."
            VoiceEndReason.AUDIO_UNAVAILABLE -> "The audio connection closed. Start a new call to reconnect."
            VoiceEndReason.SIGN_IN_REQUIRED -> "Sign in to Codex with ChatGPT on $host."
            VoiceEndReason.USAGE_UNAVAILABLE -> "Codex usage is currently unavailable on $host."
            VoiceEndReason.BUSY -> "$host is already on a voice call."
            VoiceEndReason.HOST_UNAVAILABLE -> "$host isn't reachable right now."
            VoiceEndReason.HOST_INCOMPATIBLE -> incompatible(host)
            VoiceEndReason.CONNECTION_LOST -> "The call dropped. Start a new one to reconnect."
        }
    }

    /** Debug: a scripted call (no audio, host or relay) for UI work: the orb walks through its states. */
    fun preview() {
        if (live) return
        previewing = true
        hostName = "Fedora"
        activeSinceMs = System.currentTimeMillis() - 83_000
        showStage = true
        val script = listOf(
            Triple(VoiceOrb.LISTENING, VoiceCallWork.IDLE, "Open a chat on the Mac and ask Claude to fix the failing sync test" to VoiceSpeaker.USER),
            Triple(VoiceOrb.SPEAKING, VoiceCallWork.IDLE, "On it. I'm starting a Claude session in the comet project to look at the sync test." to null),
            Triple(VoiceOrb.WORKING, VoiceCallWork.WORKING, "On it. I'm starting a Claude session in the comet project to look at the sync test." to VoiceSpeaker.ASSISTANT),
            Triple(VoiceOrb.AWAITING_INPUT, VoiceCallWork.AWAITING_INPUT, "Claude wants to know whether to update the fixture or the parser." to VoiceSpeaker.ASSISTANT),
        )
        previewJob = scope.launch {
            var step = 0
            while (true) {
                val turn = (step / 40) % script.size
                val (orb, work, cap) = script[turn]
                val wave = kotlin.math.abs(kotlin.math.sin(step / 2.3)).toFloat()
                val words = cap.first.split(" ")
                val streamed = words.take(1 + (step % 40) / 3).joinToString(" ")
                state = VoiceCallState(
                    VoiceCallPhase.ACTIVE, orb, "voice-orchestrator-preview", work, muted, orb == VoiceOrb.SPEAKING, streamed, cap.second,
                    "preview-$turn", if (orb == VoiceOrb.LISTENING) wave else 0f, if (orb == VoiceOrb.SPEAKING) wave else 0f, emptyList(),
                )
                step++
                kotlinx.coroutines.delay(100)
            }
        }
    }

    fun shutdown() {
        finish(null)
        scope.cancel()
    }
}

fun hasMicrophone(context: Context) =
    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * A single-generation platform bridge between the Rust call's media requests
 * and the WebRTC endpoint. The call holds this object; releasing it closes native audio.
 */
class NativeVoiceMedia(
    context: Context,
    private val scope: CoroutineScope,
    private val onFailure: () -> Unit,
) : VoiceMediaListener {
    @Volatile var call: VoiceCall? = null
    private val peer = CodexVoicePeer(context)
    private val operations = HashMap<ULong, Job>()
    @Volatile private var closed = false
    private var activated = false

    init {
        peer.onFailure = {
            close()
            call?.stop()
            onFailure()
        }
    }

    override fun onRequest(request: VoiceMediaRequest) {
        scope.launch { perform(request) }
    }

    private fun perform(request: VoiceMediaRequest) {
        if (request.operation == VoiceMediaOperation.CLOSE) { close(); return }
        if (closed) {
            call?.completeMedia(request.requestId, VoiceMediaFailure.UNAVAILABLE, null, 0u, 0u)
            return
        }
        if (operations.size >= 4) { close(); call?.stop(); return }
        operations[request.requestId] = scope.launch {
            try {
                var sdp: String? = null
                var microphone = 0
                var speaker = 0
                when (request.operation) {
                    VoiceMediaOperation.PREPARE -> peer.prepare()
                    VoiceMediaOperation.OFFER -> sdp = peer.offer()
                    VoiceMediaOperation.APPLY_ANSWER -> peer.apply(request.sdp ?: throw CodexVoicePeer.Failure(CodexVoicePeer.Failure.Kind.UNAVAILABLE))
                    VoiceMediaOperation.SET_MUTED -> { peer.setMuted(request.muted); activated = true }
                    VoiceMediaOperation.LEVELS -> peer.levels().let { microphone = it.first; speaker = it.second }
                    VoiceMediaOperation.CLOSE -> close()
                }
                if (closed) return@launch
                call?.completeMedia(request.requestId, null, sdp, microphone.toUShort(), speaker.toUShort())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (closed) return@launch
                val denied = (e as? CodexVoicePeer.Failure)?.kind == CodexVoicePeer.Failure.Kind.PERMISSION_DENIED
                call?.completeMedia(request.requestId, if (denied) VoiceMediaFailure.PERMISSION_DENIED else VoiceMediaFailure.UNAVAILABLE, null, 0u, 0u)
            } finally {
                operations.remove(request.requestId)
            }
        }
    }

    /** UI mute never waits for the Rust control queue or the relay. */
    fun muteLocally(muted: Boolean) {
        if (closed || !activated) return
        try { peer.setMuted(muted) } catch (_: Exception) {}
    }

    fun setNearEar(near: Boolean) {
        if (!closed) peer.setNearEar(near)
    }

    fun close() {
        if (closed) return
        closed = true
        peer.close()
        for (job in operations.values) job.cancel()
        operations.clear()
    }
}
