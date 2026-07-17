package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ==========================================
// ENTITIES
// ==========================================

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String, // "NOTES" or "LINKS"
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = Folder::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["folderId"])]
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long,
    val title: String,
    val content: String,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val imagePath: String? = null,
    val isChecklist: Boolean = false,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "links",
    foreignKeys = [
        ForeignKey(
            entity = Folder::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["folderId"])]
)
data class Link(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long,
    val title: String,
    val url: String,
    val siteName: String?,
    val imageUrl: String?,
    val price: String?,
    val brand: String?,
    val rating: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false
)

// ==========================================
// DAOS
// ==========================================

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE type = :type ORDER BY name ASC")
    fun getFoldersByType(type: String): Flow<List<Folder>>

    @Query("SELECT * FROM folders WHERE name LIKE :query ORDER BY name ASC")
    fun searchFolders(query: String): Flow<List<Folder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: Folder): Long

    @Update
    suspend fun updateFolder(folder: Folder)

    @Delete
    suspend fun deleteFolder(folder: Folder)

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolderById(id: Long): Folder?
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE folderId = :folderId AND isArchived = 0 ORDER BY isPinned DESC, updatedAt DESC")
    fun getNotesInFolder(folderId: Long): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE (title LIKE :query OR content LIKE :query) AND isArchived = 0 ORDER BY isPinned DESC, updatedAt DESC")
    fun searchNotes(query: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE isPinned = 1 AND isArchived = 0 ORDER BY updatedAt DESC")
    fun getPinnedNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE isArchived = 1 ORDER BY updatedAt DESC")
    fun getArchivedNotes(): Flow<List<Note>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long

    @Update
    suspend fun updateNote(note: Note)

    @Delete
    suspend fun deleteNote(note: Note)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: Long): Note?
}

@Dao
interface LinkDao {
    @Query("SELECT * FROM links WHERE folderId = :folderId AND isArchived = 0 ORDER BY createdAt DESC")
    fun getLinksInFolder(folderId: Long): Flow<List<Link>>

    @Query("SELECT * FROM links WHERE (title LIKE :query OR url LIKE :query OR siteName LIKE :query) AND isArchived = 0 ORDER BY createdAt DESC")
    fun searchLinks(query: String): Flow<List<Link>>

    @Query("SELECT * FROM links WHERE isArchived = 1 ORDER BY createdAt DESC")
    fun getArchivedLinks(): Flow<List<Link>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(link: Link): Long

    @Update
    suspend fun updateLink(link: Link)

    @Delete
    suspend fun deleteLink(link: Link)

    @Query("SELECT * FROM links WHERE id = :id")
    suspend fun getLinkById(id: Long): Link?
}
