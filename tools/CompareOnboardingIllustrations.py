"""Render the reference mock's SVGs and the native vectors side by side.

Also checks the markup against the rules the framework applies but a browser
does not, which is where the clip-path bug hid: a browser accepts a <clip-path>
that wraps a <path>, the framework does not, and the group it guards is then
clipped away on the device while the comparison here still matches.

The VectorDrawable is translated to SVG so the same browser can rasterise both:
<path> becomes <path>, <clip-path> becomes <clipPath>, <group> becomes <g>.
The reference is recoloured through the same accent ramp the native vectors use,
so a surviving difference is geometry rather than theme.
"""
import re, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
DRAW = ROOT / "app/src/main/res/drawable"
OUT = ROOT / "build/compare"
OUT.mkdir(parents=True, exist_ok=True)

# The reference mock the illustrations are drawn from.
REFERENCE = "pixa-onboarding-refined-7 (2).html"

ACCENT = {
    "?attr/accentColor": "#3B9EFF",
    "?attr/accentColorBright": "#00A4EF",
    "?attr/accentColorLight": "#6DB8FF",
    "?attr/accentColorMuted": "#B9DDF1",
    "?attr/accentColorDark": "#164F75",
    "?attr/accentColorDarker": "#005A9E",
    "?attr/accentColorDeep": "#003E68",
}

def color(v, default="none"):
    if v is None: return default
    v = v.strip()
    if v.startswith("?attr/"): return ACCENT.get(v, default)
    if v == "#00000000": return "none"
    return v

def num(v, d=0.0):
    try: return float(v)
    except: return d

def num_s(v):
    f = float(v)
    return str(int(f)) if f == int(f) else str(f)

def attrs_of(el):
    return dict(re.findall(r'android:(\w+)="([^"]*)"', el))

def transform_of(a):
    rot = num(a.get("rotation"), 0.0)
    if rot == 0: return ""
    return (f' transform="rotate({num_s(rot)} {num_s(a.get("pivotX", 0))} '
            f'{num_s(a.get("pivotY", 0))})"')

def shape_svg(tag, a):
    fill = color(a.get("fillColor"))
    fa = a.get("fillAlpha")
    fill_op = f' fill-opacity="{fa}"' if fa else ""
    stroke = color(a.get("strokeColor"))
    st = ""
    if stroke != "none":
        st = f' stroke="{stroke}" stroke-width="{a.get("strokeWidth", 1)}"'
        if a.get("strokeAlpha"): st += f' stroke-opacity="{a["strokeAlpha"]}"'
        if a.get("strokeLineCap"): st += f' stroke-linecap="{a["strokeLineCap"]}"'
        if a.get("strokeLineJoin"): st += f' stroke-linejoin="{a["strokeLineJoin"]}"'
    if tag == "path":
        return f'<path d="{a["pathData"]}" fill="{fill}"{fill_op}{st}{transform_of(a)}/>'
    if tag == "rect":
        rx = f' rx="{a["rx"]}"' if "rx" in a else ""
        return (f'<rect x="{a.get("x",0)}" y="{a.get("y",0)}" width="{a.get("width",0)}" '
                f'height="{a.get("height",0)}"{rx} fill="{fill}"{fill_op}{st}{transform_of(a)}/>')
    if tag == "circle":
        return (f'<circle cx="{a.get("centerX",0)}" cy="{a.get("centerY",0)}" r="{a.get("radius",0)}" '
                f'fill="{fill}"{fill_op}{st}{transform_of(a)}/>')
    if tag == "ellipse":
        return (f'<ellipse cx="{a.get("centerX",0)}" cy="{a.get("centerY",0)}" rx="{a.get("rx",0)}" '
                f'ry="{a.get("ry",0)}" fill="{fill}"{fill_op}{st}{transform_of(a)}/>')
    return ""

TAG = re.compile(r'<(\w[\w-]*)\b([^>]*?)(/?)>')

def parse_children(s):
    out, i, n = [], 0, len(s)
    while i < n:
        m = TAG.search(s, i)
        if not m: break
        tag = m.group(1)
        a = attrs_of(m.group(0))
        if tag == "group":
            close = "</group>"
            depth, j = 1, m.end()
            while depth > 0 and j < n:
                m3 = re.compile(r'<group\b|' + close).search(s, j)
                if not m3: break
                depth += -1 if m3.group(0) == close else 1
                j = m3.end()
            out.append((tag, a, s[m.end(): j - len(close)]))
            i = j
        else:
            out.append((tag, a, None))
            i = m.end()
    return out


