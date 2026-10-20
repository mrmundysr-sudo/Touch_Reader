package com.mundymorning.touchreader.ui.reader

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mundymorning.touchreader.AppContainer
import com.mundymorning.touchreader.core.document.Block
import com.mundymorning.touchreader.core.reader.Position
import com.mundymorning.touchreader.core.reader.ReaderBook
import com.mundymorning.touchreader.core.tts.NarrationState
import com.mundymorning.touchreader.data.BookmarkEntity
import com.mundymorning.touchreader.tts.EngineStatus
import com.mundymorning.touchreader.ui.theme.ReaderFont
import com.mundymorning.touchreader.ui.theme.highlightColor
import kotlinx.coroutines.launch

private enum class Sheet { NONE, CHAPTERS, BOOKMARKS, VOICE, SPEED, SLEEP, TEXT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(container: AppContainer, docId: Long, autoPlay: Boolean, onBack: () -> Unit) {
    val controller = container.controller
    val ui by controller.ui.collectAsState()
    val fontSize by container.settings.fontSize.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var viewChapter by rememberSaveable { mutableIntStateOf(-1) }
    var started by rememberSaveable { mutableStateOf(false) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { controller.play() }
    val startPlayback = {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else controller.play()
    }

    LaunchedEffect(docId) {
        controller.open(docId)
        if (autoPlay && !started && controller.ui.value.book != null) { started = true; startPlayback() }
    }
    LaunchedEffect(ui.position.chapter, ui.book) {
        if (ui.book != null) viewChapter = ui.position.chapter
    }
    LaunchedEffect(ui.error) { ui.error?.let { snackbar.showSnackbar(it) } }

    val book = ui.book
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.title.ifEmpty { "Reading" }, maxLines = 1) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library") } },
                actions = {
                    IconButton({ sheet = Sheet.TEXT }) { Icon(Icons.Rounded.TextFields, "Text size") }
                    IconButton({ sheet = Sheet.BOOKMARKS }) { Icon(Icons.Rounded.Bookmarks, "Bookmarks") }
                    IconButton({ sheet = Sheet.CHAPTERS }) { Icon(Icons.AutoMirrored.Rounded.List, "Chapters") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (book != null) PlayerPanel(ui, book, onSheet = { sheet = it }, onToggle = { if (ui.playing) controller.pause() else startPlayback() }, controller = container.controller) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ui.loadError != null -> Text(ui.loadError!!, Modifier.align(Alignment.Center).padding(24.dp))
                book != null -> {
                    val ch = viewChapter.coerceIn(0, book.chapters.lastIndex)
                    ChapterText(
                        book, ch, ui.position, ui.playing, fontSize,
                        onTapSentence = { s -> controller.seekTo(Position(ch, s)); startPlayback() },
                        onNext = { if (ch < book.chapters.lastIndex) { viewChapter = ch + 1; controller.seekTo(Position(ch + 1, 0)) } },
                    )
                }
            }
        }
    }

