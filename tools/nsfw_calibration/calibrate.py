"""
Calibrates the sensitive-content blur margin for the app's MobileCLIP-S2 model.

Content-safety: this downloads only clearly-safe public photos (Unsplash via picsum.photos) and
measures the FALSE-POSITIVE boundary — how often normal photos would be blurred at each margin.
It does NOT download explicit imagery. Recall (catching real NSFW) must be calibrated separately
by the maintainer on a trusted labeled set; see recall_eval() at the bottom.

Run: python calibrate.py [--n 120]
"""
import argparse
import io
import sys
import numpy as np
import requests
from PIL import Image

import clip_runtime as c

# Must mirror NsfwClassifier.kt (calibrated config).
SENSITIVE_PROMPTS = [
    "explicit nude sexual content",
    "pornography",
    "a naked body",
    "sexual intercourse",
    "exposed genitalia",
]
SAFE_PROMPTS = [
    "a person wearing clothes", "a portrait of a clothed person", "a group of people",
    "a selfie", "a child", "a family photo", "a wedding", "a party or celebration",
    "a landscape", "nature", "a mountain", "the sky", "a beach", "a sunset",
    "food on a plate", "a drink", "an animal", "a pet dog or cat", "a bird",
    "a flower", "a plant", "a tree", "a car or vehicle", "a building", "a street",
    "a room interior", "furniture", "a document", "a screenshot", "text on a screen",
    "a poster or artwork", "clothing and fashion", "shoes", "sports", "a concert",
    "a toy", "electronics or a gadget", "a book", "a map",
]


def fetch_safe_images(n):
    """Deterministic safe photos from picsum (Unsplash-sourced, SFW): landscapes, people, objects."""
    imgs = []
    for seed in range(1, n + 1):
        url = f"https://picsum.photos/seed/cal{seed}/512/512"
        try:
            r = requests.get(url, timeout=30)
            if r.status_code == 200 and r.content:
                imgs.append(Image.open(io.BytesIO(r.content)))
        except Exception as e:
            print(f"  skip seed {seed}: {e}", file=sys.stderr)
    return imgs


def margins_for(images, text):
    sens = [text.encode(p) for p in SENSITIVE_PROMPTS]
    safe = [text.encode(p) for p in SAFE_PROMPTS]
    sens = np.stack(sens)  # [S,512]
    safe = np.stack(safe)  # [K,512]
    out = []
    for img in images:
        emb = IMAGE_ENC.encode(img)  # [512]
        if np.isnan(emb).any():
            continue
        s = float((sens @ emb).max())
        k = float((safe @ emb).max())
        out.append((s, k, s - k))
    return np.array(out)  # [N,3] -> sensitiveScore, safeScore, margin


def report(margins):
    m = margins[:, 2]
    print(f"\nSafe images evaluated: {len(m)}")
    print("Margin (sensitiveScore - safeScore) over SAFE photos — should be low/negative:")
    for p in (50, 75, 90, 95, 99, 100):
        print(f"  p{p:<3} = {np.percentile(m, p):+.4f}")
    print(f"  mean = {m.mean():+.4f}   max = {m.max():+.4f}")

    print("\nFalse-positive rate (fraction of SAFE photos flagged) by candidate margin:")
    print("  margin | safe-flagged")
    for thr in (0.00, 0.01, 0.02, 0.03, 0.04, 0.05, 0.06, 0.08, 0.10):
        fp = float((m >= thr).mean())
        print(f"   {thr:>5.2f} | {fp*100:5.1f}%")

    # Recommend the smallest 0.01-step margin keeping safe FP <= 1%.
    rec = None
    for thr in [i / 100 for i in range(0, 21)]:
        if float((margins[:, 2] >= thr).mean()) <= 0.01:
            rec = thr
            break
    print(f"\nRecommended SENSITIVE_MARGIN (safe FP <= 1%): {rec:+.2f}" if rec is not None
          else "\nCould not reach <=1% FP within 0.20; prompts likely need work.")
    if rec is not None:
        worst = margins[np.argsort(-margins[:, 2])[:5], 2]
        print(f"  (worst safe margins: {', '.join(f'{x:+.3f}' for x in worst)})")


def recall_eval(pos_dir, neg_dir, text, image):
    """Maintainer-only: measure precision/recall/F1 over a margin sweep using YOUR labeled folders.
    pos_dir = images that SHOULD be blurred, neg_dir = images that should NOT. No images are shipped
    or downloaded here — you supply them locally."""
    import os
    from PIL import Image as PImage
    sens = np.stack([text.encode(p) for p in SENSITIVE_PROMPTS])
    safe = np.stack([text.encode(p) for p in SAFE_PROMPTS])

    def margins(folder):
        vals = []
        for name in os.listdir(folder):
            try:
                emb = image.encode(PImage.open(os.path.join(folder, name)))
                if np.isnan(emb).any():
                    continue
                vals.append(float((sens @ emb).max() - (safe @ emb).max()))
            except Exception:
                pass
        return np.array(vals)

    pos, neg = margins(pos_dir), margins(neg_dir)
    print(f"positives={len(pos)} negatives={len(neg)}")
    print("  margin | precision | recall |  F1")
    for thr in [i / 100 for i in range(0, 16)]:
        tp = float((pos >= thr).sum()); fp = float((neg >= thr).sum())
        fn = float((pos < thr).sum())
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
        print(f"   {thr:>5.2f} |   {prec:5.2f}   |  {rec:5.2f} | {f1:5.2f}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=120)
    ap.add_argument("--pos", help="folder of images that SHOULD be blurred (maintainer-supplied)")
    ap.add_argument("--neg", help="folder of images that should NOT be blurred")
    args = ap.parse_args()

    print("Loading MobileCLIP-S2 ONNX encoders…")
    TEXT_ENC, IMAGE_ENC = c.load_all()

    if args.pos and args.neg:
        recall_eval(args.pos, args.neg, TEXT_ENC, IMAGE_ENC)
        sys.exit(0)

    # Sanity: a landscape query should beat an unrelated one on a landscape-ish photo set.
    probe = TEXT_ENC.encode("a landscape")
    dog = TEXT_ENC.encode("a dog")
    print(f"(sanity) text-text cos landscape·dog = {float(probe @ dog):+.3f}")

    print(f"Fetching {args.n} safe public photos…")
    images = fetch_safe_images(args.n)
    if not images:
        print("No images fetched (network?).", file=sys.stderr)
        sys.exit(1)

    margins = margins_for(images, TEXT_ENC)
    report(margins)
