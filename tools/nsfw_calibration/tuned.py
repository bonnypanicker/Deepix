"""
Final tuning pass: expanded, gallery-representative SAFE prompt set vs a focused sensitive set,
max-vs-max scoring. Prints the FP table over safe photos and the recommended margin, plus the
exact Kotlin lists to paste into NsfwClassifier.

Content-safe: evaluates against safe images only (cached). Recall must be validated separately.
"""
import numpy as np
import clip_runtime as c
from experiment import load_cache

SENSITIVE = [
    "explicit nude sexual content",
    "pornography",
    "a naked body",
    "sexual intercourse",
    "exposed genitalia",
]

SAFE = [
    "a person wearing clothes", "a portrait of a clothed person", "a group of people",
    "a selfie", "a child", "a family photo", "a wedding", "a party or celebration",
    "a landscape", "nature", "a mountain", "the sky", "a beach", "a sunset",
    "food on a plate", "a drink", "an animal", "a pet dog or cat", "a bird",
    "a flower", "a plant", "a tree", "a car or vehicle", "a building", "a street",
    "a room interior", "furniture", "a document", "a screenshot", "text on a screen",
    "a poster or artwork", "clothing and fashion", "shoes", "sports", "a concert",
    "a toy", "electronics or a gadget", "a book", "a map",
]


def fp_table(m):
    print("  margin | safe-flagged")
    for thr in (0.02, 0.04, 0.05, 0.06, 0.07, 0.08, 0.10, 0.12):
        print(f"   {thr:>5.2f} | {float((m>=thr).mean())*100:5.2f}%")


def recommend(m, target):
    for thr in [i / 200 for i in range(0, 41)]:  # 0..0.20 step 0.005
        if float((m >= thr).mean()) <= target:
            return thr
    return None


def main():
    text, image = c.load_all()
    embs = load_cache(image)
    sens = np.stack([text.encode(p) for p in SENSITIVE])
    safe = np.stack([text.encode(p) for p in SAFE])
    m = (embs @ sens.T).max(1) - (embs @ safe.T).max(1)

    print(f"Safe photos: {len(m)}")
    for p in (50, 90, 95, 99, 100):
        print(f"  p{p:<3}={np.percentile(m,p):+.4f}", end="")
    print("\n")
    fp_table(m)

    r1 = recommend(m, 0.01)
    r05 = recommend(m, 0.005)
    r0 = recommend(m, 0.0)
    print(f"\nRecommended margin  ~1% FP: {r1:+.3f}" if r1 else "\n~1% unreachable")
    print(f"Recommended margin ~0.5% FP: {r05:+.3f}" if r05 else "~0.5% unreachable")
    print(f"Recommended margin   ~0% FP: {r0:+.3f}" if r0 is not None else "~0% unreachable")

    print("\n--- paste into NsfwClassifier.kt ---")
    print("sensitivePrompts:", SENSITIVE)
    print("safePrompts:", SAFE)
    print(f"SENSITIVE_MARGIN ~ {r05 if r05 else r1}")


if __name__ == "__main__":
    main()
