package com.mundymorning.touchreader.ui.library

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mundymorning.touchreader.AppContainer
import com.mundymorning.touchreader.data.DocumentEntity
import com.mundymorning.touchreader.importer.ImportResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(container: AppContainer, onOpen: (id: Long, play: Boolean) -> Unit, onAudiobook: (id: Long) -> Unit) {
    val docs by container.db.documents().observeAll().collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<DocumentEntity?>(null) }
    var removing by remember { mutableStateOf<DocumentEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            for (uri in uris) {
                when (val r = container.importer.import(uri)) {
                    is ImportResult.Success -> snackbar.showSnackbar("Added “${r.title}”")
                    is ImportResult.Failure -> snackbar.showSnackbar(r.message, duration = SnackbarDuration.Long)
                }
            }
            importing = false
        }
    }

    val shown = remember(docs, query) {
        if (query.isBlank()) docs else docs.filter { it.title.contains(query, true) || (it.author ?: "").contains(query, true) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Library") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!importing) picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add document") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search your library") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (shown.isEmpty()) {
                EmptyState(hasDocs = docs.isNotEmpty())
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shown, key = { it.id }) { d ->
                        DocumentCard(
                            d,
                            onOpen = { onOpen(d.id, false) },
                            onResume = { onOpen(d.id, true) },
                            onAudiobook = { onAudiobook(d.id) },
                            onRename = { renaming = d },
                            onRemove = { removing = d },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { d ->
        var text by remember(d.id) { mutableStateOf(d.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val t = text.trim()
                    if (t.isNotEmpty()) scope.launch { container.db.documents().rename(d.id, t) }
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }

    removing?.let { d ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove from library?") },
            text = { Text("“${d.title}” will be removed from Touch Reader, along with its bookmarks and reading position. The original file on your device is not deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    container.controller.closeIfOpen(d.id)
                    scope.launch { container.importer.remove(d.id) }
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton({ removing = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun EmptyState(hasDocs: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.MenuBook, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(if (hasDocs) "No matches" else "Your library is empty", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasDocs) "Try a different search." else "Add a PDF, EPUB, Word, text, HTML or Markdown file and listen to it read aloud.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DocumentCard(
    d: DocumentEntity,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onAudiobook: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(d.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    d.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More options for ${d.title}") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Audiobook…") }, leadingIcon = { Icon(Icons.Rounded.Headphones, null) }, onClick = { menu = false; onAudiobook() })
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Remove from library") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onRemove() })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onOpen, label = { Text(d.format) })
                Text(
                    "Opened " + DateUtils.getRelativeTimeSpanString(d.lastOpenedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { d.progress }, Modifier.fillMaxWidth().height(6.dp))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth()) {
                Text("${(d.progress * 100).toInt()}% read", style = MaterialTheme.typography.labelMedium)
                Text("Narrator: ${d.voiceName?.let { if (it.startsWith("custom:")) "your voice" else it } ?: "System default"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onAudiobook, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Headphones, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Audiobook")
                }
                FilledTonalButton(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Resume")
                }
            }
        }
    }
}
