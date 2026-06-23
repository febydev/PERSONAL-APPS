# Default Android optimize rules apply via proguard-android-optimize.txt.

# Keep Tink crypto primitives (loaded reflectively via the config registry).
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Argon2Kt loads a native library; keep its JNI surface.
-keep class com.lambdapioneer.argon2kt.** { *; }
