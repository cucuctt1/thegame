package dev.tilesolver.data

import android.content.Context
import androidx.room.*

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val outcome: String,
    val moves: Int,
)

@Entity(tableName = "diagnostics")
data class DiagnosticEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val path: String,
    val bytes: Long,
    val latestFailure: Boolean,
)

@Dao interface SessionDao {
    @Insert suspend fun insert(entity: SessionEntity)
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :count") suspend fun recent(count: Int): List<SessionEntity>
}

@Dao interface DiagnosticDao {
    @Query("SELECT * FROM diagnostics ORDER BY createdAt DESC") suspend fun all(): List<DiagnosticEntity>
    @Query("DELETE FROM diagnostics WHERE id = :id") suspend fun delete(id: Long)
    @Insert suspend fun insert(entity: DiagnosticEntity)
}

@Database(entities = [SessionEntity::class, DiagnosticEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun diagnosticDao(): DiagnosticDao
    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context, AppDatabase::class.java, "solver.db").build().also { instance = it }
        }
    }
}
