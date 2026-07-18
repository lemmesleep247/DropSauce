-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void checkExpressionValueIsNotNull(...);
	public static void checkNotNullExpressionValue(...);
	public static void checkReturnedValueIsNotNull(...);
	public static void checkFieldIsNotNull(...);
	public static void checkParameterIsNotNull(...);
	public static void checkNotNullParameter(...);
}

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn coil3.PlatformContext

-keep class org.acra.security.NoKeyStoreFactory { *; }
-keep class org.acra.config.DefaultRetryPolicy { *; }
-keep class org.acra.attachment.DefaultAttachmentProvider { *; }
-keep class org.acra.sender.JobSenderService

# Preserve names restored from saved state while allowing implementation details to shrink.
# AndroidX Fragment's consumer rules retain the required public no-arg constructors.
-keepnames class org.koitharu.kotatsu.** extends androidx.fragment.app.Fragment
-keepnames class org.koitharu.kotatsu.** implements android.os.Parcelable

# ============================================================
# Shizuku user service
# Instantiated via Class.newInstance() by Shizuku's ServiceStarter
# in a separate process; the app never references its constructor
# or methods directly, so R8 would otherwise strip them and the
# service process dies with InstantiationException.
# ============================================================
-keep class org.koitharu.kotatsu.extensions.install.shizuku.** { *; }

# ============================================================
# Mihon Extension Support
# Extensions are separate APKs loaded at runtime via
# ChildFirstPathClassLoader. They depend on these host classes.
# ============================================================

# Tachiyomi / Mihon API classes
-keep class eu.kanade.tachiyomi.** { *; }
-keep class tachiyomi.core.common.** { *; }

# Injekt dependency injection (used by extensions via injectLazy)
-keep class uy.kohesive.injekt.** { *; }

# RxJava (used by legacy extension API)
-keep class rx.** { *; }
-dontwarn rx.**

# QuickJS (used by Mihon's JavaScriptEngine and directly by some extensions)
-keep class app.cash.quickjs.** { *; }

# OkHttp and Okio (used by extensions)
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okio.**
-dontwarn okhttp3.**

# zstd JNI (okhttp3.zstd CompressionInterceptor) — native code resolves
# ZstdCompressor/ZstdDecompressor via JNI FindClass, invisible to R8.
-keep class com.squareup.zstd.** { *; }

# UniFile (used by Mihon DiskUtil signatures)
-keep class com.hippo.unifile.** { *; }
-dontwarn com.hippo.unifile.**

# Jsoup (used by ParsedHttpSource)
-keep class org.jsoup.** { *; }
-dontwarn com.google.re2j.**

# kotlinx.serialization (used by some extensions)
-keep class kotlinx.serialization.** { *; }

# xmlutil (nl.adaptivity.xmlutil) — used by extensions that parse XML manga sites
-keep class nl.adaptivity.xmlutil.** { *; }
-dontwarn nl.adaptivity.xmlutil.**

# Kotlin stdlib
-keep class kotlin.** { *; }
-dontwarn kotlin.**

# Kotlin coroutines (extensions-lib 1.5+)
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# AndroidX Preference (ConfigurableSource settings screen)
-keep class androidx.preference.** { *; }

# Preserve AppCompat optional menu icon method name for release reflection fallback.
-keepclassmembers class androidx.appcompat.view.menu.** {
    void setOptionalIconsVisible(boolean);
}
