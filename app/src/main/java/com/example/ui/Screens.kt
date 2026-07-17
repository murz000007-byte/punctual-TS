package com.example.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.debounce
import com.example.data.AppDatabase
import com.example.data.Folder
import com.example.data.Link
import com.example.data.Note
import java.text.SimpleDateFormat
import java.util.*

// ==========================================
// NAVIGATION HELPER
// ==========================================
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object FolderDetail : Screen("folder/{folderId}") {
        fun createRoute(folderId: Long) = "folder/$folderId"
    }
    object NoteEditor : Screen("note/{noteId}/{folderId}") {
        fun createRoute(noteId: Long, folderId: Long) = "note/$noteId/$folderId"
    }
}

// Simple Checklist item structure
data class ChecklistItem(val text: String, val isChecked: Boolean)

// Helper to convert plain checklist strings to ChecklistItem classes
fun String.toChecklistItems(): List<ChecklistItem> {
    if (this.isEmpty()) return emptyList()
    return this.lines().mapNotNull { line ->
        if (line.startsWith("[ ] ")) {
            ChecklistItem(text = line.substring(4), isChecked = false)
        } else if (line.startsWith("[x] ")) {
            ChecklistItem(text = line.substring(4), isChecked = true)
        } else {
            ChecklistItem(text = line, isChecked = false)
        }
    }
}

fun List<ChecklistItem>.toContentString(): String {
    return joinToString("\n") { item ->
        if (item.isChecked) "[x] ${item.text}" else "[ ] ${item.text}"
    }
}

@Composable
fun formatMarkdown(text: String): AnnotatedString {
    return remember(text) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                when {
                    text.startsWith("**", i) -> {
                        val end = text.indexOf("**", i + 2)
                        if (end != -1) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(text.substring(i + 2, end))
                            }
                            i = end + 2
                        } else {
                            append("**")
                            i += 2
                        }
                    }
                    text.startsWith("*", i) -> {
                        val end = text.indexOf("*", i + 1)
                        if (end != -1) {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                append(text.substring(i + 1, end))
                            }
                            i = end + 1
                        } else {
                            append("*")
                            i += 1
                        }
                    }
                    text.startsWith("__", i) -> {
                        val end = text.indexOf("__", i + 2)
                        if (end != -1) {
                            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                                append(text.substring(i + 2, end))
                            }
                            i = end + 2
                        } else {
                            append("__")
                            i += 2
                        }
                    }
                    else -> {
                        append(text[i])
                        i++
                    }
                }
            }
        }
    }
}

