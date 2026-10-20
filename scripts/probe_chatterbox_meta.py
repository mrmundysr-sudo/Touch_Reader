"""Prints what Hugging Face says about Chatterbox models: licence, tags (ONNX?), file sizes, model card."""
import json, urllib.request

def get(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()

def api(path):
    return json.loads(get("https://huggingface.co/api/" + path))

print("=== MODEL SEARCH ===")
seen = {}
for q in ["author=ResembleAI&limit=100", "search=chatterbox&limit=100", "search=chatterbox-nano&limit=50", "search=chatterbox%20onnx&limit=50"]:
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

for rid in ["ResembleAI/chatterbox-nano", "ResembleAI/chatterbox-turbo", "ResembleAI/chatterbox-turbo-ONNX"]:
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

for f in ["LICENSE", "README.md"]:
    print(f"=== BEGIN ResembleAI/chatterbox-nano/{f} ===")
    try:
        print(get(f"https://huggingface.co/ResembleAI/chatterbox-nano/resolve/main/{f}").decode("utf-8", "replace"))
    except Exception as e:
        print("(not found)", e)
    print(f"=== END {f} ===")
