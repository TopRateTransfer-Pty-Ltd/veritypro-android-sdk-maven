# VerityPro SDK ProGuard Rules
# These rules apply when building the SDK module itself with R8/ProGuard enabled.

# ── Retrofit + OkHttp ──
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Gson serialization ──
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keepclassmembers class * { @com.google.gson.annotations.SerializedName <fields>; }
# Keep all fields in data classes used for JSON deserialization
-keepclassmembers class * {
    @com.google.gson.annotations.Expose <fields>;
}

# ── SDK data classes and API models (critical for JSON deserialization) ──
-keep class com.example.veritypro_sdk.services.** { *; }
-keep class com.example.veritypro_sdk.utils.VerityOption { *; }
-keep class com.example.veritypro_sdk.utils.DataPayload { *; }
-keep class com.example.veritypro_sdk.utils.enums.** { *; }
-keep class com.example.veritypro_sdk.utils.data_classes.** { *; }

# ── Public SDK API surface ──
-keep class com.example.veritypro_sdk.VerityPro { *; }
-keep class com.example.veritypro_sdk.VerityProSdkActivity { *; }

# ── AWS Amplify & Liveness ──
-keep class com.amplifyframework.** { *; }
-dontwarn com.amplifyframework.**

# ── ML Kit / TensorFlow Lite ──
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# ── Kotlin coroutines ──
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { *; }

# ── Keep enum values (used in serialization) ──
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Keep Parcelable implementations ──
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
