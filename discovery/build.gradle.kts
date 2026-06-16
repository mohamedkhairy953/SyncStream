plugins {
    id("syncstream.android.library")
}

android {
    namespace = "com.syncstream.discovery"
}

dependencies {
    // NSD/mDNS uses the android.net.nsd framework APIs (no extra artifact).
    implementation(libs.kotlinx.coroutines.android)
}
