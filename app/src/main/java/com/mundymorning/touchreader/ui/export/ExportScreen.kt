package com.mundymorning.touchreader.ui.export

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mundymorning.touchreader.AppContainer
import com.mundymorning.touchreader.core.export.ExportEstimator
import com.mundymorning.touchreader.core.export.ExportManifest
import com.mundymorning.touchreader.core.export.ExportSettings
import com.mundymorning.touchreader.core.export.ExportStore
import com.mundymorning.touchreader.core.reader.ReaderBook
import com.mundymorning.touchreader.export.ChapterAudio
import com.mundymorning.touchreader.export.ExportPhase
import com.mundymorning.touchreader.export.formatDuration
import com.mundymorning.touchreader.tts.Engines
import com.mundymorning.touchreader.tts.NeuralVoice
import com.mundymorning.touchreader.tts.PocketNarrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun voiceLabel(name: String): String {
    val n = name.drop(1)
    return when (name.firstOrNull()) { 'M' -> "Male voice $n"; 'F' -> "Female voice $n"; else -> name }
}

private fun clock(ms: Int): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(container: AppContainer, docId: Long, onBack: () -> Unit, onOpenVoices: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = container.exporter
    val player = container.audiobook
    val status by exporter.status.collectAsState()
    val timings by container.settings.speedTimings.collectAsState()
    val onlyCharging by container.settings.exportOnlyCharging.collectAsState()
    val profiles by container.voiceLibrary.profiles.collectAsState()
    val ab by player.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var book by remember { mutableStateOf<ReaderBook?>(null) }
    var title by remember { mutableStateOf("") }
    var manifest by remember { mutableStateOf<ExportManifest?>(null) }
    var ready by remember { mutableStateOf<List<ChapterAudio>>(emptyList()) }
    var finalFile by remember { mutableStateOf<java.io.File?>(null) }
    var bytesUsed by remember { mutableStateOf(0L) }

    var voice by rememberSaveable { mutableStateOf("M1") }
    var steps by rememberSaveable { mutableStateOf(4) }
    var kbps by rememberSaveable { mutableStateOf(64) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var startingOver by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pace by rememberSaveable { mutableStateOf(100) }

    val neuralDir = remember(status.phase) { container.voicePacks.installedDir(Engines.NEURAL_PACK_ID) }
    val pocketInstalled = remember(status.phase) { container.voicePacks.installedDir(Engines.POCKET_PACK_ID) != null }
    val voiceReady = neuralDir != null || (pocketInstalled && profiles.isNotEmpty())
    val presets = remember(neuralDir) { neuralDir?.let { NeuralVoice.presetNames(it) }.orEmpty() }

    LaunchedEffect(docId) {
        val b = exporter.loadBook(docId)
        book = b
        title = container.db.documents().get(docId)?.title.orEmpty()
        if (b != null && selected.isEmpty()) selected = b.chapters.indices.toSet()
    }
    // the pace is remembered per saved voice (the same setting the reader uses)
    LaunchedEffect(voice) {
        if (voice.startsWith(Engines.CUSTOM_PREFIX)) pace = PocketNarrator.choice(container.voiceLibrary, voice.removePrefix(Engines.CUSTOM_PREFIX)).second
    }
    // with only the voice-cloning download installed there are no built-in voices to start from
    LaunchedEffect(neuralDir, profiles) {
        if (neuralDir == null && !voice.startsWith(Engines.CUSTOM_PREFIX)) profiles.firstOrNull()?.let { voice = Engines.CUSTOM_PREFIX + it.id }
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); message = null } }

    // re-read what is on disk whenever rendering moves on
    LaunchedEffect(docId, status.phase, status.progress?.chunksDone, book) {
        val b = book ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val store = exporter.storeFor(docId)
            val m = store.readManifest()
            manifest = m
            ready = if (m == null) emptyList() else m.settings.chapters.sorted().filter { store.isChapterDone(it) && it in b.chapters.indices }.map {
                ChapterAudio(it, b.chapters[it].title.ifBlank { "Chapter ${it + 1}" }, store.chapterAudio(it), store.chapterDurationMs(it) ?: 0L)
            }
            finalFile = store.finalFile.takeIf { it.isFile }
            bytesUsed = if (m == null) 0 else store.bytesUsed()
        }
    }
    LaunchedEffect(ready, title) { if (ready.isNotEmpty()) player.open(docId, title, ready) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        val f = finalFile
        if (uri != null && f != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } != null }.getOrDefault(false)
            }
            message = if (ok) "Saved." else "Could not save the file."
        }
    }

    /** The settings for a new audiobook. A saved voice is copied by Pocket TTS from the part of the recording last chosen in the voice preview, at the pace chosen here. */
    fun newSettings(chapters: List<Int>): ExportSettings {
        val customVoice = voice.startsWith(Engines.CUSTOM_PREFIX)
        val start = if (customVoice) PocketNarrator.choice(container.voiceLibrary, voice.removePrefix(Engines.CUSTOM_PREFIX)).first else 0
        return ExportSettings(
            voice, steps, kbps, chapters, announceChapters = true,
            cloneEngine = ExportSettings.CLONE_POCKET, refStartSeconds = start, pacePercent = if (customVoice) pace else 100, flow = customVoice,
        )
    }

    fun startExport(restart: Boolean) {
        val b = book ?: return
        val s = newSettings(selected.sorted().filter { it in b.chapters.indices })
        scope.launch {
            message = exporter.begin(docId, title, s, restart)
            if (message == null) startingOver = false
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> startExport(startingOver && manifest != null) }
    fun requestStart() {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startExport(startingOver && manifest != null)
    }

    val b = book
    val m = manifest
    val editing = m == null || startingOver
    val active = if (editing) newSettings(selected.sorted()) else m!!.settings
    val custom = active.voice.startsWith(Engines.CUSTOM_PREFIX)
    val engineKind = if (custom) ExportEstimator.ENGINE_POCKET else ExportEstimator.ENGINE_SUPERTONIC
    val busyElsewhere = status.running && status.phase != ExportPhase.TESTING && status.docId != docId
    val rendering = status.running && status.docId == docId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audiobook", maxLines = 1) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (ab.docId == docId && ab.chapters.isNotEmpty()) MiniPlayer(container) },
    ) { pad ->
        if (b == null) { Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return@Scaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(title, style = MaterialTheme.typography.titleLarge) }

            if (!voiceReady) item {
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No voice is installed", fontWeight = FontWeight.Bold)
                    Text("Audiobooks are made with the built-in neural voice, or with your own saved voice using the Voice cloning download. Install one from the Voices tab first (and, for your own voice, record it in Create a voice).")
                    Button(onOpenVoices) { Text("Open Voices") }
                } }
            }

            // what is already made
            if (m != null && !startingOver) item {
                val total = m.settings.chapters.size
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val doneCount = ready.size
                    Text(
                        when {
                            finalFile != null -> "Audiobook ready"
                            rendering -> "Making the audiobook…"
                            doneCount == total -> "All chapters are finished"
                            else -> "Audiobook in progress"
                        }, fontWeight = FontWeight.Bold,
                    )
                    Text("Voice: ${if (m.settings.voice.startsWith(Engines.CUSTOM_PREFIX)) profiles.firstOrNull { Engines.CUSTOM_PREFIX + it.id == m.settings.voice }?.name?.let { "$it (your voice)" } ?: "your voice" else voiceLabel(m.settings.voice)} · " +
                        "${if (m.settings.steps <= 4) "Faster" else "Better quality"} · ${m.settings.bitrateKbps} kbps")
                    Text("$doneCount of $total chapters ready · ${"%.0f".format(bytesUsed / 1_000_000.0)} MB used")
                    val p = status.progress.takeIf { rendering }
                    if (p != null && p.chunksTotal > 0) {
                        LinearProgressIndicator(progress = { p.chunksDone.toFloat() / p.chunksTotal }, Modifier.fillMaxWidth())
                        Text(
                            buildString {
                                append("${p.chunksDone * 100 / p.chunksTotal}% done")
                                p.etaSeconds?.let { append(" · about ${formatDuration(it)} left") }
                                if (p.audioSeconds > 0) append(" · ${"%.1f".format(p.rtf)}× slower than real time")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (p.skippedSentences > 0) Text("${p.skippedSentences} sentence(s) could not be spoken and were left silent.", style = MaterialTheme.typography.bodySmall)
                    } else if (rendering) LinearProgressIndicator(Modifier.fillMaxWidth())
                    status.message.takeIf { status.docId == docId }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (rendering) OutlinedButton({ exporter.requestStop() }) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop") }
                        else if (finalFile == null && doneCount < total) Button({ requestStart() }, enabled = !busyElsewhere && voiceReady) {
                            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (doneCount == 0) "Start" else "Continue")
                        }
                        if (finalFile != null) Button({ saver.launch("${title.ifBlank { "audiobook" }.replace(Regex("[^A-Za-z0-9 _-]"), "_")}.m4b") }) { Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(6.dp)); Text("Save file") }
                    }
                    if (!rendering) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ startingOver = true }) { Text("Start over…") }
                        TextButton({ confirmDelete = true }) { Text("Delete audio") }
                    }
                    if (rendering) Text("You can leave this screen or turn the screen off. Rendering keeps going while the phone is on and plugged in.", style = MaterialTheme.typography.bodySmall)
                } }
            }

            if (busyElsewhere) item { Text("Another audiobook is being made right now. Wait for it, or stop it from its own screen.", color = MaterialTheme.colorScheme.error) }

            // choices for a new audiobook
            if (editing && voiceReady) {
                item {
                    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Voice", fontWeight = FontWeight.Bold)
                        var open by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton({ open = true }) {
                                Text(if (custom) profiles.firstOrNull { Engines.CUSTOM_PREFIX + it.id == voice }?.name?.let { "★ $it (your voice)" } ?: "Your voice" else voiceLabel(voice))
                                Icon(Icons.Rounded.ArrowDropDown, null)
                            }
                            DropdownMenu(open, { open = false }) {
                                presets.forEach { p -> DropdownMenuItem({ Text(voiceLabel(p)) }, { voice = p; open = false }) }
                                if (pocketInstalled) profiles.forEach { pr ->
                                    DropdownMenuItem({ Text("★ ${pr.name} (your voice) — takes longer") }, { voice = Engines.CUSTOM_PREFIX + pr.id; open = false })
                                }
                            }
                        }
                        if (custom) {
                            Text(
                                "Your own voice is copied from your saved recording (15 seconds starting at ${PocketNarrator.choice(container.voiceLibrary, voice.removePrefix(Engines.CUSTOM_PREFIX)).first} s: change the part in Voices > Voice preview). " +
                                    "It takes much longer than a built-in voice, and this phone needs to be timed to know how long.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text("Pace", fontWeight = FontWeight.Bold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(100, 95, 90, 85, 80).forEach { pc ->
                                    FilterChip(pace == pc, {
                                        pace = pc
                                        val id = voice.removePrefix(Engines.CUSTOM_PREFIX)
                                        PocketNarrator.saveChoice(container.voiceLibrary, id, PocketNarrator.choice(container.voiceLibrary, id).first, pc)
                                    }, { Text("$pc%") })
                                }
                            }
                            Text("Slows the voice down without changing its pitch. 100% is as the model speaks; 80% is the slowest, and below that it can start to sound smeared. The same setting is used when reading live.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!pocketInstalled && profiles.isNotEmpty()) Text("To use your own voice, install the Voice cloning download in the Voices tab.", style = MaterialTheme.typography.bodySmall)

                        Text("Speed or quality", fontWeight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(steps == 4, { steps = 4 }, { Text("Faster") })
                            FilterChip(steps == 8, { steps = 8 }, { Text("Better quality") })
                        }
                        Text(
                            when {
                                custom && steps == 4 -> "Faster. Better quality adds one extra step per sound frame; it has not yet been compared by ear."
                                custom -> "Slightly slower (about 15% more work is assumed until timed). Not yet compared by ear against Faster."
                                steps == 4 -> "About twice as fast. The voice is slightly less polished."
                                else -> "Slower, with the best sound this voice can make."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )

                        Text("File size", fontWeight = FontWeight.Bold)
                        val seconds = ExportEstimator.audioSeconds(b, selected.toList())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(48 to "Smaller", 64 to "Standard", 96 to "Higher").forEach { (k, label) ->
                                FilterChip(kbps == k, { kbps = k }, { Text("$label · ${ExportEstimator.sizeBytes(seconds, k) / 1_000_000} MB") })
                            }
                        }
                        Text("This only changes the file size, not how long it takes to make.", style = MaterialTheme.typography.bodySmall)
                    } }
                }
                item {
                    val seconds = ExportEstimator.audioSeconds(b, selected.toList())
                    val rtf = ExportEstimator.predictRtf(timings, steps, custom, engineKind)
                    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Estimate", fontWeight = FontWeight.Bold)
                        Text("About ${formatDuration(seconds)} of audio · ${ExportEstimator.sizeBytes(seconds, kbps) / 1_000_000} MB")
                        if (rtf != null) {
                            val work = ExportEstimator.renderSeconds(seconds, rtf)
                            Text("About ${formatDuration(work)} to make on this phone", fontWeight = FontWeight.Bold)
                            val exact = timings.any { it.steps == steps && it.custom == custom && it.engine == engineKind }
                            Text(
                                (if (exact) "Based on timing this voice on this phone." else "Estimated from other timings on this phone; it gets more exact once this voice has been timed.") +
                                    " It runs in the background and can be stopped and continued.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (work > 12 * 3600) Text("That is long. Keep the phone plugged in, for example overnight, and continue the next night.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        } else {
                            Text("This phone has not been timed yet.")
                        }
                        if (status.phase == ExportPhase.TESTING) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(status.message.orEmpty(), style = MaterialTheme.typography.bodySmall) }
                        else {
                            val exact = timings.any { it.steps == steps && it.custom == custom && it.engine == engineKind }
                            OutlinedButton({ scope.launch { message = exporter.testSpeed(voice, steps, active.cloneEngine, active.refStartSeconds) } }, enabled = !status.running) {
                                Text(if (exact) "Test again (about a minute)" else "Test this phone's speed (about a minute)")
                            }
                        }
                    } }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(onlyCharging, { container.settings.setExportOnlyCharging(it) })
                        Spacer(Modifier.width(12.dp))
                        Text("Pause when the phone is unplugged", Modifier.weight(1f))
                    }
                }
                item {
                    Button({ if (startingOver && m != null) confirmRestart = true else requestStart() }, Modifier.fillMaxWidth(), enabled = selected.isNotEmpty() && !busyElsewhere && !status.running) {
                        Icon(Icons.Rounded.Headphones, null); Spacer(Modifier.width(8.dp)); Text(if (startingOver && m != null) "Start over and create audiobook" else "Create audiobook")
                    }
                }
                if (startingOver && m != null) item { TextButton({ startingOver = false }) { Text("Cancel, keep the existing audiobook") } }
            }

            // chapters
            item { Text("Chapters", style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(b.chapters) { i, ch ->
                val name = ch.title.ifBlank { "Chapter ${i + 1}" }
                if (editing) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(i in selected, { c -> selected = if (c) selected + i else selected - i })
                        Text(name, Modifier.weight(1f), maxLines = 2)
                    }
                } else {
                    val readyIdx = ready.indexOfFirst { it.chapter == i }
                    val planned = m != null && i in m.settings.chapters
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, maxLines = 2)
                            Text(
                                when { !planned -> "Not included"; readyIdx >= 0 -> "Ready · ${clock(ready[readyIdx].durationMs.toInt())}"; rendering -> "Waiting" else -> "Not made yet" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (readyIdx >= 0) IconButton({ player.open(docId, title, ready); player.selectChapter(readyIdx, true) }) { Icon(Icons.Rounded.PlayCircle, "Play $name") }
                    }
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete the audiobook?") },
        text = { Text("This removes the audio that was made for this book. The book itself stays in your library, and you can make the audiobook again.") },
        confirmButton = { TextButton({ confirmDelete = false; player.close(); exporter.deleteAudio(docId); manifest = null; ready = emptyList(); finalFile = null; startingOver = false }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
    if (confirmRestart) AlertDialog(
        onDismissRequest = { confirmRestart = false },
        title = { Text("Start over?") },
        text = { Text("The audio made so far is deleted and the audiobook is made again with the choices above.") },
        confirmButton = { TextButton({ confirmRestart = false; requestStart() }) { Text("Start over") } },
        dismissButton = { TextButton({ confirmRestart = false }) { Text("Cancel") } },
    )
}

@Composable
private fun MiniPlayer(container: AppContainer) {
    val player = container.audiobook
    val st by player.state.collectAsState()
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(st.current?.title.orEmpty(), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            var drag by remember { mutableStateOf<Float?>(null) }
            val dur = st.durationMs.coerceAtLeast(1)
            Slider(
                value = drag ?: (st.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { player.seekTo((it * dur).toInt()) }; drag = null },
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(st.positionMs), style = MaterialTheme.typography.bodySmall)
                Text(clock(st.durationMs), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(player::previous) { Icon(Icons.Rounded.SkipPrevious, "Previous chapter") }
                IconButton({ player.skip(-15) }) { Icon(Icons.Rounded.Replay10, "Back 15 seconds") }
                FilledIconButton(player::toggle, Modifier.size(56.dp)) { Icon(if (st.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (st.playing) "Pause" else "Play") }
                IconButton({ player.skip(30) }) { Icon(Icons.Rounded.Forward30, "Forward 30 seconds") }
                IconButton(player::next) { Icon(Icons.Rounded.SkipNext, "Next chapter") }
                val speeds = listOf(1f, 1.25f, 1.5f, 2f)
                AssistChip({ player.setSpeed(speeds[(speeds.indexOf(st.speed).coerceAtLeast(0) + 1) % speeds.size]) }, { Text("${st.speed}×") })
            }
            st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
