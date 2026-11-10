# Pleos SDKs talk to the vehicle/voice services over AIDL and reflection: keep them whole.
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**
# Debug-only seed receiver is not in release; nothing else is reached by reflection.
# The SDK parses results with Gson TypeToken, which needs generic signatures kept.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
