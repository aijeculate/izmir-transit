# Project-specific R8 rules. Retrofit, OkHttp, kotlinx.serialization, Room, Hilt and
# MapLibre bring their own consumer rules; add entries here only for verified problems.

# Keep source file names and line numbers so release crash stack traces are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
