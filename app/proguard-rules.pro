# E.D.I.T.H. ProGuard / R8 rules
# Keep Shizuku IPC interface
-keep class com.nexwork.edith.data.ShizukuControlService { *; }
-keepclassmembers class com.nexwork.edith.data.ShizukuControlService {
    public *;
}

# Keep Shizuku UserService
-keep class * extends rikka.shizuku.ShizukuProvider { *; }

# Keep ViewModel classes
-keepclassmembers public class * extends androidx.lifecycle.ViewModel {
    public <init>();
    public <init>(androidx.lifecycle.SavedStateHandler);
}
-keep class com.nexwork.edith.ui.main.** { *; }

# Keep Compose data classes (immutable, @Stable)
-keep class com.nexwork.edith.model.** { *; }

# Keep Shizuku binder interface
-keep class com.nexwork.edith.data.IShizukuControlService { *; }

# Keep serialization classes
-keep class com.nexwork.edith.data.** { *; }
-keep class com.nexwork.edith.model.** { *; }

# Keep okhttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep kotlinx.serialization
-keep class kotlinx.serialization.** { *; }
-keep class *$$serializer { *; }
-keep class *$$serializer$kotlinx_serialization { *; }

# Keep Coil (if used for async icon loading)
-keep class io.coil.** { *; }
-dontwarn io.coil.**

# Keep Gson / MiniJsonParser internal structure
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
