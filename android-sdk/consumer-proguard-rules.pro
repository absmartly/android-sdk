# Keep Jackson annotations and model classes for ABSmartly SDK
-keepattributes *Annotation*

# Keep all classes in the json package
-keep class com.absmartly.sdk.json.** { *; }

# Keep Jackson serialization
-keep class com.fasterxml.jackson.** { *; }
# Jackson's Java7SupportImpl references java.beans.* annotations that do not exist on
# Android. Suppress the warnings so a minified consumer build (R8) does not fail.
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <methods>;
}
