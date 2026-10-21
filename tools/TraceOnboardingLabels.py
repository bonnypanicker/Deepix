"""Redraw the labels inside the onboarding illustrations as real type.

Two of the mock's illustrations set their labels as live <text>: the sizes on
the Smart cleanup page ("20 MB", "4 MB", "-80%") and the plate on the Safe
page ("AES-256"). A vector drawable cannot hold text, so those have to be
outlines.

Hand-drawing them goes wrong in a specific way: a letter drawn as a stroked
skeleton gets the stroke width added to it on every side, so the ink ends up
taller and heavier than the type it stands for, and the tight curves fill in.
This takes the outlines straight from the font instead, at the size, weight,
baseline and anchor the mock asks for, so the ink is the type's own.

The font is Roboto - the platform this app runs on, and the mock's own second
choice after Segoe UI. Run with --font-dir to point at other faces.

Needs fontTools (pip install fonttools). The faces are fetched from Google
Fonts on first run and land in build/fonts, which is not tracked.

Usage:
    python tools/TraceOnboardingLabels.py            # print the path data
    python tools/TraceOnboardingLabels.py --write     # patch the drawables
"""
import argparse
import pathlib
import re
import sys
import urllib.request

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
DRAW = ROOT / "app/src/main/res/drawable"

# The mock's own text elements: label, font file, size, weight, anchor x, baseline y.
LABELS = {
    "20 MB":   dict(font="Roboto-500.ttf", size=10.0,  x=94.0,  y=183.0),
    "4 MB":    dict(font="Roboto-700.ttf", size=10.0,  x=180.0, y=183.0),
    "−80%":   dict(font="Roboto-700.ttf", size=11.5,  x=250.0, y=183.2),
    "AES-256": dict(font="Roboto-700.ttf", size=8.5,  x=150.0, y=171.2),
}

# Where each label lives: the drawable, the fill it takes, and the paths it replaces.
PLACEMENT = [
    dict(file="ic_onboarding_smart_cleanup.xml", label="20 MB",
         fill="?attr/accentColorMuted", marker='<!-- "20 MB"'),
    dict(file="ic_onboarding_smart_cleanup.xml", label="4 MB",
         fill="#FFFFFF", marker='<!-- "4 MB"'),
    dict(file="ic_onboarding_smart_cleanup.xml", label="−80%",
         fill="#FFFFFF", marker='<!-- "−80%"'),
    dict(file="ic_onboarding_safe.xml", label="AES-256",
         fill="#FFFFFF", marker='<!-- "AES-256"'),
]

GOOGLE_FONTS = ("https://fonts.googleapis.com/css2?family=Roboto:wght@{}")
WEIGHTS = {"400": "Roboto-400.ttf", "500": "Roboto-500.ttf", "700": "Roboto-700.ttf"}


def fetch_fonts(font_dir: pathlib.Path):
    """Pull the Roboto weights from Google Fonts, which serves them as TrueType."""
    font_dir.mkdir(parents=True, exist_ok=True)
    for weight, name in WEIGHTS.items():
        target = font_dir / name
        if target.exists():
            continue
        url = re.search(r"url\((https://[^)]+)\)",
                        urllib.request.urlopen(GOOGLE_FONTS.format(weight), timeout=90)
                        .read().decode()).group(1)
        target.write_bytes(urllib.request.urlopen(url, timeout=90).read())
        print("fetched", target.name)


def text_to_path(font_path: pathlib.Path, text: str, size: float, anchor_x: float,
                 baseline_y: float) -> str:
    """The outline of `text` as one SVG path, in the illustration's own units."""
    font = TTFont(font_path)
    upem = font["head"].unitsPerEm
    scale = size / upem
    cmap = font.getBestCmap()
    hmtx = font["hmtx"]
    glyphs = font.getGlyphSet()

    names = []
    for ch in text:
        name = cmap.get(ord(ch))
        if name is None and ch == "−":
            name = cmap.get(ord("-"))          # minus sign -> hyphen
        if name is None:
            raise SystemExit(f"{text!r}: no glyph for {ch!r} in {font_path.name}")
        names.append(name)

    total = sum(hmtx[n][0] for n in names) * scale
    pen_x = anchor_x - total / 2.0

    pen = SVGPathPen(glyphs)
    for name in names:
        transform = TransformPen(pen, (scale, 0, 0, -scale, pen_x, baseline_y))
        glyphs[name].draw(transform)
        pen_x += hmtx[name][0] * scale

    d = pen.getCommands()
    return re.sub(r"\s+", " ", d).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--font-dir", type=pathlib.Path,
                    default=ROOT / "build" / "fonts")
    ap.add_argument("--write", action="store_true",
                    help="patch the drawables instead of printing the path data")
    args = ap.parse_args()

    fetch_fonts(args.font_dir)

    generated = {}
    for label, spec in LABELS.items():
        generated[label] = text_to_path(args.font_dir / spec["font"], label,
                                        spec["size"], spec["x"], spec["y"])

    if not args.write:
        for label, d in generated.items():
            print(f"--- {label}\n{d}\n")
        return 0

    by_file = {}
    for place in PLACEMENT:
        by_file.setdefault(place["file"], []).append((place, generated[place["label"]]))

    for filename, placements in by_file.items():
        path = DRAW / filename
        src = path.read_text(encoding="utf-8")
        for place, d in placements:
            # the label's own paths run from its marker comment to the next comment,
            # or to the closing </vector> when the label is the last thing in the file
            start = src.find(place["marker"])
            if start < 0:
                raise SystemExit(f"{filename}: marker {place['marker']!r} not found")
            nxt = src.find("<!--", start + 10)
            close = src.find("</vector>", start)
            if close >= 0 and (nxt < 0 or close < nxt):
                nxt = close
            elif nxt < 0:
                nxt = len(src)
            block = src[start:nxt]
            # keep any comment lines that describe the label, drop the old stroked paths
            kept = [ln for ln in block.split("\n")
                    if ln.strip().startswith("<!--") or not ln.strip()]
            new_paths = (f'\n    <path\n'
                         f'        android:fillColor="{place["fill"]}"\n'
                         f'        android:pathData="{d}" />\n')
            src = src[:start] + "\n".join(kept).rstrip() + "\n" + new_paths + src[nxt:]
            print(f"{filename}: replaced {place['label']}")
        path.write_text(src, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
