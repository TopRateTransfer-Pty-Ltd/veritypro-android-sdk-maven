## Retrofit + Gson – fixes ParameterizedType crash 100%
#-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*
#-keep class retrofit2.** { *; }
#-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }
#-dontwarn retrofit2.**
#
#-keep class com.google.gson.** { *; }
#-dontwarn com.google.gson.**
#-keepclassmembers class * { @com.google.gson.annotations.SerializedName <fields>; }
#
## Keep your entire SDK
#-keep class com.example.veritypro_sdk.** { *; }
#
## Amplify & MLKit
#-keep class com.amplifyframework.** { *; }
#-dontwarn com.amplifyframework.**
#-keep class com.google.mlkit.** { *; }
#-dontwarn com.google.mlkit.**


# Keep generic signature metadata
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# Keep your SDK package
-keep class com.example.verity.** { *; }
-keep class com.example.veritypro_sdk.** { *; }

# Keep Gson TypeToken reflection helpers
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class com.google.gson.** { *; }
-keepclassmembers class * { @com.google.gson.annotations.SerializedName <fields>; }

# Keep Retrofit (if using Retrofit reflection)
-keep class retrofit2.** { *; }
-keepclassmembers class * { @retrofit2.http.* <methods>; }

# MLKit and Amplify safe rules
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.amplifyframework.** { *; }
-dontwarn com.amplifyframework.**
