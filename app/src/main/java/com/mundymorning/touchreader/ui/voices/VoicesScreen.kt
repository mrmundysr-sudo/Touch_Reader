package com.mundymorning.touchreader.ui.voices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mundymorning.touchreader.AppContainer
import com.mundymorning.touchreader.tts.EngineStatus
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesScreen(container: AppContainer) {
    val voices by container.controller.voices.collectAsState()
    val status by container.controller.engineStatus.collectAsState()
    val default by container.settings.defaultVoice.collectAsState()
    var allLanguages by remember { mutableStateOf(false) }
    val lang = Locale.getDefault().language
    val shown = remember(voices, allLanguages) { if (allLanguages) voices else voices.filter { it.language == lang } }

    Scaffold(topBar = { TopAppBar(title = { Text("Voices") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionHeader("Android system voices", "Provided by the speech engine installed on this phone. Voices marked offline work without internet.") }
            when (val s = status) {
                is EngineStatus.Initializing -> item { Box(Modifier.padding(24.dp)) { CircularProgressIndicator() } }
                is EngineStatus.Failed -> item { Text(s.message, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
                is EngineStatus.Ready -> {
                    item {
                        ListItem(
                            headlineContent = { Text("Show voices for all languages") },
                            trailingContent = { Switch(allLanguages, { allLanguages = it }) },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("System default", fontWeight = if (default == null) FontWeight.Bold else null) },
                            supportingContent = { Text("Uses your phone's own choice for your language") },
                            trailingContent = {
                                if (default == null) Icon(Icons.Rounded.Check, "Default narrator")
                                else TextButton({ container.settings.setDefaultVoice(null) }) { Text("Use") }
                            },
                        )
                    }
                    if (shown.isEmpty()) item { Text("No voices found for this language.", Modifier.padding(24.dp)) }
                    items(shown, key = { it.name }) { v ->
                        ListItem(
                            headlineContent = { Text(v.label, fontWeight = if (default == v.name) FontWeight.Bold else null) },
                            supportingContent = {
                                Text(
                                    when {
                                        !v.installed -> "Not installed — download in Android settings"
                                        v.needsNetwork -> "Needs internet"
                                        else -> "Offline"
                                    }
                                )
                            },
                            trailingContent = {
                                Row {
                                    IconButton(
                                        onClick = { container.controller.previewVoice(v.name, "This is how I sound when reading your book aloud.") },
                                        enabled = v.installed,
                                    ) { Icon(Icons.Rounded.VolumeUp, "Preview ${v.label}") }
                                    if (default == v.name) Icon(Icons.Rounded.Check, "Default narrator", Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
                                    else TextButton({ container.settings.setDefaultVoice(v.name) }, enabled = v.installed) { Text("Use") }
                                }
                            },
                        )
                    }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Downloaded offline voices", "Neural voices (Kokoro, Piper) are planned. They are not part of this version, so nothing can be downloaded yet.") }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("My voices", "Creating your own narrator from a recording (Voice Studio) is planned. It is not available in this version.") }
        }
    }
}

@Composable
private fun SectionHeader(title: String, body: String) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
