# OpenCV: las clases Java se llaman desde el código nativo por su nombre.
-keep class org.opencv.** { *; }
# LiteRT / TensorFlow Lite: igual, el intérprete nativo busca estas clases.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**
# Tesseract4Android: el código nativo lee campos y llama a métodos Java por su nombre
# (y la AAR no trae reglas propias).
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
