package com.hesabat.twopersonmessenger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.PopupMenu
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.hesabat.twopersonmessenger.databinding.ActivityCallBinding
import org.webrtc.IceCandidate

class CallActivity : AppCompatActivity() {
    private lateinit var b: ActivityCallBinding
    private val h = Handler(Looper.getMainLooper())
    private var rtc: WebRtcClient? = null
    @Volatile private var callUuid = ""
    private var incoming = false
    private var video = false
    @Volatile private var lastSignal = 0L
    @Volatile private var polling = false
    @Volatile private var destroyed = false
    private val handledSignals = HashSet<Long>()
    private var muted = false
    private var speaker = false
    private var cameraOn = true
    private var started = false
    private var connected = false
    private var accepted = false
    private var callStartedAt = System.currentTimeMillis()
    private var connectedAt = 0L
    private var historySaved = false
    private var oldAudioMode = AudioManager.MODE_NORMAL
    private val poll = object : Runnable { override fun run() { pollSignals(); h.postDelayed(this, 700) } }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { v -> v }) boot() else { Toast.makeText(this, "Mikrofon/kamera icazəsi lazımdır", Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityCallBinding.inflate(layoutInflater); setContentView(b.root)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        incoming = intent.getBooleanExtra("incoming", false); video = intent.getBooleanExtra("video", false); callUuid = intent.getStringExtra("call_uuid") ?: ""
        b.callType.text = if (video) "Video zəng" else "Səsli zəng"; b.videoContainer.visibility = if (video) View.VISIBLE else View.GONE
        b.acceptBtn.visibility = if (incoming) View.VISIBLE else View.GONE; b.rejectBtn.visibility = if (incoming) View.VISIBLE else View.GONE
        b.controls.visibility = if (incoming) View.GONE else View.VISIBLE
        b.acceptBtn.setOnClickListener { accepted=true; stopVibration(); b.acceptBtn.visibility = View.GONE; b.rejectBtn.visibility = View.GONE; b.controls.visibility = View.VISIBLE; acceptIncoming() }
        b.rejectBtn.setOnClickListener { saveHistory("rejected"); stopVibration(); signal("reject", null); finish() }
        b.endBtn.setOnClickListener { saveHistory(if(connected) "completed" else if(incoming) "missed" else "unanswered"); signal("hangup", null); finish() }
        b.micBtn.setOnClickListener { muted = !muted; rtc?.mute(muted); b.micBtn.text = if (muted) "Mikrofon aç" else "Mikrofon" }
        b.speakerBtn.setOnClickListener { showAudioRoutes() }
        b.cameraBtn.setOnClickListener { cameraOn = !cameraOn; rtc?.camera(cameraOn); b.cameraBtn.text = if (cameraOn) "Kamera" else "Kamera aç" }
        b.switchBtn.setOnClickListener { rtc?.switchCamera() }
        if(incoming) startVibration()
        ensurePermissions()
    }

    private fun ensurePermissions() {
        val ps = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (video) ps += Manifest.permission.CAMERA
        val miss = ps.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isEmpty()) boot() else permissionLauncher.launch(miss.toTypedArray())
    }

    private fun boot() {
        if (started) return
        started = true
        Api.get("ice_config.php", Session.token(this)) { ok, raw ->
            val ice = if (ok) runCatching { Gson().fromJson(raw, IceConfigResponse::class.java).iceServers }.getOrNull().orEmpty() else emptyList()
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    oldAudioMode = am.mode
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    WebRtcRuntime.initialize(this)
                    rtc = WebRtcClient(this, video, ice, { sendIce(it) }, { st -> runOnUiThread { showState(st) } })
                    if (video) { rtc?.attachLocal(b.localVideo); rtc?.attachRemote(b.remoteVideo) }
                    h.post(poll)
                    if (!incoming) startOutgoing() else b.callStatus.text = "Gələn zəng"
                } catch (e: Throwable) {
                    Log.e("TPM-RTC", "call boot failed", e)
                    b.callStatus.text = "Zəng modulu başladıla bilmədi"
                    Toast.makeText(this, "Zəng modulu başladıla bilmədi", Toast.LENGTH_LONG).show()
                    h.postDelayed({ finish() }, 1200)
                }
            }
        }
    }

    private fun showState(st: String) {
        if (destroyed) return
        b.callStatus.text = when (st) {
            "CONNECTED", "COMPLETED" -> { if(!connected){ connected=true; connectedAt=System.currentTimeMillis(); stopVibration() }; "Qoşuldu" }
            "DISCONNECTED" -> "Bağlantı kəsildi"
            "FAILED" -> "Bağlantı alınmadı"
            "NEW", "CHECKING", "CONNECTING" -> "Qoşulur…"
            "CLOSED" -> b.callStatus.text
            else -> st
        }
    }

    private fun startOutgoing() {
        if (callUuid.isBlank()) {
            Api.post("call_start.php", mapOf("call_type" to if (video) "video" else "audio"), Session.token(this)) { ok, raw ->
                val r = if (ok) runCatching { Gson().fromJson(raw, CallStartResponse::class.java) }.getOrNull() else null
                if (r != null && r.call_uuid.isNotBlank()) {
                    callUuid = r.call_uuid
                    runOnUiThread { b.callStatus.text = "Zəng gedir…" }
                    createOffer()
                } else runOnUiThread { Toast.makeText(this, "Zəng başladıla bilmədi", Toast.LENGTH_SHORT).show(); finish() }
            }
        } else createOffer()
    }

    private fun createOffer() { rtc?.createOffer { signal("offer", mapOf("sdp" to it.description)) } }
    private fun acceptIncoming() { b.callStatus.text = "Qoşulur…" /* offer poll tərəfindən qəbul ediləcək */ }
    private fun sendIce(c: IceCandidate) { if (callUuid.isNotBlank()) signal("ice", mapOf("sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "candidate" to c.sdp)) }
    private fun signal(type: String, payload: Any?) {
        if (callUuid.isBlank()) return
        Api.post("call_signal.php", mapOf("call_uuid" to callUuid, "signal_type" to type, "payload" to payload), Session.token(this)) { _, _ -> }
    }

    private fun pollSignals() {
        if (callUuid.isBlank() || polling || destroyed) return
        polling = true
        Api.get("call_poll.php?after_id=$lastSignal", Session.token(this)) { ok, raw ->
            try {
                if (!ok) return@get
                val r = Gson().fromJson(raw, SignalResponse::class.java) ?: return@get
                val list = (r.signals as List<CallSignal>?) ?: return@get
                list.filter { it.call_uuid == callUuid }.sortedBy { it.id }.forEach { s ->
                    lastSignal = maxOf(lastSignal, s.id)
                    if (handledSignals.add(s.id)) {
                        try { handleSignal(s) } catch (t: Throwable) { Log.e("TPM-RTC", "signal ${s.signal_type} failed", t) }
                    }
                }
            } catch (t: Throwable) {
                Log.e("TPM-RTC", "poll failed", t)
            } finally {
                polling = false
            }
        }
    }

    private fun handleSignal(s: CallSignal) {
        val m = s.payload as? Map<*, *>
        when (s.signal_type) {
            "offer" -> {
                if (!incoming) return
                val sdp = m?.get("sdp")?.toString() ?: return
                rtc?.setRemote("offer", sdp) {
                    rtc?.createAnswer { a ->
                        signal("answer", mapOf("sdp" to a.description))
                        runOnUiThread { if (!destroyed) b.callStatus.text = "Qoşulur…" }
                    }
                }
            }
            "answer" -> {
                if (incoming) return
                val sdp = m?.get("sdp")?.toString() ?: return
                rtc?.setRemote("answer", sdp) { runOnUiThread { if (!destroyed) b.callStatus.text = "Qoşulur…" } }
            }
            "ice" -> {
                val cand = m?.get("candidate")?.toString() ?: return
                val mid = m?.get("sdpMid")?.toString()
                val idx = (m?.get("sdpMLineIndex") as? Number)?.toInt() ?: 0
                rtc?.addIce(mid, idx, cand)
            }
            "hangup", "reject" -> runOnUiThread {
                if (!connected && incoming && s.signal_type == "hangup") saveHistory("missed") else if(s.signal_type=="reject") saveHistory("rejected") else saveHistory(if(connected) "completed" else "unanswered")
                if (destroyed) return@runOnUiThread
                b.callStatus.text = if (s.signal_type == "reject") "Zəng rədd edildi" else "Zəng bitdi"
                h.postDelayed({ finish() }, 500)
            }
        }
    }

    private fun saveHistory(status:String) {
        if(historySaved) return
        historySaved=true
        val duration=if(connectedAt>0) ((System.currentTimeMillis()-connectedAt)/1000).coerceAtLeast(0) else 0
        CallHistory.add(this, CallLogItem(callUuid=callUuid,direction=if(incoming) "incoming" else "outgoing",type=if(video) "video" else "audio",status=status,startedAt=callStartedAt,durationSec=duration))
    }

    private fun startVibration(){
        if(!getSharedPreferences("settings",0).getBoolean("vibrate",true)) return
        val v=if(Build.VERSION.SDK_INT>=31)(getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        val pattern=longArrayOf(0,450,550)
        if(Build.VERSION.SDK_INT>=26)v.vibrate(VibrationEffect.createWaveform(pattern,0)) else @Suppress("DEPRECATION") v.vibrate(pattern,0)
    }
    private fun stopVibration(){
        val v=if(Build.VERSION.SDK_INT>=31)(getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator); v.cancel()
    }
    private fun showAudioRoutes(){
        val am=getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val pop=PopupMenu(this,b.speakerBtn); pop.menu.add("Telefon"); pop.menu.add("Dinamik");
        if(Build.VERSION.SDK_INT>=31){ runCatching { am.availableCommunicationDevices.filter{it.type==android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type==android.media.AudioDeviceInfo.TYPE_BLE_HEADSET || it.type==android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER}.forEach{d->pop.menu.add("Bluetooth: ${d.productName}")} } } else { @Suppress("DEPRECATION") if(am.isBluetoothScoAvailableOffCall) pop.menu.add("Bluetooth") }
        pop.setOnMenuItemClickListener{m-> val t=m.title.toString(); if(Build.VERSION.SDK_INT>=31){ val dev=runCatching{when{t=="Dinamik"->am.availableCommunicationDevices.firstOrNull{it.type==android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER};t.startsWith("Bluetooth")->am.availableCommunicationDevices.firstOrNull{it.type==android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO||it.type==android.media.AudioDeviceInfo.TYPE_BLE_HEADSET||it.type==android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER};else->am.availableCommunicationDevices.firstOrNull{it.type==android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE}}}.getOrNull(); if(dev!=null)runCatching{am.setCommunicationDevice(dev)} } else { @Suppress("DEPRECATION") when{t=="Dinamik"->{am.isSpeakerphoneOn=true;am.stopBluetoothSco()};t.startsWith("Bluetooth")->{am.isSpeakerphoneOn=false;am.startBluetoothSco();am.isBluetoothScoOn=true};else->{am.isSpeakerphoneOn=false;am.stopBluetoothSco()}} }; b.speakerBtn.text=if(t.startsWith("Bluetooth"))"Bluetooth" else t; true};pop.show()
    }

    override fun onDestroy() {
        if(!historySaved) saveHistory(if(connected) "completed" else if(incoming && !accepted) "missed" else "unanswered")
        stopVibration()
        destroyed = true
        h.removeCallbacksAndMessages(null)
        // Release the renderers first so no frame is delivered to a dead surface.
        if (video) { runCatching { b.localVideo.release() }; runCatching { b.remoteVideo.release() } }
        rtc?.close(); rtc = null
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.isSpeakerphoneOn = false
        am.mode = oldAudioMode
        super.onDestroy()
    }
}
