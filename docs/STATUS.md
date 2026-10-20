# Touch Reader — Status (through Phase 3)

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

**Kokoro/Piper (sherpa-onnx) are not used**: the prebuilt sherpa-onnx Android library reportedly bundles GPL-3.0 espeak-ng (secondary source) and Kokoro
ran slower than real time even on an emulator. Per the owner's decision (option 2) the neural route is **Supertonic 3** (no espeak). See `RESEARCH.md` for licence, size and speed.

| Item | State | Evidence |
|---|---|---|
| Supertonic text frontend, config/voice-style parsing, latent math (`core/supertonic`) | **Verified** | 14 JVM tests |
| Supertonic inference port vs the **real weights** | **Verified in CI only**: plausible, non-silent speech, RTF 0.29–0.55, hashes match | probe workflow run; **not listened to** |
| Bounded synthesize-ahead / play pipeline (`core/audio`) | **Verified** with fakes | 7 JVM tests |
| Multi-file voice packs: pinned HTTPS download, resume, per-file SHA-256, free-space check, atomic install, licence stored with model (`core/voicepacks`) | **Verified** against a local server; shipped catalog validated | 18 JVM tests |
| Voices tab: pack download with licence/terms acceptance, memory warning, engine picker incl. neural voice, auto fallback to system voice if the neural engine fails or is too slow | **Built**, never run | – |
| Android `AudioTrack` sink + neural engine wrapper | **Built**, never run on a phone | – |
| Neural voice quality setting (4 or 8 steps) | **Built**, never run | – |
| Choose among installed Android speech engines + fallback | **Built**, never run | – |
| Seek slider, "end of chapter" sleep timer | core **Verified**; UI **Built** | 3 tests |
| Sentence highlight, speed, bookmarks, minute-based sleep timer | from Phase 1; **Built**, never run | – |

Open risks for the neural voice: 401 MB download and a large memory footprint (may not start on 2–3 GB phones, then the app falls back to the system voice);
speed on real phone CPUs is unmeasured; upstream is archived; first sentence waits for the model to load; no per-document language detection (uses the phone's language, else language-agnostic mode).

## Phase 3 — Voice Studio (recording, import, cleanup, profiles; **no cloning engine yet**)

| Item | State | Evidence |
|---|---|---|
| WAV read/write, windowed-sinc resampler, high-pass, silence trim, loudness normalise (`core/voicestudio`) | **Verified** on synthetic signals | 25 JVM tests; the tests found and fixed two real bugs (resampler centre-tap gain/alias leak; "noisy" misreported as "silent") |
| Quality report: length, speech time, level, noise/SNR, clipping, sample rate; clear blocking/warning messages | **Verified** on synthetic signals | same; thresholds are untested on real recordings |
| Private voice profiles: create (needs the ownership acknowledgement), list, rename, delete (overwrites the recording first), delete recording only, per-engine derived data keyed by engine+version | **Verified** | same |
| Microphone recording (AudioRecord, 90 s max, level meter) | **Built**, never run | needs a phone |
| Import MP3 / WAV / M4A (phone decoders via MediaCodec; WAV parsed directly) | **Built**, never run | needs real files |
| Voice Studio screen, "My voices" list (play sample, rename, delete with confirmation), ownership checkbox "I confirm that I own this recording or have permission to use the voice." | **Built**, never run | – |

Not done in Phase 3: **any voice cloning / narrating with a custom voice**, "set default narrator" and "change voice while reading" for custom voices (nothing to select until an engine exists), an audio denoiser, detection of multiple speakers or music (the report says it cannot tell).
Candidate engine: OpenVoice V2 converter after Supertonic (see `RESEARCH.md`); needs a listening check, real-recording tests and on-phone measurements first.
Privacy: recordings stay in private app storage (backup is disabled), are never uploaded; `RECORD_AUDIO` is requested only when the user taps Record. Play Store data-safety/privacy-policy wording still has to be written (Phase 5).

## Phase 3b — Narrating with your own voice (OpenVoice converter after Supertonic)

| Item | State | Evidence |
|---|---|---|
| Kotlin audio front-end (FFT, spectrogram) vs PyTorch on the real model | **Verified in CI**: spectrogram max diff 0.000004, voice fingerprint max diff 0.000006 (cosine 1.0) | converter-pack workflow, gated: it only publishes if these pass |
| Full chain Supertonic -> converter, forcing male base -> female target (synthetic voices) | **Verified in CI**: similarity to target 0.81–0.83 vs 0.23–0.31 to source; speed 0.37× and 0.86× real time in two runs on a 2-core CI machine | same |
| Converter pack (ONNX, 128 MB + 3 MB; MIT + notice), built reproducibly (identical hashes across two runs) and pinned in the app catalog | **Verified** (hash-checked); published as release `converter-v1` | 23 catalog/pack tests |
| Engine: custom voices listed as narrators, fingerprint cached per saved voice, nearest built-in base voice chosen automatically, converter loaded lazily, falls back to a built-in voice if a saved voice is gone | **Built**, never run on a phone | 22 voiceconvert tests cover routing with fakes |
| Voices tab: "Use to narrate", add-on pack with "install the neural voice first" and acceptance text | **Built**, never run | – |

**Hosting blocker (owner decision):** the repository is private, so the app cannot download `converter-v1` from its releases (anonymous request returns 404, checked in CI).
Options: make the repo (or a small separate repo) public, host the files elsewhere public, or keep bundling the files in test builds (`bundle_converter` workflow input; adds ~130 MB to the APK, hashes verified at build time and again at install).
Test builds published with the converter bundled can be used now; a public/Play release cannot rely on this.

Unproven until someone listens/measures on a phone: how it sounds with a real voice and a real phone microphone, speed and memory on phone CPUs with both models loaded, behaviour with noisy recordings. The first sentence with a custom voice also waits for fingerprint extraction (a few seconds, then cached).
Limits that will not go away: it changes timbre only (accent, rhythm and wording style are the base voice's); every output is machine-generated speech and the neural voice's licence requires it to be labelled as such when shared.

## Not built

- Kokoro and Piper (ruled out for now; see RESEARCH.md)
- (Cloning: see Phase 3b above. Chatterbox-Nano was rejected for phones.) Chatterbox-Nano **was probed and is not realistic on inexpensive phones** (1.9 GB model, 4.5 GB RAM, slower than real time on CPU) — see `RESEARCH.md`. **OpenVoice V2 tone-colour converter on top of Supertonic** was then probed and looks plausible (128 MB ONNX, real-time factor 0.66 on 2 CPU cores, 1 KB voice profile) but is unlistened, untested with real recordings and unmeasured on a phone. No cloning engine is integrated.
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
