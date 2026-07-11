# ─── TorentChat ProGuard Rules ────────────────────────────────────────────────

# --- libsignal (Signal Protocol) ---
# Keeps native crypto code intact; stripping may break Curve25519 operations.
-keep class org.whispersystems.libsignal.** { *; }
-keep class org.whispersystems.curve25519.** { *; }

# --- SQLCipher ---
-keep class net.zetetic.database.** { *; }

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# --- WebRTC (Stream build) ---
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }

# --- Kotlinx Serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Hilt / Dagger ---
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# --- Keep model classes used in serialization ---
-keep @kotlinx.serialization.Serializable class * { *; }
