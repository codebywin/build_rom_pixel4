# Keep Android Application Components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep Xposed Entrypoint & Framework Stubs
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }
-keep public class org.lineageos.camera.assistant.xposed.XposedInit {
    public *;
}

# Keep custom views and layouts
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Obfuscation settings
-repackageclasses ''
-allowaccessmodification
-dontusemixedcaseclassnames
-renamesourcefileattribute ''
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn **
