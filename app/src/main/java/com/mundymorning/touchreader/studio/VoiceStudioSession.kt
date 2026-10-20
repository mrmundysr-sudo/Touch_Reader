package com.mundymorning.touchreader.studio

import android.app.Application
import android.net.Uri
import com.mundymorning.touchreader.core.voicestudio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** State of one voice-creation attempt. App-scoped so a rotation does not lose a recording. */
class VoiceStudioSession(private val app: Application, private val library: VoiceLibrary) {
    data class Ui(
        val name: String = "",
        val consent: Boolean = false,
        val prepared: PreparedSample? = null,
        val busy: String? = null,
        val message: String? = null,
        val savedName: String? = null,
    ) {
        val canSave get() = consent && prepared != null && !prepared.report.blocking && name.isNotBlank() && busy == null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val recorder = VoiceRecorder()
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    fun setName(n: String) = _ui.update { it.copy(name = n.take(60)) }
    fun setConsent(c: Boolean) = _ui.update { it.copy(consent = c) }
    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun startRecording(): Boolean {
        _ui.update { it.copy(prepared = null, message = null, savedName = null) }
        return recorder.start()
    }

    fun stopRecording() = process(recorder.stop())

    fun importFile(uri: Uri) {
        _ui.update { it.copy(prepared = null, message = null, savedName = null, busy = "Reading the audio file…") }
        scope.launch {
            val pcm = withContext(Dispatchers.IO) {
                try { AudioDecoder.decode(app, uri) }
                catch (e: UnsupportedAudioException) { _ui.update { it.copy(message = e.message) }; null }
                catch (e: Exception) { _ui.update { it.copy(message = "This audio file could not be read.") }; null }
                catch (e: OutOfMemoryError) { _ui.update { it.copy(message = "This audio file is too large for this phone.") }; null }
            }
            process(pcm)
        }
    }

    private fun process(pcm: Pcm?) {
        if (pcm == null || pcm.samples.isEmpty()) { _ui.update { it.copy(busy = null, message = it.message ?: "Nothing was recorded.") }; return }
        _ui.update { it.copy(busy = "Checking and cleaning up the audio…") }
        scope.launch {
            val prepared = withContext(Dispatchers.Default) { VoiceSampleProcessor.prepare(pcm) }
            _ui.update { it.copy(prepared = prepared, busy = null) }
        }
    }

    fun discardSample() { recorder.discard(); _ui.update { it.copy(prepared = null, message = null) } }

    fun save(onSaved: () -> Unit) {
        val s = _ui.value
        val prepared = s.prepared ?: return
        if (!s.canSave) return
        _ui.update { it.copy(busy = "Saving…") }
        scope.launch {
            try {
                val p = library.create(s.name, prepared, s.consent)
                _ui.value = Ui(savedName = p.name)
                onSaved()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, message = "The voice could not be saved (not enough storage?).") }
            }
        }
    }

    fun reset() { recorder.discard(); _ui.value = Ui() }
}
