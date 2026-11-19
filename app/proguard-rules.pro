# DigitalLock R8 Deep Obfuscation & Protection Rules
-repackageclasses 'd'
-allowaccessmodification
-optimizationpasses 3

# Keep JNI native bridges intact
-keep class com.digitallock.graphics.NativeGlassBridge {
    public static *;
    native <methods>;
}
-keep class com.digitallock.security.NativeSecurityBridge {
    public static *;
    native <methods>;
}

# Keep Liquid Glass AGSL shader & Compose backdrop components
-keep class component.liquid.** { *; }

# Keep Android entry points, services, receivers, and WorkManager workers
-keep class com.digitallock.MainActivity { *; }
-keep class com.digitallock.lock.LockActivity { *; }
-keep class com.digitallock.service.MonitorService { *; }
-keep class com.digitallock.accessibility.AccessibilityMonitor { *; }
-keep class com.digitallock.receiver.** { *; }
-keep class com.digitallock.remote.ResourceSyncWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Strip verbose debug logs in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep line numbers for private mapping crash diagnostics only
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep SecureWebViewRuntime allowlisted Native Bridge
-keepclassmembers class com.digitallock.runtime.DigitalLockNativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
