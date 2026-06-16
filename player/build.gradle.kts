plugins {
    id("syncstream.android.library")
}

android {
    namespace = "com.syncstream.player"
}

dependencies {
    implementation(project(":webrtc")) // ExoSurfaceVideoSource

    // MasterPlayerController/PcmTapProcessor expose ExoPlayer, MediaSession, and
    // BaseAudioProcessor (media3-common, via exoplayer) on their public API → api.
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.session)
    implementation(libs.kotlinx.coroutines.android)
}
