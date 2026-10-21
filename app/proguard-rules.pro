# OpenCV: Java wrappers are called from native code through JNI.
-keep class org.opencv.** { *; }

# PdfBox-Android: optional JPEG2000 decoder and desktop-only crypto providers are not bundled.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }

# ML Kit text recognition
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
