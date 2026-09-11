# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel{*;}
-keepnames class io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel.**

# Gson + the GitHub update-check response model. Without these, R8 strips the @SerializedName
# annotations and removes the reflection-only fields, so the 200 response parses into an all-null
# object -- tagName comes back null, isNewer is false, and the update check silently reports
# "you're on the latest version" in release builds while debug (no R8) works. See GitHubRelease.
-keepattributes Signature, *Annotation*, RuntimeVisibleAnnotations, AnnotationDefault
-keep class io.github.jqssun.gpssetter.update.GitHubRelease { *; }
-keep class io.github.jqssun.gpssetter.update.GitHubRelease$* { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.annotations.** { *; }

# Retrofit service interface. In R8 full mode the generic signature is stripped from the Call<T>
# return type, so Retrofit throws "Call return type must be parameterized as Call<Foo>" at runtime
# and the update check fails outright in release builds (works in debug). Keep the interface (with
# its Signature attribute, above) and Retrofit's own reflected types.
-keep interface io.github.jqssun.gpssetter.update.GitHubService { *; }
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Modern Xposed API (libxposed). See https://github.com/libxposed/api#for-module-developers:
# module entry classes must survive shrinking, and the entry list resource must be rewritten if
# they get obfuscated.
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

-repackageclasses
-allowaccessmodification
-overloadaggressively
