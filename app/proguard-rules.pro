# MapLibre Native uses JNI; keep its classes from being stripped/renamed.
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**
