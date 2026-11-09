package app.familynote.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

enum class NoteKind { DRAWING, TEXT }

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: String,
    val kind: NoteKind,
    /** Absolute path of the PNG for drawings; null for text notes. */
    val imagePath: String?,
    val text: String?,
    val fromMember: String,
    val toMember: String,
    val createdAt: Long,
    /** Ambient-ready color that represents this note. */
    val accentColor: Int,
    val seen: Boolean = false,
    /** Set when this note is a reply to another note. */
    val replyToId: String? = null,
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): Note?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: Note)

    @Query("UPDATE notes SET seen = 1 WHERE id = :id")
    suspend fun markSeen(id: String)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun count(): Int
}

@Database(entities = [Note::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
}
