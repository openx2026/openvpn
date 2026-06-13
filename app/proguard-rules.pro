# --- Kotlin / coroutines ---
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --- Gson (Portal API + inline TypeToken) ---
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
-keep class com.google.gson.** { *; }
-keep class com.openvpn.client.api.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# --- MMKV ---
-keep class com.tencent.mmkv.** { *; }

# --- EncryptedSharedPreferences ---
-keep class androidx.security.crypto.** { *; }

# --- App entry points ---
-keep class com.openvpn.client.OpenVpnApplication { *; }
-keep class com.openvpn.client.ui.MainActivity { *; }

# --- JNI (vpn-engine native libs) ---
-keepclasseswithmembernames class * {
    native <methods>;
}
