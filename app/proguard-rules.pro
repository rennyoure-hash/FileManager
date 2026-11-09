# Add project specific ProGuard / R8 rules here.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve Room entities & DAOs
-keep class com.example.core.db.** { *; }

# Preserve libsu Shell API
-keep class com.topjohnwu.superuser.** { *; }

# Strip verbose debug logs in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
