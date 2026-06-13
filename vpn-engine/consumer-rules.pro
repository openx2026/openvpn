# vpn-engine: keep JNI / Xray core and Gson-backed config DTOs when app R8 is enabled.

-keep class com.v2ray.ang.core.** { *; }
-keep class com.v2ray.ang.service.** { *; }
-keep class com.v2ray.ang.dto.** { *; }
-keep class com.v2ray.ang.handler.** { *; }
-keep class com.v2ray.ang.fmt.** { *; }
-keep class com.v2ray.ang.receiver.** { *; }
-keep class com.v2ray.ang.util.** { *; }

-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepclasseswithmembernames class * {
    native <methods>;
}
