# Optimise and shrink release builds, but keep real class and method names
# so crash reports and playback failures stay readable.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
