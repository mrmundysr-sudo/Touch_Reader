package com.mundymorning.touchreader.studio

import android.content.Context
import com.mundymorning.touchreader.core.voicestudio.PreparedSample
import com.mundymorning.touchreader.core.voicestudio.VoiceProfile
import com.mundymorning.touchreader.core.voicestudio.VoiceProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** The user's custom voices, kept in private app storage (not backed up, never uploaded). */
class VoiceLibrary(context: Context) {
    private val store = VoiceProfileStore(File(context.filesDir, "voices"))
    private val _profiles = MutableStateFlow(store.list())
    val profiles: StateFlow<List<VoiceProfile>> = _profiles.asStateFlow()

    private fun refresh() { _profiles.value = store.list() }

    suspend fun create(name: String, sample: PreparedSample, consent: Boolean): VoiceProfile =
        withContext(Dispatchers.IO) { store.create(name, "", sample, consent) }.also { refresh() }

    suspend fun rename(id: String, name: String) { withContext(Dispatchers.IO) { store.rename(id, name) }; refresh() }

    suspend fun delete(id: String) { withContext(Dispatchers.IO) { store.delete(id) }; refresh() }

    fun sampleFile(id: String): File? = store.sampleFile(id)
}
