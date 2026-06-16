plugins {
    id("syncstream.android.library")
    id("syncstream.android.compose")
}

android {
    namespace = "com.syncstream.feature.master"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":discovery"))
    implementation(project(":signaling"))
    implementation(project(":sync"))
    implementation(project(":webrtc"))
    implementation(project(":player"))

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)   // MasterStreamingService : LifecycleService
    implementation(libs.androidx.activity.compose)    // rememberLauncherForActivityResult
    implementation(libs.androidx.compose.material.icons.extended)
}
