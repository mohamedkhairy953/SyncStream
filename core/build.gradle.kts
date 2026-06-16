plugins {
    id("syncstream.android.library")
}

android {
    namespace = "com.syncstream.core"
}

dependencies {
    // AppContainer exposes EglBase / PeerConnectionFactory / CoroutineScope / Json on its public
    // API, so these are `api` — dependents compile against those types.
    api(libs.webrtc.android)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
}
