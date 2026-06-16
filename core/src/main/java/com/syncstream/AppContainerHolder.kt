package com.syncstream

import android.content.Context

/**
 * Implemented by the process [android.app.Application] so any [Context] can reach the app-scoped
 * [AppContainer] without the `:core` module depending on the concrete Application class (which
 * lives in `:app`). Keeps the dependency direction acyclic.
 */
interface AppContainerHolder {
    val container: AppContainer
}

/** Convenience accessor for the [AppContainer] from any [Context]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as AppContainerHolder).container
