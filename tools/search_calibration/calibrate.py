"""
Calibrate the app's search cutoff against a labelled photo set.

The app used to keep a photo when both of these held:

    score >= best_score * MaxScoreDropRatio      (0.75, the "relative" cutoff)
    score >= ScoreThreshold                      (0.19, the absolute floor)

where `best_score` is the highest score the query reaches anywhere in the library. It now ships the
absolute floor alone, at 0.175, because this measurement showed the relative term to be the thing
that emptied real queries: one strong photo raised the bar for its whole query.

The sweep still covers both terms. The ratio is what was measured and rejected, and keeping it in
the grid is what lets `results.json` carry the pre-change setting as its baseline row.

Nothing else filters, so the floor is the whole rule: `DefaultTopK` is `Int.MAX_VALUE` and
`FallbackCount` is 0.

This script measures that rule, and the alternatives for it, on photos whose subject is known:
positives for a query are the photos filed under that subject, every other subject is a negative.
It reports recall (how much of the subject is found), false-positive rate (how much unrelated stuff
surfaces), precision and F1, and sweeps

    T      in 0.10 .. 0.30, step 0.005
    ratio  in 0.00 (relative rule off) .. 0.95, step 0.05

so the recommendation is a pair, not a guess. `r = 0` is the flat absolute threshold a photo app
with no "best match" anchor would use.

Every number comes from `app_clip.AppClip`, which is the app's own preprocessing and tokenizer, not
a reimplementation of a similar model. `--selftest` proves that before any calibration runs, and it
runs automatically first: a wrong pipeline produces confident nonsense.

Usage:
    python tools/search_calibration/fetch_set.py        # once, downloads the photos
    python tools/search_calibration/calibrate.py        # sweep + report + results.json
    python tools/search_calibration/calibrate.py --selftest
    python tools/search_calibration/calibrate.py --inspect "blue shirt"
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys

import numpy as np

from app_clip import CONTEXT_LENGTH, AppClip, query_variants, with_photo_prefix

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(HERE, "manifest.json")
RESULTS = os.path.join(HERE, "results.json")
CACHE = os.path.join(HERE, "embeddings.npz")

# A photo in these categories is not evidence against the query: it is a correct hit for it too, so
# counting it as a false positive would punish the model for being right.
COMPATIBLE = {
    "blue-shirts": {"blue-shirt-people"},
    "portraits": {"blue-shirt-people"},
    "blue-shirt-people": {"blue-shirts", "portraits"},
}

THRESHOLDS = np.round(np.arange(0.10, 0.3001, 0.005), 4)
RATIOS = np.round(np.arange(0.0, 0.9501, 0.05), 2)

PREVIOUS = (0.19, 0.75)         # what the app shipped before this calibration
ENTE_FLAT = (0.175, 0.0)        # flat absolute cutoff, no relative anchor


# --------------------------------------------------------------------------- library


def load_photos() -> list[dict]:
    """Read manifest.json and verify every file is still the bytes that were downloaded."""
    if not os.path.exists(MANIFEST):
        raise SystemExit(f"missing {MANIFEST}; run fetch_set.py first")
    with open(MANIFEST, "r", encoding="utf-8") as handle:
        manifest = json.load(handle)

    photos, broken = [], []
    for entry in manifest.get("photos", []):
        path = os.path.join(HERE, entry["path"])
        if not os.path.exists(path):
            broken.append((entry["path"], "missing"))
            continue
        with open(path, "rb") as handle:
            data = handle.read()
        digest = hashlib.sha256(data).hexdigest()
        if digest != entry["sha256"]:
            broken.append((entry["path"], "sha256 mismatch"))
            continue
        photos.append({**entry, "abs": path})

    if broken:
        # Loud, not silent: a truncated download would quietly skew every score.
        print(f"[warn] {len(broken)} file(s) failed verification and are excluded:", flush=True)
        for path, reason in broken[:10]:
            print(f"       {path}: {reason}", flush=True)
    if not photos:
        raise SystemExit("no verified photos; re-run fetch_set.py --refresh")
    return photos


def _model_key() -> str:
    """Bust the embedding cache when the shipped models change."""
    stamps = []
    for name in ("s1.ort", "s2.ort", "tokenizer.json"):
        path = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", name)
        stat = os.stat(path)
        stamps.append(f"{name}:{stat.st_size}:{int(stat.st_mtime)}")
    return "|".join(stamps)


def encode_library(clip: AppClip, photos: list[dict]) -> np.ndarray:
    """Encode each photo once, reusing cached vectors for unchanged files."""
    cached: dict[str, np.ndarray] = {}
    if os.path.exists(CACHE):
        try:
            with np.load(CACHE) as data:
                if str(data["model_key"]) == _model_key():
                    cached = {str(k): v for k, v in zip(data["sha256"], data["vectors"])}
        except Exception as error:  # a corrupt cache is a cache miss, not a failure
            print(f"[warn] ignoring embedding cache: {error}", flush=True)

    missing = [photo for photo in photos if photo["sha256"] not in cached]
    print(f"[info] {len(photos) - len(missing)} cached, {len(missing)} to encode", flush=True)
    if missing:
        fresh = clip.encode_images([photo["abs"] for photo in missing], batch=4)
        for photo, vector in zip(missing, fresh):
            cached[photo["sha256"]] = vector
        print(f"[info] encoded {len(missing)}", flush=True)

    stacked = np.stack([cached[photo["sha256"]] for photo in photos])
    np.savez_compressed(
        CACHE,
        model_key=np.array(_model_key()),
        sha256=np.array([photo["sha256"] for photo in photos]),
        vectors=stacked,
    )
    return stacked


# --------------------------------------------------------------------------- metrics


def scores_by_slug(clip: AppClip, queries: dict[str, str], vectors: np.ndarray) -> dict[str, np.ndarray]:
    return {slug: clip.scores(query, vectors) for slug, query in queries.items()}


def evaluate(scores: dict[str, np.ndarray], row_slug: np.ndarray, threshold: float, ratio: float) -> dict:
    """Per-query outcome of the app's rule at one (threshold, ratio)."""
    out = {}
    for slug, values in scores.items():
        positive = row_slug == slug
        negative = ~positive & ~np.isin(row_slug, list(COMPATIBLE.get(slug, ())))
        best = float(values.max())
        floor = max(threshold, ratio * best) if ratio > 0 else threshold
        kept = values >= floor

        tp = int((kept & positive).sum())
        fp = int((kept & negative).sum())
        positives = int(positive.sum())
        negatives = int(negative.sum())
        recall = tp / positives if positives else 0.0
        fpr = fp / negatives if negatives else 0.0
        precision = tp / (tp + fp) if tp + fp else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        out[slug] = {
            "best": round(best, 4),
            "floor": round(floor, 4),
            "positives": positives,
            "kept_positives": tp,
            "kept_negatives": fp,
            "negatives": negatives,
            "recall": round(recall, 4),
            "fpr": round(fpr, 4),
            "precision": round(precision, 4),
            "f1": round(f1, 4),
        }
    return out