# The framework's vector format has exactly three elements, and each one carries its
# own geometry. These checks are the ones a browser comparison cannot make: an SVG
# translation can rescue markup the framework would reject or misread.
ALLOWED = {"path", "clip-path", "group"}

def check_android_markup(src, name):
    """Reject markup the framework reads differently from an SVG translation."""
    problems = []
    for m in re.finditer(r'<(path|clip-path|group|rect|circle|ellipse|polygon|polyline)\b([^>]*?)(/?)>', src):
        tag, attrs, self_closing = m.group(1), m.group(2), m.group(3)
        if tag not in ALLOWED:
            problems.append(f"<{tag}> is not a vector element the framework implements")
        if tag == "clip-path":
            if not self_closing:
                problems.append("<clip-path> wraps content; the framework reads the clip "
                                "from the element's own android:pathData")
            if "android:pathData" not in attrs:
                problems.append("<clip-path> has no android:pathData of its own, so the "
                                "clip it guards is empty and the group is clipped away")
        if tag == "path":
            if "android:pathData" not in attrs:
                problems.append("<path> has no android:pathData")
            for group_only in ("rotation", "pivotX", "pivotY", "scaleX", "scaleY",
                               "translateX", "translateY"):
                if f"android:{group_only}" in attrs:
                    problems.append(
                        f"<path> carries android:{group_only}, which the framework reads "
                        f"from a <group> only - it is ignored on a path")
    if problems:
        raise SystemExit(f"{name}:\n  " + "\n  ".join(problems))


# An ellipse written as two arcs starts at (cx, cy-ry); the first arc then sweeps
# 2*ry. Both follow from the start point, so a mistyped centre shows up as a
# correctly-shaped ellipse in the wrong place - which a picture diff shows only as
# a smear of pixels. Comparing the geometry against the reference catches it.
TWO_ARC = re.compile(
    r'^M(-?[\d.]+),(-?[\d.]+)a([\d.]+),([\d.]+) 0,1 0,0 (-?[\d.]+)'
    r'a([\d.]+),([\d.]+) 0,1 0,0 (-?[\d.]+)$')

def ellipse_geometry(path_data):
    """The centre and radii of a two-arc ellipse, or None if it is not one."""
    m = TWO_ARC.match(path_data)
    if not m:
        return None
    x0, y0 = float(m.group(1)), float(m.group(2))
    rx, ry = float(m.group(3)), float(m.group(4))
    return (x0, y0 + float(m.group(5)) / 2, rx, ry)

def reference_ellipses(svg):
    """Every circle and ellipse in the reference, as (cx, cy, rx, ry)."""
    out = []
    for m in re.finditer(r'<circle\b([^>]*)/>', svg):
        a = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
        out.append((float(a["cx"]), float(a["cy"]), float(a["r"]), float(a["r"])))
    for m in re.finditer(r'<ellipse\b([^>]*)/>', svg):
        a = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
        out.append((float(a["cx"]), float(a["cy"]), float(a["rx"]), float(a["ry"])))
    return out

def native_ellipses(src):
    """Every two-arc ellipse in the vector, as (cx, cy, rx, ry).

    Only filled ones. The labels the reference sets as live text are traced here as
    stroked outlines, and those have no counterpart among the reference's elements
    - a "%" drawn by hand is a diagonal and two small circles, where the reference
    gets the glyph from its font.
    """
    out = []
    for m in re.finditer(r'<path\b([^>]*?)/>', src, re.S):
        a = dict(re.findall(r'android:(\w+)="([^"]*)"', m.group(1)))
        if a.get("fillColor", "") in ("#00000000", "none"):
            continue
        g = ellipse_geometry(a.get("pathData", ""))
        if g:
            out.append(g)
    return out

