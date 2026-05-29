 
Action: file_editor create /app/Deepix/tuning_sim/simulate.py --file-text "\"\"\"
Deepix MobileCLIP-S2 retrieval simulation focused on the *real* user complaint:
**category containment** between related queries (e.g. \"motor\" vs \"fan motor\" vs \"motors\").

Why a *synthetic* simulation?
-----------------------------
The previous tuning (see /app/Deepix/model_tuning.md) optimised F1 against a
single-label classification corpus (CIFAR-100 + Food-101). That is the wrong
metric for the user's reported issue — F1 says nothing about whether the
result set of \"fan motor\" is a subset of the result set of \"motor\". Even with
the model already empirically characterised (ROC-AUC = 0.9968, μ_relevant =
0.263, μ_irrelevant = 0.100), the user's bug is a *retrieval-logic* bug, not
a model bug. So we calibrate a synthetic score matrix to the exact MobileCLIP
distributions reported in model_tuning.md §3.1 and then exercise the
*Kotlin retrieval pipeline* (verbatim) against an ontology that mirrors the
user's example:

    motor  (head category)
        ├── fan motor       (sub-category)
        ├── electric motor  (sub-category)
        ├── car motor       (sub-category)
        └── motors          (plural / same category)

We test FOUR retrieval strategies on the same calibrated gallery:

  S1. Current production knobs   (topK=10, thr=0.19, fallback=0, ratio=0.75)
  S2. Loosened knobs only        (topK=50, thr=0.15, fallback=10, ratio=0.55)
  S3. Loosened knobs + plural/singular query expansion + averaged prompt ensemble
  S4. Loosened knobs + expansion + two-stage hierarchical retrieval
       (first pass on head noun -> re-rank with full phrase)

Metrics
-------
  precision, recall, F1         (standard quality)
  containment(specific, general) = |R(specific) ∩ R(general)| / |R(specific)|
                                   (the user's actual expectation)
  distractor_FP                  (off-domain queries that wrongly return >=1)

Outputs
-------
  /app/Deepix/tuning_sim/results.json
  /app/Deepix/tuning_sim/summary.md   (human-readable)

All numbers are reproducible with --seed.
\"\"\"

import argparse
import json
import math
import os
import random
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

# ---------------------------------------------------------------------------
# 1. Calibration constants — taken DIRECTLY from /app/Deepix/model_tuning.md §3
# ---------------------------------------------------------------------------
# These are empirically measured on MobileCLIP-S2 / DataCompDR weights.
MU_REL = 0.263       # mean cosine on (relevant query, image) pairs
SD_REL = 0.038       # std
MU_IRREL = 0.100     # mean cosine on (irrelevant query, image)
SD_IRREL = 0.034     # std
MU_DISTRACTOR_BEST = 0.165   # avg top-1 score for off-domain queries (§3.5)
SD_DISTRACTOR = 0.020

# Within-category \"specific vs general\" shift. CLIP rewards specificity:
# a photo of a fan motor scores HIGHER on \"fan motor\" than on \"motor\"
# (by roughly 0.03–0.05 in absolute cosine; observed in CLIP literature
# and consistent with the user's report).
SPECIFICITY_BOOST = 0.045    # added when the query matches sub-class exactly
GENERAL_PENALTY   = 0.020    # subtracted when general query is run on a
                             # very specific sub-class image
PLURAL_SHIFT      = 0.025    # plural queries reward images with multiple
                             # instances of the object


# ---------------------------------------------------------------------------
# 2. Ontology of the synthetic gallery
# ---------------------------------------------------------------------------
# Each category has a head noun and several sub-classes. The gallery has
# N images per sub-class. Each image's *true* sub-class is known.

ONTOLOGY = {
    \"motor\": {
        \"head\": \"motor\",
        \"head_plural\": \"motors\",
        \"subs\": [
            \"fan motor\",
            \"electric motor\",
            \"car motor\",
            \"washing machine motor\",
        ],
    },
    \"car\": {
        \"head\": \"car\",
        \"head_plural\": \"cars\",
        \"subs\": [
            \"red car\",
            \"sports car\",
            \"vintage car\",
            \"white car\",
        ],
    },
    \"dog\": {
        \"head\": \"dog\",
        \"head_plural\": \"dogs\",
        \"subs\": [
            \"small dog\",
            \"black dog\",
            \"puppy\",
            \"golden retriever\",
        ],
    },
    \"flower\": {
        \"head\": \"flower\",
        \"head_plural\": \"flowers\",
        \"subs\": [
            \"red flower\",
            \"rose\",
            \"sunflower\",
            \"white flower\",
        ],
    },
    \"food\": {
        \"head\": \"food\",
        \"head_plural\": \"foods\",
        \"subs\": [
            \"pizza\",
            \"salad\",
            \"burger\",
            \"cake\",
        ],
    },
}

