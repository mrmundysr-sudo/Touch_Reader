# Touch Reader — Status (through Phase 2)

Legend: **Verified** = exercised by an automated test that ran. **Built** = compiles in CI but never run
on a device or emulator. **Not built** = does not exist.

## Phase 1 — Functional Reader

| Area | State | Evidence |
|---|---|---|
| TXT, Markdown, HTML, EPUB, DOCX parsing | **Verified** (synthetic fixtures) | 13 JVM tests in `core/document` |
| EPUB spine order, TOC titles, DRM refusal | **Verified** (synthetic fixtures) | same |
| Header/footer/page-number removal, paragraph rebuild | **Verified** (synthetic data) | same |
| Sentence segmentation, chapter/sentence navigation, progress | **Verified** | 6 tests in `core/reader` |
| Narration queue: ordering, bounded look-ahead, pause/resume, seek, error, restart | **Verified** (fake engine) | 7 tests in `core/tts` |
| PDF text extraction (PDFBox-Android) | **Built**, never run on a real PDF | – |
| Scanned/image-only PDF | Reported as unsupported with a clear message. **No OCR exists.** | – |
| Room library, bookmarks, position persistence | **Built**, never run | no instrumented tests yet |
| Android TextToSpeech engine, voice list, preview | **Built**, never run | – |
| Foreground media service, notification, headset buttons, audio focus | **Built**, never run | screen-off / Bluetooth need a device |
| Reader UI (highlight, auto-scroll, tap-to-start, chapters, bookmarks, speed, sleep timer) | **Built**, never run | – |
| Library UI (search, rename, remove with confirmation, resume) | **Built**, never run | – |
| Light/dark themes, text size | **Built**, never run | – |

Real-world accuracy of the parsers has not been checked against real books; the fixtures are tiny hand-made files.

## Phase 2 — Advanced Narration

**Decision needed from the owner:** neural voices (Kokoro/Piper via sherpa-onnx) are *not* integrated. The prebuilt sherpa-onnx
Android library reportedly bundles GPL-3.0 espeak-ng (see `RESEARCH.md`), which could force GPL-3.0 terms on the app, and Kokoro
measured slower than real time even on an emulator. Choose: (a) accept GPL-3.0 / open-source the app, (b) pursue a non-espeak engine,
or (c) stay with system voices. Nothing neural will be added until that is decided and measured on a real phone.

| Item | State | Evidence |
|---|---|---|
| Bounded synthesize-ahead / play pipeline (`core/audio`), gapless queue, cancel, error, speed, RTF + time-to-first-audio stats, slow-engine signal | **Verified** with fake synthesizer/sink | 7 JVM tests |
| Not yet used by any engine; no Android `AudioTrack` sink written (nothing to feed it) | **Not built** | – |
| Voice-pack catalog (license/checksum/size mandatory), resumable HTTPS download, SHA-256 verify, free-space check, zip-slip and zip-bomb guards, atomic install, delete (`core/voicepacks`) | **Verified** against a local test server | 12 JVM tests |
| Voice-pack UI + manager in the app | **Built**, never run. The bundled catalog is **empty**: no pack is published or verified | – |
| Choose among installed Android speech engines; automatic fallback to system default if the chosen engine fails to start | **Built**, never run | – |
| Seek slider (`ReaderBook.positionAt`) | core **Verified** (1 test); UI **Built** | – |
| Sleep timer "end of chapter" (`NarrationSession.pauseAtChapterEnd`) | core **Verified** (2 tests); UI **Built** | – |
| Sentence highlight, speed, bookmarks, sleep timer (minutes) | from Phase 1; **Built**, never run | – |

Tar/bz2 packs (the format sherpa-onnx publishes) are not supported; packs must be .zip.

## Not built

- Kokoro, Piper or any neural engine (see Phase 2 decision above)
- Voice Studio, recording/import, any cloning (Phase 3). See `RESEARCH.md`: offline cloning is **not validated** on phone hardware.
- Audiobook export, MP3/M4B (Phase 4)
- OCR; DOC/RTF/ODT/MOBI/FB2
- Performance, battery and low-memory measurements (none taken)
- Accessibility audit, license audit, Play policy review (Phase 5)

The Voices tab states plainly that downloaded and custom voices are not available yet.

## Known limitations / risks

- Module layout: `core/{document,reader,tts}` are separate JVM modules; storage, audio, library, reader, voices and settings live as packages inside `app`, not separate modules yet.
- The debug APK is debug-signed; there is no release signing or minification.
- Only the system-default speech engine is used; choosing between installed engines is not exposed.
- Sentence highlight is rebuilt per block; very large single-paragraph documents may scroll/render slowly (untested).
- Whole documents are loaded into memory when opened (text only, but untested on very large books).
- CI artifact upload hit the account's Actions storage quota; APKs are delivered via GitHub pre-release assets instead.

## Next recommended step

Install the debug APK on a real, ideally low-end, phone and run the manual checklist: import one file of each
format, listen with the screen off, Bluetooth headset buttons, kill/restart the app and confirm position restore.
Fix what breaks. Then decide the licensing question above, and measure any candidate neural engine on that same phone before integrating it.
