"""Artwork pipeline for Touch Reader.

  python3 scripts/art_tool.py check   Check every picture in art/ against art/slots.json (size, transparency, weight). Exit 1 if one is wrong.
  python3 scripts/art_tool.py doc     Rewrite the slot tables in docs/ASSETS.md from art/slots.json.
  python3 scripts/art_tool.py status  List which slots have a picture and which do not (not an error: missing ones use the built-in look).

Pictures go in art/<page>/<slot id>.png (or .webp). The Android build copies them to the app as art_<slot id>.
"""
import json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "art")
MAX_BYTES = 700_000
EXTS = (".png", ".webp")


def load():
    return json.load(open(os.path.join(ART, "slots.json")))["slots"]


def files():
    out = []
    for dirpath, _, names in os.walk(ART):
        for n in names:
            if n.lower().endswith(EXTS):
                out.append(os.path.join(dirpath, n))
    return sorted(out)


def check():
    from PIL import Image
    slots = {s["id"]: s for s in load()}
    problems, ok = [], 0
    seen = {}
    for f in files():
        rel = os.path.relpath(f, ART)
        stem = os.path.splitext(os.path.basename(f))[0]
        page = os.path.dirname(rel)
        s = slots.get(stem)
        if s is None:
            problems.append(f"{rel}: no slot called '{stem}' (see art/slots.json)"); continue
        if page != s["page"]:
            problems.append(f"{rel}: '{stem}' belongs in art/{s['page']}/")
        if stem in seen:
            problems.append(f"{rel}: '{stem}' is also at {seen[stem]} (one picture per slot)")
        seen[stem] = rel
        if os.path.getsize(f) > MAX_BYTES:
            problems.append(f"{rel}: {os.path.getsize(f)//1000} KB is over the {MAX_BYTES//1000} KB limit; save it smaller")
        try:
            im = Image.open(f); im.load()
        except Exception as e:
            problems.append(f"{rel}: cannot be opened ({e})"); continue
        w, h = im.size
        sw, sh = s["width"], s["height"]
        if abs(w / h - sw / sh) > 0.03 * (sw / sh):
            problems.append(f"{rel}: shape is {w}x{h} but should be {sw}x{sh} (same proportions)")
        if w < sw * 0.5 or w > sw * 2:
            problems.append(f"{rel}: {w}x{h} is far from the wanted {sw}x{sh} (between half and double is accepted)")
        has_alpha = im.mode in ("RGBA", "LA") or "transparency" in im.info
        if s["transparent"] and not has_alpha:
            problems.append(f"{rel}: needs a transparent background")
        if s["transparent"] and has_alpha:
            lo, _ = im.convert("RGBA").getchannel("A").getextrema()
            if lo > 250:
                problems.append(f"{rel}: has an alpha channel but nothing in it is transparent")
        if s["kind"] == "nine_slice" and w * 2 <= 2 * s["slice"] * (w / sw) :
            problems.append(f"{rel}: too small for its corners")
        ok += 1
    print(f"{ok} picture(s) checked, {len(problems)} problem(s)")
    for p in problems:
        print("  PROBLEM", p)
    return 1 if problems else 0


def status():
    have = {os.path.splitext(os.path.basename(f))[0] for f in files()}
    by_page = {}
    for s in load():
        by_page.setdefault(s["page"], []).append(s)
    total = filled = 0
    for page, ss in by_page.items():
        print(f"\n{page}/")
        for s in ss:
            total += 1
            mark = "have   " if s["id"] in have else "missing"
            filled += s["id"] in have
            note = "" if s["wired"] else "  (not used by the app yet)"
            print(f"  [{mark}] {s['id']}{note}")
    print(f"\n{filled} of {total} slots have a picture; the rest use the built-in look.")
    return 0


def doc():
    path = os.path.join(ROOT, "docs", "ASSETS.md")
    text = open(path).read()
    start, end = "<!-- slots:start -->", "<!-- slots:end -->"
    a, b = text.index(start), text.index(end)
    rows = []
    pages = {}
    for s in load():
        pages.setdefault(s["page"], []).append(s)
    kinds = {"nine_slice": "stretchable frame", "texture": "seamless tile", "icon": "icon", "banner": "banner", "illustration": "picture"}
    for page, ss in pages.items():
        rows.append(f"\n### Folder `art/{page}/`\n")
        for s in ss:
            w, h = s["width"], s["height"]
            kind = kinds[s["kind"]] + (f", corners {s['slice']} px" if s["kind"] == "nine_slice" else "")
            rows.append(f"#### `{s['id']}`\n")
            rows.append(f"- **Purpose and where it appears:** {s['where']}")
            rows.append(f"- **File:** `art/{page}/{s['id']}.png` (or `.webp`), {kind}")
            rows.append(f"- **Size:** {w} x {h} px (at 3x this is {w // 3} x {h // 3} dp)")
            rows.append(f"- **Transparent background:** {'YES, transparent outside the shape' if s['transparent'] else 'no, fully opaque'}")
            rows.append(f"- **Look (colours, materials, lighting, 3D):** {s['look']}")
            rows.append(f"- **Text:** {s['text']}")
            rows.append(f"- **Interaction states:** {s['states']}")
            rows.append(f"- **Used by the app now:** {'yes' if s['wired'] else 'not yet (reserved)'}\n")
    open(path, "w").write(text[: a + len(start)] + "\n" + "\n".join(rows) + "\n" + text[b:])
    return 0


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    sys.exit({"check": check, "status": status, "doc": doc}[cmd]())
