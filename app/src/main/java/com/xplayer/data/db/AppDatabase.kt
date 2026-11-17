package com.xplayer.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.xplayer.data.model.VideoFile
import com.xplayer.data.model.VideoFolder

@Database(
    entities = [VideoFile::class, VideoFolder::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
}
