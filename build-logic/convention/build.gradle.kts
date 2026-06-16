// Hosts the precompiled script plugins (syncstream.android.library / .compose / .application).
// Plugin ids are derived from the script file names under src/main/kotlin.
plugins {
    `kotlin-dsl`
}

group = "com.syncstream.buildlogic"

dependencies {
    // The Android Gradle Plugin + Kotlin Gradle Plugin must be on the classpath so the
    // precompiled scripts can apply them by id and configure their extensions.
    implementation("com.android.tools.build:gradle:8.10.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.21")
}
