# androidx.security-crypto (EncryptedSharedPreferences) is backed by Google Tink,
# which does registry/reflection-based lookups for its key managers — R8 stripping
# those without a keep rule silently breaks encryption/decryption at runtime.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class androidx.security.crypto.** { *; }

# org.json (JSONObject/JSONArray) ships as part of the Android platform, not our
# dependencies, but keep it defensively since KorailApi parses all server responses
# through it and any accidental over-stripping there would break silently.
-dontwarn org.json.**
