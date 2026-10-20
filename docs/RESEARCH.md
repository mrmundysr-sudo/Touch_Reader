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

## Sources
- Chatterbox repo: https://github.com/resemble-ai/chatterbox
- Chatterbox ONNX exports: https://huggingface.co/onnx-community/chatterbox-ONNX , https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX
- OpenVoice V2 model card: https://huggingface.co/myshell-ai/OpenVoiceV2
- On-device TTS benchmark: https://picovoice.ai/blog/on-device-tts/
