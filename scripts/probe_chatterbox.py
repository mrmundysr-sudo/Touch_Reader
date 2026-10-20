"""
Feasibility probe for Chatterbox-Nano voice cloning on a plain CPU (CI runner).
Measures load time, memory, speed (real-time factor), cached voice-profile size, and whether the
cloned voice moves towards its reference (speaker-embedding similarity). Reference clips are
synthetic Supertonic voices, so no real person's voice is cloned.
"""
import json, os, resource, sys, time
import numpy as np
import torch
import torchaudio as ta

REFS = os.environ["REF_DIR"]
OUT = os.environ.get("PROBE_OUT", "probe-out")
os.makedirs(OUT, exist_ok=True)

def rss_mb():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0

res = {"cpu_count": os.cpu_count(), "torch": torch.__version__}
from chatterbox.tts_turbo import ChatterboxTurboTTS, Conditionals

t0 = time.time()
model = ChatterboxTurboTTS.from_pretrained(device="cpu", nano=True)
res["load_s"] = round(time.time() - t0, 2)
res["rss_after_load_mb"] = round(rss_mb())
res["sample_rate"] = model.sr

params = {k: sum(p.numel() for p in v.parameters()) for k, v in vars(model).items() if isinstance(v, torch.nn.Module)}
res["params"] = params
res["params_total_m"] = round(sum(params.values()) / 1e6, 1)
print("PARAMS", json.dumps(params))

try:
    from huggingface_hub import snapshot_download
    d = snapshot_download(repo_id="ResembleAI/chatterbox-nano", allow_patterns=["*.safetensors", "*.json", "*.txt", "*.pt", "*.model"])
    files = {f: os.path.getsize(os.path.join(d, f)) for f in sorted(os.listdir(d)) if os.path.isfile(os.path.join(d, f))}
    res["weight_files_bytes"] = files
    print("WEIGHT_FILES", json.dumps(files))
except Exception as e:
    print("could not list weights:", e)

TEXTS = [
    "The quick brown fox jumps over the lazy dog, and then it rests beside the river.",
    "In the quiet hours before dawn, the old library smelled of paper and rain, and every shelf seemed to hold a story waiting to be told.",
]
refs = {"M1": os.path.join(REFS, "ref_M1.wav"), "F1": os.path.join(REFS, "ref_F1.wav")}

def audio_np(wav):
    return wav.squeeze(0).detach().cpu().numpy().astype(np.float32)

# warm-up (excluded from timings)
model.prepare_conditionals(refs["M1"])
model.generate("Warm up.")

runs, outputs = [], {}
for threads in (4, 2):
    torch.set_num_threads(threads)
    for voice, path in refs.items():
        t = time.time(); model.prepare_conditionals(path); prep_s = time.time() - t
        for i, text in enumerate(TEXTS):
            t = time.time(); wav = model.generate(text); gen_s = time.time() - t
            a = audio_np(wav); dur = len(a) / model.sr
            row = {"threads": threads, "voice": voice, "text": i, "prep_s": round(prep_s, 2), "gen_s": round(gen_s, 2),
                   "audio_s": round(dur, 2), "rtf": round(gen_s / dur, 3), "peak_rss_mb": round(rss_mb())}
            runs.append(row); print("RUN", json.dumps(row))
            if threads == 4 and i == 1:
                outputs[voice] = a
                ta.save(os.path.join(OUT, f"clone_{voice}.wav"), torch.from_numpy(a).unsqueeze(0), model.sr)
res["runs"] = runs

# cached voice profile: save, reload, generate without the reference clip
try:
    torch.set_num_threads(4)
    model.prepare_conditionals(refs["F1"])
    p = os.path.join(OUT, "profile_F1.pt")
    model.conds.save(p)
    res["profile_bytes"] = os.path.getsize(p)
    model.conds = Conditionals.load(p, map_location="cpu").to("cpu")
    t = time.time(); wav = model.generate(TEXTS[0]); gen_s = time.time() - t
    res["profile_reuse_ok"] = True
    res["profile_reuse_rtf"] = round(gen_s / (wav.shape[-1] / model.sr), 3)
    print("PROFILE bytes", res["profile_bytes"], "reuse rtf", res["profile_reuse_rtf"])
except Exception as e:
    res["profile_reuse_ok"] = False; res["profile_error"] = repr(e); print("PROFILE FAILED", e)

# does the clone move towards its reference? cosine similarity of voice-encoder embeddings
try:
    import librosa
    def emb(a, sr):
        a16 = librosa.resample(a, orig_sr=sr, target_sr=16000)
        e = model.ve.embeds_from_wavs([a16], sample_rate=16000)
        e = np.asarray(e).reshape(-1)
        return e / (np.linalg.norm(e) + 1e-9)
    ref_e = {}
    for v, path in refs.items():
        y, sr = librosa.load(path, sr=None); ref_e[v] = emb(y, sr)
    out_e = {v: emb(a, model.sr) for v, a in outputs.items()}
    cos = lambda a, b: round(float(np.dot(a, b)), 3)
    sims = {f"clone_{v}_vs_ref_{r}": cos(out_e[v], ref_e[r]) for v in out_e for r in ref_e}
    sims["ref_M1_vs_ref_F1"] = cos(ref_e["M1"], ref_e["F1"])
    res["speaker_similarity"] = sims; print("SIMILARITY", json.dumps(sims))
except Exception as e:
    res["similarity_error"] = repr(e); print("SIMILARITY FAILED", e)

# cost of the mandatory Perth watermark
try:
    a = outputs["M1"]; t = time.time(); model.watermarker.apply_watermark(a, sample_rate=model.sr)
    res["watermark_s_for_clip"] = round(time.time() - t, 2); res["clip_s"] = round(len(a) / model.sr, 2)
except Exception as e:
    res["watermark_error"] = repr(e)

res["peak_rss_mb_final"] = round(rss_mb())
json.dump(res, open(os.path.join(OUT, "results.json"), "w"), indent=2)
print("PROBE_RESULT", json.dumps(res))
