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
