# SyncStream proguard rules for release builds.
# WebRTC JNI surface.
-keep class org.webrtc.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Ktor references RuntimeMXBean which is unavailable on Android.
-dontwarn java.lang.management.**
# Ktor client engine internal classes.
-dontwarn io.ktor.client.engine.**
