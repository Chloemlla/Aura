# Moshi
-keep class com.chloemlla.aura.data.remote.** { *; }
-keepclassmembers class com.chloemlla.aura.data.remote.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
# Codegen adapters are resolved by "<ClassName>JsonAdapter" name lookup, so the
# generated class and its (Moshi[, Type[]]) constructor must survive shrinking.
-keep class **JsonAdapter { <init>(...); }
-keepclassmembers class * { @com.squareup.moshi.Json <fields>; }

# Retrofit
# No blanket keep: the retrofit AAR ships META-INF/proguard/retrofit2.pro, whose
# consumer rules keep the http-annotated interfaces, Response and Continuation.
-dontwarn retrofit2.**
-keepattributes Signature
-keepattributes Exceptions

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# NewPipe Extractor + Rhino JS
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }

# yt-dlp / youtubedl-android + Apache Commons Compress
-keep class com.yausername.** { *; }
-keepclassmembers class com.yausername.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# R8 can nondeterministically merge this abstract Jackson serializer, changing
# its synthetic discriminator by one byte between otherwise identical builds.
# Keep it shrinkable/obfuscatable but do not optimize or merge the class.
-keep,allowshrinking,allowobfuscation class com.fasterxml.jackson.databind.ser.std.ToStringSerializerBase { *; }

# Lumen Crash SDK
-keep class com.chloemlla.lumen.crash.** { *; }
-dontwarn com.chloemlla.lumen.crash.**

# Firebase / ML Kit component discovery (full flavor only).
# FirebaseApp instantiates every ComponentRegistrar found on the classpath through
# its no-arg constructor; R8 strips that constructor because nothing calls it in
# source, and getClient() then NPEs at runtime instead of failing the build.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.firebase.components.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
