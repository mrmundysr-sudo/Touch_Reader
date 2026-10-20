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

## Sources
- Chatterbox repo: https://github.com/resemble-ai/chatterbox
- Chatterbox ONNX exports: https://huggingface.co/onnx-community/chatterbox-ONNX , https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX
- OpenVoice V2 model card: https://huggingface.co/myshell-ai/OpenVoiceV2
- On-device TTS benchmark: https://picovoice.ai/blog/on-device-tts/
- sherpa-onnx license/RTF spike (secondary): https://github.com/muxable/rtchat/issues/1500
