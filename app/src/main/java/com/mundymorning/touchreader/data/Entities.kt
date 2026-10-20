package com.mundymorning.touchreader.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    val format: String,
    val sourceName: String,
    val addedAt: Long,
    val lastOpenedAt: Long,
    val chapter: Int = 0,
    val sentence: Int = 0,
    val progress: Float = 0f,
    val voiceName: String? = null,
    val speed: Float = 1f,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(DocumentEntity::class, ["id"], ["docId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("docId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long,
    val chapter: Int,
    val sentence: Int,
    val label: String,
    val createdAt: Long,
)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: Long): DocumentEntity?

    @Insert
    suspend fun insert(d: DocumentEntity): Long

    @Query("UPDATE documents SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE documents SET chapter = :chapter, sentence = :sentence, progress = :progress, lastOpenedAt = :now WHERE id = :id")
    suspend fun savePosition(id: Long, chapter: Int, sentence: Int, progress: Float, now: Long)

    @Query("UPDATE documents SET lastOpenedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("UPDATE documents SET voiceName = :voice WHERE id = :id")
    suspend fun setVoice(id: Long, voice: String?)

    @Query("UPDATE documents SET speed = :speed WHERE id = :id")
    suspend fun setSpeed(id: Long, speed: Float)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE docId = :docId ORDER BY chapter, sentence")
    fun observe(docId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE docId = :docId ORDER BY chapter, sentence")
    suspend fun list(docId: Long): List<BookmarkEntity>

    @Insert
    suspend fun insert(b: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [DocumentEntity::class, BookmarkEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun bookmarks(): BookmarkDao
}
