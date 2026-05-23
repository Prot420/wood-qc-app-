# Proguard rules for Moradabad Wood Quality Control App

# Keep Room database and SQLCipher entities
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class com.woodqc.app.database.ItemLog { *; }

# Keep TensorFlow Lite models and interpreters
-keep class org.tensorflow.lite.** { *; }
-keep class org.opencv.** { *; }
