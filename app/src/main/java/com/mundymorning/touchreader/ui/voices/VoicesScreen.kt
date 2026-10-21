package com.mundymorning.touchreader.ui.voices

import android.app.ActivityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
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
import com.mundymorning.touchreader.core.voicestudio.VoiceProfile
import com.mundymorning.touchreader.core.voicestudio.Verdict
import kotlinx.coroutines.launch
import com.mundymorning.touchreader.tts.EngineStatus
import com.mundymorning.touchreader.tts.Engines
import com.mundymorning.touchreader.tts.VoiceInfo
import com.mundymorning.touchreader.voices.PackState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesScreen(container: AppContainer, onCreateVoice: () -> Unit) {
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
    val myVoices by container.voiceLibrary.profiles.collectAsState()
    val playingSample by container.samplePlayer.playingKey.collectAsState()
    var renameVoice by remember { mutableStateOf<VoiceProfile?>(null) }
    var needsAddon by remember { mutableStateOf(false) }
    val cloneReady = packStates[Engines.NEURAL_PACK_ID] is PackState.Installed && packStates[Engines.CONVERTER_PACK_ID] is PackState.Installed
    var deleteVoice by remember { mutableStateOf<VoiceProfile?>(null) }
    val voiceScope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { container.samplePlayer.stop() } }
    var accepted by remember { mutableStateOf(false) }
    val neuralInstalled = packStates[Engines.NEURAL_PACK_ID] is PackState.Installed
    val labReady = packStates[Engines.POCKET_PACK_ID] is PackState.Installed
    val lab by container.voiceLab.state.collectAsState()
    val labPlaying by container.samplePlayer.playingKey.collectAsState()
    val lang = Locale.getDefault().language
    val shown = remember(voices, allLanguages) { if (allLanguages) voices else voices.filter { it.language == lang || it.language.isEmpty() } }
    val engineLabel = if (chosenEngine == Engines.NEURAL) "Neural voice (Supertonic 3)" else engines.firstOrNull { it.packageName == (chosenEngine ?: controller.defaultEnginePackage) }?.label ?: "System default"

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
                    "Optional offline neural voices. They are large downloads and need a reasonably powerful phone.",
                )
            }
            if (container.voicePacks.catalog.isEmpty()) {
                item { Text("Nothing to download right now.", Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            items(container.voicePacks.catalog, key = { it.id }) { p ->
                val missing = p.requires.filter { packStates[it] !is PackState.Installed }.mapNotNull { id -> container.voicePacks.catalog.firstOrNull { it.id == id }?.name }
                PackRow(p, packStates[p.id] ?: PackState.Available, bundled = container.voicePacks.isBundled(p), missingRequirement = missing.firstOrNull(),
                    onDownload = { accepted = false; confirmPack = p }, onCancel = { container.voicePacks.cancel(p.id) },
                    onDelete = {
                        if (p.id == Engines.NEURAL_PACK_ID && chosenEngine == Engines.NEURAL) controller.switchEngine(null)
                        container.voicePacks.delete(p.id)
                    })
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                SectionHeader(
                    "My voices",
                    "Voices you create from your own recordings. They stay on this phone. To have a book read in one of your voices, install the neural voice and the voice cloning add-on above, then tap “Use to narrate”. The result has a similar tone to your recording; it is not an exact copy of the voice.",
                )
            }
            item {
                FilledTonalButton(onClick = onCreateVoice, modifier = Modifier.padding(horizontal = 24.dp).heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Mic, null); Spacer(Modifier.width(8.dp)); Text("Create a voice")
                }
            }
            items(myVoices, key = { it.id }) { v ->
              Column {
                ListItem(
                    headlineContent = { Text(v.name) },
                    supportingContent = {
                        Text(
                            "Saved " + android.text.format.DateUtils.getRelativeTimeSpanString(v.createdAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS) +
                                " · " + when (v.qualityVerdict) { Verdict.GOOD -> "good sample"; Verdict.USABLE -> "usable sample"; Verdict.POOR -> "weak sample" }
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val file = container.voiceLibrary.sampleFile(v.id)
                            val isPlaying = playingSample == v.id
                            if (file != null) IconButton({ if (isPlaying) container.samplePlayer.stop() else container.samplePlayer.play(v.id, file) }) {
                                Icon(if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (isPlaying) "Stop" else "Play ${v.name}'s sample")
                            }
                            if (default == Engines.CUSTOM_PREFIX + v.id) Icon(Icons.Rounded.Check, "Current narrator")
                            else TextButton({
                                if (cloneReady) container.controller.useCustomVoice(v.id) else needsAddon = true
                            }) { Text("Use to narrate") }
                            IconButton({ renameVoice = v }) { Icon(Icons.Rounded.Edit, "Rename ${v.name}") }
                            IconButton({ deleteVoice = v }) { Icon(Icons.Rounded.Delete, "Delete ${v.name}") }
                        }
                    },
                )
                if (labReady && container.voiceLibrary.sampleFile(v.id) != null) {
                    TextButton({ container.voiceLab.make(v.id, v.name) }, Modifier.padding(start = 16.dp)) { Text("Try in voice lab (experimental)") }
                }
              }
            }
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
                    val options = listOf<String?>(null) + engines.map { it.packageName } + (if (neuralInstalled) listOf(Engines.NEURAL) else emptyList())
                    options.forEach { pkg ->
                        val label = when (pkg) {
                            null -> "System default"
                            Engines.NEURAL -> "Neural voice (Supertonic 3) — offline"
                            else -> engines.first { it.packageName == pkg }.label
                        }
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

    if (lab !is com.mundymorning.touchreader.lab.LabState.Idle) {
        val st = lab
        AlertDialog(
            onDismissRequest = { if (st !is com.mundymorning.touchreader.lab.LabState.Working) container.voiceLab.close() },
            title = { Text("Voice lab: ${when (st) { is com.mundymorning.touchreader.lab.LabState.Working -> st.voiceName; is com.mundymorning.touchreader.lab.LabState.Done -> st.voiceName; is com.mundymorning.touchreader.lab.LabState.Failed -> st.voiceName; else -> "" }}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when (st) {
                        is com.mundymorning.touchreader.lab.LabState.Working -> Text(st.message + "\n\nThis can take a few minutes on a slower phone. Keep the app open.")
                        is com.mundymorning.touchreader.lab.LabState.Failed -> Text(st.message, color = MaterialTheme.colorScheme.error)
                        is com.mundymorning.touchreader.lab.LabState.Done -> {
                            Text("A ${"%.0f".format(st.audioSeconds)} second sample was made from your saved recording and played. Listen for how close it is to the real person.", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Timings on this phone:\n" +
                                    "• Making speech: ${"%.1f".format(st.speechSeconds)} s for ${"%.1f".format(st.audioSeconds)} s of audio (${"%.2f".format(st.rtf)} s of work per second of speech" +
                                    (if (st.rtf <= 1.0) "; fast enough to read live)" else "; slower than real time)") + "\n" +
                                    "• Loading the model: ${"%.1f".format(st.loadSeconds)} s\n" +
                                    "• Studying the recording: " + (if (st.voiceCached) "already done earlier" else st.voiceSeconds?.let { "${"%.1f".format(it)} s" } ?: "-"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        else -> {}
                    }
                }
            },
            confirmButton = {
                if (st is com.mundymorning.touchreader.lab.LabState.Done) {
                    TextButton({ if (labPlaying == "voice-lab") container.voiceLab.stopPlaying() else container.voiceLab.play() }) { Text(if (labPlaying == "voice-lab") "Stop" else "Play again") }
                } else if (st is com.mundymorning.touchreader.lab.LabState.Working) {
                    TextButton({ container.voiceLab.close() }) { Text("Cancel") }
                }
            },
            dismissButton = { if (st !is com.mundymorning.touchreader.lab.LabState.Working) TextButton({ container.voiceLab.close() }) { Text("Close") } },
        )
    }

    if (needsAddon) {
        AlertDialog(
            onDismissRequest = { needsAddon = false },
            title = { Text("Two downloads needed") },
            text = { Text("To have books read in your own voice, install both items under “Downloaded offline voices” above: the neural voice (about 400 MB) and the voice cloning add-on (about 130 MB). Both run fully on your phone.") },
            confirmButton = { TextButton({ needsAddon = false }) { Text("OK") } },
        )
    }

    renameVoice?.let { v ->
        var text by remember(v.id) { mutableStateOf(v.name) }
        AlertDialog(
            onDismissRequest = { renameVoice = null },
            title = { Text("Rename voice") },
            text = { OutlinedTextField(text, { text = it.take(60) }, singleLine = true) },
            confirmButton = { TextButton({ voiceScope.launch { container.voiceLibrary.rename(v.id, text) }; renameVoice = null }) { Text("Save") } },
            dismissButton = { TextButton({ renameVoice = null }) { Text("Cancel") } },
        )
    }
    deleteVoice?.let { v ->
        AlertDialog(
            onDismissRequest = { deleteVoice = null },
            title = { Text("Delete ${v.name}?") },
            text = { Text("This permanently deletes the recording and the voice from this phone. It cannot be undone.") },
            confirmButton = {
                TextButton({
                    container.samplePlayer.stop()
                    voiceScope.launch { container.voiceLibrary.delete(v.id) }
                    deleteVoice = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ deleteVoice = null }) { Text("Keep") } },
        )
    }

    confirmPack?.let { p ->
        val ctx = LocalContext.current
        val totalGb = remember { ctx.getSystemService(ActivityManager::class.java).let { am -> ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / 1e9 } }
        AlertDialog(
            onDismissRequest = { confirmPack = null },
            title = { Text(if (container.voicePacks.isBundled(p)) "Install ${p.name}?" else "Download ${p.name}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (container.voicePacks.isBundled(p)) Text("This test build already contains the files (${p.sizeBytes / 1_000_000} MB). Nothing is downloaded.")
                    else Text("Downloads about ${p.sizeBytes / 1_000_000} MB directly from the publisher and stores it on your phone. It uses your data connection, so Wi-Fi is recommended.")
                    if (totalGb < 3.5) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This phone has about ${"%.1f".format(totalGb)} GB of memory. The neural voice is large and may not run well here. If it cannot start, Touch Reader switches back to the system voice automatically.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("License: ${p.license}", style = MaterialTheme.typography.titleSmall)
                    p.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (p.requiresAcceptance) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(accepted, role = Role.Checkbox) { accepted = !accepted },
                            verticalAlignment = Alignment.CenterVertically,
                        ) { Checkbox(accepted, null); Spacer(Modifier.width(8.dp)); Text("I have read and agree to these terms") }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = accepted || !p.requiresAcceptance,
                    onClick = {
                        if (p.requiresAcceptance) container.settings.recordAcceptance(p.id, p.version)
                        if (container.voicePacks.isBundled(p)) container.voicePacks.installBundled(p) else container.voicePacks.download(p)
                        confirmPack = null
                    },
                ) { Text(if (container.voicePacks.isBundled(p)) "Install" else "Download") }
            },
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
private fun PackRow(p: VoicePack, state: PackState, bundled: Boolean, missingRequirement: String?, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(p.name) },
        supportingContent = {
            Column {
                Text("${p.sizeBytes / 1_000_000} MB · ${p.license}${p.attribution?.let { " · $it" } ?: ""}")
                if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall)
                if (bundled && state !is PackState.Installed) Text("Included in this test build: no download needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (missingRequirement != null && state !is PackState.Installed) Text("Install “$missingRequirement” first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                is PackState.Available, is PackState.Failed -> TextButton(onDownload, enabled = missingRequirement == null) { Text(if (bundled) "Install" else "Download") }
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
