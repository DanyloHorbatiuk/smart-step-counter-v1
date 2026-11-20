package com.example.smartstepcounter.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SensorRecord::class, ActuatorEventEntity::class, PendingUpload::class, SmartRule::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sensorRecordDao(): SensorRecordDao
    abstract fun actuatorEventDao(): ActuatorEventDao
    abstract fun pendingUploadDao(): PendingUploadDao
    abstract fun smartRuleDao(): SmartRuleDao

    companion object {
        const val NAME = "smart_step.db"

        /** Lab 7: ML label in sensor_records + table smart_rules. Existing rows keep null labels. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sensor_records` ADD COLUMN `aiLabel` TEXT")
                db.execSQL("ALTER TABLE `sensor_records` ADD COLUMN `aiConfidence` REAL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `smart_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, `metric` TEXT NOT NULL, `threshold` REAL NOT NULL, `action` TEXT NOT NULL)"
                )
            }
        }

        /** Called once from AppContainer, which keeps the singleton. */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
