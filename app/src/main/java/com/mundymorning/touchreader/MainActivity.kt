package com.mundymorning.touchreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.mundymorning.touchreader.data.ThemeMode
import com.mundymorning.touchreader.ui.library.LibraryScreen
import com.mundymorning.touchreader.ui.reader.ReaderScreen
import com.mundymorning.touchreader.ui.settings.SettingsScreen
import com.mundymorning.touchreader.ui.theme.TouchReaderTheme
import com.mundymorning.touchreader.ui.voices.VoicesScreen

class MainActivity : ComponentActivity() {
    private var pendingDoc by mutableLongStateOf(-1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingDoc = intent.getLongExtra(EXTRA_DOC_ID, -1L)
        val container = (application as TouchReaderApp).container
        setContent {
            val mode by container.settings.theme.collectAsState()
            val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
            TouchReaderTheme(dark) { AppRoot(container, pendingDoc) { pendingDoc = -1L } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingDoc = intent.getLongExtra(EXTRA_DOC_ID, -1L)
    }

    companion object { const val EXTRA_DOC_ID = "docId" }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
private fun AppRoot(container: AppContainer, pendingDoc: Long, consumed: () -> Unit) {
    val nav = rememberNavController()
    val tabs = listOf(
        Tab("library", "Library", Icons.AutoMirrored.Rounded.LibraryBooks),
        Tab("voices", "Voices", Icons.Rounded.RecordVoiceOver),
        Tab("settings", "Settings", Icons.Rounded.Settings),
    )
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    LaunchedEffect(pendingDoc) {
        if (pendingDoc >= 0) { nav.navigate("reader/$pendingDoc?play=false"); consumed() }
    }

    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true; restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "library", modifier = Modifier.padding(padding)) {
            composable("library") {
                LibraryScreen(container) { id, play -> nav.navigate("reader/$id?play=$play") }
            }
            composable("voices") { VoicesScreen(container) }
            composable("settings") { SettingsScreen(container) }
            composable(
                "reader/{id}?play={play}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("play") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { e ->
                ReaderScreen(
                    container, e.arguments!!.getLong("id"), e.arguments!!.getBoolean("play"),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
