package com.mundymorning.touchreader.playback

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.mundymorning.touchreader.core.reader.Position
import com.mundymorning.touchreader.core.reader.ReaderBook
import com.mundymorning.touchreader.core.export.ExportEstimator
import com.mundymorning.touchreader.core.export.LiveSpeed
import com.mundymorning.touchreader.core.export.SpeedClass
import com.mundymorning.touchreader.core.tts.NarrationSession
import com.mundymorning.touchreader.core.tts.NarrationState
import com.mundymorning.touchreader.data.AppDatabase
import com.mundymorning.touchreader.data.BookmarkEntity
import com.mundymorning.touchreader.data.DocumentEntity
import com.mundymorning.touchreader.data.DocumentStore
import com.mundymorning.touchreader.data.Settings
import com.mundymorning.touchreader.tts.AndroidTtsEngine
import com.mundymorning.touchreader.tts.AppEngine
import com.mundymorning.touchreader.tts.Engines
import com.mundymorning.touchreader.tts.PocketNarrator
import com.mundymorning.touchreader.tts.SupertonicAppEngine
import com.mundymorning.touchreader.voices.VoicePackManager
import com.mundymorning.touchreader.tts.EngineStatus
import com.mundymorning.touchreader.tts.TtsEngineInfo
import com.mundymorning.touchreader.tts.VoiceInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The neural voice takes [rtf] seconds to make one second of speech here ([custom]: with a saved voice). */
data class SpeedAdvice(val rtf: Double, val custom: Boolean)

data class PlayerUi(
    val docId: Long? = null,
    val title: String = "",
    val book: ReaderBook? = null,
    val loading: Boolean = false,
    val loadError: String? = null,
    val position: Position = Position.START,
    val state: NarrationState = NarrationState.IDLE,
    val speed: Float = 1f,
    val voiceName: String? = null,
    val error: String? = null,
    val sleepEndsAt: Long? = null,
    val sleepEndOfChapter: Boolean = false,
    /** Set when the neural voice is slower than real time on this phone; the reader offers an audiobook instead. */
    val speedAdvice: SpeedAdvice? = null,
) {
    val playing get() = state == NarrationState.PLAYING
    val chapterTitle get() = book?.chapters?.getOrNull(position.chapter)?.title.orEmpty()
    val sleepActive get() = sleepEndsAt != null || sleepEndOfChapter
}

/**
 * App-wide owner of the open document and its narration. UI and the media service both talk to this,
 * so the reading position stays single-sourced and survives screen changes.
 */
