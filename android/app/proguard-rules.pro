# Keep kotlinx.serialization generated serializers used by Retrofit DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Hilt / Dagger
-dontwarn dagger.hilt.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep Accessibility USSD capture service for OEM fallback.
-keep class com.routedns.routebot.ussd.UssdAccessibilityService { *; }


# Optional annotations referenced by Tink (security-crypto) / OkHttp / ZXing.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn com.google.zxing.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
