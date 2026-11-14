# Keep Room database entities and DAOs intact during R8 shrinking
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
