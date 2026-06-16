plugins {
    id("syncstream.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.syncstream.signaling"
}

dependencies {
    implementation(project(":core")) // PeerState

    // ClientHandle exposes io.ktor.websocket.WebSocketSession on its public API → api.
    api(libs.ktor.server.websockets)
    api(libs.ktor.client.websockets)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
