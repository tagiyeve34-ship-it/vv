package com.hesabat.twopersonmessenger

import android.content.Context
import org.webrtc.PeerConnectionFactory

/**
 * Process-wide WebRTC bootstrap. PeerConnectionFactory.initialize() must run once.
 * The factory itself stays call-scoped in WebRtcClient because audio/video calls
 * use different codec resources.
 */
object WebRtcRuntime {
    @Volatile private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        initialized = true
    }
}
