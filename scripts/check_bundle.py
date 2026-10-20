"""Fails unless the files bundled into a test build are exactly the ones the app catalog pins (size + SHA-256)."""
import hashlib, json, os, sys
catalog = json.load(open("app/src/main/assets/voicepacks.json"))["packs"]
pack_id = sys.argv[1]
pack = next(p for p in catalog if p["id"] == pack_id)
root = os.path.join("app/src/main/assets/bundled", pack_id)
bad = 0
for f in pack["files"]:
    path = os.path.join(root, f["path"])
    if not os.path.isfile(path):
        print("MISSING", f["path"]); bad += 1; continue
    h = hashlib.sha256(open(path, "rb").read()).hexdigest()
    ok = os.path.getsize(path) == f["sizeBytes"] and h == f["sha256"]
    print("OK  " if ok else "BAD ", f["path"], os.path.getsize(path), h)
    bad += 0 if ok else 1
sys.exit(1 if bad else 0)
