# Keep proot / native helpers
-keep class com.ubuntumobile.linux.** { *; }
-keepclassmembers class com.ubuntumobile.linux.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.ubuntumobile.** {
    *** Companion;
}
-keepclasseswithmembers class com.ubuntumobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}
