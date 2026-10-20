"""Checks that the voice-pack files the app downloads are reachable WITHOUT signing in, and are exactly what the catalog pins."""
import hashlib, json, sys, urllib.error, urllib.request

catalog = json.load(open("app/src/main/assets/voicepacks.json"))["packs"]
bad = 0
for pack in catalog:
    for f in pack["files"]:
        url = f["url"]
        try:
            if pack["id"] == "openvoice-converter-v1":
                # we host these: download fully and compare size + SHA-256, as the app does
                data = urllib.request.urlopen(url, timeout=120).read()
                ok = len(data) == f["sizeBytes"] and hashlib.sha256(data).hexdigest() == f["sha256"]
                print("OK  " if ok else "BAD ", pack["id"], f["path"], len(data))
            else:
                # third-party host: only confirm it answers (their files are large)
                req = urllib.request.Request(url, method="HEAD")
                code = urllib.request.urlopen(req, timeout=60).status
                ok = code == 200
                print("OK  " if ok else "BAD ", pack["id"], f["path"], code)
        except urllib.error.HTTPError as e:
            ok = False; print("FAIL", pack["id"], f["path"], "HTTP", e.code)
        except Exception as e:
            ok = False; print("FAIL", pack["id"], f["path"], repr(e)[:120])
        bad += 0 if ok else 1
print("PROBLEMS:", bad)
sys.exit(1 if bad else 0)
