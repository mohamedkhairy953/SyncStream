// Convention for Compose-enabled modules. Apply ALONGSIDE syncstream.android.library or
// syncstream.android.application. Enables the compose buildFeature on whichever android
// extension is present and wires the BOM-managed core Compose dependencies.
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

pluginManager.withPlugin("com.android.application") {
    extensions.configure<ApplicationExtension> { buildFeatures { compose = true } }
}
pluginManager.withPlugin("com.android.library") {
    extensions.configure<LibraryExtension> { buildFeatures { compose = true } }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    val bom = libs.findLibrary("androidx-compose-bom").get()
    "implementation"(platform(bom))
    "androidTestImplementation"(platform(bom))
    "implementation"(libs.findLibrary("androidx-compose-ui").get())
    "implementation"(libs.findLibrary("androidx-compose-ui-graphics").get())
    "implementation"(libs.findLibrary("androidx-compose-ui-tooling-preview").get())
    "implementation"(libs.findLibrary("androidx-compose-material3").get())
    "debugImplementation"(libs.findLibrary("androidx-compose-ui-tooling").get())
}
