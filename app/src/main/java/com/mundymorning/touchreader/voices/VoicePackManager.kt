package com.mundymorning.touchreader.voices

import android.content.Context
import com.mundymorning.touchreader.core.voicepacks.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface PackState {
    data object Available : PackState
    data class Downloading(val done: Long, val total: Long) : PackState
    data object Installing : PackState
    data class Installed(val sizeOnDisk: Long) : PackState
    data class Failed(val message: String) : PackState
}

/**
 * Lists the bundled voice-pack catalog and manages downloads. The catalog currently ships empty:
 * no pack has been published or verified, so nothing is offered that could not be checked.
 */
class VoicePackManager(private val context: Context) {
    private val store = PackStore(File(context.filesDir, "voicepacks"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val parsed: CatalogResult = try {
        VoicePackCatalog.parse(context.assets.open("voicepacks.json").bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        CatalogResult(emptyList(), listOf("catalog unreadable: ${e.message}"))
    }

    val catalog: List<VoicePack> = parsed.packs

    private val _states = MutableStateFlow(
        catalog.associate { p ->
            p.id to (if (store.get(p.id) != null) PackState.Installed(store.sizeOnDisk(p.id)) else PackState.Available)
        }
    )
    val states: StateFlow<Map<String, PackState>> = _states.asStateFlow()

    private fun set(id: String, s: PackState) = _states.update { it + (id to s) }

    fun download(pack: VoicePack) {
        if (jobs[pack.id]?.isActive == true) return
        jobs[pack.id] = scope.launch {
            val job = coroutineContext[Job]!!
            set(pack.id, PackState.Downloading(0, pack.sizeBytes))
            try {
                store.installFromNetwork(
                    pack, PackDownloader(),
                    isCancelled = { !job.isActive },
                    onProgress = { d, t -> if (d < t) set(pack.id, PackState.Downloading(d, t)) else set(pack.id, PackState.Installing) },
                )
                set(pack.id, PackState.Installed(store.sizeOnDisk(pack.id)))
            } catch (e: DownloadCancelledException) {
                set(pack.id, PackState.Available) // partial file is kept so the next attempt resumes
            } catch (e: InsufficientSpaceException) {
                set(pack.id, PackState.Failed("Not enough free storage (needs about ${e.needed / 1_000_000} MB)."))
            } catch (e: ChecksumMismatchException) {
                set(pack.id, PackState.Failed("The download was corrupt and has been discarded. Try again."))
            } catch (e: Exception) {
                set(pack.id, PackState.Failed("Download failed: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    fun cancel(id: String) { jobs[id]?.cancel() }

    fun delete(id: String) {
        scope.launch {
            store.delete(id)
            set(id, PackState.Available)
        }
    }

    /** Folder of an installed pack, for a future engine to load. */
    fun installedDir(id: String): File? = store.get(id)?.dir
}
