package com.mundymorning.touchreader.ui.voices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mundymorning.touchreader.AppContainer
import com.mundymorning.touchreader.core.voicepacks.VoicePack
import com.mundymorning.touchreader.tts.EngineStatus
import com.mundymorning.touchreader.tts.VoiceInfo
import com.mundymorning.touchreader.voices.PackState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesScreen(container: AppContainer) {
    val controller = container.controller
    val voices by controller.voices.collectAsState(initial = emptyList())
    val status by controller.engineStatus.collectAsState(initial = EngineStatus.Initializing)
    val engines by controller.engines.collectAsState(initial = emptyList())
    val default by container.settings.defaultVoice.collectAsState()
    val chosenEngine by container.settings.ttsEngine.collectAsState()
    val packStates by container.voicePacks.states.collectAsState()
    var allLanguages by remember { mutableStateOf(false) }
    var pickEngine by remember { mutableStateOf(false) }
    var confirmPack by remember { mutableStateOf<VoicePack?>(null) }
    val lang = Locale.getDefault().language
    val shown = remember(voices, allLanguages) { if (allLanguages) voices else voices.filter { it.language == lang } }
    val engineLabel = engines.firstOrNull { it.packageName == (chosenEngine ?: controller.defaultEnginePackage) }?.label ?: "System default"

    Scaffold(topBar = { TopAppBar(title = { Text("Voices") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionHeader("Android system voices", "Provided by the speech engine installed on this phone. Voices marked offline work without internet.") }
            item {
                ListItem(
                    headlineContent = { Text("Speech engine") },
                    supportingContent = { Text(engineLabel) },
                    modifier = Modifier.clickable { pickEngine = true }.heightIn(min = 56.dp),
                )
            }
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
                    items(shown, key = { it.name }) { v -> VoiceRow(v, default, container) }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                SectionHeader(
                    "Downloaded offline voices",
                    "Neural voice packs (such as Kokoro or Piper) are not part of this version: no pack has been published and no neural engine is built in yet.",
                )
            }
            if (container.voicePacks.catalog.isEmpty()) {
                item { Text("Nothing to download right now.", Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            items(container.voicePacks.catalog, key = { it.id }) { p ->
                PackRow(p, packStates[p.id] ?: PackState.Available,
                    onDownload = { confirmPack = p }, onCancel = { container.voicePacks.cancel(p.id) }, onDelete = { container.voicePacks.delete(p.id) })
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("My voices", "Creating your own narrator from a recording (Voice Studio) is planned. It is not available in this version.") }
        }
    }

    if (pickEngine) {
        AlertDialog(
            onDismissRequest = { pickEngine = false },
            title = { Text("Speech engine") },
            text = {
                Column {
                    Text("Other speech engines you install in Android settings appear here. Changing engine resets the narrator for the open book.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    val options = listOf<String?>(null) + engines.map { it.packageName }
                    options.forEach { pkg ->
                        val label = if (pkg == null) "System default" else engines.first { it.packageName == pkg }.label
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(chosenEngine == pkg, role = Role.RadioButton) {
                                if (chosenEngine != pkg) controller.switchEngine(pkg)
                                pickEngine = false
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) { RadioButton(chosenEngine == pkg, null); Spacer(Modifier.width(12.dp)); Text(label) }
                    }
                }
            },
            confirmButton = { TextButton({ pickEngine = false }) { Text("Close") } },
        )
    }

    confirmPack?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmPack = null },
            title = { Text("Download ${p.name}?") },
            text = { Text("This downloads about ${p.sizeBytes / 1_000_000} MB and stores up to ${p.unpackedBytes / 1_000_000} MB on your phone. It uses your data connection, so Wi-Fi is recommended.\n\nLicense: ${p.license}") },
            confirmButton = { TextButton({ container.voicePacks.download(p); confirmPack = null }) { Text("Download") } },
            dismissButton = { TextButton({ confirmPack = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VoiceRow(v: VoiceInfo, default: String?, container: AppContainer) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { container.controller.previewVoice(v.name, "This is how I sound when reading your book aloud.") },
                    enabled = v.installed,
                ) { Icon(Icons.Rounded.VolumeUp, "Preview ${v.label}") }
                if (default == v.name) Icon(Icons.Rounded.Check, "Default narrator")
                else TextButton({ container.settings.setDefaultVoice(v.name) }, enabled = v.installed) { Text("Use") }
            }
        },
    )
}

@Composable
private fun PackRow(p: VoicePack, state: PackState, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(p.name) },
        supportingContent = {
            Column {
                Text("${p.sizeBytes / 1_000_000} MB · ${p.license}${p.attribution?.let { " · $it" } ?: ""}")
                when (state) {
                    is PackState.Downloading -> LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 4.dp))
                    is PackState.Installing -> Text("Installing…")
                    is PackState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    else -> {}
                }
            }
        },
        trailingContent = {
            when (state) {
                is PackState.Available, is PackState.Failed -> TextButton(onDownload) { Text("Download") }
                is PackState.Downloading -> TextButton(onCancel) { Text("Cancel") }
                is PackState.Installing -> CircularProgressIndicator(Modifier.size(24.dp))
                is PackState.Installed -> TextButton(onDelete) { Text("Delete") }
            }
        },
    )
}

@Composable
private fun SectionHeader(title: String, body: String) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
