package com.mundymorning.touchreader.data

import android.content.Context
import com.mundymorning.touchreader.core.document.CleanupOptions
import com.mundymorning.touchreader.core.export.ExportEstimator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** LIGHT is shown as "Parchment", DARK as "Study". New entries go at the end: the saved choice is the position. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, CONTRAST }

/** Small preference store exposing observable values. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _fontSize = MutableStateFlow(prefs.getFloat("fontSize", 19f))
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()
    fun setFontSize(v: Float) { _fontSize.value = v.coerceIn(13f, 34f); prefs.edit().putFloat("fontSize", _fontSize.value).apply() }

    private val _theme = MutableStateFlow(ThemeMode.entries.getOrElse(prefs.getInt("theme", 0)) { ThemeMode.SYSTEM })
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    fun setTheme(m: ThemeMode) { _theme.value = m; prefs.edit().putInt("theme", m.ordinal).apply() }

    private val _defaultVoice = MutableStateFlow(prefs.getString("defaultVoice", null))
    val defaultVoice: StateFlow<String?> = _defaultVoice.asStateFlow()
    fun setDefaultVoice(name: String?) { _defaultVoice.value = name; prefs.edit().putString("defaultVoice", name).apply() }

    private val _ttsEngine = MutableStateFlow(prefs.getString("ttsEngine", null))
    /** Package name of the chosen Android speech engine, or null for the system default. */
    val ttsEngine: StateFlow<String?> = _ttsEngine.asStateFlow()
    fun setTtsEngine(pkg: String?) { _ttsEngine.value = pkg; prefs.edit().putString("ttsEngine", pkg).apply() }

    private val _neuralSteps = MutableStateFlow(prefs.getInt("neuralSteps", 8))
    /** Denoising steps for the neural voice: fewer is faster, 8 is the reference quality. */
    val neuralSteps: StateFlow<Int> = _neuralSteps.asStateFlow()
    fun setNeuralSteps(n: Int) { _neuralSteps.value = n; prefs.edit().putInt("neuralSteps", n).apply() }

    private val _speedTimings = MutableStateFlow(parseTimings(prefs.getString("speedTimings", "") ?: ""))
    /** How fast this phone makes speech with the neural voice (built-in or converted), from timing a few sentences. */
    val speedTimings: StateFlow<List<ExportEstimator.Measurement>> = _speedTimings.asStateFlow()
    fun recordSpeed(m: ExportEstimator.Measurement) {
        val next = _speedTimings.value.filterNot { it.steps == m.steps && it.custom == m.custom && it.engine == m.engine } + m
        _speedTimings.value = next
        prefs.edit().putString("speedTimings", next.joinToString(";") { "${it.steps}:${it.custom}:${it.rtf}:${it.engine}" }).apply()
    }
    fun clearSpeedTimings() { _speedTimings.value = emptyList(); prefs.edit().remove("speedTimings").apply() }

    fun speedAdviceDismissed(steps: Int, custom: Boolean) = prefs.getBoolean("speedAdviceOff_${steps}_$custom", false)
    fun dismissSpeedAdvice(steps: Int, custom: Boolean) { prefs.edit().putBoolean("speedAdviceOff_${steps}_$custom", true).apply() }

    private val _exportOnlyCharging = MutableStateFlow(prefs.getBoolean("exportOnlyCharging", true))
    /** Pause audiobook rendering when the phone is unplugged. */
    val exportOnlyCharging: StateFlow<Boolean> = _exportOnlyCharging.asStateFlow()
    fun setExportOnlyCharging(v: Boolean) { _exportOnlyCharging.value = v; prefs.edit().putBoolean("exportOnlyCharging", v).apply() }

    private fun parseTimings(s: String): List<ExportEstimator.Measurement> = s.split(';').mapNotNull { part ->
        val f = part.split(':')
        if (f.size != 3 && f.size != 4) return@mapNotNull null
        val steps = f[0].toIntOrNull() ?: return@mapNotNull null
        val rtf = f[2].toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        ExportEstimator.Measurement(steps, f[1].toBooleanStrictOrNull() ?: return@mapNotNull null, rtf, f.getOrNull(3) ?: ExportEstimator.ENGINE_SUPERTONIC)
    }

    /** Records that the user accepted a pack's terms (pack id, version, time). */
    fun recordAcceptance(packId: String, version: Int) {
        prefs.edit().putLong("accepted_${packId}_$version", System.currentTimeMillis()).apply()
    }

    private val _defaultSpeed = MutableStateFlow(prefs.getFloat("defaultSpeed", 1f))
    val defaultSpeed: StateFlow<Float> = _defaultSpeed.asStateFlow()
    fun setDefaultSpeed(v: Float) { _defaultSpeed.value = v; prefs.edit().putFloat("defaultSpeed", v).apply() }

    private val _cleanup = MutableStateFlow(
        CleanupOptions(
            removeHeadersAndFooters = prefs.getBoolean("cu_headers", true),
            joinHyphenatedWords = prefs.getBoolean("cu_hyphen", true),
            unwrapLines = prefs.getBoolean("cu_unwrap", true),
        )
    )
    val cleanup: StateFlow<CleanupOptions> = _cleanup.asStateFlow()
    fun setCleanup(o: CleanupOptions) {
        _cleanup.value = o
        prefs.edit().putBoolean("cu_headers", o.removeHeadersAndFooters)
            .putBoolean("cu_hyphen", o.joinHyphenatedWords).putBoolean("cu_unwrap", o.unwrapLines).apply()
    }
}
