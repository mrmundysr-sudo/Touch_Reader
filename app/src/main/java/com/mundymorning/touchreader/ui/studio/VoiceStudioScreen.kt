package com.mundymorning.touchreader.ui.studio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mundymorning.touchreader.AppContainer
import com.mundymorning.touchreader.core.voicestudio.QualityIssue
import com.mundymorning.touchreader.core.voicestudio.Severity
import com.mundymorning.touchreader.core.voicestudio.Verdict
import com.mundymorning.touchreader.studio.VoiceRecorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceStudioScreen(container: AppContainer, onClose: () -> Unit) {
    val studio = container.studio
    val ui by studio.ui.collectAsState()
    val rec by studio.recorder.state.collectAsState()
    val playing by container.samplePlayer.playingKey.collectAsState()
    val playerError by container.samplePlayer.error.collectAsState()
    val ctx = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { container.samplePlayer.stop() } }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { permissionDenied = false; studio.startRecording() } else permissionDenied = true
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) studio.importFile(uri) }

    fun record() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) studio.startRecording()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create a voice") },
                navigationIcon = { IconButton({ container.samplePlayer.stop(); studio.recorder.discard(); onClose() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "Your recording stays on this phone. It is never uploaded or shared, and you can delete it any time.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(ui.name, studio::setName, label = { Text("Voice name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(ui.consent, role = Role.Checkbox) { studio.setConsent(!ui.consent) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(ui.consent, null)
                Spacer(Modifier.width(12.dp))
                Text("I confirm that I own this recording or have permission to use the voice.")
            }

            Spacer(Modifier.height(16.dp))
            Text("Tips for a good sample", style = MaterialTheme.typography.titleSmall)
            Text(
                "One person talking, in a quiet room, with the phone about a hand's width from your mouth. Read something aloud at your natural pace for 10 to 30 seconds. Avoid music and background noise.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            if (rec.recording) {
                RecordingPanel(rec) { studio.stopRecording() }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = ::record, enabled = ui.consent && ui.busy == null, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Icon(Icons.Rounded.Mic, null); Spacer(Modifier.width(8.dp)); Text(if (ui.prepared == null) "Record" else "Record again")
                    }
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("audio/*", "application/ogg")) },
                        enabled = ui.consent && ui.busy == null, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    ) { Icon(Icons.Rounded.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Import audio") }
                }
                if (!ui.consent) Text("Tick the box above to continue.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                Text("Import accepts MP3, WAV and M4A files.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (permissionDenied) {
                Spacer(Modifier.height(8.dp))
                Text("Microphone access was denied. You can allow it in Android settings, or import an audio file instead.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            rec.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            playerError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            ui.busy?.let {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text(it) }
            }

            ui.prepared?.let { prepared ->
                val r = prepared.report
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (label, colour) = when (r.verdict) {
                        Verdict.GOOD -> "Good sample" to MaterialTheme.colorScheme.primary
                        Verdict.USABLE -> "Usable, could be better" to MaterialTheme.colorScheme.secondary
                        Verdict.POOR -> "Not good enough yet" to MaterialTheme.colorScheme.error
                    }
                    Text(label, style = MaterialTheme.typography.titleMedium, color = colour)
                    Spacer(Modifier.weight(1f))
                    Text("%.0f s of speech".format(r.speechSeconds), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                r.issues.filter { it.severity != Severity.INFO }.forEach { IssueRow(it) }
                r.issues.filter { it.severity == Severity.INFO }.forEach { IssueRow(it) }

                Spacer(Modifier.height(12.dp))
                val isPlaying = playing == "prepared"
                OutlinedButton(
                    onClick = { if (isPlaying) container.samplePlayer.stop() else container.samplePlayer.playPcm("prepared", prepared.audio) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp))
                    Text(if (isPlaying) "Stop" else "Listen to the cleaned-up sample")
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { container.samplePlayer.stop(); studio.save(onClose) },
                    enabled = ui.canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Save voice") }
                if (!ui.canSave && !r.blocking) {
                    Text(
                        if (ui.name.isBlank()) "Give your voice a name." else "Confirm the permission box above to save.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Saved voices can be renamed and deleted from the Voices tab. Narrating a book in your own voice needs a voice-cloning engine that is not available in this version yet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RecordingPanel(rec: VoiceRecorder.State, onStop: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Recording… %d:%02d".format(rec.elapsedMs / 60000, rec.elapsedMs / 1000 % 60), style = MaterialTheme.typography.titleLarge)
        LinearProgressIndicator(progress = { rec.level.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(vertical = 12.dp).height(8.dp))
        Text("Speak naturally. Aim for 10 to 30 seconds.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop")
        }
    }
}

@Composable
private fun IssueRow(issue: QualityIssue) {
    val (icon, tint) = when (issue.severity) {
        Severity.BLOCKING -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
        Severity.WARNING -> Icons.Rounded.Warning to MaterialTheme.colorScheme.secondary
        Severity.INFO -> Icons.Rounded.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(issue.message, style = MaterialTheme.typography.bodySmall)
    }
}
