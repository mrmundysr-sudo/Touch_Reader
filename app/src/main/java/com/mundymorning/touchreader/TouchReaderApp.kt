package com.mundymorning.touchreader

import android.app.Application
import androidx.room.Room
import com.mundymorning.touchreader.data.AppDatabase
import com.mundymorning.touchreader.data.DocumentStore
import com.mundymorning.touchreader.data.Settings
import com.mundymorning.touchreader.importer.ImportManager
import com.mundymorning.touchreader.playback.PlaybackController
import com.mundymorning.touchreader.voices.VoicePackManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** Hand-rolled service locator; small enough that a DI framework would only add build risk. */
class AppContainer(app: Application) {
    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "touchreader.db").build()
    val store = DocumentStore(app)
    val settings = Settings(app)
    val importer = ImportManager(app, db, store, settings)
    val controller = PlaybackController(app, db, store, settings)
    val voicePacks = VoicePackManager(app)
}

class TouchReaderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(this)
    }
}
