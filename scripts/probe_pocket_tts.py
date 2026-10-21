"""
Feasibility probe for Kyutai Pocket TTS voice cloning on a plain CPU (CI runner): load time, memory, speed (real-time
factor), cost of preparing a voice from a reference clip, and whether the cloned voice moves towards its reference.
Reference clips are synthetic Supertonic voices, so no real person's voice is cloned.
"""
import json, os, resource, time
import numpy as np
import torch
import scipy.io.wavfile as sw

REFS = os.environ["REF_DIR"]
OUT = os.environ.get("PROBE_OUT", "probe-out")
os.makedirs(OUT, exist_ok=True)

def rss_mb():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0

res = {"cpu_count": os.cpu_count(), "torch": torch.__version__}
from pocket_tts import TTSModel
import importlib.metadata as md
res["pocket_tts_version"] = md.version("pocket-tts")

t0 = time.time()
model = TTSModel.load_model()
res["load_s"] = round(time.time() - t0, 2)
res["rss_after_load_mb"] = round(rss_mb())
res["sample_rate"] = model.sample_rate
try:
    n = sum(p.numel() for p in model.parameters())
    res["params_m"] = round(n / 1e6, 1)
    res["weights_fp32_mb"] = round(n * 4 / 1e6)
except Exception as e:
    print("param count failed", e)
print("LOADED", json.dumps(res))

TEXTS = [
    "The quick brown fox jumps over the lazy dog, and then it rests beside the river.",
    "In the quiet hours before dawn, the old library smelled of paper and rain, and every shelf seemed to hold a story waiting to be told.",
]
refs = {"M1": os.path.join(REFS, "ref_M1.wav"), "F1": os.path.join(REFS, "ref_F1.wav")}

def to_np(a):
    a = a.detach().cpu().numpy() if hasattr(a, "detach") else np.asarray(a)
    return a.reshape(-1).astype(np.float32)

def gen(state, text):
    t = time.time(); a = to_np(model.generate_audio(state, text)); dt = time.time() - t
    return a, dt

# warm-up (excluded)
st = model.get_state_for_audio_prompt(refs["M1"])
gen(st, "Warm up.")

runs, outputs = [], {}
for threads in (4, 2):
    torch.set_num_threads(threads)
    for voice in ["alba", "M1", "F1"]:
        path = refs.get(voice, voice)
        t = time.time(); st = model.get_state_for_audio_prompt(path); prep_s = time.time() - t
        for i, text in enumerate(TEXTS):
            a, dt = gen(st, text); dur = len(a) / model.sample_rate
            row = {"threads": threads, "voice": voice, "text": i, "prep_s": round(prep_s, 2), "gen_s": round(dt, 2),
                   "audio_s": round(dur, 2), "rtf": round(dt / dur, 3), "peak_rss_mb": round(rss_mb())}
            runs.append(row); print("RUN", json.dumps(row))
            if threads == 4 and i == 1:
                outputs[voice] = a
                sw.write(os.path.join(OUT, f"clone_{voice}.wav"), model.sample_rate, a)
res["runs"] = runs

# does the clone move towards its reference? independent speaker encoder
try:
    import librosa
    from resemblyzer import VoiceEncoder, preprocess_wav
    enc = VoiceEncoder("cpu")
    def emb(a, sr):
        return enc.embed_utterance(preprocess_wav(librosa.resample(a, orig_sr=sr, target_sr=16000), source_sr=16000))
    ref_e = {}
    for v, path in refs.items():
        y, sr = librosa.load(path, sr=None); ref_e[v] = emb(y, sr)
    cos = lambda a, b: round(float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9)), 3)
    sims = {}
    for v in ("M1", "F1", "alba"):
        e = emb(outputs[v], model.sample_rate)
        for r in ref_e: sims[f"{v}_output_vs_ref_{r}"] = cos(e, ref_e[r])
    sims["ref_M1_vs_ref_F1"] = cos(ref_e["M1"], ref_e["F1"])
    res["speaker_similarity_resemblyzer"] = sims; print("SIMILARITY", json.dumps(sims))
except Exception as e:
    res["similarity_error"] = repr(e)[:300]; print("SIMILARITY FAILED", repr(e)[:300])

# a 5 s reference is enough?
try:
    import librosa
    y, sr = librosa.load(refs["F1"], sr=None)
    short = os.path.join(OUT, "ref_F1_5s.wav"); sw.write(short, sr, (y[: 5 * sr] * 32767).astype(np.int16))
    torch.set_num_threads(4)
    t = time.time(); st = model.get_state_for_audio_prompt(short); res["prep_5s_s"] = round(time.time() - t, 2)
    a, dt = gen(st, TEXTS[0]); sw.write(os.path.join(OUT, "clone_F1_from5s.wav"), model.sample_rate, a)
except Exception as e:
    res["short_ref_error"] = repr(e)[:300]

res["peak_rss_mb_final"] = round(rss_mb())
json.dump(res, open(os.path.join(OUT, "results.json"), "w"), indent=2)
print("PROBE_RESULT", json.dumps(res))