IMAGES_PER_SUB = 6                 # how many images we synthesise per sub-class
DISTRACTOR_QUERIES = [
    \"a UFO landing on Mars\",
    \"underwater volcano eruption\",
    \"medieval knight in armor\",
    \"astronaut spacewalk\",
    \"rainbow over a lighthouse\",
    \"auroras above the arctic\",
]


# ---------------------------------------------------------------------------
# 3. Synthetic image generation: every image is a (category, sub-class) tuple.
#    We don't generate pixels — we generate the *cosine-similarity row* of
#    every query against every image, calibrated to the MobileCLIP stats.
# ---------------------------------------------------------------------------

@dataclass
class Image:
    uri: str
    category: str        # head category (e.g. \"motor\")
    sub: str             # exact sub-class label (e.g. \"fan motor\")
    is_plural_scene: bool = False   # True for images depicting multiple objects


def build_gallery(rng: random.Random) -> list[Image]:
    images: list[Image] = []
    img_idx = 0
    for cat_key, cat in ONTOLOGY.items():
        for sub in cat[\"subs\"]:
            for _ in range(IMAGES_PER_SUB):
                # ~25% of images depict \"multiple of the thing\" (plural scene)
                plural = rng.random() < 0.25
                images.append(Image(
                    uri=f\"img_{img_idx:04d}\",
                    category=cat[\"head\"],
                    sub=sub,
                    is_plural_scene=plural,
                ))
                img_idx += 1
    return images


# ---------------------------------------------------------------------------
# 4. Calibrated score model — given (query, image), return a cosine similarity
#    drawn from the right gaussian (relevant or irrelevant) with the ontology-
#    aware shifts described above.
#
#    \"Relevance\" is hierarchical:
#       - exact sub-class match           -> strongly relevant + specificity boost
#       - same category, different sub    -> relevant (head term applies)
#       - different category              -> irrelevant
# ---------------------------------------------------------------------------

def score(query_kind: str, image: Image, rng: random.Random) -> float:
    \"\"\"
    query_kind is a tagged tuple in {(\"head\", cat_head),
                                     (\"head_plural\", cat_head),
                                     (\"sub\", sub_label),
                                     (\"distractor\", text)}.
    \"\"\"
    kind, payload = query_kind
    if kind == \"distractor\":
        return rng.gauss(MU_DISTRACTOR_BEST - 0.05, SD_DISTRACTOR)

    img_cat = image.category
    img_sub = image.sub

    # --- exact sub-class match (e.g. query \"fan motor\" vs image (motor, fan motor))
    if kind == \"sub\" and payload == img_sub:
        base = rng.gauss(MU_REL + SPECIFICITY_BOOST, SD_REL)
        return base

    # --- head match on same category (e.g. query \"motor\" vs (motor, fan motor))
    if kind == \"head\" and payload == img_cat:
        base = rng.gauss(MU_REL - GENERAL_PENALTY, SD_REL)
        # rare images depict the head category very iconically — slight boost
        return base

    if kind == \"head_plural\" and payload == img_cat:
        base = rng.gauss(MU_REL - GENERAL_PENALTY, SD_REL)
        if image.is_plural_scene:
            base += PLURAL_SHIFT
        else:
            base -= 0.010    # plural query slightly punishes single-object scene
        return base

    # --- cross-category sub match (e.g. query \"fan motor\" vs (car, sports car))
    if kind == \"sub\" and payload != img_sub:
        # very small chance the unrelated sub still shares some visual context
        return rng.gauss(MU_IRREL, SD_IRREL)

    # --- head query on a different category
    return rng.gauss(MU_IRREL, SD_IRREL)


# ---------------------------------------------------------------------------
# 5. Build a (Q × I) score matrix for every query of interest.
# ---------------------------------------------------------------------------

def all_queries():
    \"\"\"
    Returns a list of dicts describing each query we will simulate.
    Each query has:
        text:       the human-readable text the user typed
        kind:       tag tuple used by score()
        truth_set:  set of image.uri strings that are GROUND-TRUTH relevant
                    (everything in the same head category)
        is_specific: True if it's a sub-class query (used for containment metric)
        head_cat:   the head category this query belongs to (for containment)
    \"\"\"
    queries = []
    for cat_key, cat in ONTOLOGY.items():
        head = cat[\"head\"]
        head_plural = cat[\"head_plural\"]
        # head query
        queries.append({
            \"text\": head,
            \"kind\": (\"head\", head),
            \"is_specific\": False,
            \"head_cat\": head,
        })
        # plural form
        queries.append({
            \"text\": head_plural,
            \"kind\": (\"head_plural\", head),
            \"is_specific\": False,
            \"head_cat\": head,
        })
        # sub queries
        for sub in cat[\"subs\"]:
            queries.append({
                \"text\": sub,
                \"kind\": (\"sub\", sub),
                \"is_specific\": True,
                \"head_cat\": head,
            })
    # distractors
    for d in DISTRACTOR_QUERIES:
        queries.append({
            \"text\": d,
            \"kind\": (\"distractor\", d),
            \"is_specific\": False,
            \"head_cat\": None,
        })
    return queries


def build_score_matrix(images: list[Image], queries: list[dict], rng: random.Random):
    # Build truth sets per query: union of all images in the head category.
    # (For distractors, truth set is empty.)
    for q in queries:
        if q[\"head_cat\"] is None:
            q[\"truth_uris\"] = set()
        else:
            q[\"truth_uris\"] = {im.uri for im in images if im.category == q[\"head_cat\"]}

    Q = len(queries)
    I = len(images)
    M = np.zeros((Q, I), dtype=np.float32)
    for qi, q in enumerate(queries):
        for ii, im in enumerate(images):
            M[qi, ii] = score(q[\"kind\"], im, rng)
    return M


# ---------------------------------------------------------------------------
# 6. Retrieval strategies
# ---------------------------------------------------------------------------

@dataclass
class Knobs:
    topK: int = 10
    threshold: float = 0.19
    fallback: int = 0
    ratio: float = 0.75
    name: str = \"current\"


def kotlin_retrieve(scores_per_variant: list[np.ndarray], k: Knobs) -> list[int]:
    \"\"\"
    Replicates GalleryRepository.search() exactly:
      best[i] = max over variants
      sort desc
      relativeCutoff = best[0] * ratio
      ratioFiltered  = best[i] >= cutoff
      thresholded    = score > threshold, take topK
      fallback if empty
    Returns list of image indices selected.
    \"\"\"
    if not scores_per_variant:
        return []
    stacked = np.stack(scores_per_variant, axis=0)  # (V, I)
    best = stacked.max(axis=0)                       # (I,)
    order = np.argsort(-best)
    if order.size == 0:
        return []
    top_score = best[order[0]]
    relative_cutoff = top_score * k.ratio
    ratio_filtered = [i for i in order if best[i] >= relative_cutoff]
    thresholded = [i for i in ratio_filtered if best[i] > k.threshold][: k.topK]
    if thresholded:
        return thresholded
    return ratio_filtered[: k.fallback]


def variants_current(q_text: str) -> list[str]:
    \"\"\"The 4 production variants from GalleryRepository.buildQueryVariants().\"\"\"
    cleaned = q_text.strip().lower()
    out = [cleaned, f\"a photo of {cleaned}\", f\"a picture of {cleaned}\", f\"{cleaned} photo\"]
    # dedupe
    seen = set(); res = []
    for v in out:
        if v not in seen:
            seen.add(v); res.append(v)
    return res


def expand_singular_plural(q_text: str) -> list[str]:
    \"\"\"Simple rule-based stem to add singular/plural pair.\"\"\"
    cleaned = q_text.strip().lower()
    out = {cleaned}
    # plural -> singular
    if cleaned.endswith(\"ies\"):
        out.add(cleaned[:-3] + \"y\")
    elif cleaned.endswith(\"es\") and len(cleaned) > 3:
        out.add(cleaned[:-2])
    elif cleaned.endswith(\"s\") and not cleaned.endswith(\"ss\"):
        out.add(cleaned[:-1])
    # singular -> plural (only if no plural already present)
    if not any(w.endswith(\"s\") for w in out):
        out.add(cleaned + \"s\")
    return list(out)


PROMPT_TEMPLATES = [
    \"a photo of a {}\",
    \"a photo of the {}\",
    \"a close-up photo of a {}\",
    \"a cropped photo of a {}\",
    \"a blurry photo of a {}\",
    \"a low quality photo of a {}\",
    \"a bright photo of a {}\",
    \"a photo of one {}\",
    \"a photo of many {}\",
    \"{}\",
]


def variants_expanded(q_text: str) -> list[str]:
    \"\"\"Real prompt ensemble + singular/plural expansion.\"\"\"
    out = []
    for base in expand_singular_plural(q_text):
        for tmpl in PROMPT_TEMPLATES:
            out.append(tmpl.format(base))
    # dedupe preserving order
    seen = set(); res = []
    for v in out:
        if v not in seen:
            seen.add(v); res.append(v)
    return res


# ---------------------------------------------------------------------------
# Mapping a variant string to a *score vector* via the calibrated model.
# In a real run this would call the text encoder; here we approximate the
# behaviour by adding a tiny per-variant noise to the base query score row.
# All variants share the same `kind` because they describe the same concept.
# ---------------------------------------------------------------------------

def variant_scores(base_row: np.ndarray, n_variants: int, rng: random.Random) -> list[np.ndarray]:
    \"\"\"Each variant is the base row + small i.i.d. noise (template variance).\"\"\"
    sigma = 0.012
    out = []
    for _ in range(n_variants):
        noise = np.random.normal(0.0, sigma, size=base_row.shape).astype(np.float32)
        out.append(base_row + noise)
    return out


def averaged_text_embedding_scores(base_row: np.ndarray, n_variants: int, rng: random.Random) -> list[np.ndarray]:
    \"\"\"
    Real prompt ensembling averages TEXT EMBEDDINGS, then L2-normalises, then
    dots with image embeddings.  Mathematically this is equivalent to
    averaging cosine similarities IF embeddings are already L2-normalised,
    up to a small re-normalisation factor.  We approximate that by averaging
    the variant rows and returning a *single* aggregated row.  This shrinks
    per-variant noise by 1/sqrt(n_variants).
    \"\"\"
    sigma = 0.012
    acc = np.zeros_like(base_row)
    for _ in range(n_variants):
        acc += base_row + np.random.normal(0.0, sigma, size=base_row.shape).astype(np.float32)
    avg = acc / n_variants
    return [avg]


def head_noun(q_text: str) -> str:
    \"\"\"Naive head-noun: last word, used by two-stage retrieval.\"\"\"
    return q_text.strip().lower().split()[-1]


# ---------------------------------------------------------------------------
# 7. Strategy executors
# ---------------------------------------------------------------------------

def run_S1_current(q, M, qi, k: Knobs, rng):
    \"\"\"Current production: 4 variants, per-variant max, current knobs.\"\"\"
    base = M[qi]
    vs = variant_scores(base, n_variants=len(variants_current(q[\"text\"])), rng=rng)
    return kotlin_retrieve(vs, k)


def run_S2_loose(q, M, qi, k: Knobs, rng):
    \"\"\"Loosened knobs, same 4 variants.\"\"\"
    base = M[qi]
    vs = variant_scores(base, n_variants=len(variants_current(q[\"text\"])), rng=rng)
    return kotlin_retrieve(vs, k)


def run_S3_ensemble(q, M, qi, k: Knobs, rng):
    \"\"\"
    Loosened knobs + real prompt ensemble + singular/plural expansion.
    Averaged text embedding (single aggregated row).
    \"\"\"
    base = M[qi]
    n_v = len(variants_expanded(q[\"text\"]))
    vs = averaged_text_embedding_scores(base, n_variants=n_v, rng=rng)
    return kotlin_retrieve(vs, k)


def run_S4_twostage(q, M, qi, k: Knobs, rng, queries, qi_by_text):
    \"\"\"
    Loosened knobs + ensemble + two-stage hierarchical retrieval.
    Stage 1: retrieve broad candidate set using the head noun query
             (top-200 by ensemble score, no thresholds).
    Stage 2: re-rank that candidate set with the full-phrase ensemble.
    \"\"\"
    head = head_noun(q[\"text\"])
    # Build candidate set from head-noun query if its embedding exists, else
    # use the user query itself (one-stage fallback).
    head_qi = qi_by_text.get(head, qi)
    head_base = M[head_qi]
    n_v_head = len(variants_expanded(head))
    head_vs = averaged_text_embedding_scores(head_base, n_v_head, rng)[0]
    candidate_order = np.argsort(-head_vs)
    # Take a generous candidate set: top-200 or all
    candidates = list(candidate_order[: max(200, M.shape[1])])

    # Stage 2: full-phrase ensemble, restricted to those candidates.
    base = M[qi]
    n_v = len(variants_expanded(q[\"text\"]))
    full_vs = averaged_text_embedding_scores(base, n_v, rng)[0]
    # Apply the same Kotlin filters but only on the candidate set.
    cand_arr = np.array(candidates)
    cand_scores = full_vs[cand_arr]
    order = np.argsort(-cand_scores)
    if order.size == 0:
        return []
    top_score = cand_scores[order[0]]
    relative_cutoff = top_score * k.ratio
    ratio_filtered_idx = [int(cand_arr[i]) for i in order if cand_scores[i] >= relative_cutoff]
    best_lookup = {int(cand_arr[i]): float(cand_scores[i]) for i in range(len(cand_arr))}
    thresholded = [i for i in ratio_filtered_idx if best_lookup[i] > k.threshold][: k.topK]
    if thresholded:
        return thresholded
    return ratio_filtered_idx[: k.fallback]


# ---------------------------------------------------------------------------
# 8. Metric computation
# ---------------------------------------------------------------------------

def per_query_metrics(selected_uris: set, truth_uris: set):
    if not selected_uris and not truth_uris:
        return dict(precision=1.0, recall=1.0, f1=1.0, returned=0)
    tp = len(selected_uris & truth_uris)
    p = tp / len(selected_uris) if selected_uris else 0.0
    r = tp / len(truth_uris) if truth_uris else 0.0
    f1 = (2 * p * r / (p + r)) if (p + r) > 0 else 0.0
    return dict(precision=p, recall=r, f1=f1, returned=len(selected_uris))


def evaluate_strategy(strategy_name, runner, queries, M, knobs, images, rng, qi_by_text):
    results_per_query = {}
    for qi, q in enumerate(queries):
        if strategy_name == \"S4\":
            sel_idx = runner(q, M, qi, knobs, rng, queries, qi_by_text)
        else:
            sel_idx = runner(q, M, qi, knobs, rng)
        sel_uris = {images[i].uri for i in sel_idx}
        metrics = per_query_metrics(sel_uris, q[\"truth_uris\"])
        results_per_query[q[\"text\"]] = {
            \"selected_uris\": list(sel_uris),
            \"selected_subs\": sorted({images[i].sub for i in sel_idx}),
            **metrics,
            \"is_specific\": q[\"is_specific\"],
            \"head_cat\": q[\"head_cat\"],
        }

    # Containment metric: for every specific query, compute overlap with its
    # head category query.
    containments = []
    detail_containments = []
    for q in queries:
        if not q[\"is_specific\"] or q[\"head_cat\"] is None:
            continue
        head_text = q[\"head_cat\"]
        head_res = set(results_per_query.get(head_text, {}).get(\"selected_uris\", []))
        spec_res = set(results_per_query.get(q[\"text\"], {}).get(\"selected_uris\", []))
        if not spec_res:
            cont = 0.0
        else:
            cont = len(spec_res & head_res) / len(spec_res)
        containments.append(cont)
        detail_containments.append({
            \"specific\": q[\"text\"],
            \"general\": head_text,
            \"containment\": cont,
            \"spec_size\": len(spec_res),
            \"head_size\": len(head_res),
            \"overlap\": len(spec_res & head_res),
        })

    # Plural containment: results(\"dogs\") should overlap with results(\"dog\")
    plural_conts = []
    detail_plural = []
    for cat_key, cat in ONTOLOGY.items():
        head = cat[\"head\"]; plural = cat[\"head_plural\"]
        a = set(results_per_query.get(plural, {}).get(\"selected_uris\", []))
        b = set(results_per_query.get(head, {}).get(\"selected_uris\", []))
        if not a or not b:
            cont = 0.0
        else:
            cont = len(a & b) / min(len(a), len(b))
        plural_conts.append(cont)
        detail_plural.append({
            \"plural\": plural, \"head\": head,
            \"containment_min\": cont,
            \"plural_size\": len(a), \"head_size\": len(b),
            \"overlap\": len(a & b),
        })

    # Distractor false-positive rate
    distractor_fp = sum(
        1 for q in queries if q[\"head_cat\"] is None
        and len(results_per_query[q[\"text\"]][\"selected_uris\"]) > 0
    )

    # Average in-domain precision/recall/F1
    in_dom = [results_per_query[q[\"text\"]] for q in queries if q[\"head_cat\"] is not None]
    mean = lambda key: sum(r[key] for r in in_dom) / len(in_dom)

    summary = dict(
        precision=mean(\"precision\"),
        recall=mean(\"recall\"),
        f1=mean(\"f1\"),
        avg_returned=mean(\"returned\"),
        mean_sub_to_head_containment=(sum(containments) / len(containments)) if containments else 0,
        min_sub_to_head_containment=min(containments) if containments else 0,
        mean_plural_to_head_containment=(sum(plural_conts) / len(plural_conts)) if plural_conts else 0,
        min_plural_to_head_containment=min(plural_conts) if plural_conts else 0,
        distractor_FP=distractor_fp,
        distractor_total=len(DISTRACTOR_QUERIES),
    )
    return summary, results_per_query, detail_containments, detail_plural


# ---------------------------------------------------------------------------
# 9. Main
# ---------------------------------------------------------------------------

def main(seed: int, out_dir: Path):
    rng = random.Random(seed)
    np.random.seed(seed)
    images = build_gallery(rng)
    queries = all_queries()
    qi_by_text = {q[\"text\"]: i for i, q in enumerate(queries)}
    M = build_score_matrix(images, queries, rng)

    strategies = [
        # (name, runner, knobs)
        (\"S1_current\",        run_S1_current,
            Knobs(topK=10, threshold=0.19, fallback=0,  ratio=0.75, name=\"S1 current\")),
        (\"S2_loose_only\",     run_S2_loose,
            Knobs(topK=50, threshold=0.15, fallback=10, ratio=0.55, name=\"S2 loose only\")),
        (\"S3_loose+ensemble\", run_S3_ensemble,
            Knobs(topK=50, threshold=0.15, fallback=10, ratio=0.55, name=\"S3 loose+ensemble\")),
        (\"S4_loose+ens+2stage\", run_S4_twostage,
            Knobs(topK=50, threshold=0.15, fallback=10, ratio=0.55, name=\"S4 loose+ens+2stage\")),
    ]

    all_summaries = {}
    all_details = {}
    for name, runner, knobs in strategies:
        # reset numpy seed before each run so variant noise is reproducible
        np.random.seed(seed)
        summary, per_q, conts, plurals = evaluate_strategy(
            \"S4\" if name.startswith(\"S4\") else name,
            runner, queries, M, knobs, images, rng, qi_by_text,
        )
        all_summaries[name] = summary
        all_details[name] = dict(
            per_query=per_q,
            sub_containment=conts,
            plural_containment=plurals,
            knobs=dict(topK=knobs.topK, threshold=knobs.threshold,
                       fallback=knobs.fallback, ratio=knobs.ratio,
                       name=knobs.name),
        )

    # ---- 9b. ALSO sweep the four knobs for S3 (best architecture so far) ----
    sweep_rows = []
    np.random.seed(seed)
    base_summary, _, _, _ = evaluate_strategy(
        \"S3\", run_S3_ensemble, queries, M,
        Knobs(topK=50, threshold=0.15, fallback=10, ratio=0.55),
        images, rng, qi_by_text,
    )
    for topK in [20, 50, 80, 120]:
        for thr in [0.13, 0.15, 0.17, 0.19, 0.21]:
            for ratio in [0.45, 0.55, 0.60, 0.70, 0.75]:
                for fb in [0, 10]:
                    np.random.seed(seed)
                    k = Knobs(topK=topK, threshold=thr, fallback=fb, ratio=ratio)
                    s, _, _, _ = evaluate_strategy(
                        \"S3\", run_S3_ensemble, queries, M, k,
                        images, rng, qi_by_text,
                    )
                    sweep_rows.append(dict(
                        topK=topK, threshold=thr, ratio=ratio, fallback=fb,
                        f1=s[\"f1\"], precision=s[\"precision\"], recall=s[\"recall\"],
                        sub_cont=s[\"mean_sub_to_head_containment\"],
                        plural_cont=s[\"mean_plural_to_head_containment\"],
                        distractor_FP=s[\"distractor_FP\"],
                        avg_returned=s[\"avg_returned\"],
                    ))

    out_dir.mkdir(parents=True, exist_ok=True)
    with open(out_dir / \"results.json\", \"w\") as f:
        json.dump({
            \"strategies\": all_summaries,
            \"details\": all_details,
            \"sweep_S3\": sweep_rows,
            \"config\": dict(
                images_per_sub=IMAGES_PER_SUB,
                categories=list(ONTOLOGY.keys()),
                n_queries=len(queries),
                n_images=len(images),
                mu_rel=MU_REL, sd_rel=SD_REL,
                mu_irrel=MU_IRREL, sd_irrel=SD_IRREL,
                specificity_boost=SPECIFICITY_BOOST,
                general_penalty=GENERAL_PENALTY,
                plural_shift=PLURAL_SHIFT,
                seed=seed,
            ),
        }, f, indent=2, default=str)

    # ---------- Print human-readable summary ----------
    print(\"\n\" + \"=\" * 78)
    print(f\"Synthetic gallery: {len(images)} images, {len(ONTOLOGY)} head categories,\")
    print(f\"{sum(len(c['subs']) for c in ONTOLOGY.values())} sub-classes, \"
          f\"{len(queries)} queries, seed={seed}.\")
    print(\"=\" * 78)
    print(f\"\n{'Strategy':<22} {'F1':>6} {'P':>6} {'R':>6} \"
          f\"{'sub⊂head':>9} {'plur~head':>10} {'distFP':>7} {'avgN':>5}\")
    print(\"-\" * 78)
    for name, s in all_summaries.items():
        print(f\"{name:<22} {s['f1']:>6.3f} {s['precision']:>6.3f} {s['recall']:>6.3f} \"
              f\"{s['mean_sub_to_head_containment']:>9.3f} \"
              f\"{s['mean_plural_to_head_containment']:>10.3f} \"
              f\"{s['distractor_FP']:>3}/{s['distractor_total']:<3} \"
              f\"{s['avg_returned']:>5.1f}\")
    print()
    print(\"Legend:\")
    print(\"  sub⊂head     mean over specific queries of \"
          \"|R(specific)∩R(head)| / |R(specific)|\")
    print(\"               (user's actual expectation; higher = closer to 'fan motor ⊂ motor')\")
    print(\"  plur~head    overlap of plural vs head sets (motors ↔ motor)\")
    print(\"  distFP       off-domain queries that wrongly returned ≥1 image\")
    print(\"  avgN         average result-list size on in-domain queries\")
    print()

    # ---------- Best config from sweep on the *containment* objective ----------
    # Composite objective: F1 + sub_containment + plural_containment, penalise
    # distractor FPs heavily.
    for row in sweep_rows:
        row[\"objective\"] = (
            row[\"f1\"]
            + 0.8 * row[\"sub_cont\"]
            + 0.6 * row[\"plural_cont\"]
            - 0.2 * row[\"distractor_FP\"]
        )
    sweep_rows.sort(key=lambda r: -r[\"objective\"])
    print(\"Top-10 sweep configs (S3 architecture) — composite objective:\")
    print(f\"  {'topK':>4} {'thr':>5} {'ratio':>5} {'fb':>3} | \"
          f\"{'F1':>5} {'sub⊂':>5} {'plur':>5} {'dFP':>3} {'avgN':>5}  obj\")
    for row in sweep_rows[:10]:
        print(f\"  {row['topK']:>4} {row['threshold']:>5.2f} {row['ratio']:>5.2f} \"
              f\"{row['fallback']:>3} | \"
              f\"{row['f1']:>5.3f} {row['sub_cont']:>5.3f} {row['plural_cont']:>5.3f} \"
              f\"{row['distractor_FP']:>3} {row['avg_returned']:>5.1f}  \"
              f\"{row['objective']:>5.3f}\")

    # Show one concrete example: motor / fan motor / motors selected subs
    print(\"\nExample — what each strategy returns for motor / fan motor / motors:\")
    for name in [\"S1_current\", \"S2_loose_only\", \"S3_loose+ensemble\", \"S4_loose+ens+2stage\"]:
        print(f\"\n  [{name}]\")
        for qt in [\"motor\", \"fan motor\", \"motors\", \"electric motor\"]:
            sel = all_details[name][\"per_query\"][qt]
            print(f\"    {qt!r:<18} -> {sel['returned']:>3} results, \"
                  f\"P={sel['precision']:.2f} R={sel['recall']:.2f} \"
                  f\"subs={sel['selected_subs']}\")

    print(f\"\nFull JSON: {out_dir / 'results.json'}\")
    return all_summaries, sweep_rows


if __name__ == \"__main__\":
    ap = argparse.ArgumentParser()
    ap.add_argument(\"--seed\", type=int, default=42)
    ap.add_argument(\"--out\", type=str, default=\"/app/Deepix/tuning_sim\")
    args = ap.parse_args()
    main(args.seed, Path(args.out))
"
Observation: Create successful: /app/Deepix/tuning_sim/simulate.py
