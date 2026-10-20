# JNA and the UniFFI bindings are reached by reflection / native callbacks.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-keep class uniffi.** { *; }
-dontwarn java.awt.**
# WebRTC calls back into Java from native code.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# Tink (via security-crypto) references annotation-only classes.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
