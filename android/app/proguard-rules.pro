# OpenCV: las clases Java se llaman desde el código nativo por su nombre.
-keep class org.opencv.** { *; }
# LiteRT / TensorFlow Lite: igual, el intérprete nativo busca estas clases.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**
