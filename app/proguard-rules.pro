# Jetpack Compose rules
-keepclassmembers class * extends androidx.compose.ui.node.Owner { *; }
-keep class androidx.compose.ui.platform.AndroidComposeView { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}

# Keep main entry points
-keep class com.example.MainActivity { *; }
-keep class com.example.** { *; }

# Serialization and Moshi
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
}
-dontwarn com.squareup.moshi.**

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Room
-dontwarn androidx.room.**
-keep class * extends androidx.room.RoomDatabase { *; }

# General Kotlin and Coroutines
-keepclassmembers class * extends kotlin.coroutines.jvm.internal.ContinuationImpl { *; }
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

