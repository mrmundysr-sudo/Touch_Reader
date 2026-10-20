#!/usr/bin/env bash
# Downloads the pinned Supertonic 3 revision, prints its licence and model card, lists every file with
# size and SHA-256 (for pinning in the voice-pack catalog). Run from CI; needs internet.
set -euo pipefail
REPO=supertone-oss-archive/supertonic-3
REV=aafc6e32416a594460b32413efc49d7fe4ce6d46
OUT="${SUPERTONIC_DIR:-.supertonic}"
mkdir -p "$OUT"
BASE="https://huggingface.co/$REPO/resolve/$REV"

echo "=== TREE ($REPO @ $REV) ==="
curl -fsSL "https://huggingface.co/api/models/$REPO/tree/$REV?recursive=1" -o "$OUT/tree.json"
python3 - "$OUT/tree.json" <<'PY'
import json, sys
for e in json.load(open(sys.argv[1])):
    if e["type"] == "file":
        print(e["path"], e["size"], (e.get("lfs") or {}).get("oid", "-"))
PY

for f in LICENSE README.md; do
  echo "=== BEGIN $f ==="
  curl -fsSL "$BASE/$f" | tee "$OUT/$f" || echo "(could not fetch $f)"
  echo; echo "=== END $f ==="
done

echo "=== DOWNLOADING onnx/ and voice_styles/ ==="
python3 - "$OUT" "$BASE" <<'PY'
import hashlib, json, os, sys, urllib.request
out, base = sys.argv[1], sys.argv[2]
tree = json.load(open(os.path.join(out, "tree.json")))
for e in tree:
    p = e["path"]
    if e["type"] != "file" or not (p.startswith("onnx/") or p.startswith("voice_styles/")):
        continue
    dest = os.path.join(out, p)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    urllib.request.urlretrieve(f"{base}/{p}", dest)
    h = hashlib.sha256(open(dest, "rb").read()).hexdigest()
    oid = (e.get("lfs") or {}).get("oid")
    status = "" if oid is None else ("  [matches HF lfs oid]" if oid == h else "  [MISMATCH vs HF oid " + oid + "]")
    print(f"CATALOG_FILE path={p} size={os.path.getsize(dest)} sha256={h}{status}")
PY
