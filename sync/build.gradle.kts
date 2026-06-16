plugins {
    id("syncstream.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.syncstream.sync"
}

dependencies {
    // DataChannel appears in public method signatures (SyncEngine/ClockSync/AudioStreamer) → api.
    api(libs.webrtc.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