def macro(per_query: dict[str, dict], metric_slugs: list[str]) -> dict:
    rows = [per_query[slug] for slug in metric_slugs]
    return {
        key: round(float(np.mean([row[key] for row in rows])), 4)
        for key in ("recall", "fpr", "precision", "f1")
    }


# --------------------------------------------------------------------------- selftest


def selftest(clip: AppClip) -> bool:
    """Prove the pipeline is the app's, before trusting any number it produces."""
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "") -> None:
        checks.append((name, bool(condition), detail))

    ids_plain, mask_plain = clip.tokenizer.encode("dog")
    ids_prefixed, _ = clip.tokenizer.encode(with_photo_prefix("dog"))
    check(
        "tokenizer adds the caption prefix exactly once",
        np.array_equal(ids_plain, ids_prefixed),
        f"len {int(mask_plain.sum())}",
    )
    check("token ids are 77 wide", ids_plain.shape == (CONTEXT_LENGTH,), str(ids_plain.shape))

    variants = query_variants("blue shirt person")
    check(
        "query variants are distinct and all prefixed",
        len(variants) == len(set(variants)) and all(v.startswith("a photo of ") for v in variants),
        " | ".join(variants),
    )

    text_dog = clip.encode_text("dog")
    check("text embedding is unit length", abs(float(np.linalg.norm(text_dog)) - 1.0) < 1e-5,
          f"{float(np.linalg.norm(text_dog)):.6f}")
    check("text embedding is 512 wide", text_dog.shape == (512,), str(text_dog.shape))

    try:
        photos = load_photos()
    except SystemExit:
        photos = []
    if photos:
        subset = photos[:6]
        first = clip.encode_images([p["abs"] for p in subset])
        second = clip.encode_images([p["abs"] for p in subset])
        check("image embedding is unit length", np.allclose(np.linalg.norm(first, axis=1), 1.0, atol=1e-5),
              f"{np.linalg.norm(first, axis=1).min():.6f}..{np.linalg.norm(first, axis=1).max():.6f}")
        check("encoding is deterministic", np.array_equal(first, second))

        # The one check a wrong preprocessing pipeline cannot pass: with CLIP mean/std applied to
        # 0..1 pixels the embeddings collapse and pos/neg scores interleave.
        dog_scores = clip.scores("dog", clip.encode_images([p["abs"] for p in photos]))
        slug = np.array([p["slug"] for p in photos])
        positive, negative = dog_scores[slug == "dogs"], dog_scores[slug != "dogs"]
        if positive.size >= 3 and negative.size >= 3:
            separation = float(positive.mean() - negative.mean())
            check("dog photos outscore non-dogs on average", separation > 0.02, f"margin {separation:+.3f}")
    else:
        print("[warn] no photos yet: skipping the image checks", flush=True)

    failed = [name for name, ok, _ in checks if not ok]
    for name, ok, detail in checks:
        mark = "PASS" if ok else "FAIL"
        print(f"[{mark}] {name}" + (f"  ({detail})" if detail else ""), flush=True)
    print(f"[info] selftest {len(checks) - len(failed)}/{len(checks)} passed", flush=True)
    return not failed


