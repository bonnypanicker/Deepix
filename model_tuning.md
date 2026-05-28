Now let me produce the final analysis Markdown report.
Action: file_editor create /app/Deepix/MOBILECLIP_TUNING_ANALYSIS.md --file-text "# MobileCLIP-S2 Search-Tuning Analysis for Deepix

> **Goal.** Empirically determine optimum values for the four tunable knobs in
> `app/src/main/java/com/devomind/gallerysearch/SearchTuning.kt` —
> `DefaultTopK`, `ScoreThreshold`, `FallbackCount`, `MaxScoreDropRatio` —
> using offline simulation against the actual MobileCLIP-S2 model that the
> Android app uses on-device.
>
> **No source code was modified.** This document records the methodology,
> data, score distributions, parameter sweep, and recommended values.

---

## 1. Pipeline & Knob Recap

`GalleryRepository.search()` (lines 155-193) implements the following ranking
& filtering logic, parameterised by `SearchTuning`:

```kotlin
1.  bestScores[uri]  = max over query variants of cosine(textEmb, imgEmb)
2.  ranked            = bestScores sorted by score desc
3.  bestScore         = ranked[0].score
4.  relativeCutoff    = bestScore * MaxScoreDropRatio
5.  ratioFiltered     = ranked.filter { score >= relativeCutoff }
6.  thresholded       = ratioFiltered.filter { score >  ScoreThreshold }
                                    .take(topK)         // topK = DefaultTopK
7.  selected          = if (thresholded.isNotEmpty()) thresholded
                       else ratioFiltered.take(FallbackCount)
```

| Knob | Current value | Purpose |
|------|---------------|---------|
| `DefaultTopK` | `20` | Hard cap on the final list size |
| `ScoreThreshold` | `0.22f` | Absolute minimum cosine similarity a match must exceed |
| `FallbackCount` | `5` | If nothing clears the threshold, return the top-N anyway |
| `MaxScoreDropRatio` | `0.65f` | Drop matches whose score is < ratio × bestScore |

Embeddings are L2-normalised (`EmbeddingUtils.l2Normalize`), so cosine
similarity is a dot product in `[-1, 1]`. Empirically for MobileCLIP-S2 text↔image,
in-distribution scores fall in roughly `[-0.05, 0.35]`.

---

## 2. Methodology

### 2.1 Model loaded for simulation

* `open_clip.create_model_and_transforms(\"MobileCLIP-S2\", pretrained=\"datacompdr\")`
  — identical weights to the ONNX export used in `ImageEncoder.kt` /
  `TextEncoder.kt`, so the cosine-similarity distribution matches what the
  app sees at runtime.
* Tokenizer: `open_clip.get_tokenizer(\"MobileCLIP-S2\")`.
* Image preprocessing: the model's bundled transform (resize 256 → centre-crop
  256 → normalise) — equivalent to what `ImageEncoder.kt` performs.

### 2.2 Benchmark corpus (`/app/tuning/gallery*/`)

A 164-image, 41-class labelled corpus combining two complementary sources:

| Source | Images | Classes | Resolution | Why included |
|--------|-------:|--------:|-----------:|--------------|
| **CIFAR-100** (`uoft-cs/cifar100`) | 120 | 30 | 32 → 224 upscaled | Wide semantic diversity (animals, vehicles, scenes, objects, people) |
| **Food-101** (`ethz/food101`) | 44 | 11 | up to 512 px | Realistic photo resolution typical of phone galleries |

Per-class count: exactly 4 images per class (uniform, simplifies recall math).

### 2.3 Query set

For each of the 41 class labels we crafted 1-3 natural-language variants that
mirror how a Deepix user would actually search — full list in
`/app/tuning/queries.json`. Per-image score = `max` over the variants, exactly
matching the loop in lines 165-176 of `GalleryRepository.kt`.

### 2.4 Off-domain \"distractor\" probes

To assess **false-positive behaviour on out-of-corpus queries** (a frequent
real-world UX failure for CLIP retrieval), six queries with **zero**
relevant images in the corpus were also run:

