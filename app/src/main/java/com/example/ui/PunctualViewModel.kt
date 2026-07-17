package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Folder
import com.example.data.Link
import com.example.data.Note
import com.example.data.PunctualRepository
import com.example.util.LinkMetadataParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PunctualViewModel(
    application: Application,
    private val repository: PunctualRepository
) : AndroidViewModel(application) {

    private val sharedPrefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    // Main tabs: "NOTES" or "LINKS"
    private val _currentTab = MutableStateFlow(sharedPrefs.getString("current_tab", "LINKS") ?: "LINKS")
    val currentTab: StateFlow<String> = _currentTab.asStateFlow()

    // Folder states
    val notesFolders: StateFlow<List<Folder>> = repository.getFolders("NOTES")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val linksFolders: StateFlow<List<Folder>> = repository.getFolders("LINKS")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedFolder = MutableStateFlow<Folder?>(null)
    val selectedFolder: StateFlow<Folder?> = _selectedFolder.asStateFlow()

    // Notes state
    private val _notesList = MutableStateFlow<List<Note>>(emptyList())
    val notesList: StateFlow<List<Note>> = _notesList.asStateFlow()

    // Links state
    private val _linksList = MutableStateFlow<List<Link>>(emptyList())
    val linksList: StateFlow<List<Link>> = _linksList.asStateFlow()

    // Sorting state
    private val _sortOption = MutableStateFlow(SortOption.DATE_CREATED)
    val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    val sortedNotesList: StateFlow<List<Note>> = combine(notesList, sortOption) { list, sort ->
        when (sort) {
            SortOption.DATE_CREATED -> list.sortedByDescending { it.createdAt }
            SortOption.LAST_MODIFIED -> list.sortedByDescending { it.updatedAt }
            SortOption.ALPHABETICAL -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sortedLinksList: StateFlow<List<Link>> = combine(linksList, sortOption) { list, sort ->
        when (sort) {
            SortOption.DATE_CREATED -> list.sortedByDescending { it.createdAt }
            SortOption.LAST_MODIFIED -> list.sortedByDescending { it.createdAt }
            SortOption.ALPHABETICAL -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSortOption(option: SortOption) {
        _sortOption.value = option
    }

    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResultsFolders = MutableStateFlow<List<Folder>>(emptyList())
    val searchResultsFolders: StateFlow<List<Folder>> = _searchResultsFolders.asStateFlow()

    private val _searchResultsNotes = MutableStateFlow<List<Note>>(emptyList())
    val searchResultsNotes: StateFlow<List<Note>> = _searchResultsNotes.asStateFlow()

    private val _searchResultsLinks = MutableStateFlow<List<Link>>(emptyList())
    val searchResultsLinks: StateFlow<List<Link>> = _searchResultsLinks.asStateFlow()

    // Loading / fetching indicators
    private val _isFetchingMetadata = MutableStateFlow(false)
    val isFetchingMetadata: StateFlow<Boolean> = _isFetchingMetadata.asStateFlow()

    init {
        // Collect notes and links dynamically based on the selected folder
        viewModelScope.launch {
            _selectedFolder.collectLatest { folder ->
                if (folder != null) {
                    if (folder.id == -100L) {
                        if (folder.type == "NOTES") {
                            repository.getArchivedNotes().collect { notes ->
                                _notesList.value = notes
                            }
                        } else {
                            repository.getArchivedLinks().collect { links ->
                                _linksList.value = links
                            }
                        }
                    } else {
                        if (folder.type == "NOTES") {
                            repository.getNotes(folder.id).collect { notes ->
                                _notesList.value = notes
                            }
                        } else {
                            repository.getLinks(folder.id).collect { links ->
                                _linksList.value = links
                            }
                        }
                    }
                } else {
                    _notesList.value = emptyList()
                    _linksList.value = emptyList()
                }
            }
        }

        // Setup reactive search
        viewModelScope.launch {
            _searchQuery
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if (query.isNotBlank()) {
                        launch(Dispatchers.IO) {
                            repository.searchFolders(query).collect { folders ->
                                _searchResultsFolders.value = folders
                            }
                        }
                        launch(Dispatchers.IO) {
                            repository.searchNotes(query).collect { notes ->
                                _searchResultsNotes.value = notes
                            }
                        }
                        launch(Dispatchers.IO) {
                            repository.searchLinks(query).collect { links ->
                                _searchResultsLinks.value = links
                            }
                        }
                    } else {
                        _searchResultsFolders.value = emptyList()
                        _searchResultsNotes.value = emptyList()
                        _searchResultsLinks.value = emptyList()
                    }
                }
        }
    }

    fun selectTab(tab: String) {
        _currentTab.value = tab
        _selectedFolder.value = null // reset folder selection when switching tabs
        sharedPrefs.edit().putString("current_tab", tab).apply()
    }

    fun selectFolder(folder: Folder?) {
        _selectedFolder.value = folder
    }

    fun selectFolderById(folderId: Long) {
        if (folderId == -100L) {
            _selectedFolder.value = Folder(id = -100L, name = "Archive", type = _currentTab.value, createdAt = 0L)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val folder = repository.getFolderById(folderId)
            _selectedFolder.value = folder
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // ==========================================
    // FOLDER CRUD
    // ==========================================
    fun createFolder(name: String, type: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertFolder(Folder(name = name, type = type))
        }
    }

    fun renameFolder(folderId: Long, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = repository.getFolderById(folderId)
            if (folder != null) {
                repository.updateFolder(folder.copy(name = newName))
                // update active folder state if it is the renamed folder
                if (_selectedFolder.value?.id == folderId) {
                    _selectedFolder.value = folder.copy(name = newName)
                }
            }
        }
    }

    fun deleteFolder(folder: Folder) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteFolder(folder)
            if (_selectedFolder.value?.id == folder.id) {
                _selectedFolder.value = null
            }
        }
    }

    // ==========================================
    // NOTE CRUD
    // ==========================================
    fun addNote(folderId: Long, title: String, content: String, isChecklist: Boolean = false, imagePath: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertNote(
                Note(
                    folderId = folderId,
                    title = title,
                    content = content,
                    isChecklist = isChecklist,
                    imagePath = imagePath
                )
            )
        }
    }

    fun updateNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateNote(note.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteNote(note)
        }
    }

    fun togglePinNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateNote(note.copy(isPinned = !note.isPinned))
        }
    }

    // ==========================================
    // LINK CRUD
    // ==========================================
    fun addLink(folderId: Long, url: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _isFetchingMetadata.value = true
            try {
                val metadata = withContext(Dispatchers.IO) {
                    LinkMetadataParser.fetchMetadata(getApplication(), url)
                }
                withContext(Dispatchers.IO) {
                    repository.insertLink(
                        Link(
                            folderId = folderId,
                            title = metadata.title,
                            url = metadata.originalUrl,
                            siteName = metadata.siteName,
                            imageUrl = metadata.imageUrl,
                            price = metadata.price,
                            brand = metadata.brand,
                            rating = metadata.rating
                        )
                    )
                }
                _isFetchingMetadata.value = false
                onComplete(true)
            } catch (e: Exception) {
                e.printStackTrace()
                // Insert with fallback metadata
                withContext(Dispatchers.IO) {
                    repository.insertLink(
                        Link(
                            folderId = folderId,
                            title = "Product Link",
                            url = url,
                            siteName = "Web",
                            imageUrl = null,
                            price = null,
                            brand = null,
                            rating = null
                        )
                    )
                }
                _isFetchingMetadata.value = false
                onComplete(false)
            }
        }
    }

    fun editLink(link: Link, newTitle: String, newUrl: String, newSiteName: String?, newPrice: String?, newBrand: String?, newRating: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateLink(
                link.copy(
                    title = newTitle,
                    url = newUrl,
                    siteName = newSiteName,
                    price = newPrice,
                    brand = newBrand,
                    rating = newRating
                )
            )
        }
    }

    fun deleteLink(link: Link) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteLink(link)
        }
    }

    fun archiveNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateNote(note.copy(isArchived = true, isPinned = false))
        }
    }

    fun unarchiveNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateNote(note.copy(isArchived = false))
        }
    }

    fun updateLink(link: Link) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateLink(link)
        }
    }

    fun archiveLink(link: Link) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateLink(link.copy(isArchived = true))
        }
    }

    fun unarchiveLink(link: Link) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateLink(link.copy(isArchived = false))
        }
    }

    fun archiveNotes(notes: List<Note>) {
        viewModelScope.launch(Dispatchers.IO) {
            notes.forEach { repository.updateNote(it.copy(isArchived = true, isPinned = false)) }
        }
    }

    fun unarchiveNotes(notes: List<Note>) {
        viewModelScope.launch(Dispatchers.IO) {
            notes.forEach { repository.updateNote(it.copy(isArchived = false)) }
        }
    }

    fun archiveLinks(links: List<Link>) {
        viewModelScope.launch(Dispatchers.IO) {
            links.forEach { repository.updateLink(it.copy(isArchived = true)) }
        }
    }

    fun unarchiveLinks(links: List<Link>) {
        viewModelScope.launch(Dispatchers.IO) {
            links.forEach { repository.updateLink(it.copy(isArchived = false)) }
        }
    }

    // Simple Factory pattern for ViewModel
    class Factory(
        private val application: Application,
        private val repository: PunctualRepository
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PunctualViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return PunctualViewModel(application, repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

enum class SortOption {
    DATE_CREATED,
    LAST_MODIFIED,
    ALPHABETICAL
}
