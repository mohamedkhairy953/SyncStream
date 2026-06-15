# SyncStream proguard rules (release minify currently disabled).
# WebRTC JNI surface.
-keep class org.webrtc.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
