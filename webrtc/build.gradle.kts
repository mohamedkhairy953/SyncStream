plugins {
    id("syncstream.android.library")
}

android {
    namespace = "com.syncstream.webrtc"
}

dependencies {
    implementation(project(":core")) // PeerState

    // org.webrtc types (PeerConnection, VideoTrack, DataChannel, ...) are all over the public API.
    api(libs.webrtc.android)
    implementation(libs.kotlinx.coroutines.android)
}
