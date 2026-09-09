# Retrofit/Gson payloads and Parcelable results form the public integration contract.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keep class com.example.veritypro_sdk.services.** { *; }
-keep class com.example.veritypro_sdk.utils.VerityOption { *; }
-keep class com.example.veritypro_sdk.utils.VerityResult { *; }
-keep class com.example.veritypro_sdk.utils.VerityVerificationError { *; }
-keep class com.example.veritypro_sdk.utils.LivenessResult { *; }
-keep class com.example.veritypro_sdk.utils.VpBrandConfig { *; }
-keepclassmembers class * implements android.os.Parcelable { public static final android.os.Parcelable$Creator CREATOR; }
