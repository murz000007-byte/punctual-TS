package com.example.data

import kotlinx.coroutines.flow.Flow

class PunctualRepository(private val database: AppDatabase) {
    private val folderDao = database.folderDao()
    private val noteDao = database.noteDao()
    private val linkDao = database.linkDao()

    // Folder operations
    fun getFolders(type: String): Flow<List<Folder>> = folderDao.getFoldersByType(type)
    fun searchFolders(query: String): Flow<List<Folder>> = folderDao.searchFolders("%$query%")
    suspend fun insertFolder(folder: Folder): Long = folderDao.insertFolder(folder)
    suspend fun updateFolder(folder: Folder) = folderDao.updateFolder(folder)
    suspend fun deleteFolder(folder: Folder) = folderDao.deleteFolder(folder)
    suspend fun getFolderById(id: Long): Folder? = folderDao.getFolderById(id)

    // Note operations
    fun getNotes(folderId: Long): Flow<List<Note>> = noteDao.getNotesInFolder(folderId)
    fun searchNotes(query: String): Flow<List<Note>> = noteDao.searchNotes("%$query%")
    fun getPinnedNotes(): Flow<List<Note>> = noteDao.getPinnedNotes()
    fun getArchivedNotes(): Flow<List<Note>> = noteDao.getArchivedNotes()
    suspend fun insertNote(note: Note): Long = noteDao.insertNote(note)
    suspend fun updateNote(note: Note) = noteDao.updateNote(note)
    suspend fun deleteNote(note: Note) = noteDao.deleteNote(note)
    suspend fun getNoteById(id: Long): Note? = noteDao.getNoteById(id)

    // Link operations
    fun getLinks(folderId: Long): Flow<List<Link>> = linkDao.getLinksInFolder(folderId)
    fun searchLinks(query: String): Flow<List<Link>> = linkDao.searchLinks("%$query%")
    fun getArchivedLinks(): Flow<List<Link>> = linkDao.getArchivedLinks()
    suspend fun insertLink(link: Link): Long = linkDao.insertLink(link)
    suspend fun updateLink(link: Link) = linkDao.updateLink(link)
    suspend fun deleteLink(link: Link) = linkDao.deleteLink(link)
    suspend fun getLinkById(id: Long): Link? = linkDao.getLinkById(id)
}
