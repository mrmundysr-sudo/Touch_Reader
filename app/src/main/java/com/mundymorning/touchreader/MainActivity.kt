package com.mundymorning.touchreader

import com.mundymorning.touchreader.ui.art.*
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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
import com.mundymorning.touchreader.ui.export.ExportScreen
import com.mundymorning.touchreader.ui.library.LibraryScreen
import com.mundymorning.touchreader.ui.reader.ReaderScreen
import com.mundymorning.touchreader.ui.settings.SettingsScreen
import com.mundymorning.touchreader.ui.studio.VoiceStudioScreen
import com.mundymorning.touchreader.ui.theme.TouchReaderTheme
import com.mundymorning.touchreader.ui.voices.VoicesScreen

class MainActivity : ComponentActivity() {
    private var pendingDoc by mutableLongStateOf(-1L)
    private var pendingExport by mutableLongStateOf(-1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingDoc = intent.getLongExtra(EXTRA_DOC_ID, -1L)
        pendingExport = intent.getLongExtra(EXTRA_EXPORT_DOC_ID, -1L)
        val container = (application as TouchReaderApp).container
        setContent {
            val mode by container.settings.theme.collectAsState()
            val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK, ThemeMode.CONTRAST -> true }
            // the status and navigation bar icons must follow the chosen theme, not just the phone's own setting
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                )
                onDispose { }
            }
            var splash by rememberSaveable { mutableStateOf(true) }
            TouchReaderTheme(dark, contrast = mode == ThemeMode.CONTRAST) {
                val rootColor = when { mode == ThemeMode.CONTRAST -> Color.Black; dark -> Color(0xFF1C120B); else -> Color(0xFFF6ECD8) }
                val rootBase = Modifier.fillMaxSize().background(rootColor)
                Box(if (mode == ThemeMode.CONTRAST) rootBase else rootBase.artTexture(if (dark) "global_bg_dark" else "global_bg_light")) {
                    AppRoot(container, pendingDoc, pendingExport) { pendingDoc = -1L; pendingExport = -1L }
                    AnimatedVisibility(splash, exit = fadeOut(tween(450))) { SplashScreen { splash = false } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingDoc = intent.getLongExtra(EXTRA_DOC_ID, -1L)
        pendingExport = intent.getLongExtra(EXTRA_EXPORT_DOC_ID, -1L)
    }

    companion object {
        const val EXTRA_DOC_ID = "docId"
        const val EXTRA_EXPORT_DOC_ID = "exportDocId"
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val artSlot: String)

@Composable
private fun AppRoot(container: AppContainer, pendingDoc: Long, pendingExport: Long, consumed: () -> Unit) {
    val nav = rememberNavController()
    val tabs = listOf(
        Tab("library", "Library", Icons.AutoMirrored.Rounded.LibraryBooks, "icon_library"),
        Tab("voices", "Voices", Icons.Rounded.RecordVoiceOver, "icon_voice"),
        Tab("settings", "Settings", Icons.Rounded.Settings, "icon_settings"),
    )
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    LaunchedEffect(pendingDoc) {
        if (pendingDoc >= 0) { nav.navigate("reader/$pendingDoc?play=false"); consumed() }
    }
    LaunchedEffect(pendingExport) {
        if (pendingExport >= 0) { nav.navigate("export/$pendingExport"); consumed() }
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
                            icon = { ArtIcon(t.artSlot, t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "library", modifier = Modifier.padding(padding)) {
            composable("library") {
                LibraryScreen(container, onOpen = { id, play -> nav.navigate("reader/$id?play=$play") }, onAudiobook = { id -> nav.navigate("export/$id") })
            }
            composable("voices") { VoicesScreen(container, onCreateVoice = { nav.navigate("studio") }) }
            composable("studio") { VoiceStudioScreen(container, onClose = { nav.popBackStack() }) }
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
                    onExport = { id -> nav.navigate("export/$id") },
                )
            }
            composable("export/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                ExportScreen(
                    container, e.arguments!!.getLong("id"),
                    onBack = { nav.popBackStack() },
                    onOpenVoices = { nav.navigate("voices") },
                )
            }
        }
    }
}

/** The artwork shown for a moment when the app opens (tap to skip); the library loads underneath it. */
@Composable
private fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) { delay(1600); onDone() }
    Box(Modifier.fillMaxSize().background(Color(0xFF1C120B)).clickable(onClickLabel = "Skip") { onDone() }) {
        ArtImage("splash_art", "Touch Reader", Modifier.fillMaxSize(), ContentScale.Crop, R.drawable.splash_art)
        Text(
            "Mundy Morning Studios\nAlways Professional. Always Free.",
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            color = Color(0xFFF1E6D0), fontSize = 13.sp, textAlign = TextAlign.Center,
        )
    }
}
