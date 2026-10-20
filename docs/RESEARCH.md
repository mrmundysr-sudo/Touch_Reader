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

## Phase 3 probe: Chatterbox-Nano voice cloning on a plain CPU (CI run, Oct 2026)

Method: pinned commit `5de7a54`, official PyTorch weights, CPU-only torch 2.6, GitHub runner with **2 vCPUs**; reference voices were synthetic
(Supertonic M1/F1, 14.5 s each) so no real person was cloned. Listening samples: pre-release `probe-chatterbox-1` (`clone_M1.wav`, `clone_F1.wav` and the two references).

| Question | Result |
|---|---|
| Does it clone from a reference clip? | **Yes.** Voice-encoder cosine similarity: clone of M1 vs M1 reference 0.92, vs F1 reference 0.62; clone of F1 vs F1 0.94, vs M1 0.68 (the two references are 0.64 apart). Measured with Chatterbox's own encoder (biased); **nobody has listened yet** |
| Reference needed | >5 s; only the **first ~10–15 s** are used, so a 30–60 s recording adds nothing |
| Saved voice profile | **170 KB** (`Conditionals.save`), reloads and generates without the clip. Computing it takes ~2.3–2.8 s |
| Licence | Repo MIT; model card `license: mit`, not gated. Every output carries the Perth watermark (cost: 0.08 s per 8 s clip) |
| Real weights size | **3.0 GB repo**, ~1.9 GB actually loaded (T3 870 MB + S3Gen meanflow 1.06 GB + VE 6 MB). Loaded model is **446M parameters** (T3 179M, S3Gen 266M), not "110M" |
| Memory | **Peak 4.5 GB RAM**; load time 36 s |
| Speed | **Real-time factor 2.2–2.7** (slower than real time) on 2 vCPUs, same at 2 and 4 threads. Vendor claim: 3x faster than real time on 8 cores |

**Verdict: not realistic on inexpensive phones with the official weights** (1.9 GB model, 4.5 GB peak RAM, slower than real time even on a desktop-class CPU).
Cloning quality looks promising on the model's own metric, but a phone-class build would need a heavily reduced model.

What exists on the Hub (not evaluated): no official ONNX for Nano. Community ONNX exports (`KitsuMate/chatterbox-nano-onnx`, `…nano-v2-onnx`, `Masterx/chatterbox-nano-ONNX`,
`owensong/chatterbox-nano-ONNX`), CoreML and GGUF ports exist; official **Turbo** ONNX has q4/q4f16 variants (about 560-720 MB in total). Provenance, exact licence, quality and
speed of third-party conversions are unverified, and a phone port would also need the speech tokenizer, voice encoder and watermarker.

Better-fitting idea to probe next: **voice conversion on top of the fast base voice** (e.g. OpenVoice V2's tone-colour converter, MIT) — Supertonic already runs at RTF ≈ 0.3 on a CI CPU,
so the added cost would be only the converter. Unevaluated: converter size/speed, ONNX export, quality, Android feasibility.

## Phase 3 probe 2: OpenVoice V2 tone-colour converter after Supertonic (CI run, Oct 2026)

Idea: keep Supertonic as the fast base voice and convert its output to the user's timbre with OpenVoice V2's converter (a voice *converter*, not a full TTS).
Method: pinned OpenVoice commit `74a1d14`, HF weights `myshell-ai/OpenVoiceV2` @ `f36e7ed`, 2-vCPU x86 CI runner, synthetic Supertonic voices only
(source M1 -> target F1 and back). Samples: pre-release `probe-openvoice-1`.

| Question | Result |
|---|---|
| Licence | **MIT** (code and HF model card), not gated, free for commercial use |
| Size | converter checkpoint **131 MB** (32.8M parameters); ONNX export **128 MB** (+3.3 MB speaker-embedding model) |
| Voice profile | one 256-float speaker embedding = **1 KB** (no recording has to be kept for synthesis) |
| Timbre moves to the target? | **Yes.** OpenVoice's encoder: similarity to target 0.81 / 0.86 vs to source 0.39 / 0.33. Independent encoder (Resemblyzer): to target 0.89 / 0.79 vs to source 0.66 / 0.64. **Nobody has listened yet.** Weaknesses to expect: output is 22.05 kHz, prosody/accent stay those of the base voice |
| PyTorch CPU speed | real-time factor 1.10–1.15 (slower than real time), 2 vCPUs |
| **ONNX Runtime CPU speed** | **real-time factor 0.66** (2 threads, 14.5 s clip) — the runtime an Android build would use |
| ONNX export | works (opset 17, dynamic length); speaker-embedding model matches PyTorch to 3e-6; converted timbre statistics match (0.815 to target) |
| Memory | peak 3.6 GB for the whole PyTorch test (58 s clip, PyTorch overhead). ONNX Runtime memory on its own **not measured** — long audio must be converted in short chunks |
| Combined estimate | Supertonic (RTF ≈ 0.29 at 4 steps) + converter (≈ 0.66) ≈ **0.95** on this CPU: borderline real-time here, slower on weak phone CPUs; fine for background export |
| Total download | Supertonic 401 MB + converter ~128 MB |

**Verdict: the only cloning route so far that is plausible on phones**, but unproven: needs (1) a listening check, (2) tests with real, noisy phone recordings
(not tried — all references were synthetic), (3) ONNX Runtime memory and speed on a real phone, (4) a hosting decision for the converted ONNX (the upstream checkpoint is a pickle `.pth`, which the app must never load).
Weights are MIT, so a converted ONNX may be redistributed with the MIT notice.
The converter changes timbre only: it will not copy accent or speaking style, and it adds no consent check, so the in-app acknowledgement and OpenRAIL-M rule against impersonation without consent still apply.

## Phase 3b result: OpenVoice converter integrated and verified in CI

Following the probe, the converter was exported to ONNX (`scripts/build_converter_pack.py`), the Kotlin front-end was written (`core/voiceconvert`), and CI checks it against PyTorch's real outputs before publishing `converter-v1`:
spectrogram max difference 4e-6, fingerprint 6e-6 (cosine 1.0); full chain similarity to target 0.81 (source 0.31); real-time factor 0.37–0.86 across two CI runs (2 vCPUs, noisy).
The export is byte-reproducible (same ONNX hashes on two runs). Anonymous download of the release returns 404 because the repository is private (see STATUS.md).

## Sources
- Chatterbox repo: https://github.com/resemble-ai/chatterbox
- Chatterbox ONNX exports: https://huggingface.co/onnx-community/chatterbox-ONNX , https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX
- OpenVoice V2 model card: https://huggingface.co/myshell-ai/OpenVoiceV2
- OpenVoice V2 probe run and samples: https://github.com/mrmundysr-sudo/Touch_Reader/releases/tag/probe-openvoice-1
- Chatterbox Nano probe run and samples: https://github.com/mrmundysr-sudo/Touch_Reader/releases/tag/probe-chatterbox-1
- On-device TTS benchmark: https://picovoice.ai/blog/on-device-tts/
- sherpa-onnx license/RTF spike (secondary): https://github.com/muxable/rtchat/issues/1500
