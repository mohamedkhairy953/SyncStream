plugins {
    id("syncstream.android.library")
    id("syncstream.android.compose")
}

android {
    namespace = "com.syncstream.feature.client"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":discovery"))
    implementation(project(":signaling"))
    implementation(project(":sync"))
    implementation(project(":webrtc"))

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)     // rememberLauncherForActivityResult (camera permission)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.zxing.android.embedded)        // camera-based QR scanner view
}
