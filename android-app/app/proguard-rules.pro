# ─── TorentChat ProGuard Rules ────────────────────────────────────────────────

# --- libsignal (Signal Protocol) ---
-keep class org.signal.libsignal.** { *; }
-keep class org.signal.libsignal.internal.** { *; }
-keepclassmembers class org.signal.libsignal.** { *; }
# Keep native method names
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Kotlinx Serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class * { *; }

# --- Ktor ---
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# --- WebRTC (Stream) ---
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }

# --- Coroutines ---
-keep class kotlinx.coroutines.** { *; }

# --- Keep model classes ---
-keep class com.torentchat.domain.model.** { *; }
-keep class com.torentchat.crypto.** { *; }
-keep class com.torentchat.data.local.entity.** { *; }

# --- Compose ---
-dontwarn androidx.compose.**

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# --- SLF4J (missing impl classes — safe to ignore on Android) ---
-dontwarn org.slf4j.impl.**
-dontwarn org.slf4j.LoggerFactory
-dontwarn org.slf4j.MDC
