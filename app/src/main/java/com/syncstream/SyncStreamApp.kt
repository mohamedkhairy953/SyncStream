package com.syncstream

import android.app.Application
import org.webrtc.PeerConnectionFactory

/**
 * Process-level [Application]. Performs the one-time global WebRTC native initialization and
 * owns the app-scoped [AppContainer]. Activities/services read [container]; nothing else
 * constructs app-scoped singletons.
 */
class SyncStreamApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // One-time global WebRTC init. MUST run before any PeerConnectionFactory is built.
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )

        container = AppContainer(this)
    }
}

/** Convenience accessor for the [AppContainer] from any [android.content.Context]. */
val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as SyncStreamApp).container
