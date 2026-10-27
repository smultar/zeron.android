package sh.zeron.android.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The phone's audio endpoint of a host voice call. Credentials and tools stay
 * on the execution host; this only carries audio. The ordered `oai-events`
 * channel and the gathered (non-trickle) offer mirror the host's pinned helper
 * and the iOS endpoint.
 *
 * Audio behaves like a phone call: the loudspeaker while the phone is in hand,
 * the earpiece while it is held to the ear, and a headset or car whenever one
 * is connected. A call survives the screen locking (the call service holds
 * it); an interruption such as an incoming call ends it.
 */
class CodexVoicePeer(context: Context) {
    class Failure(val kind: Kind) : Exception(kind.name) {
        enum class Kind { CLOSED, UNAVAILABLE, PERMISSION_DENIED, TIMEOUT }
    }

    private val context = context.applicationContext
    private val audioManager = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null

    @Volatile private var closed = false
    private var active = false
    private var nearEar = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false

    /** The connection or audio was lost: the controller ends the call. */
    var onFailure: (() -> Unit)? = null

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = applyRoute()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = applyRoute()
    }

    suspend fun prepare() {
        if (closed) throw Failure(Failure.Kind.CLOSED)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw Failure(Failure.Kind.PERMISSION_DENIED)
        }
        if (factory == null) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            val module = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(p0: String?) = fail()
                    override fun onWebRtcAudioRecordStartError(p0: JavaAudioDeviceModule.AudioRecordStartErrorCode?, p1: String?) = fail()
                    override fun onWebRtcAudioRecordError(p0: String?) = fail()
                })
                .createAudioDeviceModule()
            // No capture until the host confirms the call.
            module.setMicrophoneMute(true)
            adm = module
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        }
        val factory = factory ?: throw Failure(Failure.Kind.UNAVAILABLE)

        // A call-style audio session: voice-communication mode with focus, so other audio ducks or pauses.
        previousMode = audioManager.mode
        previousSpeaker = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // A phone call, alarm or another app taking the audio ends ours: end, never resume.
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) fail()
            }
            .build()
        focus = request
        audioManager.requestAudioFocus(request)
        audioManager.registerAudioDeviceCallback(devices, null)

        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
        val connection = factory.createPeerConnection(config, observer) ?: throw Failure(Failure.Kind.UNAVAILABLE)
        peer = connection
        val audioSource = factory.createAudioSource(MediaConstraints())
        source = audioSource
        val audioTrack = factory.createAudioTrack("voice-audio", audioSource)
        audioTrack.setEnabled(false)
        track = audioTrack
        connection.addTrack(audioTrack, listOf("voice"))
        channel = connection.createDataChannel("oai-events", DataChannel.Init().apply { ordered = true })
            ?: throw Failure(Failure.Kind.UNAVAILABLE)
        channel?.registerObserver(channelObserver)
    }

    /** The gathered offer SDP (no trickle ICE), at most 64 KiB. */
    suspend fun offer(): String {
        val peer = peer ?: throw Failure(Failure.Kind.CLOSED)
        if (closed) throw Failure(Failure.Kind.CLOSED)
        val constraints = MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")) }
        val offer = suspendCancellableCoroutine<SessionDescription> { cont ->
            peer.createOffer(object : SdpAdapter() {
                override fun onCreateSuccess(description: SessionDescription) = cont.resume(description)
                override fun onCreateFailure(error: String?) = cont.resumeWithException(Failure(Failure.Kind.UNAVAILABLE))
            }, constraints)
        }
        suspendCancellableCoroutine<Unit> { cont ->
            peer.setLocalDescription(object : SdpAdapter() {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) = cont.resumeWithException(Failure(Failure.Kind.UNAVAILABLE))
            }, offer)
        }
        waitUntil { peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE }
        val sdp = peer.localDescription?.description ?: throw Failure(Failure.Kind.UNAVAILABLE)
        if (sdp.toByteArray().size > 65_536) throw Failure(Failure.Kind.UNAVAILABLE)
        return sdp
    }

    suspend fun apply(answer: String) {
        val peer = peer ?: throw Failure(Failure.Kind.CLOSED)
        if (closed || answer.isEmpty() || answer.toByteArray().size > 65_536) throw Failure(Failure.Kind.CLOSED)
        suspendCancellableCoroutine<Unit> { cont ->
            peer.setRemoteDescription(object : SdpAdapter() {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) = cont.resumeWithException(Failure(Failure.Kind.UNAVAILABLE))
            }, SessionDescription(SessionDescription.Type.ANSWER, answer))
        }
        waitUntil { peer.connectionState() == PeerConnection.PeerConnectionState.CONNECTED && channel?.state() == DataChannel.State.OPEN }
    }

    /** Called only after the host confirms the current generation. */
    fun setMuted(muted: Boolean) {
        if (closed || peer?.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) throw Failure(Failure.Kind.CLOSED)
        if (!active) {
            active = true
            applyRoute()
        }
        adm?.setMicrophoneMute(muted)
        track?.setEnabled(!muted)
    }

    /** The proximity sensor: held to the ear, play through the earpiece. */
    fun setNearEar(near: Boolean) {
        if (nearEar == near) return
        nearEar = near
        applyRoute()
    }

    /** Loudspeaker in hand, earpiece at the ear; a headset, Bluetooth or car route always wins. */
    private fun applyRoute() {
        if (!active || closed) return
        val external = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        audioManager.isSpeakerphoneOn = !external && !nearEar
    }

    /** Normalized microphone and speaker peaks as u16 (0 while backgrounded is the caller's call). */
    suspend fun levels(): Pair<Int, Int> {
        val peer = peer ?: throw Failure(Failure.Kind.CLOSED)
        if (closed || peer.connectionState() == PeerConnection.PeerConnectionState.FAILED || peer.connectionState() == PeerConnection.PeerConnectionState.CLOSED) {
            throw Failure(Failure.Kind.CLOSED)
        }
        val report = suspendCancellableCoroutine<org.webrtc.RTCStatsReport> { cont -> peer.getStats { cont.resume(it) } }
        var microphone = 0.0
        var speaker = 0.0
        for (stat in report.statsMap.values) {
            val level = (stat.members["audioLevel"] as? Number)?.toDouble() ?: continue
            if (stat.type == "media-source") microphone = maxOf(microphone, level)
            if (stat.type == "inbound-rtp") speaker = maxOf(speaker, level)
        }
        return (microphone.coerceIn(0.0, 1.0) * 65535).toInt() to (speaker.coerceIn(0.0, 1.0) * 65535).toInt()
    }

    fun close() {
        if (closed) return
        closed = true
        try { track?.setEnabled(false) } catch (_: Exception) {}
        try { adm?.setMicrophoneMute(true) } catch (_: Exception) {}
        try { channel?.unregisterObserver(); channel?.close() } catch (_: Exception) {}
        try { peer?.close() } catch (_: Exception) {}
        try { channel?.dispose(); track?.dispose(); source?.dispose(); peer?.dispose() } catch (_: Exception) {}
        channel = null; track = null; source = null; peer = null
        try { audioManager.unregisterAudioDeviceCallback(devices) } catch (_: Exception) {}
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
        audioManager.isSpeakerphoneOn = previousSpeaker
        audioManager.mode = previousMode
        try { factory?.dispose(); adm?.release() } catch (_: Exception) {}
        factory = null; adm = null
        active = false
    }

    private fun fail() {
        if (closed) return
        close()
        onFailure?.invoke()
    }

    private suspend fun waitUntil(ready: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 25_000
        while (!ready()) {
            if (closed) throw Failure(Failure.Kind.CLOSED)
            if (System.currentTimeMillis() > deadline) throw Failure(Failure.Kind.TIMEOUT)
            delay(20)
        }
        if (closed) throw Failure(Failure.Kind.CLOSED)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED ||
                state == PeerConnection.IceConnectionState.DISCONNECTED
            ) fail()
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dataChannel: DataChannel?) { dataChannel?.close() }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
    }

    private val channelObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}
        override fun onStateChange() {
            val s = channel?.state()
            if (s == DataChannel.State.CLOSED || s == DataChannel.State.CLOSING) fail()
        }
        // The host owns canonical transcripts and handoffs. Do not persist a second copy.
        override fun onMessage(buffer: DataChannel.Buffer?) {}
    }
}

/** An [SdpObserver] with every callback optional. */
open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
