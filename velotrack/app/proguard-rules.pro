# MapLibre Native uses JNI; keep everything it may call back into.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**
# OkHttp/Timber are transitive MapLibre deps; harmless without INTERNET permission.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn timber.**
# Keep Kotlin metadata for reflection-free but annotation-based code paths.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
