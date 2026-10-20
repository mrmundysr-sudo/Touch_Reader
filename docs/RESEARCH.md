# Engine research and licensing (desk research, Oct 2026)

**Nothing below has been measured on Android hardware.** These are findings from public
documentation only. Every performance claim must be verified on a real low-end phone before a
neural engine ships. Licensing must be re-verified from the actual LICENSE files and model cards of the
exact artifacts bundled, and reviewed by someone qualified before release.

## Voice cloning candidates

| | Chatterbox (Resemble AI) | OpenVoice V2 (MyShell) |
|---|---|---|
| Reference-audio cloning | Yes (all variants take a reference clip) | Yes (tone-colour conversion on top of a base TTS) |
| Variants | Nano 110M (EN), Turbo 350M (EN), original 500M (EN), Multilingual V3 500M (23 langs) | V2: EN, ES, FR, ZH, JA, KO |
| License (stated) | Repo is MIT. Per-model-card terms not individually stated on the repo page | MIT per HF model card, "free for commercial use" |
| ONNX | Community ONNX exports exist on Hugging Face (`onnx-community/chatterbox-*`, `ResembleAI/chatterbox-turbo-ONNX`); not part of the main repo | No ONNX/Android path found |
| Android evidence | None found. One report of fp32 ONNX on iPhone/Mac with ~3.2 GB peak RAM. A third-party benchmark measured Turbo at ~48 s time-to-first-audio on a CPU (not a phone) | None found |
| Watermark | Every output carries Resemble's Perth neural watermark | not checked |

Assessment: Chatterbox-Nano is the only candidate that is plausibly small enough for phones, and it is
claimed to run ~3x real time on **8 desktop-class CPU cores** — a low-end phone has fewer, slower cores.
Cloning plus a 2+ GB memory footprint for larger variants is very likely infeasible on inexpensive devices.
**Offline cloning on target hardware is therefore NOT validated and Voice Studio is not built.**
Recommended next step: export Nano to ONNX, run it under ONNX Runtime Mobile on a real budget phone
(2–3 GB RAM), and record RAM, real-time factor and time-to-first-audio. Only proceed if RTF < 1 with margin.
No paid/cloud fallback will be introduced without explicit approval.

## Built-in neural voices

| | Kokoro-82M | Piper |
|---|---|---|
| Model license | Apache-2.0 | Varies **per voice** (many voices carry their own dataset licenses) |
| Code license | – | Current Piper repo is GPL-3.0-or-later (original archived repo was MIT); phonemization uses espeak-ng (GPL) |
| Android runtime | sherpa-onnx (Apache-2.0 – verify) runs Kokoro on Android | sherpa-onnx can run Piper/VITS models |
| Risk | Verify bundled phonemizer/lexicon licenses | **Copyleft risk** for a closed-source app: espeak-ng/GPL obligations and per-voice licenses must be cleared before bundling or hosting any voice |

Plan: Phase 2 evaluates Kokoro via sherpa-onnx first (permissive weights), measures it on a budget phone,
and treats Piper as optional pending a license review. Android `TextToSpeech` remains the default/fallback.

## Phase 2 findings: can Kokoro / Piper ship in this app?

