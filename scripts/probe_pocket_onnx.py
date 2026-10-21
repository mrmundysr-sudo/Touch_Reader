"""
Feasibility probe for the community ONNX export of Kyutai's Pocket TTS (KevinAHM/pocket-tts-onnx, int8) with voice
cloning, on a plain CPU (CI runner) with ONNX Runtime -- the same runtime an Android port would use.
Reference clips are synthetic Supertonic voices, so no real person's voice is cloned.
"""
import inspect, json, os, resource, sys, time
import numpy as np
import soundfile as sf

SRC = os.environ["POCKET_SRC"]
REFS = os.environ["REF_DIR"]
OUT = os.environ.get("PROBE_OUT", "probe-out")
os.makedirs(OUT, exist_ok=True)
sys.path.insert(0, SRC)
os.chdir(SRC)

def rss_mb():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0

import onnxruntime as ort
res = {"cpu_count": os.cpu_count(), "ort": ort.__version__}
from pocket_tts_onnx import PocketTTSOnnx
print("SIGNATURE __init__", inspect.signature(PocketTTSOnnx.__init__))
for name in ("generate", "stream", "save_audio"):
    if hasattr(PocketTTSOnnx, name): print("SIGNATURE", name, inspect.signature(getattr(PocketTTSOnnx, name)))

TEXTS = [
    "The quick brown fox jumps over the lazy dog, and then it rests beside the river.",
    "In the quiet hours before dawn, the old library smelled of paper and rain, and every shelf seemed to hold a story waiting to be told.",
]
refs = {"M1": os.path.join(REFS, "ref_M1.wav"), "F1": os.path.join(REFS, "ref_F1.wav")}
SR = 24000

def to_np(a):
    a = np.asarray(a, dtype=np.float32).reshape(-1)
    return a

runs, outputs = [], {}
def phase(label, cores, kwargs):
    if cores:
        os.sched_setaffinity(0, set(range(cores))); os.cpu_count = lambda c=cores: c
    t0 = time.time(); tts = PocketTTSOnnx(**kwargs); load_s = time.time() - t0
    print("LOADED", label, round(load_s, 2), "s rss", round(rss_mb()), "MB")
    sr = getattr(tts, "sample_rate", SR)
    tts.generate(text="Warm up.", voice=refs["M1"])
    for voice, path in refs.items():
        for i, text in enumerate(TEXTS):
            t = time.time(); a = to_np(tts.generate(text=text, voice=path)); dt = time.time() - t
            dur = len(a) / sr
            row = {"phase": label, "voice": voice, "text": i, "load_s": round(load_s, 2), "gen_s": round(dt, 2), "audio_s": round(dur, 2),
                   "rtf": round(dt / dur, 3), "peak_rss_mb": round(rss_mb())}
            runs.append(row); print("RUN", json.dumps(row))
            if label == "default" and i == 1:
                outputs[voice] = (a, sr)
                sf.write(os.path.join(OUT, f"clone_{voice}.wav"), a, sr)
    return tts

tts = phase("default", None, {})
res["runs_default"] = [r for r in runs if r["phase"] == "default"]
# same call twice: is the voice prepared once or on every call?
t = time.time(); tts.generate(text="Short.", voice=refs["F1"]); t1 = time.time() - t
t = time.time(); tts.generate(text="Short.", voice=refs["F1"]); t2 = time.time() - t
res["same_voice_twice_s"] = [round(t1, 2), round(t2, 2)]; print("TWICE", res["same_voice_twice_s"])
try:
    phase("two_cores", 2, {})
except Exception as e:
    print("two-core phase failed", repr(e)[:300])
try:
    phase("lsd_steps_1", None, {"lsd_steps": 1})
except Exception as e:
    print("lsd_steps phase failed", repr(e)[:300])
res["runs"] = runs

try:
    import librosa
    from resemblyzer import VoiceEncoder, preprocess_wav
    enc = VoiceEncoder("cpu")
    emb = lambda a, sr: enc.embed_utterance(preprocess_wav(librosa.resample(a, orig_sr=sr, target_sr=16000), source_sr=16000))
    ref_e = {}
    for v, path in refs.items():
        y, sr = librosa.load(path, sr=None); ref_e[v] = emb(y, sr)
    cos = lambda a, b: round(float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9)), 3)
    sims = {}
    for v, (a, sr) in outputs.items():
        e = emb(a, sr)
        for r in ref_e: sims[f"{v}_output_vs_ref_{r}"] = cos(e, ref_e[r])
    sims["ref_M1_vs_ref_F1"] = cos(ref_e["M1"], ref_e["F1"])
    res["speaker_similarity_resemblyzer"] = sims; print("SIMILARITY", json.dumps(sims))
except Exception as e:
    res["similarity_error"] = repr(e)[:300]; print("SIMILARITY FAILED", repr(e)[:300])

res["peak_rss_mb_final"] = round(rss_mb())
json.dump(res, open(os.path.join(OUT, "results.json"), "w"), indent=2)
print("PROBE_RESULT", json.dumps(res))