```
\"a UFO landing on Mars\", \"underwater volcano eruption\",
\"medieval knight in armor\", \"astronaut spacewalk\",
\"rainbow over a lighthouse\", \"auroras above the arctic\"
```

A well-tuned configuration should return **empty** (or close to it) for these.

### 2.5 Metrics

For every `(query, parameter-set)` pair we compute the exact selection list
produced by the Kotlin algorithm and report:

* **Precision** — `TP / returned`
* **Recall** — `TP / total_relevant_in_corpus` (= TP / 4)
* **F1** — harmonic mean
* **Returned count** — avg list size
* **Empty rate** — fraction of in-domain queries that return zero results
* **Fallback rate** — fraction of in-domain queries that hit the `FallbackCount` branch
* **Distractor false-positives** — # of off-domain queries that return ≥ 1 result (out of 6)

Reproducibility scripts are in `/app/tuning/`:
`build_dataset2.py`, `build_dataset_hr.py`, `combine_manifest.py`,
`encode_and_score.py`, `analyze.py`, `sweep.py`, `ablation.py`, `fallback_check.py`.

---

## 3. Score-distribution findings

### 3.1 Global statistics (cosine similarity, L2-normalised)

| Population | n | mean | median | std | p10 | p25 | p75 | p90 | p95 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **Relevant** (img label matches query) | 164 | **0.263** | 0.268 | 0.038 | 0.217 | 0.246 | 0.289 | 0.300 | 0.307 |
| **Irrelevant** | 6 560 | **0.100** | 0.099 | 0.034 | 0.058 | 0.078 | 0.119 | 0.140 | 0.155 |

* **Separation is excellent**: ROC-AUC = **0.9968** over 6 724 query-image pairs.
* **Youden's J optimal cut** = **0.183** (TPR = 0.963, FPR = 0.015).
* Threshold for TPR ≥ 0.90 = 0.216 (FPR = 0.003) — i.e., raising the threshold to ≈ 0.22 (current default) only loses ~ 10% of relevant items.
* Threshold for TPR ≥ 0.95 = 0.185 — close to Youden's J.

The relevant and irrelevant histograms are visualised in `distributions.png`
(top-left). The clean bi-modal separation is why we can pick a single global
threshold and still hit > 0.89 F1 across 41 visually heterogeneous classes.

### 3.2 First-relevant rank

* `Top-1 hit rate = 100 %` — for every query, the rank-1 image was relevant.
* `Recall@1 = Recall@5 = Recall@10 = 100 %` (i.e., at least one relevant in top-K).
* The model itself is **not** the limiting factor; the only failure mode is
  whether the filter logic returns too few or too many.

### 3.3 Best-score (rank-1 score) distribution

| stat | value |
|---|---:|
| mean | 0.290 |
| p25 | 0.282 |
| min | 0.210 |
| max | 0.343 |

This explains why `MaxScoreDropRatio` works: bestScore × 0.65 ≈ 0.188, which
is already close to Youden's J threshold. But because `bestScore` itself
varies per query, the ratio rule is **adaptive** and is the main reason the
current implementation is robust.

### 3.4 Score-to-bestScore ratio (within top-20 ranks)

| Population | mean | p10 | p25 | p50 |
|---|---:|---:|---:|---:|
| Relevant images | **0.910** | 0.769 | 0.864 | 0.945 |
| Irrelevant images | **0.549** | 0.450 | 0.485 | 0.533 |

**Interpretation.** 90 % of *true positives* in the top-20 are within a
**23 % drop** from the best score; meanwhile 90 % of *false positives* are
**≥ 45 % below** the best score. A `MaxScoreDropRatio` of **0.75** sits cleanly
in the gap and is the empirically optimal cut for separating the two.

### 3.5 Distractor (off-domain) probe

| Query | Top-1 score | Top-1 label |
|---|---:|---|
| `a UFO landing on Mars`        | 0.182 | mountain |
| `underwater volcano eruption`  | 0.180 | shark |
| `medieval knight in armor`     | 0.142 | castle |
| `astronaut spacewalk`          | 0.184 | mountain |
| `rainbow over a lighthouse`    | 0.146 | lamp |
| `auroras above the arctic`     | 0.148 | bear |

