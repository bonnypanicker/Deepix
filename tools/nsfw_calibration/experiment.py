"""
Iterates prompt sets + scoring methods against cached SAFE image embeddings to find a config that
pushes normal photos to clearly-negative scores (wide safe margin). Content-safe: safe images only.

First run downloads + encodes safe photos and caches embeddings to safe_embeddings.npy.
"""
import io
import os
import numpy as np
import requests
from PIL import Image
import clip_runtime as c

CACHE = os.path.join(os.path.dirname(__file__), "safe_embeddings.npy")
N = 200


def build_cache(image_enc):
    embs = []
    for seed in range(1, N + 1):
        try:
            r = requests.get(f"https://picsum.photos/seed/cal{seed}/512/512", timeout=30)
            if r.status_code != 200 or not r.content:
                continue
            e = image_enc.encode(Image.open(io.BytesIO(r.content)))
            if not np.isnan(e).any():
                embs.append(e)
        except Exception:
            pass
    arr = np.stack(embs).astype(np.float32)
    np.save(CACHE, arr)
    return arr


def load_cache(image_enc):
    if os.path.exists(CACHE):
        return np.load(CACHE)
    return build_cache(image_enc)


# ---- prompt-set variants ----
VARIANTS = {
    "current(max-max)": (
        ["a naked person", "a nude body", "explicit sexual content", "nudity", "pornography", "sexual activity"],
        ["a person wearing clothes", "a landscape", "an everyday scene", "food on a plate",
         "a document or screenshot", "an animal", "a building or street"],
        "maxmax",
    ),
    "sharp(max-max)": (
        ["explicit nude sexual content", "pornography", "an uncensored naked body having sex"],
        ["a normal safe photo", "an ordinary everyday photograph", "a regular picture"],
        "maxmax",
    ),
    "sharp(mean-mean)": (
        ["explicit nude sexual content", "pornography", "an uncensored naked body having sex"],
        ["a normal safe photo", "an ordinary everyday photograph", "a regular picture"],
        "meanmean",
    ),
    "rich-safe(max-max)": (
        ["explicit sexual content", "pornography", "a nude person", "genitalia", "sexual intercourse"],
        ["a photo of people", "a portrait", "clothing and fashion", "a landscape", "food",
         "an animal", "a vehicle", "architecture", "a document", "a screenshot", "a plant",
         "a party or event", "sports", "a selfie", "nature"],
        "maxmax",
    ),
    "softmax(t=100)": (
        ["explicit nude sexual content", "pornography", "a naked body"],
        ["a normal safe photo", "an ordinary everyday photograph", "a regular picture"],
        "softmax100",
    ),
}


def score(img_embs, sens, safe, method):
    S = np.stack(sens); K = np.stack(safe)
    s = (img_embs @ S.T)  # [N,|S|]
    k = (img_embs @ K.T)  # [N,|K|]
    if method == "maxmax":
        return s.max(1) - k.max(1)
    if method == "meanmean":
        return s.mean(1) - k.mean(1)
    if method == "softmax100":
        # P(sensitive) via temperature-scaled softmax over max-of-each-group logits
        smax, kmax = s.max(1), k.max(1)
        z = np.stack([smax, kmax], 1) * 100.0
        z -= z.max(1, keepdims=True)
        p = np.exp(z); p /= p.sum(1, keepdims=True)
        return p[:, 0] - 0.5  # >0 means sensitive wins
    raise ValueError(method)


def main():
    text, image = c.load_all()
    embs = load_cache(image)
    print(f"Safe embeddings: {embs.shape}\n")
    for name, (sp, kp, method) in VARIANTS.items():
        sens = [text.encode(p) for p in sp]
        safe = [text.encode(p) for p in kp]
        m = score(embs, sens, safe, method)
        pct = {p: float(np.percentile(m, p)) for p in (50, 90, 95, 99, 100)}
        print(f"{name}")
        print(f"  safe-score p50={pct[50]:+.4f} p90={pct[90]:+.4f} p95={pct[95]:+.4f} "
              f"p99={pct[99]:+.4f} max={pct[100]:+.4f}")
        # FP at a few thresholds
        fps = {thr: float((m >= thr).mean()) for thr in (0.0, 0.02, 0.05)}
        print(f"  FP@0.00={fps[0.0]*100:.1f}%  FP@0.02={fps[0.02]*100:.1f}%  FP@0.05={fps[0.05]*100:.1f}%\n")


if __name__ == "__main__":
    main()
