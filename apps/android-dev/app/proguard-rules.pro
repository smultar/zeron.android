# JNA and the UniFFI bindings are reached by reflection / native callbacks.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-keep class uniffi.** { *; }
-dontwarn java.awt.**
# WebRTC calls back into Java from native code.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# Car App Library: Android Auto binds the car service by class name.
-keep class sh.zeron.android.auto.** { *; }
# Tink (via security-crypto) references annotation-only classes.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
