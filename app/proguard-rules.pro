# kotlinx.serialization ships its own rules; keep our @Serializable classes' generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.marketbot.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.marketbot.**$$serializer { *; }

# BouncyCastle resolves algorithms by class name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp's optional platform integrations.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
