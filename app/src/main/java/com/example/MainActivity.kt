package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.AppDatabase
import com.example.data.PunctualRepository
import com.example.ui.FolderDetailScreen
import com.example.ui.HomeScreen
import com.example.ui.NoteEditorScreen
import com.example.ui.PunctualViewModel
import com.example.ui.Screen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fix for Android launcher task resumption bug (app starting from beginning when opened from home screen launcher)
        if (!isTaskRoot && 
            intent.hasCategory(Intent.CATEGORY_LAUNCHER) && 
            intent.action != null && 
            intent.action == Intent.ACTION_MAIN
        ) {
            finish()
            return
        }

        enableEdgeToEdge()

        val sharedPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        setContent {
            val database = AppDatabase.getDatabase(applicationContext)
            val repository = PunctualRepository(database)
            val viewModel: PunctualViewModel = viewModel(
                factory = PunctualViewModel.Factory(application, repository)
            )

            var isDarkTheme by remember { 
                mutableStateOf(sharedPrefs.getBoolean("dark_theme", true)) 
            }

            MyApplicationTheme(darkTheme = isDarkTheme) {
                val navController = rememberNavController()
                var isRestoring by remember { mutableStateOf(false) }

                LaunchedEffect(navController) {
                    val savedRoute = sharedPrefs.getString("last_route", "home") ?: "home"
                    if (savedRoute != "home") {
                        isRestoring = true
                        if (savedRoute.startsWith("folder/")) {
                            try {
                                navController.navigate(savedRoute)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else if (savedRoute.startsWith("note/")) {
                            val parts = savedRoute.split("/")
                            if (parts.size >= 3) {
                                val noteId = parts[1]
                                val folderId = parts[2]
                                try {
                                    navController.navigate("folder/$folderId")
                                    navController.navigate("note/$noteId/$folderId")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                        isRestoring = false
                    }
                }

                DisposableEffect(navController) {
                    val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, arguments ->
                        if (isRestoring) return@OnDestinationChangedListener
                        
                        val route = destination.route ?: return@OnDestinationChangedListener
                        val resolvedRoute = when {
                            route == "home" -> "home"
                            route.startsWith("folder/") || route == "folder/{folderId}" -> {
                                val folderId = arguments?.getLong("folderId") ?: -1L
                                "folder/$folderId"
                            }
                            route.startsWith("note/") || route == "note/{noteId}/{folderId}" -> {
                                val noteId = arguments?.getLong("noteId") ?: -1L
                                val folderId = arguments?.getLong("folderId") ?: -1L
                                "note/$noteId/$folderId"
                            }
                            else -> route
                        }
                        sharedPrefs.edit().putString("last_route", resolvedRoute).apply()
                    }
                    navController.addOnDestinationChangedListener(listener)
                    onDispose {
                        navController.removeOnDestinationChangedListener(listener)
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(Screen.Home.route) {
                        HomeScreen(
                            viewModel = viewModel,
                            onNavigateToFolder = { folderId ->
                                navController.navigate(Screen.FolderDetail.createRoute(folderId))
                            },
                            onNavigateToNote = { noteId, folderId ->
                                navController.navigate(Screen.NoteEditor.createRoute(noteId, folderId))
                            },
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = { 
                                val newValue = !isDarkTheme
                                isDarkTheme = newValue
                                sharedPrefs.edit().putBoolean("dark_theme", newValue).apply()
                            }
                        )
                    }

                    composable(
                        route = Screen.FolderDetail.route,
                        arguments = listOf(navArgument("folderId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val folderId = backStackEntry.arguments?.getLong("folderId") ?: -1L
                        FolderDetailScreen(
                            folderId = folderId,
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToNote = { noteId, fId ->
                                navController.navigate(Screen.NoteEditor.createRoute(noteId, fId))
                            }
                        )
                    }

                    composable(
                        route = Screen.NoteEditor.route,
                        arguments = listOf(
                            navArgument("noteId") { type = NavType.LongType },
                            navArgument("folderId") { type = NavType.LongType }
                        )
                    ) { backStackEntry ->
                        val noteId = backStackEntry.arguments?.getLong("noteId") ?: -1L
                        val folderId = backStackEntry.arguments?.getLong("folderId") ?: -1L
                        NoteEditorScreen(
                            noteId = noteId,
                            folderId = folderId,
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
