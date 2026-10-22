# Third-party components and licences

Touch Reader's own code is licensed under the **Apache License 2.0**, Copyright 2026 Mundy Morning Studios (see `LICENSE` and `NOTICE`). That licence covers this repository only: the voice models below are downloaded separately and keep their own licences and terms.

Touch Reader (Mundy Morning Studios) includes or downloads the following. Licence texts are in each project; this list says what is used and under which terms.

## Libraries built into the app

| Component | Used for | Licence |
|---|---|---|
| AndroidX (Core, Activity, Lifecycle, Navigation, Media, Room), Jetpack Compose, Material 3 | App framework and UI | Apache-2.0 |
| Kotlin, kotlinx.coroutines | Language and concurrency | Apache-2.0 |
| PDFBox-Android (`com.tom-roush:pdfbox-android`) | PDF text extraction | Apache-2.0 |
| jsoup | HTML and EPUB parsing | MIT |
| Playfair Display (headings) and Lora (book text), embedded unmodified | Fonts | SIL Open Font Licence 1.1 (texts in `docs/licenses/`) |
| ONNX Runtime (`onnxruntime-android`) | Runs the neural voices on the phone | MIT |

## Optional downloads (not part of the APK unless a test build bundles them)

| Component | Source | Licence | Notes |
|---|---|---|---|
| **Supertonic 3** neural voice (weights) | Hugging Face, `supertone-oss-archive/supertonic-3`, pinned revision `aafc6e32…` | **BigScience OpenRAIL-M** | Free, including commercial use, with use restrictions that the app shows and asks the user to accept before download. The licence file is stored with the model. Restrictions include: no impersonation without consent; machine-generated content must be disclosed as such when shared; no medical advice; no legal/law-enforcement/immigration decisions. |
| Supertonic sample code (ported to Kotlin in `core/supertonic`) | `supertone-oss-archive/supertonic` | MIT, Copyright (c) 2025 Supertone Inc. | |
| **Pocket TTS** voice cloning (weights, via an ONNX export) | Kyutai's model, exported to ONNX by KevinAHM: Hugging Face `KevinAHM/pocket-tts-onnx`, pinned revision `58a6d00c…` (English bundle `english_2026-04`) | **CC-BY-4.0** (weights); MIT (Kyutai's code) | Credit: Kyutai (model), KevinAHM (ONNX export). Kyutai's terms prohibit copying a voice without the person's explicit, lawful consent and impersonation; the app shows this and asks the user to accept before download. The licence file is saved with the files. The Kotlin port in `core/pockettts` was written for this app and checked against Kyutai's reference behaviour; the model is run unmodified (the community export uses int8 quantised parts). |

## Used only while developing (not shipped)

Chatterbox (MIT), OpenVoice (MIT) and the Pocket TTS reference code (MIT) were run in CI probes to measure feasibility. Synthetic voices only were used as test input. The OpenVoice tone-converter add-on was shipped earlier and has since been removed from the app.

## Notes

- The Supertonic licence requires its use restrictions to be passed on to users in an enforceable agreement; the in-app acceptance dialog is how this is done. Legal review before a public store release is advisable.
- Text-to-speech voices from the Android system are provided by the phone's own speech engine and are not part of this app.
