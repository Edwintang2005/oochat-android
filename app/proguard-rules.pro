# Add project specific ProGuard rules here.

# Keep kotlinx.serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# kotlinx.serialization generates a synthetic $$serializer per @Serializable
# class, looked up via reflection at runtime — R8 can't see that reference
# statically, so without this every wire DTO (protocol frames, voice
# transcription requests/responses, SessionSnapshot, etc., which live outside
# data/** too, in domain/model and network) would break serialization in a
# minified release build despite compiling and passing unit tests fine
# (those run unminified). Rules per the official kotlinx-serialization R8 guide.
-keep,includedescriptorclasses class ai.openonion.oochat.**$$serializer { *; }
-keepclassmembers class ai.openonion.oochat.** {
    *** Companion;
}
-keepclasseswithmembers class ai.openonion.oochat.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class ai.openonion.oochat.** { *; }

# Keep ConnectOnion data classes
-keep class ai.openonion.oochat.data.** { *; }

# Room entities/DAOs are referenced from generated *_Impl classes by name;
# Room's own AAR ships consumer rules for its generated code, but the
# entity/DAO classes themselves still need their field names preserved.
-keep class ai.openonion.oochat.data.local.db.entity.** { *; }
-keep interface ai.openonion.oochat.data.local.db.dao.** { *; }

# lazysodium/JNA (Ed25519 signing in KeyManager) resolves native method
# bindings and the Library interface via reflection — obfuscating either
# breaks the JNI bridge at runtime with no compile-time warning.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * implements com.sun.jna.Library { *; }
-keep class com.goterl.lazysodium.** { *; }

# JNA's desktop AWT integration (java.awt.Component/Window/GraphicsEnvironment)
# is dead code on Android — those classes don't exist on the platform and
# the code path referencing them is never reached, but R8 still needs to be
# told not to fail the build over it.
-dontwarn java.awt.**

# androidx.security-crypto (EncryptedSharedPreferences, used by KeyManager/
# SafePreferencesWrapper) pulls in Google Tink, which references
# error-prone's annotations only at compile time (source retention) — they
# have no runtime presence to keep, just no need to warn about missing them.
-dontwarn com.google.errorprone.annotations.**
