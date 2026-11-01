-keepattributes *Annotation*
-keep class com.openlauncher.app.data.** { *; }
-keep class com.openlauncher.app.model.** { *; }

# Gson reads settings lists through TypeToken subclasses; keep their generic signatures
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
