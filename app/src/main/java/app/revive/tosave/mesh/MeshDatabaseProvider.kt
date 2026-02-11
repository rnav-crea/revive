package app.revive.tosave.mesh

import android.content.Context
import androidx.room.Room

/**
 * Singleton provider for MeshDatabase
 */
object MeshDatabaseProvider {
    @Volatile
    private var INSTANCE: MeshDatabase? = null

    fun getDatabase(context: Context): MeshDatabase {
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                MeshDatabase::class.java,
                MeshDatabase.DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
            INSTANCE = instance
            instance
        }
    }
}