    if (book != null) when (sheet) {
        Sheet.NONE -> {}
        Sheet.CHAPTERS -> SimpleSheet("Chapters", { sheet = Sheet.NONE }) {
            itemsIndexed(book.chapters) { i, c ->
                ListItem(
                    headlineContent = { Text(c.title, maxLines = 2, fontWeight = if (i == viewChapter) FontWeight.Bold else null) },
                    modifier = Modifier.clickable { viewChapter = i; controller.seekTo(Position(i, 0)); sheet = Sheet.NONE },
                )
            }
        }
        Sheet.BOOKMARKS -> BookmarksSheet(container, docId, book, ui.position, { sheet = Sheet.NONE }) { p -> viewChapter = p.chapter; controller.seekTo(p); sheet = Sheet.NONE }
        Sheet.VOICE -> VoiceSheet(container, ui.voiceName) { sheet = Sheet.NONE }
        Sheet.SPEED -> SimpleSheet("Playback speed", { sheet = Sheet.NONE }) {
            items(listOf(0.7f, 0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f).size) { i ->
                val v = listOf(0.7f, 0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f)[i]
                ListItem(
                    headlineContent = { Text("${v}×", fontWeight = if (v == ui.speed) FontWeight.Bold else null) },
                    trailingContent = { if (v == ui.speed) Icon(Icons.Rounded.Check, null) },
                    modifier = Modifier.clickable { controller.setSpeed(v); sheet = Sheet.NONE },
                )
            }
        }
        Sheet.SLEEP -> SimpleSheet("Sleep timer", { sheet = Sheet.NONE }) {
            val options = listOf<Pair<String, Int?>>("Off" to null, "15 minutes" to 15, "30 minutes" to 30, "45 minutes" to 45, "60 minutes" to 60)
            items(options.size) { i ->
                ListItem(headlineContent = { Text(options[i].first) }, modifier = Modifier.clickable { controller.setSleepTimer(options[i].second); sheet = Sheet.NONE })
            }
            item {
                ListItem(
                    headlineContent = { Text("End of this chapter") },
                    modifier = Modifier.clickable { controller.setSleepTimer(endOfChapter = true); sheet = Sheet.NONE },
                )
            }
        }
        Sheet.TEXT -> ModalBottomSheet({ sheet = Sheet.NONE }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("Text size", style = MaterialTheme.typography.titleMedium)
                Slider(value = fontSize, onValueChange = container.settings::setFontSize, valueRange = 13f..34f)
                Text("The quick brown fox jumps over the lazy dog.", fontFamily = ReaderFont, fontSize = fontSize.sp)
            }
        }
    }
}

