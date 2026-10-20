"""Prints what Hugging Face says about OpenVoice V2: licence, files and sizes, model card."""
import json, urllib.request

def get(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()

rid = "myshell-ai/OpenVoiceV2"
info = json.loads(get(f"https://huggingface.co/api/models/{rid}?blobs=true"))
print("license:", (info.get("cardData") or {}).get("license"), "| gated:", info.get("gated"), "| sha:", info.get("sha"))
total = 0
for s in info.get("siblings", []):
    size = s.get("size") or 0
    total += size
    print(f"FILE {s['rfilename']} {size}")
print("TOTAL_BYTES", total)
for f in ["README.md", "LICENSE"]:
    print(f"=== BEGIN {f} ===")
    try:
        print(get(f"https://huggingface.co/{rid}/resolve/main/{f}").decode("utf-8", "replace"))
    except Exception as e:
        print("(not found)", e)
    print(f"=== END {f} ===")
