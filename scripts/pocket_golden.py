"""
Records what the reference Python implementation (pocket_tts_onnx.py, pinned) produces for fixed inputs, so the Kotlin port can be
compared with it: tokenizer ids, text preparation and chunking, voice embeddings, the transformer's conditioning over several steps,
one flow-network step and the audio decoder. A synthetic (computer-made) reference signal is used: no real person's voice.
"""
import json, os, sys, wave
import numpy as np
from huggingface_hub import snapshot_download

SRC = os.environ["POCKET_SRC"]
OUT = os.environ["POCKET_GOLDEN"]
os.makedirs(OUT, exist_ok=True)
B = "onnx/english_2026-04/"
snapshot_download(repo_id="KevinAHM/pocket-tts-onnx", revision=os.environ["POCKET_ONNX_REV"],
                  allow_patterns=[B + "*int8*", B + "mimi_encoder.onnx", B + "text_conditioner.onnx", B + "bundle.json", B + "bos_before_voice.npy",
                                  B + "tokenizer.model", "pocket_tts_onnx.py"], local_dir=SRC)
sys.path.insert(0, SRC)
from pocket_tts_onnx import PocketTTSOnnx

tts = PocketTTSOnnx(models_dir=os.path.join(SRC, "onnx"), language="english_2026-04")
print("REFERENCE", tts)

TEXTS = [
    "Hello there, this is a test.",
    "The quick brown fox jumps over the lazy dog, and then it rests beside the river.",
    "\"We used something of the sort once before,\" said Billy.",
    "Café crème costs €3.50 — isn’t that odd?",
    "In 1887 Mr. Holmes (of Baker Street) solved 12 cases; Watson wrote them up.",
    "Emoji \U0001F600 and unusual symbols § ¶ ∞ should not crash anything.",
    "short",
    "A much longer passage follows, with commas, semicolons; colons: and a few more clauses to push it past the chunk limit, because the reference splits long sentences at punctuation, and the port must split them in exactly the same places!",
    "Is this a question? Yes! It is... and it keeps going.",
    "   spaces   everywhere   in   this   one   ",
]
sp = tts.tokenizer
tokens = []
for t in TEXTS:
    ids = [int(i) for i in sp.Encode(t)]
    tokens.append({"text": t, "ids": ids, "decoded": sp.Decode(ids), "half_decoded": sp.Decode(ids[: len(ids) // 2])})
boundaries = {"eos": [int(i) for i in sp.Encode(".!...?")], "fallback": [int(i) for i in sp.Encode(",;:")]}
prepared = []
for t in TEXTS:
    if not t.strip(): continue
    p, g = tts._prepare_text_prompt(t)
    prepared.append({"text": t, "prepared": p, "guess": int(g), "chunks": tts._split_into_best_sentences(t), "tokenized": [int(i) for i in tts._tokenize(t).reshape(-1)]})
json.dump({"tokens": tokens, "boundaries": boundaries, "prepared": prepared}, open(os.path.join(OUT, "text.json"), "w"), ensure_ascii=False)
print("GOLDEN text", len(tokens), "tokenisations", len(prepared), "preparations; chunk counts", [len(p["chunks"]) for p in prepared])

# a deterministic voice-like reference: harmonic stack with a slow pitch drift and syllable-rate amplitude modulation
sr = 24000
t = np.arange(sr * 6) / sr
f0 = 120 + 20 * np.sin(2 * np.pi * 0.5 * t)
phase = 2 * np.pi * np.cumsum(f0) / sr
sig = sum(np.sin(k * phase) / k for k in range(1, 13)) * (0.5 + 0.5 * np.sin(2 * np.pi * 3 * t)) ** 2 * 0.3
pcm = (np.clip(sig, -1, 1) * 32767).astype(np.int16)
wav_path = os.path.join(OUT, "ref.wav")
with wave.open(wav_path, "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm.tobytes())

emb = tts.encode_voice(wav_path)
emb.astype(np.float32).tofile(os.path.join(OUT, "enc.bin"))
print("GOLDEN encoder embeddings shape", emb.shape, "first values", emb.reshape(-1)[:4].tolist())

# transformer conditioning over several steps (no randomness involved)
state = tts.prepare_voice_state(wav_path)
ids = tts._tokenize("Hello there, this is a test.")
te = tts.text_conditioner.run(None, {"token_ids": ids})[0]
if te.ndim == 2: te = te[None]
empty_seq = np.zeros((1, 0, tts.latent_dim), dtype=np.float32)
empty_text = np.zeros((1, 0, tts.conditioning_dim), dtype=np.float32)
st = tts._clone_state(state)
r = tts.flow_lm_main.run(None, {"sequence": empty_seq, "text_embeddings": te, **st})
tts._update_state_from_outputs(st, r, tts.flow_state_manifest, output_offset=2)
curr = np.full((1, 1, tts.latent_dim), np.nan, dtype=np.float32)
steps = []
for i in range(5):
    r = tts.flow_lm_main.run(None, {"sequence": curr, "text_embeddings": empty_text, **st})
    steps.append({"conditioning": [float(x) for x in r[0].reshape(-1)], "eos": float(r[1].reshape(-1)[0])})
    tts._update_state_from_outputs(st, r, tts.flow_state_manifest, output_offset=2)
    curr = np.full((1, 1, tts.latent_dim), 0.1, dtype=np.float32)
rng = np.random.default_rng(7)
x = rng.normal(0, 1, (1, tts.latent_dim)).astype(np.float32)
c0 = np.array(steps[0]["conditioning"], dtype=np.float32).reshape(r[0].shape)
flow = tts.flow_lm_flow.run(None, {"c": c0, "s": np.array([[0.0]], dtype=np.float32), "t": np.array([[1.0]], dtype=np.float32), "x": x})[0]
latents = rng.normal(0, 0.5, (1, 25, tts.latent_dim)).astype(np.float32)
audio = tts.decode_latents(latents, chunk_size=12)
json.dump({"token_ids": [int(i) for i in ids.reshape(-1)], "steps": steps, "flow_x": x.reshape(-1).tolist(), "flow_out": flow.reshape(-1).tolist(),
           "latents": latents.reshape(-1).tolist(), "frames": int(emb.shape[1]), "cond_shape": list(r[0].shape), "text_emb_shape": list(te.shape)},
          open(os.path.join(OUT, "model.json"), "w"))
audio.astype(np.float32).tofile(os.path.join(OUT, "decoded.bin"))
print("GOLDEN model: conditioning shape", r[0].shape, "eos first", steps[0]["eos"], "decoded samples", audio.shape)