// ==========================================
// COMPOSABLE SCREENS
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: PunctualViewModel,
    onNavigateToFolder: (Long) -> Unit,
    onNavigateToNote: (Long, Long) -> Unit,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val notesFolders by viewModel.notesFolders.collectAsStateWithLifecycle()
    val linksFolders by viewModel.linksFolders.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    val searchResultsFolders by viewModel.searchResultsFolders.collectAsStateWithLifecycle()
    val searchResultsNotes by viewModel.searchResultsNotes.collectAsStateWithLifecycle()
    val searchResultsLinks by viewModel.searchResultsLinks.collectAsStateWithLifecycle()

    var showAddFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<Folder?>(null) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }

    Scaffold(
        topBar = {
            Column {
                // Sophisticated Top App Bar matching the Design HTML
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Punctual TS",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.5).sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Theme selector
                        IconButton(
                            onClick = onToggleTheme,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    CircleShape
                                )
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Toggle theme",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Profile badge
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                .clickable { /* Profile interaction */ },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "JD",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // Search Bar (MD3 Style)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search icon",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search folders, notes, links...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                androidx.compose.foundation.text.BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { viewModel.updateSearchQuery(it) },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("search_input")
                                )
                            }
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { viewModel.updateSearchQuery("") },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear search",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Module Tabs
                if (searchQuery.isBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (currentTab == "NOTES") MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                )
                                .clickable { viewModel.selectTab("NOTES") }
                                .padding(vertical = 10.dp)
                                .testTag("tab_chip_Notes"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (currentTab == "NOTES") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Notes",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = if (currentTab == "NOTES") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (currentTab == "LINKS") MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                )
                                .clickable { viewModel.selectTab("LINKS") }
                                .padding(vertical = 10.dp)
                                .testTag("tab_chip_Links"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    tint = if (currentTab == "LINKS") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Links",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = if (currentTab == "LINKS") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Folders Header Horizontal strip simulation
                if (searchQuery.isBlank()) {
                    val folders = if (currentTab == "NOTES") notesFolders else linksFolders
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (currentTab == "NOTES") "FOLDERS" else "CATEGORIES",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${folders.size} Total",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (searchQuery.isBlank()) {
                FloatingActionButton(
                    onClick = { showAddFolderDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("add_folder_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create new",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(80.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Item 1: Home (Active)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { /* Already on Home */ }
                            .padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Home",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Home",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    // Item 2: Folders
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { 
                                // Reset search to show folders
                                viewModel.updateSearchQuery("")
                            }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Folders",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Folders",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    // Item 3: Settings
                    var showSettingsDialog by remember { mutableStateOf(false) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { showSettingsDialog = true }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    if (showSettingsDialog) {
                        AlertDialog(
                            onDismissRequest = { showSettingsDialog = false },
                            title = { Text("Punctual TS Settings") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("This is a sophisticated, highly optimized local productivity companion configured with the 'Sophisticated Dark' Material 3 theme.")
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Theme Mode", fontWeight = FontWeight.Bold)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(if (isDarkTheme) "Dark" else "Light")
                                            androidx.compose.material3.Switch(
                                                checked = isDarkTheme,
                                                onCheckedChange = { onToggleTheme() },
                                                modifier = Modifier.testTag("theme_switch")
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                Button(onClick = { showSettingsDialog = false }) {
                                    Text("Done")
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (searchQuery.isNotBlank()) {
                // Display global search results
                GlobalSearchScreen(
                    query = searchQuery,
                    searchResultsFolders = searchResultsFolders,
                    searchResultsNotes = searchResultsNotes,
                    searchResultsLinks = searchResultsLinks,
                    onNavigateToFolder = { folder ->
                        viewModel.selectFolder(folder)
                        onNavigateToFolder(folder.id)
                    },
                    onNavigateToNote = onNavigateToNote,
                    viewModel = viewModel
                )
            } else {
                // Display folder grid with system-provided Archive folder appended
                val customFolders = if (currentTab == "NOTES") notesFolders else linksFolders
                val folders = customFolders + Folder(
                    id = -100L,
                    name = "Archive",
                    type = currentTab,
                    createdAt = 0L
                )

                val context = LocalContext.current
                val sharedPrefs = remember(context) { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
                
                val gridIndexKey = "folders_scroll_${currentTab}_index"
                val gridOffsetKey = "folders_scroll_${currentTab}_offset"
                
                val initialIndex = remember(currentTab) { sharedPrefs.getInt(gridIndexKey, 0) }
                val initialOffset = remember(currentTab) { sharedPrefs.getInt(gridOffsetKey, 0) }
                
                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
                    initialFirstVisibleItemIndex = initialIndex,
                    initialFirstVisibleItemScrollOffset = initialOffset
                )
                
                LaunchedEffect(gridState, currentTab) {
                    snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                        .debounce(500)
                        .collect { (index, offset) ->
                            sharedPrefs.edit()
                                .putInt(gridIndexKey, index)
                                .putInt(gridOffsetKey, offset)
                                .apply()
                        }
                }

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(folders, key = { it.id }) { folder ->
                        FolderGridItem(
                            folder = folder,
                            onClick = {
                                viewModel.selectFolder(folder)
                                onNavigateToFolder(folder.id)
                            },
                            onRename = { folderToRename = folder },
                            onDelete = { folderToDelete = folder }
                        )
                    }
                }
            }
        }
    }

    // Add Folder Dialog
    if (showAddFolderDialog) {
        var newFolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddFolderDialog = false },
            title = { Text("Create ${if (currentTab == "NOTES") "Notes Folder" else "Links Folder"}") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_folder_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            viewModel.createFolder(newFolderName.trim(), currentTab)
                            showAddFolderDialog = false
                        }
                    },
                    enabled = newFolderName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rename Folder Dialog
    folderToRename?.let { folder ->
        var renameValue by remember { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text("Rename Folder") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("New Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameValue.isNotBlank()) {
                            viewModel.renameFolder(folder.id, renameValue.trim())
                            folderToRename = null
                        }
                    },
                    enabled = renameValue.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Folder Dialog
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("Delete Folder?") },
            text = { Text("Are you sure you want to delete '${folder.name}'? All contents inside this folder will be permanently erased. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteFolder(folder)
                        folderToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun TabChip(
    title: String,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("tab_chip_$title"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = contentColor)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor
        )
    }
}

fun Modifier.size(size: androidx.compose.ui.unit.Dp): Modifier = this.width(size).height(size)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderGridItem(
    folder: Folder,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (folder.id != -100L) showMenu = true }
            )
            .testTag("folder_item_${folder.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Folder visual logo
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (folder.id == -100L) Icons.Default.Archive
                                      else if (folder.type == "NOTES") Icons.Default.FolderOpen 
                                      else Icons.Default.Bookmarks,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                if (folder.id != -100L) {
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onRename()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                }
                            )
                        }
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Archive,
                        contentDescription = "Archived Folder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = if (folder.id == -100L) "Archived items"
                       else if (folder.type == "NOTES") "Personal Notes"
                       else "Product Links",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

// ==========================================
// FOLDER DETAIL SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailScreen(
    folderId: Long,
    viewModel: PunctualViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToNote: (Long, Long) -> Unit
) {
    val selectedFolder by viewModel.selectedFolder.collectAsStateWithLifecycle()
    val notesList by viewModel.sortedNotesList.collectAsStateWithLifecycle()
    val linksList by viewModel.sortedLinksList.collectAsStateWithLifecycle()
    val isFetchingMetadata by viewModel.isFetchingMetadata.collectAsStateWithLifecycle()

    var showAddLinkDialog by remember { mutableStateOf(false) }
    var folderSearchQuery by remember { mutableStateOf("") }
    
    var linkToEdit by remember { mutableStateOf<Link?>(null) }
    var linkToDelete by remember { mutableStateOf<Link?>(null) }

    val context = LocalContext.current
    var selectedNoteIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedLinkIds by remember { mutableStateOf(setOf<Long>()) }
    val isSelectionModeActive = selectedNoteIds.isNotEmpty() || selectedLinkIds.isNotEmpty()

    fun toggleNoteSelection(noteId: Long) {
        selectedNoteIds = if (selectedNoteIds.contains(noteId)) {
            selectedNoteIds - noteId
        } else {
            selectedNoteIds + noteId
        }
    }

    fun toggleLinkSelection(linkId: Long) {
        selectedLinkIds = if (selectedLinkIds.contains(linkId)) {
            selectedLinkIds - linkId
        } else {
            selectedLinkIds + linkId
        }
    }

    fun clearSelection() {
        selectedNoteIds = emptySet()
        selectedLinkIds = emptySet()
    }

    // Reset selection if folderId changes
    LaunchedEffect(folderId) {
        clearSelection()
    }

    // Fetch folder info dynamically if selected folder is null
    LaunchedEffect(folderId) {
        if (selectedFolder == null || selectedFolder?.id != folderId) {
            viewModel.selectFolderById(folderId)
        }
    }

    val folder = selectedFolder ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionModeActive) {
                        Text(
                            text = "${if (folder.type == "NOTES") selectedNoteIds.size else selectedLinkIds.size} selected",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    } else {
                        Column {
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (folder.type == "NOTES") "${notesList.size} items" else "${linksList.size} product cards",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (isSelectionModeActive) {
                        IconButton(onClick = { clearSelection() }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    } else {
                        IconButton(onClick = onNavigateBack) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back")
                        }
                    }
                },
                actions = {
                    if (isSelectionModeActive) {
                        // Select All
                        IconButton(onClick = {
                            if (folder.type == "NOTES") {
                                selectedNoteIds = notesList.map { it.id }.toSet()
                            } else {
                                selectedLinkIds = linksList.map { it.id }.toSet()
                            }
                        }) {
                            Icon(imageVector = Icons.Default.SelectAll, contentDescription = "Select All")
                        }

                        // Batch Archive/Restore Action
                        if (folderId != -100L) {
                            IconButton(onClick = {
                                if (folder.type == "NOTES") {
                                    val selectedNotes = notesList.filter { selectedNoteIds.contains(it.id) }
                                    viewModel.archiveNotes(selectedNotes)
                                    Toast.makeText(context, "Moved ${selectedNotes.size} notes to Archive", Toast.LENGTH_SHORT).show()
                                } else {
                                    val selectedLinks = linksList.filter { selectedLinkIds.contains(it.id) }
                                    viewModel.archiveLinks(selectedLinks)
                                    Toast.makeText(context, "Moved ${selectedLinks.size} links to Archive", Toast.LENGTH_SHORT).show()
                                }
                                clearSelection()
                            }) {
                                Icon(imageVector = Icons.Default.Archive, contentDescription = "Archive Selected")
                            }
                        } else {
                            IconButton(onClick = {
                                if (folder.type == "NOTES") {
                                    val selectedNotes = notesList.filter { selectedNoteIds.contains(it.id) }
                                    viewModel.unarchiveNotes(selectedNotes)
                                    Toast.makeText(context, "Restored ${selectedNotes.size} notes from Archive", Toast.LENGTH_SHORT).show()
                                } else {
                                    val selectedLinks = linksList.filter { selectedLinkIds.contains(it.id) }
                                    viewModel.unarchiveLinks(selectedLinks)
                                    Toast.makeText(context, "Restored ${selectedLinks.size} links from Archive", Toast.LENGTH_SHORT).show()
                                }
                                clearSelection()
                            }) {
                                Icon(imageVector = Icons.Default.Unarchive, contentDescription = "Restore Selected")
                            }
                        }
                    } else {
                        // Regular Search
                        var isSearching by remember { mutableStateOf(false) }
                        if (isSearching) {
                            OutlinedTextField(
                                value = folderSearchQuery,
                                onValueChange = { folderSearchQuery = it },
                                placeholder = { Text("Filter items...") },
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        folderSearchQuery = ""
                                        isSearching = false
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = null)
                                    }
                                },
                                modifier = Modifier
                                    .width(180.dp)
                                    .padding(end = 8.dp)
                            )
                        } else {
                            IconButton(onClick = { isSearching = true }) {
                                Icon(imageVector = Icons.Default.Search, contentDescription = "Filter")
                            }

                            // Sorting Menu Button
                            var showSortMenu by remember { mutableStateOf(false) }
                            val activeSortOption by viewModel.sortOption.collectAsStateWithLifecycle()

                            Box {
                                IconButton(
                                    onClick = { showSortMenu = true },
                                    modifier = Modifier.testTag("sort_menu_button")
                                ) {
                                    Icon(imageVector = Icons.Default.Sort, contentDescription = "Sort options")
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Date Created") },
                                        onClick = {
                                            viewModel.setSortOption(SortOption.DATE_CREATED)
                                            showSortMenu = false
                                        },
                                        leadingIcon = {
                                            if (activeSortOption == SortOption.DATE_CREATED) {
                                                Icon(Icons.Default.Check, contentDescription = "Active")
                                            }
                                        },
                                        modifier = Modifier.testTag("sort_option_date_created")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Last Modified") },
                                        onClick = {
                                            viewModel.setSortOption(SortOption.LAST_MODIFIED)
                                            showSortMenu = false
                                        },
                                        leadingIcon = {
                                            if (activeSortOption == SortOption.LAST_MODIFIED) {
                                                Icon(Icons.Default.Check, contentDescription = "Active")
                                            }
                                        },
                                        modifier = Modifier.testTag("sort_option_last_modified")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Alphabetical") },
                                        onClick = {
                                            viewModel.setSortOption(SortOption.ALPHABETICAL)
                                            showSortMenu = false
                                        },
                                        leadingIcon = {
                                            if (activeSortOption == SortOption.ALPHABETICAL) {
                                                Icon(Icons.Default.Check, contentDescription = "Active")
                                            }
                                        },
                                        modifier = Modifier.testTag("sort_option_alphabetical")
                                    )
                                }
                            }

                            // Select button to manually enter multi-selection
                            IconButton(onClick = {
                                if (folder.type == "NOTES") {
                                    if (notesList.isNotEmpty()) {
                                        selectedNoteIds = setOf(notesList.first().id)
                                    }
                                } else {
                                    if (linksList.isNotEmpty()) {
                                        selectedLinkIds = setOf(linksList.first().id)
                                    }
                                }
                            }) {
                                Icon(imageVector = Icons.Default.PlaylistAddCheck, contentDescription = "Select multiple items")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            if (folderId != -100L) {
                ExtendedFloatingActionButton(
                    text = { Text(if (folder.type == "NOTES") "Write Note" else "Add Link") },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = {
                        if (folder.type == "NOTES") {
                            // Navigate to new note editor with noteId = -1
                            onNavigateToNote(-1L, folder.id)
                        } else {
                            showAddLinkDialog = true
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("add_item_fab")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (folder.type == "NOTES") {
                // Notes View
                val filteredNotes = notesList.filter {
                    it.title.contains(folderSearchQuery, ignoreCase = true) ||
                            it.content.contains(folderSearchQuery, ignoreCase = true)
                }

                if (filteredNotes.isEmpty()) {
                    EmptyStatePlaceholder(
                        title = if (folderSearchQuery.isNotBlank()) "No Matching Notes" else "No Notes Yet",
                        subtitle = if (folderSearchQuery.isNotBlank()) "Try searching for another phrase or keyword inside this folder."
                            else "Click 'Write Note' below to create your first formatted note or checklist inside this category.",
                        icon = Icons.Outlined.Lightbulb
                    )
                } else {
                    val context = LocalContext.current
                    val sharedPrefs = remember(context) { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
                    
                    val listIndexKey = "folder_notes_scroll_${folderId}_index"
                    val listOffsetKey = "folder_notes_scroll_${folderId}_offset"
                    
                    val initialIndex = remember(folderId) { sharedPrefs.getInt(listIndexKey, 0) }
                    val initialOffset = remember(folderId) { sharedPrefs.getInt(listOffsetKey, 0) }
                    
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
                        initialFirstVisibleItemIndex = initialIndex,
                        initialFirstVisibleItemScrollOffset = initialOffset
                    )
                    
                    LaunchedEffect(listState, folderId) {
                        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                            .debounce(500)
                            .collect { (index, offset) ->
                                sharedPrefs.edit()
                                    .putInt(listIndexKey, index)
                                    .putInt(listOffsetKey, offset)
                                    .apply()
                            }
                    }

                    // Elegant Staggered-like Dual Column Grid for Google Notes experience
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredNotes, key = { it.id }) { note ->
                            val isSelected = selectedNoteIds.contains(note.id)
                            NoteCardItem(
                                note = note,
                                onClick = {
                                    if (isSelectionModeActive) {
                                        toggleNoteSelection(note.id)
                                    } else {
                                        onNavigateToNote(note.id, folder.id)
                                    }
                                },
                                onTogglePin = { viewModel.togglePinNote(note) },
                                onDelete = { viewModel.deleteNote(note) },
                                onArchive = {
                                    if (folderId == -100L) {
                                        viewModel.unarchiveNote(note)
                                    } else {
                                        viewModel.archiveNote(note)
                                    }
                                },
                                isArchiveView = (folderId == -100L),
                                isSelected = isSelected,
                                isSelectionModeActive = isSelectionModeActive,
                                onLongClick = {
                                    toggleNoteSelection(note.id)
                                }
                            )
                        }
                    }
                }
            } else {
                // Links View
                val filteredLinks = linksList.filter {
                    it.title.contains(folderSearchQuery, ignoreCase = true) ||
                            (it.siteName ?: "").contains(folderSearchQuery, ignoreCase = true) ||
                            it.url.contains(folderSearchQuery, ignoreCase = true)
                }

                if (filteredLinks.isEmpty()) {
                    EmptyStatePlaceholder(
                        title = if (folderSearchQuery.isNotBlank()) "No Matching Links" else "No Links Saved",
                        subtitle = if (folderSearchQuery.isNotBlank()) "Try refining your search text or add a new URL."
                            else "Paste any online commerce product link from Amazon, Flipkart, Myntra, Nykaa, or other stores. We will index it offline for you.",
                        icon = Icons.Outlined.Link
                    )
                } else {
                    val context = LocalContext.current
                    val sharedPrefs = remember(context) { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
                    
                    val listIndexKey = "folder_links_scroll_${folderId}_index"
                    val listOffsetKey = "folder_links_scroll_${folderId}_offset"
                    
                    val initialIndex = remember(folderId) { sharedPrefs.getInt(listIndexKey, 0) }
                    val initialOffset = remember(folderId) { sharedPrefs.getInt(listOffsetKey, 0) }
                    
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
                        initialFirstVisibleItemIndex = initialIndex,
                        initialFirstVisibleItemScrollOffset = initialOffset
                    )
                    
                    LaunchedEffect(listState, folderId) {
                        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                            .debounce(500)
                            .collect { (index, offset) ->
                                sharedPrefs.edit()
                                    .putInt(listIndexKey, index)
                                    .putInt(listOffsetKey, offset)
                                    .apply()
                            }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredLinks, key = { it.id }) { link ->
                            val isSelected = selectedLinkIds.contains(link.id)
                            ProductLinkCard(
                                link = link,
                                onEdit = { linkToEdit = link },
                                onDelete = { linkToDelete = link },
                                onArchive = {
                                    if (folderId == -100L) {
                                        viewModel.unarchiveLink(link)
                                    } else {
                                        viewModel.archiveLink(link)
                                    }
                                },
                                isArchiveView = (folderId == -100L),
                                isSelected = isSelected,
                                isSelectionModeActive = isSelectionModeActive,
                                onClick = {
                                    toggleLinkSelection(link.id)
                                },
                                onLongClick = {
                                    toggleLinkSelection(link.id)
                                }
                            )
                        }
                    }
                }
            }

            // Spinner while auto-fetching link metadata
            if (isFetchingMetadata) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Fetching Details...",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                "Extracting product image, rating, brand, website name & price details...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(220.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    // Add Link Dialog
    if (showAddLinkDialog) {
        var pastedUrl by remember { mutableStateOf("") }
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showAddLinkDialog = false },
            title = { Text("Save Product Link") },
            text = {
                Column {
                    Text(
                        "Paste a web URL. We automatically extract and download price, title, and image metadata for instant offline indexing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = pastedUrl,
                        onValueChange = { pastedUrl = it },
                        label = { Text("Product URL") },
                        singleLine = true,
                        placeholder = { Text("https://amazon.in/dp/...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pasted_url_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pastedUrl.isNotBlank()) {
                            showAddLinkDialog = false
                            viewModel.addLink(folder.id, pastedUrl.trim()) { success ->
                                if (success) {
                                    Toast.makeText(context, "Successfully index link metadata offline!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Saved link with placeholder metadata.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    enabled = pastedUrl.isNotBlank()
                ) {
                    Text("Index Link")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddLinkDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Edit Link Dialog
    linkToEdit?.let { link ->
        var editTitle by remember { mutableStateOf(link.title) }
        var editUrl by remember { mutableStateOf(link.url) }
        var editSiteName by remember { mutableStateOf(link.siteName ?: "") }
        var editPrice by remember { mutableStateOf(link.price ?: "") }
        var editBrand by remember { mutableStateOf(link.brand ?: "") }
        var editRating by remember { mutableStateOf(link.rating ?: "") }

        AlertDialog(
            onDismissRequest = { linkToEdit = null },
            title = { Text("Edit Saved Link") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Product Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        label = { Text("URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editSiteName,
                        onValueChange = { editSiteName = it },
                        label = { Text("Website Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editPrice,
                            onValueChange = { editPrice = it },
                            label = { Text("Price") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editBrand,
                            onValueChange = { editBrand = it },
                            label = { Text("Brand") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = editRating,
                        onValueChange = { editRating = it },
                        label = { Text("Rating (e.g. 4.5)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editTitle.isNotBlank() && editUrl.isNotBlank()) {
                            viewModel.editLink(
                                link = link,
                                newTitle = editTitle.trim(),
                                newUrl = editUrl.trim(),
                                newSiteName = editSiteName.trim().takeIf { it.isNotEmpty() },
                                newPrice = editPrice.trim().takeIf { it.isNotEmpty() },
                                newBrand = editBrand.trim().takeIf { it.isNotEmpty() },
                                newRating = editRating.trim().takeIf { it.isNotEmpty() }
                            )
                            linkToEdit = null
                        }
                    },
                    enabled = editTitle.isNotBlank() && editUrl.isNotBlank()
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { linkToEdit = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Link Dialog
    linkToDelete?.let { link ->
        AlertDialog(
            onDismissRequest = { linkToDelete = null },
            title = { Text("Delete Saved Link?") },
            text = { Text("Are you sure you want to delete '${link.title}'? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteLink(link)
                        linkToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { linkToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCardItem(
    note: Note,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit = {},
    isArchiveView: Boolean = false,
    isSelected: Boolean = false,
    isSelectionModeActive: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val formatter = remember { SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault()) }
    val dateString = formatter.format(Date(note.updatedAt))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("note_card_${note.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                             else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            if (isSelected) 2.dp else 1.dp, 
            if (isSelected) MaterialTheme.colorScheme.primary
            else if (note.isPinned) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) 
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionModeActive) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(start = 16.dp).testTag("note_checkbox_${note.id}")
                )
            }
            if (note.isPinned && !isSelectionModeActive) {
                // border-l-4 style accent highlight bar
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp)
            ) {
                // Display optional note image preview
                note.imagePath?.let { uriString ->
                    AsyncImage(
                        model = uriString,
                        contentDescription = "Attached note banner",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .padding(bottom = 12.dp),
                        contentScale = ContentScale.Crop
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = note.title.ifBlank { "Untitled Note" },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = dateString,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }

                    if (!isSelectionModeActive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!isArchiveView) {
                                IconButton(onClick = onTogglePin, modifier = Modifier.size(24.dp)) {
                                    Icon(
                                        imageVector = if (note.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                        contentDescription = "Pin Note",
                                        tint = if (note.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            IconButton(onClick = onArchive, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = if (isArchiveView) Icons.Default.Unarchive else Icons.Default.Archive,
                                    contentDescription = if (isArchiveView) "Unarchive Note" else "Archive Note",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Note",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (note.isChecklist) {
                    // Render checklist item preview (first 4 items max)
                    val checklistItems = note.content.toChecklistItems().take(4)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        checklistItems.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (item.isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = if (item.isChecked) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    textDecoration = if (item.isChecked) TextDecoration.LineThrough else TextDecoration.None,
                                    color = if (item.isChecked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (note.content.toChecklistItems().size > 4) {
                            Text(
                                text = "+ ${note.content.toChecklistItems().size - 4} more items",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 21.dp, top = 2.dp)
                            )
                        }
                    }
                } else {
                    // Standard markdown body preview
                    Text(
                        text = formatMarkdown(note.content),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProductLinkCard(
    link: Link,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit = {},
    isArchiveView: Boolean = false,
    isSelected: Boolean = false,
    isSelectionModeActive: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("product_card_${link.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            if (isSelected) 2.dp else 1.dp, 
            if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionModeActive) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.padding(end = 8.dp).testTag("link_checkbox_${link.id}")
                    )
                }
                // Image or high-quality placeholder
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (link.imageUrl != null) {
                        AsyncImage(
                            model = link.imageUrl,
                            contentDescription = link.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // Custom vector icon placeholder
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ShoppingBag,
                                contentDescription = "Product placeholder",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    // Site Tag
                    link.siteName?.let { tag ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(topEnd = 8.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Metadata Details
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = link.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Price
                        link.price?.let { pr ->
                            Text(
                                text = pr,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Rating
                        link.rating?.let { rt ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Rating",
                                    tint = Color(0xFFFBBF24),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = rt,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Brand
                    link.brand?.let { br ->
                        Text(
                            text = "by $br",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Bottom Actions Panel
            if (!isSelectionModeActive) {
                // Divider Line
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    thickness = 1.dp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(link.url))
                            Toast.makeText(context, "Product link copied to clipboard!", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy URL",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Web Link button
                        IconButton(onClick = {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(link.url))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot open browser. URL: ${link.url}", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Launch,
                                contentDescription = "Open Browser",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Share Link button
                        IconButton(onClick = {
                            try {
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, "${link.title}: ${link.url}")
                                    type = "text/plain"
                                }
                                val shareIntent = android.content.Intent.createChooser(sendIntent, "Share Link")
                                context.startActivity(shareIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot share link", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Link",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onArchive) {
                            Icon(
                                imageVector = if (isArchiveView) Icons.Default.Unarchive else Icons.Default.Archive,
                                contentDescription = if (isArchiveView) "Unarchive link" else "Archive link",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (!isArchiveView) {
                            IconButton(onClick = onEdit) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Edit link details",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete product card",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// NOTE EDITOR SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    noteId: Long,
    folderId: Long,
    viewModel: PunctualViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var isChecklist by remember { mutableStateOf(false) }
    var imagePath by remember { mutableStateOf<String?>(null) }
    var isPinned by remember { mutableStateOf(false) }

    // State for Checklist mode items
    var checklistItems by remember { mutableStateOf<List<ChecklistItem>>(emptyList()) }

    // Media picker for images picking
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistable permission if possible, otherwise use standard string representation
            try {
                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
            } catch (e: Exception) {
                // Fallback
            }
            imagePath = it.toString()
        }
    }

    // Load note if editing
    LaunchedEffect(noteId) {
        if (noteId != -1L) {
            val note = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(context).noteDao().getNoteById(noteId)
            }
            if (note != null) {
                title = note.title
                content = note.content
                isChecklist = note.isChecklist
                imagePath = note.imagePath
                isPinned = note.isPinned

                if (note.isChecklist) {
                    checklistItems = note.content.toChecklistItems()
                }
            }
        }
    }

    // Auto-save logic on back press / change
    fun performSave() {
        val finalContent = if (isChecklist) checklistItems.toContentString() else content
        if (title.isBlank() && finalContent.isBlank() && imagePath == null) {
            // Nothing to save
            return
        }
        if (noteId == -1L) {
            viewModel.addNote(
                folderId = folderId,
                title = title.trim(),
                content = finalContent.trim(),
                isChecklist = isChecklist,
                imagePath = imagePath
            )
        } else {
            viewModel.updateNote(
                Note(
                    id = noteId,
                    folderId = folderId,
                    title = title.trim(),
                    content = finalContent.trim(),
                    isChecklist = isChecklist,
                    imagePath = imagePath,
                    isPinned = isPinned
                )
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (noteId == -1L) "Write Note" else "Edit Note") },
                navigationIcon = {
                    IconButton(onClick = {
                        performSave()
                        onNavigateBack()
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back and save")
                    }
                },
                actions = {
                    IconButton(onClick = { isPinned = !isPinned }) {
                        Icon(
                            imageVector = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "Pin Note",
                            tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = {
                        imagePickerLauncher.launch("image/*")
                    }) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "Add image attachment"
                        )
                    }

                    if (imagePath != null) {
                        IconButton(onClick = { imagePath = null }) {
                            Icon(
                                imageVector = Icons.Default.HideImage,
                                contentDescription = "Remove image attachment",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Visual Banner Preview
            imagePath?.let { path ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .padding(bottom = 12.dp)
                ) {
                    AsyncImage(
                        model = path,
                        contentDescription = "Note attachment",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    IconButton(
                        onClick = { imagePath = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Note title field
            TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Title", style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("note_title_input")
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // Switch layout between standard editor and Checklist editor
            Box(modifier = Modifier.weight(1f)) {
                if (isChecklist) {
                    // Checklist mode
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        checklistItems.forEachIndexed { index, item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        checklistItems = checklistItems.mapIndexed { idx, value ->
                                            if (idx == index) value.copy(isChecked = !value.isChecked) else value
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (item.isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                        contentDescription = null,
                                        tint = if (item.isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                OutlinedTextField(
                                    value = item.text,
                                    onValueChange = { newText ->
                                        checklistItems = checklistItems.mapIndexed { idx, value ->
                                            if (idx == index) value.copy(text = newText) else value
                                        }
                                    },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        textDecoration = if (item.isChecked) TextDecoration.LineThrough else TextDecoration.None,
                                        color = if (item.isChecked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent
                                    )
                                )

                                IconButton(
                                    onClick = {
                                        checklistItems = checklistItems.filterIndexed { idx, _ -> idx != index }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Remove checkpoint",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }

                        // Add checklist item action
                        Button(
                            onClick = {
                                checklistItems = checklistItems + ChecklistItem("", false)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .padding(16.dp)
                                .align(Alignment.CenterHorizontally)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Checklist Item")
                        }
                    }
                } else {
                    // Rich markdown mode
                    TextField(
                        value = content,
                        onValueChange = { content = it },
                        placeholder = { Text("Note content (supports **bold**, *italic*, __underline__ formatting tags)...", style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))) },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("note_content_input")
                    )
                }
            }

            // Quick Formatting toolbar panel
            Card(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = {
                                isChecklist = !isChecklist
                                if (isChecklist && checklistItems.isEmpty()) {
                                    // Parse current lines into checklist
                                    checklistItems = if (content.isNotBlank()) {
                                        content.lines().map { ChecklistItem(it, false) }
                                    } else {
                                        listOf(ChecklistItem("", false))
                                    }
                                } else if (!isChecklist) {
                                    // Compile checklist into content
                                    content = checklistItems.toContentString()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isChecklist) Icons.Default.Notes else Icons.Default.FormatListBulleted,
                                contentDescription = "Toggle Checklist Mode",
                                tint = if (isChecklist) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (!isChecklist) {
                            // Bold shortcut button
                            IconButton(onClick = { content += "**bold**" }) {
                                Icon(Icons.Default.FormatBold, contentDescription = "Insert bold")
                            }
                            // Italic shortcut button
                            IconButton(onClick = { content += "*italic*" }) {
                                Icon(Icons.Default.FormatItalic, contentDescription = "Insert italic")
                            }
                            // Underline shortcut button
                            IconButton(onClick = { content += "__underlined__" }) {
                                Icon(Icons.Default.FormatUnderlined, contentDescription = "Insert underlined")
                            }
                        }
                    }

                    Button(
                        onClick = {
                            performSave()
                            Toast.makeText(context, "Note auto-saved safely", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save")
                    }
                }
            }
        }
    }
}

// ==========================================
// GLOBAL SEARCH COMPOSABLE SCREEN
// ==========================================
@Composable
fun GlobalSearchScreen(
    query: String,
    searchResultsFolders: List<Folder>,
    searchResultsNotes: List<Note>,
    searchResultsLinks: List<Link>,
    onNavigateToFolder: (Folder) -> Unit,
    onNavigateToNote: (Long, Long) -> Unit,
    viewModel: PunctualViewModel
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // MATCHING FOLDERS SECTION
        if (searchResultsFolders.isNotEmpty()) {
            item {
                Text(
                    text = "Matching Folders",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(searchResultsFolders, key = { "folder_${it.id}" }) { folder ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToFolder(folder) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (folder.type == "NOTES") Icons.Default.FolderOpen else Icons.Default.Bookmarks,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(folder.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                            Text(
                                text = if (folder.type == "NOTES") "Personal Notes Folder" else "Product Links Folder",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        // MATCHING NOTES SECTION
        if (searchResultsNotes.isNotEmpty()) {
            item {
                Text(
                    text = "Matching Notes",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(searchResultsNotes, key = { "note_${it.id}" }) { note ->
                NoteCardItem(
                    note = note,
                    onClick = { onNavigateToNote(note.id, note.folderId) },
                    onTogglePin = { viewModel.togglePinNote(note) },
                    onDelete = { viewModel.deleteNote(note) },
                    onArchive = { viewModel.archiveNote(note) }
                )
            }
        }

        // MATCHING LINKS SECTION
        if (searchResultsLinks.isNotEmpty()) {
            item {
                Text(
                    text = "Matching Product Links",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(searchResultsLinks, key = { "link_${it.id}" }) { link ->
                ProductLinkCard(
                    link = link,
                    onEdit = { /* can manage in dialog */ },
                    onDelete = { viewModel.deleteLink(link) },
                    onArchive = { viewModel.archiveLink(link) }
                )
            }
        }

        // Global empty state inside search
        if (searchResultsFolders.isEmpty() && searchResultsNotes.isEmpty() && searchResultsLinks.isEmpty()) {
            item {
                EmptyStatePlaceholder(
                    title = "No Matches Found",
                    subtitle = "We couldn't find any folders, notes, checklist terms, website tags, or product names matching '$query'. Try another keyword.",
                    icon = Icons.Outlined.SearchOff
                )
            }
        }
    }
}

// ==========================================
// DECORATIVE EMPTY STATE COMPONENT
// ==========================================
@Composable
fun EmptyStatePlaceholder(
    title: String,
    subtitle: String,
    icon: ImageVector
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}
