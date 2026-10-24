# MediaCodec, Camera2, OpenGL and RTMP use no reflection in application code.
# Keep model names used by explicit diagnostic reporting only.
-keep class com.mobilestreaming.studio.core.model.** { *; }
-dontwarn android.media.MediaCodec