# --------------------------------------------------------------------------- report


def print_distribution(name: str, per_query: dict[str, dict], report: dict) -> None:
    print(f"\n{name}")
    print(f"  {'query':<22}{'n':>4}{'best':>8}{'bestNeg':>9}{'kept':>7}{'recall':>8}{'fpr':>8}{'prec':>7}{'F1':>7}")
    for slug, row in per_query.items():
        diagnostic = bool(report.get(slug, {}).get("diagnostic"))
        tag = " (diag)" if diagnostic else ""
        print(
            f"  {row['query']:<22}{row['positives']:>4}{row['best']:>8.3f}{row['best_negative']:>9.3f}"
            f"{row['kept_positives']:>7}{row['recall']:>8.2f}{row['fpr']:>8.2f}"
            f"{row['precision']:>7.2f}{row['f1']:>7.2f}{tag}"
        )


def annotate(per_query: dict[str, dict], queries: dict[str, str], scores: dict[str, np.ndarray],
             row_slug: np.ndarray) -> dict[str, dict]:
    for slug, row in per_query.items():
        positive = row_slug == slug
        negative = ~positive & ~np.isin(row_slug, list(COMPATIBLE.get(slug, ())))
        row["query"] = queries[slug]
        row["best_negative"] = float(scores[slug][negative].max()) if negative.any() else float("nan")
        row["median_positive"] = float(np.median(scores[slug][positive])) if positive.any() else float("nan")
    return per_query


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--selftest", action="store_true", help="run the pipeline checks and exit")
    parser.add_argument("--inspect", metavar="QUERY", help="print every photo's score for one query")
    args = parser.parse_args()

    clip = AppClip()
    print(f"[info] embedding dim {clip.embedding_dim}", flush=True)
    if not selftest(clip):
        print("[abort] the pipeline is not the app's; numbers from it would be wrong", flush=True)
        return 2
    if args.selftest:
        return 0

    photos = load_photos()
    vectors = encode_library(clip, photos)
    row_slug = np.array([photo["slug"] for photo in photos])
    paths = [photo["path"] for photo in photos]

    # One query per category; order follows the manifest so runs are reproducible.
    queries: dict[str, str] = {}
    diagnostic: dict[str, bool] = {}
    for photo in photos:
        queries.setdefault(photo["slug"], photo["query"])
        diagnostic[photo["slug"]] = bool(photo["diagnostic"])

    scores = scores_by_slug(clip, queries, vectors)

    if args.inspect:
        wanted = args.inspect.strip().lower()
        slug = next((key for key, text in queries.items() if text.lower() == wanted), None)
        if slug is None:
            raise SystemExit(f"no query {args.inspect!r}; known: {sorted(queries.values())}")
        order = np.argsort(-scores[slug])
        print(f"\n{queries[slug]!r}: {int((row_slug == slug).sum())} positives, best {scores[slug].max():.4f}")
        for index in order:
            mark = "POS" if row_slug[index] == slug else "   "
            print(f"  {scores[slug][index]:.4f} {mark} {paths[index]}")
        return 0

    metric_slugs = [slug for slug in queries if not diagnostic[slug] and (row_slug == slug).sum() >= 8]
    diagnostic_slugs = [slug for slug in queries if diagnostic[slug]]
    dropped = [slug for slug in queries if slug not in metric_slugs and slug not in diagnostic_slugs]
    print(f"[info] {len(vectors)} photos, {len(metric_slugs)} scored queries, "
          f"{len(diagnostic_slugs)} diagnostic, {len(dropped)} too small", flush=True)

    # Sweep -------------------------------------------------------------------
    sweep = []
    for threshold in THRESHOLDS:
        for ratio in RATIOS:
            per_query = evaluate(scores, row_slug, float(threshold), float(ratio))
            overall = macro(per_query, metric_slugs)
            sweep.append({"threshold": float(threshold), "ratio": float(ratio), **overall})

    feasible = [row for row in sweep if row["fpr"] <= 0.05]

    # A recall-weighted score is what a gallery search wants: a missed photo is invisible, an extra
    # photo is one row the user scrolls past.
    def f_beta(row: dict, beta: float) -> float:
        p, r = row["precision"], row["recall"]
        if not p and not r:
            return 0.0
        return (1 + beta ** 2) * p * r / (beta ** 2 * p + r) if (beta ** 2 * p + r) else 0.0

    best_f1 = max(sweep, key=lambda row: (row["f1"], row["recall"]))
    bounded_f1 = max(feasible, key=lambda row: (row["f1"], row["recall"])) if feasible else None
    bounded_recall = max(feasible, key=lambda row: (row["recall"], row["f1"])) if feasible else None
    bounded_f2 = max(feasible, key=lambda row: (f_beta(row, 2.0), row["recall"])) if feasible else None
    # The recommendation is the balanced point: best F1 among settings that keep unrelated photos
    # under 5%. If nothing meets that, fall back to the best F1 anywhere.
    recommendation = bounded_f1 or best_f1

    candidates = {
        "macro-F1 best with FPR <= 5% (recommended)": bounded_f1,
        "macro-F2 best with FPR <= 5% (favours recall)": bounded_f2,
        "macro-recall best with FPR <= 5%": bounded_recall,
        "macro-F1 best overall": best_f1,
    }

    print("\nTop 12 settings by macro F1")
    print(f"  {'T':>6}{'ratio':>7}{'recall':>8}{'fpr':>8}{'prec':>7}{'F1':>7}{'F2':>7}")
    for row in sorted(sweep, key=lambda item: -item["f1"])[:12]:
        print(f"  {row['threshold']:>6.3f}{row['ratio']:>7.2f}{row['recall']:>8.2f}"
              f"{row['fpr']:>8.2f}{row['precision']:>7.2f}{row['f1']:>7.2f}{f_beta(row, 2):>7.2f}")

    print("\nNamed settings")
    print(f"  {'setting':<44}{'T':>6}{'ratio':>7}{'recall':>8}{'fpr':>8}{'prec':>7}{'F1':>7}")
    named = {
        "app before this change": PREVIOUS,
        "flat 0.175 (no relative cut)": ENTE_FLAT,
        "flat 0.19 (relative cut off)": (0.19, 0.0),
        "flat 0.16 (relative cut off)": (0.16, 0.0),
    }
    for label, (threshold, ratio) in named.items():
        row = next(item for item in sweep
                   if abs(item["threshold"] - threshold) < 1e-6 and abs(item["ratio"] - ratio) < 1e-6)
        print(f"  {label:<44}{row['threshold']:>6.3f}{row['ratio']:>7.2f}{row['recall']:>8.2f}"
              f"{row['fpr']:>8.2f}{row['precision']:>7.2f}{row['f1']:>7.2f}")

    print("\nCandidates")
    for label, row in candidates.items():
        if row is None:
            print(f"  {label}: nothing meets the constraint")
            continue
        print(f"  {label}: T={row['threshold']:.3f} ratio={row['ratio']:.2f} "
              f"recall={row['recall']:.2f} fpr={row['fpr']:.2f} F1={row['f1']:.2f}")
    chosen = (float(recommendation["threshold"]), float(recommendation["ratio"]))

    # Per-query detail for the previous setting, the shipped value and the alternatives.
    details = {}
    for label, (threshold, ratio) in {**named, "recommended": chosen}.items():
        per_query = evaluate(scores, row_slug, float(threshold), float(ratio))
        per_query = annotate(per_query, queries, scores, row_slug)
        details[label] = per_query
        print_distribution(f"{label}  (T={threshold:.3f}, ratio={ratio:.2f})", per_query,
                           {slug: {"diagnostic": diagnostic[slug]} for slug in queries})

    for slug in diagnostic_slugs:
        row = details["recommended"][slug]
        print(f"\n[diagnostic] '{row['query']}' ({slug}): {row['kept_positives']}/{row['positives']} "
              f"labelled photos survive (recall {row['recall']:.2f}); its own labels are too thin to "
              f"score false positives.")

    payload = {
        "photos": len(vectors),
        "scored_queries": metric_slugs,
        "diagnostic_queries": diagnostic_slugs,
        "previous_setting": {"threshold": PREVIOUS[0], "ratio": PREVIOUS[1]},
        "shipped": {"threshold": chosen[0], "ratio": chosen[1]},
        "candidates": {k: v for k, v in candidates.items() if v},
        "sweep": sweep,
        "details": details,
        "notes": [
            "Positives are Wikimedia category members, so they include paintings, statues and "
            "museum shots that survived the title filter; recall is measured against that noise.",
            "Photos are 640px Commons thumbnails; a phone indexes full-resolution files, so absolute "
            "scores on a real library can sit slightly higher.",
            "FPR counts every other category as a negative; COMPATIBLE drops categories that are "
            "also correct answers (blue shirts vs people wearing blue shirts).",
        ],
    }
    with open(RESULTS, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2)
    print(f"\n[info] wrote {os.path.relpath(RESULTS, HERE)}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