class PlaybackController(
    private val app: Application,
    private val db: AppDatabase,
    private val store: DocumentStore,
    private val settings: Settings,
    private val packs: VoicePackManager,
    private val voiceLibrary: com.mundymorning.touchreader.studio.VoiceLibrary,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Created lazily on the main thread so app start-up does not wait for the speech service.
    private val engineFlow = MutableStateFlow<AppEngine?>(null)
    private var statusJob: Job? = null

    private fun engine(): AppEngine = engineFlow.value ?: createEngine().also { e ->
        engineFlow.value = e
        watchStatus(e)
    }

    private fun createEngine(): AppEngine {
        if (settings.ttsEngine.value == Engines.NEURAL && !neuralBlocked) {
            val dir = packs.installedDir(Engines.NEURAL_PACK_ID)
            val pocketDir = packs.installedDir(Engines.POCKET_PACK_ID)
            if (dir != null || pocketDir != null) {
                val steps = settings.neuralSteps.value
                return SupertonicAppEngine(app, dir, pocketDir, steps, voiceLibrary, settings.defaultVoice.value).also { e ->
                    // both callbacks run on the synthesis thread
                    e.onSlow = { rtf -> scope.launch { learnSpeed(steps, rtf) } }
                    e.onSpeedKnown = { rtf -> scope.launch { learnSpeed(steps, rtf) } }
                }
            }
            settings.setTtsEngine(null) // the pack was deleted: go back to the system voice
        }
        return AndroidTtsEngine(app, settings.ttsEngine.value)
    }

    /** While an audiobook is rendering with the neural voice, live narration uses the system voice (both together would not fit in memory). */
    @Volatile var neuralBlocked = false

    /** Frees the narrator and closes the open document so the exporter can use the memory. */
    fun releaseForExport() {
        statusJob?.cancel()
        closeCurrent()
        engineFlow.value?.release()
        engineFlow.value = null
    }

    /** Rendering ended: if narration had to fall back to the system voice meanwhile, go back to the neural voice next time. */
    fun unblockNeural() {
        neuralBlocked = false
        if (settings.ttsEngine.value == Engines.NEURAL && engineFlow.value is AndroidTtsEngine && !_ui.value.playing) releaseForExport()
    }

    private fun usingCustomVoice() = (_ui.value.voiceName ?: settings.defaultVoice.value)?.startsWith(Engines.CUSTOM_PREFIX) == true

    /** Remembers how fast this phone is with the current voice; if it cannot keep up with real time, tells the reader screen. */
    private fun learnSpeed(steps: Int, rtf: Double) {
        val custom = usingCustomVoice()
        // saved voices are spoken by a different model, timed at its "Faster" setting (4)
        val key = if (custom) 4 else steps
        settings.recordSpeed(ExportEstimator.Measurement(key, custom, rtf, if (custom) ExportEstimator.ENGINE_POCKET else ExportEstimator.ENGINE_SUPERTONIC))
        if (SpeedClass.classify(rtf) == LiveSpeed.TOO_SLOW && !settings.speedAdviceDismissed(key, custom) && _ui.value.speedAdvice == null) {
            _ui.update { it.copy(speedAdvice = SpeedAdvice(rtf, custom)) }
        }
    }

    /** [forever]: do not offer an audiobook again for this voice setting (the Export screen stays available). */
    fun dismissSpeedAdvice(forever: Boolean) {
        _ui.value.speedAdvice?.let { if (forever) settings.dismissSpeedAdvice(if (it.custom) 4 else settings.neuralSteps.value, it.custom) }
        _ui.update { it.copy(speedAdvice = null) }
    }

    /** Never freeze or crash on a speech engine that will not start: fall back to the system default once. */
    private fun watchStatus(e: AppEngine) {
        statusJob?.cancel()
        statusJob = scope.launch {
            val failed = e.status.first { it is EngineStatus.Failed || it is EngineStatus.Ready }
            if (failed is EngineStatus.Failed && settings.ttsEngine.value != null) {
                val neural = settings.ttsEngine.value == Engines.NEURAL
                _ui.update {
                    it.copy(
                        error = if (neural) "The neural voice could not start on this phone. Using the system voice instead."
                        else "The selected speech engine could not start. Using the system default instead."
                    )
                }
                switchEngine(null)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val voices: Flow<List<VoiceInfo>>
        get() { engine(); return engineFlow.filterNotNull().flatMapLatest { it.voices } }

    @OptIn(ExperimentalCoroutinesApi::class)
    val engineStatus: Flow<EngineStatus>
        get() { engine(); return engineFlow.filterNotNull().flatMapLatest { it.status } }

    /** What the narrator is doing right now (loading, preparing a voice, timings); empty if nothing to show. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val engineActivity: Flow<String>
        get() { engine(); return engineFlow.filterNotNull().flatMapLatest { it.activity } }

    @OptIn(ExperimentalCoroutinesApi::class)
    val engines: Flow<List<TtsEngineInfo>>
        get() { engine(); return engineFlow.filterNotNull().flatMapLatest { it.engines } }

    val defaultEnginePackage: String? get() = engineFlow.value?.defaultEnginePackage

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private var session: NarrationSession? = null
    private var collectJob: Job? = null
    private var saveJob: Job? = null
    private var sleepJob: Job? = null
    private var resumeOnFocusGain = false

    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val focusRequest: AudioFocusRequestCompat by lazy {
        AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH).build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocusGain = false; pause() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnFocusGain = _ui.value.playing; pause() }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) { resumeOnFocusGain = false; play() }
                }
            }.build()
    }

    /** Loads [docId] (no-op if already open) and restores the saved position, voice and speed. */
    suspend fun open(docId: Long) {
        if (_ui.value.docId == docId && _ui.value.book != null) return
        closeCurrent()
        _ui.value = PlayerUi(docId = docId, loading = true)
        val loaded = withContext<Pair<Pair<DocumentEntity, ReaderBook>?, String?>>(Dispatchers.IO) {
            try {
                val entity = db.documents().get(docId) ?: return@withContext null to "This document is no longer in your library."
                val book = ReaderBook.from(store.load(docId))
                if (book.totalSentences == 0) return@withContext null to "This document has no readable text."
                (entity to book) to null
            } catch (e: OutOfMemoryError) {
                null to "This document is too large to open on this device."
            } catch (e: Exception) {
                null to "This document could not be opened (${e.javaClass.simpleName})."
            }
        }
        val pair = loaded.first
        if (pair == null) { _ui.value = PlayerUi(docId = docId, loadError = loaded.second); return }
        val (entity, book) = pair
        val e = engine()
        // A voice saved with this document may belong to another engine (e.g. an old system voice); it must not
        // silently override the narrator the user chose since, so ignore it if the active neural engine does not know it.
        val saved = entity.voiceName?.takeIf { v -> (e as? SupertonicAppEngine)?.hasVoice(v) ?: true }
        val voice = saved ?: settings.defaultVoice.value
        val start = book.clamp(Position(entity.chapter, entity.sentence))
        e.setSpeed(entity.speed)
        e.setVoice(voice)
        _ui.value = PlayerUi(docId, entity.title, book, false, null, start, NarrationState.IDLE, entity.speed, voice)
        attachSession(book, start)
        withContext(Dispatchers.IO) { db.documents().touch(docId, System.currentTimeMillis()) }
    }

    private fun attachSession(book: ReaderBook, start: Position) {
        val s = NarrationSession(book, engine(), start)
        session = s
        collectJob?.cancel()
        collectJob = scope.launch {
            combine(s.position, s.state, s.error) { p, st, e -> Triple(p, st, e) }.collect { (p, st, e) ->
                val wasPlaying = _ui.value.playing
                _ui.update { it.copy(position = p, state = st, error = e ?: it.error, sleepEndOfChapter = s.pauseAtChapterEnd) }
                persist(immediate = wasPlaying && st != NarrationState.PLAYING || st == NarrationState.FINISHED)
                if (st != NarrationState.PLAYING) abandonFocusIfIdle(st)
            }
        }
    }

    private fun abandonFocusIfIdle(st: NarrationState) {
        if (st == NarrationState.IDLE || st == NarrationState.FINISHED || st == NarrationState.ERROR) {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
        }
    }

    private fun persist(immediate: Boolean) {
        val ui = _ui.value
        val id = ui.docId ?: return
        val book = ui.book ?: return
        saveJob?.cancel()
        val p = ui.position
        saveJob = scope.launch {
            if (!immediate) delay(1500)
            withContext(NonCancellable + Dispatchers.IO) {
                db.documents().savePosition(id, p.chapter, p.sentence, book.progress(p), System.currentTimeMillis())
            }
        }
    }

    fun play() {
        val s = session ?: return
        _ui.update { it.copy(error = null) }
        engine().setVoice(_ui.value.voiceName)
        val granted = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            _ui.update { it.copy(error = "Another app is using the audio. Try again in a moment.") }
            return
        }
        ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
        s.play()
    }

    fun pause() { session?.pause(); persist(immediate = true) }
    fun toggle() { if (_ui.value.playing) pause() else play() }

    fun seekTo(p: Position) { session?.seekTo(p); persist(immediate = false) }
    fun seekToFraction(f: Float) { _ui.value.book?.let { seekTo(it.positionAt(f)) } }
    fun playFrom(p: Position) { seekTo(p); play() }
    fun nextSentence() { session?.nextSentence() }
    fun previousSentence() { session?.previousSentence() }
    fun nextChapter() { session?.nextChapter() }
    fun previousChapter() { session?.previousChapter() }

    fun setSpeed(speed: Float) {
        val id = _ui.value.docId ?: return
        engine().setSpeed(speed)
        _ui.update { it.copy(speed = speed) }
        session?.restartCurrent()
        scope.launch(Dispatchers.IO) { db.documents().setSpeed(id, speed) }
    }

    /** The pace (80-100%) of the current narrator if it is a saved voice, else null. */
    fun voicePace(): Int? {
        val name = _ui.value.voiceName ?: settings.defaultVoice.value ?: return null
        if (!name.startsWith(Engines.CUSTOM_PREFIX)) return null
        return PocketNarrator.choice(voiceLibrary, name.removePrefix(Engines.CUSTOM_PREFIX)).second
    }

    /** Slows the saved voice down (80-100%) without changing its pitch; applies from the sentence being made. */
    fun setVoicePace(percent: Int) {
        val name = _ui.value.voiceName ?: settings.defaultVoice.value ?: return
        if (!name.startsWith(Engines.CUSTOM_PREFIX)) return
        val id = name.removePrefix(Engines.CUSTOM_PREFIX)
        PocketNarrator.saveChoice(voiceLibrary, id, PocketNarrator.choice(voiceLibrary, id).first, percent)
        session?.restartCurrent()
    }

    /** Choose a narrator for this document; null = system default. */
    fun setVoice(name: String?) {
        val id = _ui.value.docId ?: return
        engine().setVoice(name)
        _ui.update { it.copy(voiceName = name) }
        session?.restartCurrent()
        scope.launch(Dispatchers.IO) { db.documents().setVoice(id, name) }
    }

    /** Make a saved voice the narrator: switch to the neural engine (which hosts custom voices) and select it. */
    fun useCustomVoice(profileId: String) {
        val name = Engines.CUSTOM_PREFIX + profileId
        settings.setDefaultVoice(name)
        if (settings.ttsEngine.value != Engines.NEURAL) switchEngine(Engines.NEURAL, name)
        else if (_ui.value.docId != null) setVoice(name)
    }

    /** Switch narrator engine: null = system default, a package name, or [Engines.NEURAL]. Keeps place and play state. */
    fun switchEngine(pkg: String?, voice: String? = null) {
        settings.setTtsEngine(pkg)
        val ui = _ui.value
        val wasPlaying = ui.playing
        collectJob?.cancel()
        session?.stop(); session?.release(); session = null
        engineFlow.value?.release()
        engineFlow.value = null
        val e = engine()
        e.setSpeed(ui.speed)
        // Voice names belong to one engine, so a switch resets the narrator to that engine's default.
        e.setVoice(voice)
        ui.docId?.let { id -> scope.launch(Dispatchers.IO) { db.documents().setVoice(id, voice) } }
        _ui.update { it.copy(voiceName = voice, state = NarrationState.IDLE, speedAdvice = null) }
        ui.book?.let { attachSession(it, ui.position) }
        if (wasPlaying) play()
    }

    fun previewVoice(name: String, sample: String) {
        pause()
        engine().preview(name, sample)
    }

    fun stopPreview() { engineFlow.value?.stop() }

    /** Pause after [minutes], or at the end of the current chapter. Pass nothing to turn the timer off. */
    fun setSleepTimer(minutes: Int? = null, endOfChapter: Boolean = false) {
        sleepJob?.cancel()
        session?.pauseAtChapterEnd = endOfChapter
        _ui.update { it.copy(sleepEndsAt = null, sleepEndOfChapter = endOfChapter) }
        if (minutes == null) return
        val ends = System.currentTimeMillis() + minutes * 60_000L
        _ui.update { it.copy(sleepEndsAt = ends) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            _ui.update { it.copy(sleepEndsAt = null) }
        }
    }

    suspend fun addBookmark(label: String) {
        val ui = _ui.value
        val id = ui.docId ?: return
        withContext(Dispatchers.IO) {
            db.bookmarks().insert(
                BookmarkEntity(
                    docId = id, chapter = ui.position.chapter, sentence = ui.position.sentence,
                    label = label, createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Called before a document is removed from the library. */
    fun closeIfOpen(docId: Long) { if (_ui.value.docId == docId) closeCurrent() }

    private fun closeCurrent() {
        sleepJob?.cancel()
        collectJob?.cancel()
        session?.stop()
        session?.release()
        session = null
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
        _ui.value = PlayerUi()
    }
}
