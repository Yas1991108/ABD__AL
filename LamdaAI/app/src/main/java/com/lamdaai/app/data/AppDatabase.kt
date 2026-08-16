package com.lamdaai.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ChatEntity::class, MessageEntity::class, ActionAuditEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun actionAuditDao(): ActionAuditDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lamdaai.db"
                )
                    // Pre-release schema; there's no user data worth migrating yet.
                    // A real migration path goes in before the first release build.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}

