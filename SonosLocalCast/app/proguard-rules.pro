# R8 / ProGuard 规则（release 已开启 minify + shrinkResources）

# ---------- OkHttp / Okio ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------- NanoHTTPD ----------
# 内部用反射创建部分类（如 AsyncRunner 的默认实现），整包保留最稳。
-keep class fi.iki.elonen.** { *; }
-keepclassmembers class fi.iki.elonen.** { *; }

# ---------- Kotlin 协程 ----------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---------- Kotlin 元数据 ----------
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# ---------- 本工程 ----------
# i18n 的 Lang/Str 是枚举，保留 values()/valueOf()，避免被裁剪后 fromTag 失效
-keepclassmembers enum com.sonoscast.local.core.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
