# R8 keep rules for the release and fast builds (build.gradle.kts).
#
# The app's own code uses no reflection: JSON is read and written by hand
# with org.json (part of Android), enums by name with valueOf, which R8
# follows. What needs keeping is code that native libraries call back into
# by name, which R8 cannot see.

# libVLC calls into its Java classes from libvlcjni.so (events, the video
# output's surface callbacks, MediaPlayer state) by class and method name,
# and its AAR carries no rules of its own. VLC's own app keeps the package
# whole, as here.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }

# StreamPack (the phone's camera transmit) builds its encoders and
# endpoints through factories and reaches srtdroid's JNI; srtdroid ships its
# own rules, StreamPack none. Kept whole rather than guessed at: the camera
# path cannot be exercised on a TV, and a few hundred kB of kept classes is
# not what made the dex large.
-keep class io.github.thibaultbee.streampack.** { *; }
-dontwarn io.github.thibaultbee.streampack.**

# Names kept: shrinking is what made the dex small (41 MB to 5 MB), and
# renaming would save little more while turning every stack trace in a
# logcat from a test device into something that needs this build's
# mapping.txt to read.
-dontobfuscate
