package com.mundymorning.touchreader.data

import android.content.Context
import com.mundymorning.touchreader.core.document.CleanupOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

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
