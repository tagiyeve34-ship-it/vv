package com.hesabat.twopersonmessenger

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "TPM-RTC"

/**
 * V6.6
 *  - Every PeerConnection.Observer / SdpObserver callback is wrapped in try/catch.
 *    (A Java exception thrown inside a callback that native WebRTC invoked via JNI
 *    aborts the whole process with SIGABRT inside libjingle_peerconnection_so.so.)
 *  - peer.getReceivers()/getTransceivers() is never called (it disposes wrappers that
 *    were already handed to callbacks -> IllegalStateException on the native thread).
 *  - Remote ICE candidates are buffered until the remote description is applied.
 *  - Duplicate offers/answers are ignored.
 *  - Correct dispose order (tracks -> sources -> factory -> ADM -> EGL).
 */
class WebRtcClient(
    context: Context,
    private val video: Boolean,
    iceDtos: List<IceServerDto>,
    private val onIce: (IceCandidate) -> Unit,
    private val onState: (String) -> Unit
) {
    private val app = context.applicationContext
    private val thread = HandlerThread("TPM-WebRTC").apply { start() }
    private val h = Handler(thread.looper)

    // Everything below is touched only on the `h` thread after init.
    private var egl: EglBase? = null
    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var remoteVideo: VideoTrack? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var remoteApplied = false
    private var remoteReady = false
    private val pendingIce = ArrayList<IceCandidate>()
    @Volatile private var closed = false

    val eglContext: EglBase.Context
        get() = requireNotNull(egl) { "EGL is available only for video calls" }.eglBaseContext

    init {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        h.post {
            try { initRtc(iceDtos) } catch (t: Throwable) { failure = t } finally { latch.countDown() }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            thread.quitSafely()
            throw IllegalStateException("WebRTC init timeout")
        }
        failure?.let { thread.quitSafely(); throw it }
    }

    private fun safe(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.e(TAG, "$what failed", t) }
    }

    private fun initRtc(iceDtos: List<IceServerDto>) {
        WebRtcRuntime.initialize(app)

        adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(e: String?) { Log.e(TAG, "AudioRecord init error: $e") }
                override fun onWebRtcAudioRecordStartError(c: JavaAudioDeviceModule.AudioRecordStartErrorCode?, e: String?) { Log.e(TAG, "AudioRecord start error: $c $e") }
                override fun onWebRtcAudioRecordError(e: String?) { Log.e(TAG, "AudioRecord error: $e") }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(e: String?) { Log.e(TAG, "AudioTrack init error: $e") }
                override fun onWebRtcAudioTrackStartError(c: JavaAudioDeviceModule.AudioTrackStartErrorCode?, e: String?) { Log.e(TAG, "AudioTrack start error: $c $e") }
                override fun onWebRtcAudioTrackError(e: String?) { Log.e(TAG, "AudioTrack error: $e") }
            })
            .createAudioDeviceModule()

        val fb = PeerConnectionFactory.builder().setAudioDeviceModule(adm)
        if (video) {
            egl = EglBase.create()
            fb.setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            fb.setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
        }
        factory = fb.createPeerConnectionFactory()

        val servers = iceDtos.mapNotNull { d ->
            if (d.urls.isEmpty()) null else {
                val b = PeerConnection.IceServer.builder(d.urls)
                // Only set credentials when they exist (empty strings on stun: are pointless).
                if (!d.username.isNullOrEmpty()) b.setUsername(d.username)
                if (!d.credential.isNullOrEmpty()) b.setPassword(d.credential)
                b.createIceServer()
            }
        }.ifEmpty {
            listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
        }

        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peer = factory!!.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) { safe("onIceCandidate") { onIce(c) } }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) { safe("onConnectionChange") { onState(s.name) } }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { safe("onIceConnectionChange") { onState(s.name) } }
            override fun onTrack(t: RtpTransceiver) {
                safe("onTrack") {
                    val tr = t.receiver.track()
                    if (tr is VideoTrack) h.post { safe("setRemoteVideo") { setRemoteVideo(tr) } }
                }
            }
            override fun onAddTrack(r: RtpReceiver, streams: Array<out MediaStream>) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(v: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: error("PeerConnection yaradıla bilmədi")

        audioSource = factory!!.createAudioSource(MediaConstraints())
        audioTrack = factory!!.createAudioTrack("audio0", audioSource).apply { setEnabled(true) }
        peer!!.addTrack(audioTrack, listOf("stream0"))
        if (video) createVideoTrack()
    }

    private fun createVideoTrack() {
        val e = Camera2Enumerator(app)
        val name = e.deviceNames.firstOrNull { e.isFrontFacing(it) } ?: e.deviceNames.firstOrNull() ?: return
        capturer = e.createCapturer(name, null) ?: return
        videoSource = factory!!.createVideoSource(false)
        surfaceHelper = SurfaceTextureHelper.create("TPM-Capture", eglContext)
        capturer!!.initialize(surfaceHelper, app, videoSource!!.capturerObserver)
        capturer!!.startCapture(640, 480, 24)
        videoTrack = factory!!.createVideoTrack("video0", videoSource).apply { setEnabled(true) }
        peer!!.addTrack(videoTrack, listOf("stream0"))
    }

    private fun setRemoteVideo(t: VideoTrack) {
        remoteVideo = t
        remoteRenderer?.let { t.addSink(it) }
    }

    private fun flushIce() {
        remoteReady = true
        val p = peer ?: return
        pendingIce.forEach { safe("addIceCandidate") { p.addIceCandidate(it) } }
        pendingIce.clear()
    }

    fun attachLocal(r: SurfaceViewRenderer) {
        r.init(eglContext, null); r.setMirror(true); r.setEnableHardwareScaler(true)
        h.post { localRenderer = r; safe("attachLocal") { videoTrack?.addSink(r) } }
    }

    fun attachRemote(r: SurfaceViewRenderer) {
        r.init(eglContext, null); r.setMirror(false); r.setEnableHardwareScaler(true)
        h.post { remoteRenderer = r; safe("attachRemote") { remoteVideo?.addSink(r) } }
    }

    fun createOffer(cb: (SessionDescription) -> Unit) {
        h.post {
            val p = peer ?: return@post
            safe("createOffer") {
                p.createOffer(object : SimpleSdpObserver("createOffer") {
                    override fun onCreateSuccess(s: SessionDescription) {
                        safe("offer.setLocal") {
                            p.setLocalDescription(object : SimpleSdpObserver("offer.setLocal") {
                                override fun onSetSuccess() { safe("offer.cb") { cb(s) } }
                            }, s)
                        }
                    }
                }, MediaConstraints())
            }
        }
    }

    fun createAnswer(cb: (SessionDescription) -> Unit) {
        h.post {
            val p = peer ?: return@post
            safe("createAnswer") {
                p.createAnswer(object : SimpleSdpObserver("createAnswer") {
                    override fun onCreateSuccess(s: SessionDescription) {
                        safe("answer.setLocal") {
                            p.setLocalDescription(object : SimpleSdpObserver("answer.setLocal") {
                                override fun onSetSuccess() { safe("answer.cb") { cb(s) } }
                            }, s)
                        }
                    }
                }, MediaConstraints())
            }
        }
    }

    fun setRemote(type: String, sdp: String, done: () -> Unit = {}) {
        h.post {
            val p = peer ?: return@post
            if (remoteApplied) { Log.w(TAG, "duplicate remote $type ignored"); return@post }
            remoteApplied = true
            val t = if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
            safe("setRemote") {
                p.setRemoteDescription(object : SimpleSdpObserver("setRemote") {
                    override fun onSetSuccess() {
                        h.post { flushIce() }
                        safe("setRemote.done") { done() }
                    }
                }, SessionDescription(t, sdp))
            }
        }
    }

    fun addIce(mid: String?, index: Int, sdp: String) {
        h.post {
            val p = peer ?: return@post
            val c = IceCandidate(mid, index, sdp)
            if (remoteReady) safe("addIce") { p.addIceCandidate(c) } else pendingIce.add(c)
        }
    }

    fun mute(m: Boolean) { h.post { safe("mute") { audioTrack?.setEnabled(!m) } } }
    fun camera(e: Boolean) { h.post { safe("camera") { videoTrack?.setEnabled(e) } } }
    fun switchCamera() { h.post { safe("switchCamera") { (capturer as? CameraVideoCapturer)?.switchCamera(null) } } }

    fun close() {
        if (closed) return
        closed = true
        h.post {
            safe("stopCapture") { capturer?.stopCapture() }
            safe("removeSinks") {
                localRenderer?.let { videoTrack?.removeSink(it) }
                remoteRenderer?.let { remoteVideo?.removeSink(it) }
            }
            remoteVideo = null; localRenderer = null; remoteRenderer = null
            safe("peer.close") { peer?.close() }
            safe("peer.dispose") { peer?.dispose() }; peer = null
            safe("audioTrack") { audioTrack?.dispose() }; audioTrack = null
            safe("videoTrack") { videoTrack?.dispose() }; videoTrack = null
            safe("capturer") { capturer?.dispose() }; capturer = null
            safe("videoSource") { videoSource?.dispose() }; videoSource = null
            safe("surfaceHelper") { surfaceHelper?.dispose() }; surfaceHelper = null
            safe("audioSource") { audioSource?.dispose() }; audioSource = null
            safe("factory") { factory?.dispose() }; factory = null
            safe("adm") { adm?.release() }; adm = null
            safe("egl") { egl?.release() }; egl = null
            thread.quitSafely()
        }
    }
}

open class SimpleSdpObserver(private val tag: String = "sdp") : SdpObserver {
    override fun onCreateSuccess(s: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) { Log.e(TAG, "$tag create failure: $e") }
    override fun onSetFailure(e: String?) { Log.e(TAG, "$tag set failure: $e") }
}
