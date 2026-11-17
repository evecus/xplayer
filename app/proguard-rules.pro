# Media3 / ExoPlayer / NextLib
-keep class androidx.media3.** { *; }
-keep class io.github.anilbeesetti.nextlib.** { *; }
-dontwarn androidx.media3.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# Kotlin coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Coil
-keep class coil.** { *; }

# Data classes used with Gson / serialization
-keepclassmembers class com.xplayer.data.model.** { *; }

# Suppress warnings
-dontwarn java.lang.invoke.*
-dontwarn javax.annotation.**