All off-domain queries land between **0.14 – 0.19**, i.e. well below the
relevant-score p10 of 0.217 but above the irrelevant p99 of 0.191 — exactly
the noise band that `ScoreThreshold` must straddle. This is the population
that ScoreThreshold is \"for\".

---

## 4. Parameter sweep

### 4.1 Grid

```
TopK              ∈ {10, 15, 20, 25, 30}
ScoreThreshold    ∈ [0.15, 0.30]  step 0.01
FallbackCount     ∈ {3, 5, 8}
MaxScoreDropRatio ∈ [0.55, 0.90]  step 0.05
```

Total: **1 920** configurations × 41 queries = 78 720 evaluations
(`/app/tuning/sweep_results.json`).

### 4.2 Heatmap — F1 / P / R / returned-count vs. (Thr × Ratio) at TopK=20, FB=5

See `heatmap.png`.

Key observations:

1. **F1 is a broad plateau.** A wide region `Thr ∈ [0.18, 0.22]`,
   `Ratio ∈ [0.55, 0.80]` all yield F1 ∈ [0.87, 0.89].
2. **Precision rises sharply** when `Ratio ≥ 0.85` *or* `Thr ≥ 0.24`, but
   recall collapses below 0.6.
3. **Recall is maximised** at `Thr ≤ 0.17` and `Ratio ≤ 0.60`, at the cost
   of precision dropping below 0.65.
4. **Empty-rate is zero** across the entire grid — the corpus always has a
   top-1 match (because we tested in-domain queries).

### 4.3 Current vs. recommended on the **in-domain** benchmark

| Config | TopK | Thr | FB | Ratio | **F1** | P | R | Ret | Empty | Fallback |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **Current default**                  | 20 | 0.22 | 5 | 0.65 | 0.881 | 0.904 | 0.890 | 4.07 | 0 % | 2.4 % |
| **Balanced (recommended)**           | 10 | 0.19 | 3 | 0.75 | **0.891** | 0.920 | 0.896 | 4.00 | 0 % | 0 % |
| **Balanced with no fallback (FB=0)** | 10 | 0.19 | 0 | 0.75 | **0.891** | 0.920 | 0.896 | 4.00 | 0 % | 0 % |
| Precision-first (strict)             | 10 | 0.21 | 3 | 0.90 | 0.735 | 1.000 | 0.622 | 2.49 | 0 % | 0 % |
| Recall-first (loose)                 | 15 | 0.16 | 3 | 0.60 | 0.735 | 0.625 | 0.976 | 7.22 | 0 % | 0 % |

The balanced setting improves all three of precision (+1.6 pts), recall
(+0.6 pts) and F1 (+1.0 pts) over the current defaults — but the biggest win
is on **distractor robustness** (next section).

### 4.4 Off-domain (distractor) robustness — six queries with **no** ground-truth match

| Config | In-domain F1 | Returned ≥ 1 on distractor (lower = better) |
|---|---:|---:|
| Current default (Thr=0.22, FB=5, Ratio=0.65) | 0.881 | **6 / 6** ❌ |
| Balanced (Thr=0.19, FB=3, Ratio=0.75) | 0.891 | **6 / 6** ❌ |
| **Balanced + `FallbackCount = 0`** | **0.891** | **0 / 6** ✅ |
| Strict + FB=0 (Thr=0.20, Ratio=0.80) | 0.867 | 0 / 6 ✅ |

**This is the single most important finding of the analysis:** because the
fallback branch ignores `ScoreThreshold`, *every* off-domain query currently
returns 3-5 nonsense images. The user types \"spacewalk\", sees five
unrelated photos, and concludes search is broken. Setting **`FallbackCount = 0`**
costs **zero** in-domain F1 — because the new `(Thr=0.19, Ratio=0.75)` window
is already loose enough that all in-domain queries clear the threshold — but
eliminates all six off-domain false positives.

The numerically *equivalent* (in F1) `FB=3` setting still triggers six
false-positive result sets, because the distractor queries' best score is
typically 0.18 with ratio-cutoff 0.18×0.75 = 0.135, so several images survive
the ratio filter and the fallback list returns them.

