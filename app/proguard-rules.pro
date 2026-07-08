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

# ---------------------------------------------------------------------------
# JNI boundary (native <-> Kotlin). The native engine looks these up by name
# at runtime, so R8 must not rename/remove them.
# ---------------------------------------------------------------------------

# Kotlin -> native: nativeConvert/nativeConvertXcz/... link by the JNI symbol
# Java_com_androNSZ_NszConverter_<method>, so both the class name and the
# native method names must survive (the AGP default rule also covers native
# methods, this is the explicit, self-documenting form).
-keepclasseswithmembernames,includedescriptorclasses class com.androNSZ.NszConverter {
    native <methods>;
}

# native -> Kotlin: jni_bridge.c resolves these callback methods via
# GetMethodID("onProgress","(JJ)V") / ("onStatus","(...)V"). Keep the interface
# method names and every concrete override, or the lookup returns null -> crash.
-keep interface com.androNSZ.NszConverter$ProgressCallback { *; }
-keep interface com.androNSZ.NszConverter$StatusCallback { *; }
-keepclassmembers class * implements com.androNSZ.NszConverter$ProgressCallback {
    public void onProgress(long, long);
}
-keepclassmembers class * implements com.androNSZ.NszConverter$StatusCallback {
    public void onStatus(java.lang.String, java.lang.String);
}