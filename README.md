# Touch Reader

Offline-first Android app that turns documents into audiobooks. Free, no accounts, no cloud.
*Mundy Morning Studios — Always Professional. Always Free.*

## Status

See [docs/STATUS.md](docs/STATUS.md) for exactly what works, what is untested, and what is not built.
Engine research and licensing notes: [docs/RESEARCH.md](docs/RESEARCH.md).

## Licence

Apache License 2.0, Copyright 2026 Mundy Morning Studios: see [LICENSE](LICENSE) and [NOTICE](NOTICE). The app's artwork (logo, splash picture, mascot) is all rights reserved and not covered by that licence. The voice models the app downloads are not part of this repository and keep their own licences and terms (including Kyutai's rule that a voice may only be copied with the person's consent): see [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).

## Layout

| Module | Kind | Purpose |
|---|---|---|
| `core/document` | pure Kotlin/JVM | Parsers (TXT, Markdown, HTML, EPUB, DOCX), text cleanup, registry |
| `core/reader` | pure Kotlin/JVM | Sentence segmentation, chapters, reading positions, navigation |
| `core/tts` | pure Kotlin/JVM | `SpeechEngine` interface + `NarrationSession` (look-ahead queue, pause/seek) |
| `core/audio` | pure Kotlin/JVM | Bounded synthesize-ahead/play pipeline for PCM engines, RTF monitoring |
| `core/voicepacks` | pure Kotlin/JVM | Voice-pack catalog, resumable verified download, safe install |
| `app` | Android | Room storage, PDF parser (PDFBox-Android), Android TTS engine, media service, Compose UI |

Pure-Kotlin modules can be built and tested without the Android SDK:

    ./gradlew -PjvmOnly=true test

Full APK (needs Android SDK; CI does this):

    ./gradlew :app:assembleDebug

## Builds

GitHub Actions (`.github/workflows/build.yml`) runs the unit tests and builds a debug APK on every push,
uploaded as the `TouchReader-debug-apk` artifact (14-day retention).
