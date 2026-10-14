plugins {
    id("syncstream.android.library")
    id("syncstream.android.compose")
}

android {
    namespace = "com.syncstream.design"
}

// Core Compose dependencies (BOM, ui, material3, tooling) are supplied by the
// syncstream.android.compose convention plugin. SyncStreamTheme needs nothing else.

dependencies {
    implementation(libs.zxing.core)
    implementation(libs.androidx.compose.material.icons.extended)
}
