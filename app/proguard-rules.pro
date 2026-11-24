# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# MediaPipe Tasks Vision resolves its JNI bridge by class name and ships no consumer
# ProGuard rules, so R8 must not rename or strip the library.
-keep class com.google.mediapipe.** { *; }

# MediaPipe 0.10.x references optional accelerators it does not bundle: the LiteRT-LM image
# embedder backend, AutoValue's compile-time annotation and the graph-profiler protos. The editor
# only uses FaceLandmarker and ImageSegmenter, so these code paths are never reached; R8 still
# needs the references to resolve at build time.
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.auto.value.extension.memoized.**
-dontwarn com.google.mediapipe.proto.**
