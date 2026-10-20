package com.mundymorning.touchreader.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mundymorning.touchreader.AppContainer
import com.mundymorning.touchreader.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val s = container.settings
    val theme by s.theme.collectAsState()
    val speed by s.defaultSpeed.collectAsState()
    val size by s.fontSize.collectAsState()
    val cleanup by s.cleanup.collectAsState()
    val steps by s.neuralSteps.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Header("Appearance")
            Column(Modifier.selectableGroup()) {
                listOf(ThemeMode.SYSTEM to "Follow system", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (m, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(theme == m, onClick = { s.setTheme(m) }, role = Role.RadioButton).padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { RadioButton(theme == m, null); Spacer(Modifier.width(16.dp)); Text(label) }
                }
            }
            LabeledSlider("Reading text size", "${size.toInt()} sp", size, 13f..34f) { s.setFontSize(it) }

            Header("Narration")
            LabeledSlider("Default speed for new documents", "${"%.2f".format(speed)}×", speed, 0.7f..2f) { s.setDefaultSpeed((it * 20).toInt() / 20f) }

            Header("Neural voice quality")
            Column(Modifier.selectableGroup()) {
                listOf(4 to "Faster (4 steps) — less processing, may sound rougher", 8 to "Best (8 steps) — reference quality, slower").forEach { (n, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(steps == n, role = Role.RadioButton, onClick = {
                            s.setNeuralSteps(n)
                            if (s.ttsEngine.value == com.mundymorning.touchreader.tts.Engines.NEURAL) container.controller.switchEngine(com.mundymorning.touchreader.tts.Engines.NEURAL)
                        }).padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { RadioButton(steps == n, null); Spacer(Modifier.width(16.dp)); Text(label) }
                }
            }
            Text("Only used if you install the optional neural voice.", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Header("Text cleanup (applies to documents you add next)")
            Toggle("Remove running headers, footers and page numbers", cleanup.removeHeadersAndFooters) { s.setCleanup(cleanup.copy(removeHeadersAndFooters = it)) }
            Toggle("Join words split by a hyphen at line ends", cleanup.joinHyphenatedWords) { s.setCleanup(cleanup.copy(joinHyphenatedWords = it)) }
            Toggle("Rejoin lines that were wrapped mid-sentence", cleanup.unwrapLines) { s.setCleanup(cleanup.copy(unwrapLines = it)) }

            Header("About")
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Touch Reader 0.1.0 — Mundy Morning Studios", style = MaterialTheme.typography.bodyMedium)
                Text("Always Professional. Always Free.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("Everything works offline. Your documents never leave this phone.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text("Open-source components", style = MaterialTheme.typography.titleSmall)
                Text(
                    "AndroidX, Jetpack Compose, Room — Apache License 2.0\nPDFBox-Android — Apache License 2.0\nONNX Runtime — MIT License\nSupertonic code — MIT License; Supertonic 3 model — BigScience OpenRAIL-M\njsoup — MIT License\nKotlin and kotlinx.coroutines — Apache License 2.0",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable private fun Header(t: String) =
    Text(t, Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    ListItem(headlineContent = { Text(label) }, trailingContent = { Switch(value, onChange) }, modifier = Modifier.heightIn(min = 56.dp))

@Composable private fun LabeledSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(valueText) }
        Slider(value, onChange, valueRange = range)
    }
}
