# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.maiconerick.xdisplaymanager.**$$serializer { *; }
-keepclassmembers class com.maiconerick.xdisplaymanager.** {
    *** Companion;
}
-keepclasseswithmembers class com.maiconerick.xdisplaymanager.** {
    kotlinx.serialization.KSerializer serializer(...);
}
