"""Prints what Hugging Face and GitHub say about Kyutai's Pocket TTS and its ONNX ports: licences, file sizes, model cards."""
import json, subprocess, urllib.request, os

def get(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()

def api(path):
    return json.loads(get("https://huggingface.co/api/" + path))

print("=== MODEL SEARCH ===")
seen = {}
for q in ["author=kyutai&limit=100", "search=pocket-tts&limit=100", "search=pocket-tts-onnx&limit=100", "search=pocket%20tts%20onnx&limit=50"]:
    try:
        for m in api("models?" + q):
            seen[m["id"]] = m
    except Exception as e:
        print("search failed", q, e)
for mid in sorted(seen):
    m = seen[mid]
    tags = m.get("tags", [])
    lic = next((t for t in tags if t.startswith("license:")), "license:?")
    flags = [t for t in tags if t in ("onnx", "safetensors", "text-to-speech", "tflite", "coreml", "executorch")]
    print(f"MODEL {mid} {lic} downloads={m.get('downloads')} tags={flags}")

def detail(rid, show_card=True):
    print(f"=== DETAIL {rid} ===")
    try:
        info = api(f"models/{rid}?blobs=true")
        print("license:", (info.get("cardData") or {}).get("license"), "| gated:", info.get("gated"), "| sha:", info.get("sha"))
        total = 0
        for s in info.get("siblings", []):
            size = s.get("size") or 0
            total += size
            print(f"FILE {s['rfilename']} {size}")
        print(f"TOTAL_BYTES {total}")
    except Exception as e:
        print("not available:", e)
        return
    if show_card:
        print(f"=== BEGIN {rid}/README.md ===")
        try:
            print(get(f"https://huggingface.co/{rid}/resolve/main/README.md").decode("utf-8", "replace")[:12000])
        except Exception as e:
            print("(no card)", e)
        print("=== END README.md ===")

detail("kyutai/pocket-tts")
detail("kyutai/tts-voices")
# the most downloaded ONNX ports (cards trimmed)
onnx = sorted([m for m in seen.values() if "onnx" in m["id"].lower() and "pocket" in m["id"].lower()], key=lambda m: -(m.get("downloads") or 0))[:5]
for m in onnx:
    detail(m["id"], show_card=False)
for rid in ("ottomate/pocket-tts-ONNX",):
    detail(rid)