def check_shapes_match(ref_svg, src, name):
    """The reference's circles and ellipses must land where the reference puts them."""
    def bucket(shapes, tol=0.05):
        counts = {}
        for s in shapes:
            k = tuple(round(v / tol) for v in s)
            counts.setdefault(k, []).append(s)
        return counts

    ref = bucket(reference_ellipses(ref_svg))
    nat = bucket(native_ellipses(src))
    problems = []
    for k, shapes in ref.items():
        if k not in nat:
            for s in shapes:
                problems.append(f"reference has a circle/ellipse at {s} that the vector does not")
    for k, shapes in nat.items():
        if k not in ref:
            for s in shapes:
                problems.append(f"the vector has a circle/ellipse at {s} that the reference does not")
    if problems:
        raise SystemExit(f"{name}:\n  " + "\n  ".join(problems))


def convert(path, counter):
    src = path.read_text(encoding="utf-8")
    check_android_markup(src, path.name)
    vw = re.search(r'android:viewportWidth="([\d.]+)"', src).group(1)
    vh = re.search(r'android:viewportHeight="([\d.]+)"', src).group(1)
    m = re.search(r'<vector\b[^>]*>', src, re.S)
    inner = src[m.end(): src.rindex("</vector>")]
    defs = []

    def emit(children):
        out = []
        for tag, a, body in children:
            if tag == "group":
                op = a.get("alpha")
                gattrs = f' opacity="{op}"' if op else ""
                # rotation and pivot are the group's own, the way the framework reads them
                gattrs += transform_of(a)
                kids = parse_children(body) if body is not None else []
                # Only a clip-path that is a direct child clips this group; one nested
                # deeper belongs to the group it sits in. The geometry comes from the
                # element's own android:pathData, the way the framework reads it.
                clip_attr = ""
                kept = []
                for ktag, ka, kbody in kids:
                    if ktag == "clip-path" and not clip_attr:
                        data = ka.get("pathData")
                        if data:
                            counter[0] += 1
                            cid = f"clip{counter[0]}"
                            defs.append(f'<clipPath id="{cid}"><path d="{data}"/></clipPath>')
                            clip_attr = f' clip-path="url(#{cid})"'
                            continue
                    kept.append((ktag, ka, kbody))
                inner_svg = emit(kept)
                out.append(f"<g{gattrs}{clip_attr}>{inner_svg}</g>")
            elif tag == "clip-path":
                continue
            else:
                out.append(shape_svg(tag, a))
        return "".join(out)

    body = emit(parse_children(inner))
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}" '
            f'width="300" height="220">{"".join(defs)}{body}</svg>')

ref = (ROOT / REFERENCE).read_text(encoding="utf-8")
svgs = re.findall(r'<svg class="(?:mark|feature-icon)"[^>]*>.*?</svg>', ref, re.S)
names = ["mark", "search", "people", "smart_albums", "smart_cleanup", "editor", "safe"]
draw = ["ic_onboarding_mark", "ic_onboarding_search", "ic_onboarding_people",
        "ic_onboarding_smart_albums", "ic_onboarding_smart_cleanup",
        "ic_onboarding_editor", "ic_onboarding_safe"]

SUB = {"#0078D4": "#3B9EFF", "#52C7F9": "#6DB8FF"}
counter = [0]
rows = []
for idx, (nm, dn) in enumerate(zip(names, draw)):
    r = svgs[idx]
    check_shapes_match(r, (DRAW / f"{dn}.xml").read_text(encoding="utf-8"), dn)
    r = re.sub(r'\sclass="[^"]*"', '', r)
    for o, nw in SUB.items():
        r = r.replace('fill="%s"' % o, 'fill="%s"' % nw)
    r = r.replace("<svg ", '<svg data-side="ref" ')
    c = convert(DRAW / f"{dn}.xml", counter).replace("<svg ", '<svg data-side="nat" ', 1)
    rows.append(f'<div class="row" data-name="{nm}"><h3>{nm}</h3>'
                f'<div class="pair"><div class="cell">{r}</div><div class="cell">{c}</div></div></div>')

page = """<!DOCTYPE html><html><head><meta charset="utf-8"><style>
body{background:#111;color:#eee;font:13px system-ui;margin:0;padding:12px}
h3{margin:14px 0 5px;font-size:14px;color:#8cf}
.pair{display:flex;gap:14px}
.cell{background:#000;border:1px solid #333;padding:4px}
svg{display:block;width:300px;height:220px}
</style></head><body>
""" + "\n".join(rows) + "\n</body></html>"
(OUT / "compare.html").write_text(page, encoding="utf-8")
print("wrote", OUT / "compare.html", "with", len(rows), "pairs")
print("open it in a browser to compare each reference drawing with the native vector")
