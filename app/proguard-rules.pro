# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.classeve.earslate.**$$serializer { *; }
-keepclassmembers class com.classeve.earslate.** {
    *** Companion;
}
-keepclasseswithmembers class com.classeve.earslate.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Compose
-keep class androidx.compose.runtime.** { *; }

# Removed 2026-08-13: keep/dontwarn rules for Tink, Google API client, Joda-Time
# and WorkManager. None of those libraries are dependencies — KeyVault
# deliberately talks to AndroidKeyStore directly instead of pulling in
# androidx.security-crypto (and Tink behind it), and there is no WorkManager in
# the version catalogue. Rules naming absent libraries are not harmless: they
# are read as evidence that the library IS present, which contradicts the audit
# story the KeyVault KDoc tells, and a blanket keep would suppress real shrinker
# feedback the day one of them ever is added. Verified by a clean release build
# after removal.

# Strip android.util.Log calls in release builds — mirrors Lven-Android.
# Logcat is world-readable to any adb-attached host and to system bugreports;
# parser/serialization error messages can embed frame excerpts (translated
# conversation content) and network errors can embed request detail. Debug
# builds keep full logging. The compiler can remove these because Log.*
# returns an Int that is never used.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
