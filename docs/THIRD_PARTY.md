# Third-party components and licences

Touch Reader (Mundy Morning Studios) includes or downloads the following. Licence texts are in each project; this list says what is used and under which terms.

## Libraries built into the app

| Component | Used for | Licence |
|---|---|---|
| AndroidX (Core, Activity, Lifecycle, Navigation, Media, Room), Jetpack Compose, Material 3 | App framework and UI | Apache-2.0 |
| Kotlin, kotlinx.coroutines | Language and concurrency | Apache-2.0 |
| PDFBox-Android (`com.tom-roush:pdfbox-android`) | PDF text extraction | Apache-2.0 |
| jsoup | HTML and EPUB parsing | MIT |
| ONNX Runtime (`onnxruntime-android`) | Runs the neural voice and the cloning add-on on the phone | MIT |

## Optional downloads (not part of the APK unless a test build bundles them)

| Component | Source | Licence | Notes |
|---|---|---|---|
| **Supertonic 3** neural voice (weights) | Hugging Face, `supertone-oss-archive/supertonic-3`, pinned revision `aafc6e32…` | **BigScience OpenRAIL-M** | Free, including commercial use, with use restrictions that the app shows and asks the user to accept before download. The licence file is stored with the model. Restrictions include: no impersonation without consent; machine-generated content must be disclosed as such when shared; no medical advice; no legal/law-enforcement/immigration decisions. |
| Supertonic sample code (ported to Kotlin in `core/supertonic`) | `supertone-oss-archive/supertonic` | MIT, Copyright (c) 2025 Supertone Inc. | |
| **OpenVoice V2** tone-colour converter | `myshell-ai/OpenVoice`, weights `myshell-ai/OpenVoiceV2` @ `f36e7ed` | MIT, Copyright 2024 MyShell.ai | The checkpoint is **converted to ONNX** by `scripts/build_converter_pack.py` (a modification); the licence and a modification notice ship inside the pack. |

## Used only while developing (not shipped)

Chatterbox (MIT) and the OpenVoice code were run in CI probes to measure feasibility. Synthetic voices only were used as test input.

## Notes

- The Supertonic licence requires its use restrictions to be passed on to users in an enforceable agreement; the in-app acceptance dialog is how this is done. Legal review before a public store release is advisable.
- Text-to-speech voices from the Android system are provided by the phone's own speech engine and are not part of this app.
