# Keep route names stable because Navigation 3 uses @Serializable route types.
-keepnames @kotlinx.serialization.Serializable class com.mineradio.app.navigation.Screen$*
-keepclassmembers class com.mineradio.app.navigation.Screen$* {
    static **$Companion Companion;
}
-keepclassmembers class com.mineradio.app.navigation.Screen$*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit and Sandwich depend on generic signatures and runtime annotations.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking interface com.skydoves.sandwich.ApiResponse

# Koin (dependency injection uses reflection)
-keep class org.koin.** { *; }
-keep class com.mineradio.app.di.** { *; }

# MediaPipe (native method declarations)
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
-keepclassmembers class * { native <methods>; }
# MediaPipe protobuf classes (not bundled, suppress warnings)
-dontwarn com.google.mediapipe.proto.**
# MediaPipe TaskRunner.create() uses StackWalker (via flogger) to inspect the call stack
# ★ 关键：必须保留 flogger 类，否则栈遍历找不到调用者 → "no caller found on the stack"
-keep class com.google.mediapipe.framework.Graph { *; }
-keep class com.google.mediapipe.tasks.core.TaskRunner { *; }
-keep class com.google.mediapipe.tasks.core.TaskRunner$* { *; }
# flogger 是 MediaPipe 栈遍历的底层依赖，混淆/优化会破坏 StackWalker
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
# auto.value 被 mediapipe/flogger 引用
-keep class com.google.auto.value.** { *; }
-dontwarn com.google.auto.value.**
-dontwarn autovalue.shaded.**
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.**

# ★ MediaPipe 栈遍历依赖全局不混淆不优化（参照参考项目 D:\1\1.1.5\aimapk4_extracted）
# 仅 shrink（移除未使用代码），不混淆不优化
-dontobfuscate
-dontoptimize

# Moshi (reflection-based JSON parsing)
-keepclassmembers,allowshrinking,allowobfuscation class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonClass @com.squareup.moshi.* class *

# Keep Compose related classes
-keep class androidx.compose.** { *; }

# TagLib (native library)
-keep class com.kyant.taglib.** { *; }

# Amplituda
-keep class com.lincollincol.amplituda.** { *; }

# ★ PluginManager (JNI native 方法绑定，防止类名/方法名混淆)
-keep class com.mineradio.app.manager.PluginManager { *; }

# ★★★ Live2D 萌宠 JNI 桥接类（C++ 通过 RegisterNatives / GetStaticMethodID 调用 Java 方法，
#    必须保留类名和方法名，否则 native 加载时报 NoSuchMethodError 闪退）
-keep class com.mineradio.app.wallpaper.JniBridgePet { *; }
-keepclassmembers class com.mineradio.app.wallpaper.JniBridgePet {
    public static *;
}

# ★★★ Wallpaper Engine JNI 桥接类（native 库 libscenejni.so 通过 JNI FindClass/GetMethodID
#    调用这些类，Java 层无直接引用，R8 shrinking 会误删 → initScene 时 SIGABRT 闪退）
#    必须保留类名、方法名、字段名，否则 native FindClass 返回 null → GetMethodID abort
-keep class io.wallpaperengine.** { *; }
-keepclassmembers class io.wallpaperengine.** {
    public *;
    private *;
}
