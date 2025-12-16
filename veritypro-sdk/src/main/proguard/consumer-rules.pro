# Retrofit + Gson – fixes ParameterizedType crash 100%
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }
-dontwarn retrofit2.**

-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keepclassmembers class * { @com.google.gson.annotations.SerializedName <fields>; }

# Keep your entire SDK
-keep class com.example.veritypro_sdk.** { *; }

# Amplify & MLKit
-keep class com.amplifyframework.** { *; }
-dontwarn com.amplifyframework.**
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
