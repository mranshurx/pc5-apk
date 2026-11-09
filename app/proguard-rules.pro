# JNI: names are resolved by string in RegisterNatives — never rename.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.pc5.android.Native { *; }

# Shizuku provider is declared in the manifest and loaded reflectively.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class androidx.appcompat.app.AppCompatActivity { *; }

# BuildConfig.DEBUG drives the tamper check in Guard.
-keep class com.pc5.android.BuildConfig { *; }

-dontwarn rikka.shizuku.**
-dontwarn org.jetbrains.annotations.**
