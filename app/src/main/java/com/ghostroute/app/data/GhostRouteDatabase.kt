package com.ghostroute.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CameraEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class GhostRouteDatabase : RoomDatabase() {
    abstract fun cameraDao(): CameraDao

    companion object {
        @Volatile
        private var instance: GhostRouteDatabase? = null

        /** v1→v2: add the OSM `timestamp` column (per-camera data-freshness). Additive, so
         *  existing camera rows are kept (the column is null until their next sync). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cameras ADD COLUMN osmTimestamp INTEGER")
            }
        }

        /** Process-wide singleton. No DI framework on purpose — keeps deps minimal. */
        fun get(context: Context): GhostRouteDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GhostRouteDatabase::class.java,
                    "ghostroute.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
