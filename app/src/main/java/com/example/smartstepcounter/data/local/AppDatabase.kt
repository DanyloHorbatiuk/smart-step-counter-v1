package com.example.smartstepcounter.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SensorRecord::class, ActuatorEventEntity::class, PendingUpload::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sensorRecordDao(): SensorRecordDao
    abstract fun actuatorEventDao(): ActuatorEventDao
    abstract fun pendingUploadDao(): PendingUploadDao

    companion object {
        const val NAME = "smart_step.db"

        /** Called once from AppContainer, which keeps the singleton. */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}
