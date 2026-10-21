"""Stage 1 for the Pocket TTS port: pins the exact model files (sizes, SHA-256, URLs) and prints the metadata formats the Kotlin port must read."""
import hashlib, json, os

from huggingface_hub import snapshot_download

REPO = "KevinAHM/pocket-tts-onnx"
REV = os.environ["POCKET_ONNX_REV"]
DEST = os.environ["POCKET_SRC"]
B = "onnx/english_2026-04/"
FILES = [B + n for n in [
    "flow_lm_main_int8.onnx", "flow_lm_flow_int8.onnx", "mimi_decoder_int8.onnx", "mimi_encoder.onnx", "mimi_encoder_int8.onnx",
    "text_conditioner.onnx", "bundle.json", "bos_before_voice.npy", "tokenizer.model"]] + ["onnx/LICENSE"]
snapshot_download(repo_id=REPO, revision=REV, allow_patterns=FILES, local_dir=DEST)

print("=== PIN (catalog entries) ===")
entries = []
for f in FILES:
    p = os.path.join(DEST, f)
    h = hashlib.sha256(open(p, "rb").read()).hexdigest()
    e = {"path": f.split("/")[-1], "url": f"https://huggingface.co/{REPO}/resolve/{REV}/{f}", "bytes": os.path.getsize(p), "sha256": h}
    entries.append(e); print("PIN", json.dumps(e))
print("PIN_TOTAL_BYTES", sum(e["bytes"] for e in entries))

print("=== bundle.json structure ===")
b = json.load(open(os.path.join(DEST, B + "bundle.json")))
for k, v in b.items():
    if isinstance(v, list):
        print(f"KEY {k}: list[{len(v)}]")
        for item in v[:3]: print("   ", json.dumps(item))
        if len(v) > 3: print("    ...", json.dumps(v[-1]))
    elif isinstance(v, dict):
        print(f"KEY {k}: dict[{len(v)}]", json.dumps(v)[:300])
    else:
        print(f"KEY {k}:", json.dumps(v)[:200])
import collections
for name in ("flow_lm_state_manifest", "mimi_state_manifest"):
    m = b[name]
    print(name, "entries", len(m), "dtypes", dict(collections.Counter(e["dtype"] for e in m)), "fills", dict(collections.Counter(e["fill"] for e in m)))
    big = sorted(m, key=lambda e: -eval("*".join(map(str, e["shape"])) or "1"))[:3]
    print("  largest:", json.dumps(big))
    print("  total elements:", sum(eval("*".join(map(str, e["shape"])) or "1") for e in m))

print("=== tokenizer.model ===")
from sentencepiece import sentencepiece_model_pb2 as pb
m = pb.ModelProto(); m.ParseFromString(open(os.path.join(DEST, B + "tokenizer.model"), "rb").read())
t, n = m.trainer_spec, m.normalizer_spec
print("TRAINER", json.dumps({"model_type": t.model_type, "vocab_size": t.vocab_size, "byte_fallback": t.byte_fallback, "unk_id": t.unk_id, "bos_id": t.bos_id, "eos_id": t.eos_id, "pad_id": t.pad_id, "unk_piece": t.unk_piece, "split_by_whitespace": t.split_by_whitespace, "treat_whitespace_as_suffix": t.treat_whitespace_as_suffix}))
print("NORMALIZER", json.dumps({"name": n.name, "add_dummy_prefix": n.add_dummy_prefix, "remove_extra_whitespaces": n.remove_extra_whitespaces, "escape_whitespaces": n.escape_whitespaces, "charsmap_bytes": len(n.precompiled_charsmap)}))
print("PIECES", len(m.pieces), dict(collections.Counter(p.type for p in m.pieces)))
print("FIRST_PIECES", [(p.piece, round(p.score, 3), p.type) for p in m.pieces[:8]])
print("LICENSE_HEAD", open(os.path.join(DEST, "onnx/LICENSE"), errors="replace").read()[:400].replace("\n", " | "))
