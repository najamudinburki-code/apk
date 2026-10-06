# Release shrinking is enabled in build.gradle.kts. The Socket.IO client reaches OkHttp's
# platform TLS helpers reflectively, so both libraries keep their public surface.
-keep class io.socket.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