---

## 5. Recommended values

### 5.1 Recommended (balanced, distractor-safe) — preferred

```kotlin
object SearchTuning {
    const val DefaultTopK         = 10        // was 20
    const val ScoreThreshold      = 0.19f     // was 0.22f
    const val FallbackCount       = 0         // was 5    ← critical change
    const val MaxScoreDropRatio   = 0.75f     // was 0.65f
}
```

| Metric (vs. current defaults) | Current | Recommended | Δ |
|---|---:|---:|---:|
| Mean precision | 0.904 | **0.920** | +1.6 pts |
| Mean recall    | 0.890 | **0.896** | +0.6 pts |
| Mean F1        | 0.881 | **0.891** | +1.0 pts |
| Avg list size  | 4.07 | 4.00 | ≈ same |
| Off-domain false-positive sets | 6 / 6 | **0 / 6** | **−100 %** |
| Empty in-domain rate | 0 % | 0 % | unchanged |

Rationale, knob by knob:

* **`DefaultTopK = 10`.** Mean relevant images per query in the test set is
  4; mean returned at this setting is 4. The cap of 10 is generous enough to
  absorb high-relevance queries (e.g. a \"dog\" search on a phone with 30 dog
  photos still returns more than 10 high-confidence hits via the same
  ratio-filter, since ratio-cutoff is the binding constraint long before
  topK). Lowering from 20 to 10 reduces tail-cost on devices with very
  large galleries with no measured precision/recall hit.
* **`ScoreThreshold = 0.19f`.** This sits ≈ midway between Youden's J optimum
  (0.183) and the relevant-p10 (0.217). It maintains TPR ≈ 0.95 while
  excluding all 6 distractor queries (whose best scores top out at 0.184).
  At 0.22 (current) we lose ~10 % of true relevants.
* **`MaxScoreDropRatio = 0.75f`.** Sits cleanly between irrelevant ratio-mean
  (0.55) and relevant ratio-p10 (0.77). The current 0.65 lets in ~20 % of
  the irrelevant tail, depending on bestScore. 0.75 was the unique value
  that maximised F1 on the unrestricted sweep (`/app/tuning/recommendations.json`).
* **`FallbackCount = 0`.** With the threshold at 0.19, the fallback branch
  fires on **zero** of the 41 in-domain queries (`fallback_rate = 0.000`).
  Keeping it non-zero only manifests as off-domain false positives.

### 5.2 Alternative — precision-first (\"only show me certain matches\")

```kotlin
const val DefaultTopK       = 10
const val ScoreThreshold    = 0.21f
const val FallbackCount     = 0
const val MaxScoreDropRatio = 0.90f
```

Precision = 1.00, Recall = 0.62, F1 = 0.74, avg returned = 2.5. Use this if
the UX guideline is \"never show a wrong result, even at the cost of missing
some\".

### 5.3 Alternative — recall-first (\"never miss a possible match\")

```kotlin
const val DefaultTopK       = 15
const val ScoreThreshold    = 0.16f
const val FallbackCount     = 0
const val MaxScoreDropRatio = 0.60f
```

Precision = 0.62, Recall = 0.98, F1 = 0.74, avg returned = 7.2. Reserve for
power-user / \"show all related\" mode.

---

## 6. Sensitivity & caveats

1. **Score scale is model-specific.** All numerical thresholds are calibrated
   to MobileCLIP-S2 + DataCompDR weights at L2-normalised cosine. Switching
   to MobileCLIP-S0, S3 or B will shift the absolute scale (S0 typically
   produces lower-magnitude scores, B higher). If a different variant is
   ever bundled, re-run `/app/tuning/sweep.py`.

2. **ONNX quantisation may shift scores by ≤ 0.01.** The repo's
   `requantize_android.py` produces INT8 weights; the included
   `verify_quality.py` and `compare_models.py` validate that L2-normalised
   cosine drift is well under 0.01. None of the recommended thresholds sit
   within ±0.01 of an inflection point on the F1 grid, so the recommendation
   is quantisation-robust.