@Composable
private fun ChapterText(
    book: ReaderBook,
    chapterIndex: Int,
    position: Position,
    playing: Boolean,
    fontSize: Float,
    onTapSentence: (Int) -> Unit,
    onNext: () -> Unit,
) {
    val chapter = book.chapters[chapterIndex]
    val listState = rememberLazyListState()
    val current = if (position.chapter == chapterIndex) chapter.sentences.getOrNull(position.sentence) else null

    LaunchedEffect(chapterIndex) { listState.scrollToItem(current?.block ?: 0) }
    LaunchedEffect(current?.block, playing) {
        if (playing && current != null) listState.animateScrollToItem(current.block, scrollOffset = -160)
    }

    val hl = highlightColor()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(chapter.blocks) { bi, block ->
            val range = if (current != null && current.block == bi) current.start until current.end else null
            BlockText(block, range, hl, fontSize) { offset -> onTapSentence(chapter.sentenceAt(bi, offset)) }
        }
        item {
            if (chapterIndex < book.chapters.lastIndex) {
                TextButton(onNext, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Next chapter: ${book.chapters[chapterIndex + 1].title}", maxLines = 1) }
            } else {
                Text("The end", Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BlockText(block: Block, highlight: IntRange?, hl: androidx.compose.ui.graphics.Color, fontSize: Float, onTapAt: (Int) -> Unit) {
    val text: AnnotatedString = remember(block, highlight, hl) {
        buildAnnotatedString {
            val r = highlight
            if (r == null) append(block.text) else {
                append(block.text.substring(0, r.first))
                withStyle(SpanStyle(background = hl)) { append(block.text.substring(r.first, r.last + 1)) }
                append(block.text.substring(r.last + 1))
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val size = when (block.level) { 0 -> fontSize; 1 -> fontSize * 1.5f; 2 -> fontSize * 1.3f; else -> fontSize * 1.15f }
    Text(
        text,
        fontFamily = ReaderFont,
        fontSize = size.sp,
        lineHeight = (size * 1.55f).sp,
        fontWeight = if (block.isHeading) FontWeight.Bold else null,
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
            detectTapGestures { pos -> layout?.let { onTapAt(it.getOffsetForPosition(pos)) } }
        },
    )
}

@Composable
private fun PlayerPanel(ui: com.mundymorning.touchreader.playback.PlayerUi, book: ReaderBook, onSheet: (Sheet) -> Unit, onToggle: () -> Unit, controller: com.mundymorning.touchreader.playback.PlaybackController) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(ui.chapterTitle, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
            var drag by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = drag ?: book.progress(ui.position),
                onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { controller.seekToFraction(it) }; drag = null },
                modifier = Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = "Reading position" },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(controller::previousChapter, Modifier.size(48.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous chapter") }
                IconButton(controller::previousSentence, Modifier.size(48.dp)) { Icon(Icons.Rounded.NavigateBefore, "Previous sentence") }
                FilledIconButton(onToggle, Modifier.size(64.dp), shape = CircleShape) {
                    Icon(if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (ui.playing) "Pause" else "Play", Modifier.size(34.dp))
                }
                IconButton(controller::nextSentence, Modifier.size(48.dp)) { Icon(Icons.Rounded.NavigateNext, "Next sentence") }
                IconButton(controller::nextChapter, Modifier.size(48.dp)) { Icon(Icons.Rounded.SkipNext, "Next chapter") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                AssistChip({ onSheet(Sheet.SPEED) }, { Text("${ui.speed}×") }, leadingIcon = { Icon(Icons.Rounded.Speed, null, Modifier.size(18.dp)) })
                AssistChip({ onSheet(Sheet.VOICE) }, { Text("Voice") }, leadingIcon = { Icon(Icons.Rounded.RecordVoiceOver, null, Modifier.size(18.dp)) })
                AssistChip(
                    { onSheet(Sheet.SLEEP) },
                    { Text(if (ui.sleepActive) "On" else "Sleep") },
                    leadingIcon = { Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleSheet(title: String, onDismiss: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    ModalBottomSheet(onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.padding(bottom = 24.dp), content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(container: AppContainer, docId: Long, book: ReaderBook, pos: Position, onDismiss: () -> Unit, onGo: (Position) -> Unit) {
    val marks by container.db.bookmarks().observe(docId).collectAsState(initial = emptyList<BookmarkEntity>())
    val scope = rememberCoroutineScope()
    SimpleSheet("Bookmarks", onDismiss) {
        item {
            FilledTonalButton(
                onClick = {
                    val label = book.chapters[pos.chapter].let { c -> c.text(pos.sentence).take(60) }
                    scope.launch { container.controller.addBookmark(label) }
                },
                modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().heightIn(min = 48.dp),
            ) { Icon(Icons.Rounded.BookmarkAdd, null); Spacer(Modifier.width(8.dp)); Text("Bookmark current position") }
        }
        if (marks.isEmpty()) item { Text("No bookmarks yet.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(marks.size) { i ->
            val m = marks[i]
            ListItem(
                headlineContent = { Text(m.label, maxLines = 2) },
                supportingContent = { Text(book.chapters.getOrNull(m.chapter)?.title.orEmpty(), maxLines = 1) },
                trailingContent = { IconButton({ scope.launch { container.db.bookmarks().delete(m.id) } }) { Icon(Icons.Rounded.Delete, "Delete bookmark") } },
                modifier = Modifier.clickable { onGo(book.clamp(Position(m.chapter, m.sentence))) },
            )
        }
    }
}

@Composable
private fun VoiceSheet(container: AppContainer, selected: String?, onDismiss: () -> Unit) {
    val voices by container.controller.voices.collectAsState()
    val status by container.controller.engineStatus.collectAsState()
    val lang = java.util.Locale.getDefault().language
    SimpleSheet("Narrator", onDismiss) {
        item {
            ListItem(
                headlineContent = { Text("System default", fontWeight = if (selected == null) FontWeight.Bold else null) },
                modifier = Modifier.clickable { container.controller.setVoice(null); onDismiss() },
            )
        }
        if (status is EngineStatus.Failed) item { Text((status as EngineStatus.Failed).message, Modifier.padding(24.dp)) }
        val list = voices.filter { it.language == lang && it.installed }
        items(list.size) { i ->
            val v = list[i]
            ListItem(
                headlineContent = { Text(v.label, fontWeight = if (selected == v.name) FontWeight.Bold else null) },
                supportingContent = { Text(if (v.needsNetwork) "Needs internet" else "Works offline") },
                modifier = Modifier.clickable { container.controller.setVoice(v.name); onDismiss() },
            )
        }
        if (list.isEmpty() && status is EngineStatus.Ready) item { Text("No extra voices for your language are installed. Add some in Android settings → Text-to-speech.", Modifier.padding(24.dp)) }
    }
}
