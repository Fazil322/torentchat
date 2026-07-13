# ─── TorentChat Desktop ProGuard Rules ────────────────────────────────────────

# --- libsignal (Signal Protocol) ---
-keep class org.signal.libsignal.** { *; }
-keep class org.signal.libsignal.internal.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Kotlinx Serialization ---
-keepattributes *Annotation*, InnerClasses
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }

# --- Ktor ---
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# --- Coroutines ---
-keep class kotlinx.coroutines.** { *; }

# --- Compose Desktop ---
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# --- Keep app classes ---
-keep class com.torentchat.desktop.crypto.** { *; }
-keep class com.torentchat.desktop.data.** { *; }
-keep class com.torentchat.desktop.chat.** { *; }
-keep class com.torentchat.desktop.signaling.** { *; }
-keep class com.torentchat.desktop.identity.** { *; }

# --- ZXing ---
-keep class com.google.zxing.** { *; }
