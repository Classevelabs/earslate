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

# Tink crypto (transitive: errorprone annotations + optional KeysDownloader deps are compile-time / unused)
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.** { *; }

# WorkManager / EncryptedSharedPreferences workers
-keep class * extends androidx.work.CoroutineWorker { <init>(...); }
-keep class * extends androidx.work.Worker { <init>(...); }

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
