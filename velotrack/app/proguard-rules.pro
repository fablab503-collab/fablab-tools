# MapLibre Native uses JNI; keep everything it may call back into.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**
# OkHttp 4.12.0 is used directly by the map download module (and transitively by MapLibre); it ships
# its own consumer R8 rules, these only silence optional-dependency warnings. Timber is MapLibre's.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn timber.**
# Keep Kotlin metadata for reflection-free but annotation-based code paths.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