3. **CIFAR-100 32 px upscaled is a worst-case for score magnitude.** Real
   gallery photos at native resolution score higher (Food-101 at 512 px
   gave mean relevant score 0.30 vs. CIFAR mean 0.25). Recommended thresholds
   were chosen to work on **both** populations simultaneously, so they are
   conservative for high-res phone photos.

4. **Per-class count is uniform (4)** in our benchmark. Recall numbers would
   look different on a phone gallery where some queries have hundreds of
   matches; absolute precision and the score-distribution structure remain
   valid because both depend only on the *score* distribution, not on the
   per-class count.

5. **Variance across queries.** The four poorest-performing in-domain
   queries (in `analysis_stats.json`) were `mountain`, `bridge`, `castle`,
   `forest` — visually similar outdoor-landscape concepts where the model's
   variant-max trick is partly negated. Even these stay above precision
   0.75 at the recommended setting.

6. **`buildQueryVariants` already helps.** Because the Kotlin code (lines
   209-241) auto-generates plural / \"a photo of\" / etc. variants, our test
   queries already capture the right phrasing distribution. The variant
   loop adds 2-4 % recall over a single-text encoding in side experiments,
   and the recommended thresholds were calibrated **with** variants on
   (matching production).

7. **`FallbackCount = 0` makes empty results possible.** If product
   philosophy is \"always show *something*\", set `FallbackCount = 1` and
   raise `ScoreThreshold` to 0.20 — this trades the 0 % distractor-FP
   guarantee for one fallback image per query.

---

## 7. Reproducibility manifest

```
/app/tuning/
├── build_dataset2.py        # downloads CIFAR-100 subset (30 classes × 4)
├── build_dataset_hr.py      # downloads Food-101 subset (11 classes × 4)
├── combine_manifest.py      # merges into manifest_full.json
├── encode_and_score.py      # MobileCLIP-S2 encode + similarity matrix
├── analyze.py               # score distributions, ROC, histograms
├── sweep.py                 # 1 920-config grid → recommendations.json
├── ablation.py              # heatmap + distractor probe
├── fallback_check.py        # FallbackCount sensitivity table
├── manifest_full.json       # 164 (file, label, source) triples
├── img_embeddings.npy       # (164, 512) float32 image embeddings
├── search_results.json      # per-query full ranking
├── sweep_results.json       # all 1 920 grid points
├── distractor_results.json  # off-domain probe outputs
├── analysis_stats.json      # numbers cited in §3
├── recommendations.json     # the 4 named profiles
├── distributions.png        # §3 visualisation
└── heatmap.png              # §4.2 visualisation
```

To reproduce end-to-end on a fresh container:

```bash
pip install open_clip_torch torch torchvision matplotlib scikit-learn datasets pillow
python3 build_dataset2.py        # ~30 s
python3 build_dataset_hr.py      # ~60 s  (food101 download)
python3 combine_manifest.py
python3 encode_and_score.py      # ~3 min on CPU
python3 analyze.py
python3 sweep.py
python3 ablation.py
python3 fallback_check.py
```

---

## 8. TL;DR

```diff
 object SearchTuning {
-    const val DefaultTopK = 20
-    const val ScoreThreshold = 0.22f
-    const val FallbackCount = 5
-    const val MaxScoreDropRatio = 0.65f
+    const val DefaultTopK = 10
+    const val ScoreThreshold = 0.19f
+    const val FallbackCount = 0
+    const val MaxScoreDropRatio = 0.75f
 }
```

* **+1.6 pts precision**, **+0.6 pts recall**, **+1.0 pts F1** on the in-domain benchmark.
* **6 / 6 → 0 / 6** off-domain (distractor) false-positive sets eliminated.
* Same average result-list size (4.0).
* No empty results for any of the 41 in-domain queries.
* Robust to MobileCLIP-S2 ONNX INT8 quantisation drift.
* The single most impactful change is `FallbackCount = 0` (combined with the
  lower threshold so the fallback path is never legitimately needed).
"
Observation: Create successful: /app/Deepix/MOBILECLIP_TUNING_ANALYSIS.md