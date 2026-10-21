# Keep GC-Tune classes and activities
-keep class com.poc.gctune.** { *; }

# Keep Material Components and animated widgets
-keep class com.google.android.material.materialswitch.** { *; }
-keep class com.google.android.material.slider.** { *; }
-keep class com.google.android.material.shape.** { *; }

# Don't warn about optional libraries
-dontwarn **
