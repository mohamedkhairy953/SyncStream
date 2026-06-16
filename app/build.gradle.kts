plugins {
    id("syncstream.android.application")
    id("syncstream.android.compose")
}

android {
    namespace = "com.syncstream"

    defaultConfig {
        applicationId = "com.syncstream"
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // The app shell composes the foundation, design system, and the two feature modules.
    implementation(project(":core"))
    implementation(project(":design"))
    implementation(project(":feature-master"))
    implementation(project(":feature-client"))

    // Shell-only UI deps (MainActivity host + RolePickerScreen).
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
}