Source: a third-party engineering issue ("Spike: benchmark sherpa-onnx TTS models on real devices + license review",
muxable/rtchat#1500). **Secondary source; not verified against sherpa-onnx's own LICENSE files, and this sandbox cannot
reach GitHub release pages or Hugging Face to check.** Re-verify before relying on any of it.

1. **Copyleft blocker (needs a product decision).** It states the prebuilt sherpa-onnx Android library statically
   links **espeak-ng, which is GPL-3.0**, and that Kokoro (multi-lang, v0.19) and Piper packs need espeak-ng data in sherpa-onnx.
   Shipping that library would likely require Touch Reader to be distributed under GPL-3.0-compatible terms (source available).
   Alternatives: build sherpa-onnx without espeak (not possible for Piper/Kokoro-v0.19 per that source), or use an engine that
   does not need it (Supertonic 3: OpenRAIL-M weights with use restrictions that need their own review; ~129 MB).
2. **Speed.** Measured only on an x86_64 *emulator* (close to desktop speed), real-time factor (synthesis time / audio time; must be < 1):
   Kokoro multi-lang v1.1 fp32 0.59, **int8 1.52**; Kokoro en v0.19 int8 1.58; Piper libritts_r int8 0.21; Piper ru irina int8 0.23.
   Phones are slower than that, so **Kokoro is unlikely to keep up on inexpensive phones**; Piper int8 plausibly can.
3. **Sizes (download / on disk):** Kokoro multi-lang v1.1 int8 147 / 215 MB; Kokoro en v0.19 int8 103 / 158 MB; Piper medium int8 ~21–23 / 37–40 MB.
4. **Piper voice licenses vary per voice** (libritts_r: CC BY 4.0 dataset; some voices unknown or non-commercial). Each voice must be cleared individually.

Consequence for this build: no neural engine is integrated. What *is* built and tested is the engine-independent plumbing
(bounded synthesis/playback pipeline with real-time-factor monitoring, resumable verified voice-pack downloads) plus the ability to
use any other speech engine installed on the phone through Android's TextToSpeech.

## Phase 2b: Supertonic 3 (chosen route for neural voices)

Chosen because it avoids espeak-ng/GPL entirely (the model reads Unicode characters directly) and is small enough to be plausible on phones.
Everything below was checked from primary sources or measured in CI unless marked otherwise.

- **Code:** MIT (sample code, `supertone-oss-archive/supertonic`). Touch Reader's Kotlin port (`core/supertonic`) follows its Java sample.
- **Model license:** BigScience **OpenRAIL-M** (full text read from the pinned Hugging Face revision; copy kept at install time in the pack's `LICENSE`).
  Free and commercial use allowed. Conditions that matter to us: (4a/5) the use restrictions in Attachment A must be passed on to users in an enforceable agreement,
  (4b) recipients get a copy of the license, (4d) keep notices. Attachment A forbids, among others: impersonation without consent, publishing machine-generated
  content without disclosing that it is machine generated, medical advice, and legal/justice/law-enforcement decisions. The app shows a summary and requires
  acceptance before downloading; **audiobook export (Phase 4) must label output as machine-generated**. *This is the developer's reading, not legal advice; have counsel confirm before release.*
- **Distribution:** files are downloaded by the user straight from Hugging Face at a pinned revision (`aafc6e32…`), each checked against a SHA-256 (the four ONNX hashes also match Hugging Face's own). Touch Reader hosts nothing.
- **Project status risk:** upstream is **archived** (read-only since Sep 2026, no fixes or support) and the weights moved to the `supertone-oss-archive` namespace. If that host disappears, the pack stops downloading; a mirror would then need to honour OpenRAIL-M 4(a)-(d).
- **Size:** **401 MB** download and on disk (not ~129 MB as one secondary source claimed): vector_estimator 257 MB, vocoder 101 MB, text_encoder 36 MB, duration_predictor 4 MB, plus voices (≈0.3 MB each).
- **Quality of port:** the Kotlin port produced plausible, non-silent 44.1 kHz speech from the real weights in CI. **Nobody has listened to it yet**; naturalness is unjudged.
- **Speed (x86 CI runner, 2 threads, 5.6 s sentence pair):** real-time factor 0.52–0.55 at 8 steps, **0.29 at 4 steps**; model load 1.0–1.3 s; peak process memory ≈ 580–620 MB (includes JVM overhead). Upstream claims RTF 0.3 on an Android e-reader (unverified). **Not measured on any phone.**
- **No voice cloning:** voices are 10 presets (M1–M5, F1–F5); the hosted Voice Builder that made new styles has shut down. Supertonic does not help with the cloning requirement.

## Sources
- Chatterbox repo: https://github.com/resemble-ai/chatterbox
- Chatterbox ONNX exports: https://huggingface.co/onnx-community/chatterbox-ONNX , https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX
- OpenVoice V2 model card: https://huggingface.co/myshell-ai/OpenVoiceV2
- On-device TTS benchmark: https://picovoice.ai/blog/on-device-tts/
- sherpa-onnx license/RTF spike (secondary): https://github.com/muxable/rtchat/issues/1500
