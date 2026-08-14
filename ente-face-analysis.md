# Ente Face Recognition & Categorization — Analysis & Port Plan

**Author:** Deepix engineering note  
**Repo analyzed:** https://github.com/ente-io/ente.git (commit `f8b4a9c`, shallow clone)  
**Goal:** Why Deepix's People page still splits one person into several collections, and what to copy from Ente.

---

## 1. Executive summary

Ente and Deepix use **the same embedding model** (MobileFaceNet / InsightFace `w600k_mbf`, 112×112, 512-D) and the same L2-normalized cosine embedding space. The split bug is **not in the model, the normalizer, or the aligner** — it is in the **clustering decision rule**.

- Deepix assigns a new face to a person via a **centroid pre-filter + a capped 10-exemplar vote** at `PersonMatchThreshold = 0.55`.
- Ente assigns a new face via **single-linkage against *all* previously clustered faces** at a much higher bar (`cosine ≥ 0.76` good / `0.84` bad), with **no centroid, no exemplar cap**, and a **dynamic per-face quality threshold**.
- The result: Deepix's gate rejects a face that matches *one* exemplar but not the centroid/mean, and its **capped 10-exemplar set** drops the very exemplar that would have matched — so the same identity fragments into multiple collections.

The fix is to replace Deepix's matching rule with Ente's **linear single-linkage incremental clustering** plus a **post-index reconciliation/merge** pass.

---

## 2. Model & preprocessing — Deepix already matches Ente

| Aspect | Ente | Deepix | Match? |
|---|---|---|---|
| Embedding model | MobileFaceNet `w600k_mbf`, 112×112, 512-D | `mobilefacenet_w600k_mbf.onnx`, 512-D | ✅ same |
| Embedding space | L2-normalized, cosine = dot | L2-normalized, cosine = dot | ✅ |
| Input size | 112×112 | 112×112 | ✅ |
| Channel order / normalize | RGB, `px * (1/127.5) - 1` = `(px/127.5) - 1` | BGR, `(px/255 - 0.5)/0.5` = `(px/127.5) - 1` | ⚠️ see note |
| Alignment template | InsightFace 5-pt canonical landmarks | same canonical landmarks | ✅ |
| Detector | YOLO-family face net (post-NMS, IoU 0.4, score ≥ 0.5) | YuNet (post-NMS) | ✅ equivalent |

**Channel-order note:** Ente feeds **RGB** (`px[0]`=R first). Deepix's `FaceNormalizer` feeds **BGR** (Blue first). Both then map to the same `[-1,1]` range. For InsightFace `w600k_mbf`, the reference pipeline expects **RGB**. This is a *real* discrepancy worth flagging — but because it's a fixed global permutation it does **not** cause same-person splits by itself (it shifts all embeddings uniformly). Deepix has been shipping BGR and the pairwise debug page "worked", so this is a **secondary** concern, not the split cause. Fixing it later (RGB) would change the embedding space and require a fresh index — treat it as an optional accuracy upgrade, not the bug.

---

## 3. The core difference: clustering decision rule

### 3.1 Deepix (current) — `PersonMatcher.match()`

```
1. centroid pre-filter:  cosine(newFace, centroid(person)) ≥ 0.25  → keep top 8 candidates
2. exemplar vote:        support = MAX cosine(newFace, exemplar) over ≤10 exemplars
3. assign iff  support ≥ 0.55   (and, removed: margin + centroid-confirm)
```

Problems that cause over-splitting:

1. **Centroid pre-filter still gates.** The centroid is the *mean* of ≤10 exemplars. A new pose/lighting of the same person can score `0.25–0.55` against the blended centroid — even though it matches a *single* stored face at `0.7+`. If it clears the floor it proceeds; if the mean pulls it below `0.25` it is **dropped** and a new collection is born.
2. **Exemplar cap of 10 drops the matching face.** A person with 50 photos keeps only the 10 "best quality" exemplars. If the single stored photo that best matches a new face is *not* among those 10, the vote can fall below `0.55` even though a full single-linkage would find `≥ 0.76`.
3. **Exemplar rotation by "quality" (sharpness), not representativeness.** Blur/sharpness-based exemplar eviction can remove pose-varied exemplars, shrinking the cluster's coverage of that identity.

### 3.2 Ente (target) — `runLinearClustering` (Dart) / `clusterBatchLinear` (web)

```
for each new face fi (sorted NEWEST-first):
    threshold = fi.isBadFace ? 0.84 : 0.76          // dynamic, per-face
    for each earlier face fj (j < i):                // single-linkage over ALL faces
        csim = dot(fi.emb, fj.emb)                   // cosine, embeddings normalized
        if csim ≥ threshold  → remember nearest fj
    if nearest found → join fj's cluster
    else → new cluster
```

Key properties:

- **Single-linkage over all faces** — no centroid, no exemplar cap. A new face joins a collection if it matches **any one** stored face above the threshold. This is exactly the "best pair" test the Deepix debug page already uses and that the user confirmed works.
- **Higher, quality-adaptive threshold** — `0.76` for a clean face, `0.84` for a *bad* face (low score / blur / sideways). Bad faces are held to a stricter bar, not a looser one (opposite of Deepix's `isLowQuality` skip, which *excluded* them entirely).
- **Newest-first ordering** improves the heuristic (recent, likely-similar burst photos anchor clusters first).
- **Rejected-cluster memory** — a face remembers clusters the user rejected it from and will not rejoin them.
- **Full `O(n²)` recompute** is batched (10k, overlap 7.5k) and also supports a **complete clustering** variant (`_runCompleteClustering`) that adds a **hierarchical mean-embedding merge pass** (merge two clusters when mean-distance `< mergeThreshold=0.30`, weight by cluster size) — this is what retroactively repairs splits.

---

## 4. Quality gating comparison

| Aspect | Ente | Deepix |
|---|---|---|
| Face inclusion in clustering | `blur > 10 && score > 0.8` (hard filter at indexing) | stored all; `isLowQuality` used to *skip matching* |
| Bad-face handling | *still clustered*, but stricter threshold `0.84` | low-quality faces **excluded** from matching (creates gaps → splits) |
| Sideways | treated as bad face (stricter threshold) | pose computed but not gating |

Deepix's `FaceIndexWorker` currently `return@mapNotNull null` for `isLowQuality` faces (from the earlier SFace-era fix). That means a person whose only photos are slightly blurry gets **no cluster membership** — a direct contributor to fragmentation. Ente instead keeps bad faces but tightens the bar.

---

## 5. Reconciliation / healing existing splits

Ente has **two** mechanisms Deepix lacks that fix *already-broken* collections:

1. **Complete re-clustering with hierarchical mean merge** — recompute over all faces; merge clusters whose *mean* embeddings are within `0.30` distance. This collapses the same identity's fragments even when individual faces never exceeded the pair threshold.
2. **Auto-merge** (`PersonService.kDefaultAutoMergeThreshold = 0.24` distance ⇒ cosine `0.76`) — merges person clusters whose best/mean similarity is high enough, surfaced and applied.

Deepix's `PersonMatcher.reconcileSplits()` (added earlier) is a step in the right direction but uses the **same 0.55 exemplar vote** and only runs pairwise person-vs-person, so it cannot merge fragments whose *representative faces* are far apart even when the *clusters' means* align.

---

## 6. Recommended port to Deepix (minimal-but-correct)

### 6.1 Replace `PersonMatcher.match()` with single-linkage

- **Remove** the centroid pre-filter and the exemplar cap as the *decision* mechanism.
- For the top candidates (still cheaply pre-filtered by a very low centroid floor like `0.0–0.05` just to skip unrelated persons), compute cosine against **all** that person's stored faces (via `FaceVectorIndex.allEntries()` or the Room faces), take the **max**, and assign iff `max ≥ threshold`.
- **Dynamic threshold per face:** carry `isLowQuality`/blur through to the matcher; use `0.76` for good faces, `0.84` for low-quality/sideways faces.
- **Sort new faces newest-first** before incremental assignment.

### 6.2 Keep bad faces in the pipeline

- Remove the `isLowQuality` skip in `FaceIndexWorker.matchOutcomes` (revert to matching all embedded faces) — Ente clusters bad faces under a stricter bar rather than excluding them.

### 6.3 Add a post-index merge pass (fix existing splits)

- After each full index (and nightly maintenance), run a **complete/hierarchical merge** over cluster **mean embeddings**: for any two persons whose mean-cosine `≥ 0.76` (or distance `< 0.24`), merge them (weighted by size). This heals fragments already in the DB without a model bump.
- Keep user merge/split/ignore actions authoritative (do not auto-override user-created splits).

### 6.4 Threshold table (target)

| Constant | Ente (distance) | Deepix (cosine) |
|---|---|---|
| Good-face match | `1 - 0.76` = 0.24 | **0.76** |
| Bad-face match | `1 - 0.84` = 0.16 | **0.84** |
| Cluster mean merge | `1 - 0.30` = 0.70 | **0.30** (distance) / mean-cos 0.70 |

Note: These are Ente's *validated operating points for the same w600k_mbf model*, so they are directly transferable to Deepix — far more trustworthy than Deepix's current `0.55`, which was inherited from an unrelated/older tuning.

---

## 7. Files to change in Deepix

| File | Change |
|---|---|
| `PersonMatcher.kt` | Rewrite `match()` to single-linkage max-over-all-faces; add dynamic threshold; add `mergeClusters()` mean-based pass |
| `FaceIndexWorker.kt` | Sort new faces newest-first; stop skipping `isLowQuality` faces in matching |
| `FaceMaintenanceWorker.kt` | Invoke the mean-based merge pass nightly |
| (optional) `FaceNormalizer.kt` | RGB channel order to match InsightFace reference (triggers reindex — do separately) |

---

## 8. Bottom line

Deepix's splits are caused by a **clustering rule** (centroid + capped exemplars at 0.55) that is far less forgiving than Ente's **single-linkage over all faces at 0.76/0.84**. Since both apps run the **identical MobileFaceNet w600k model**, copying Ente's matching rule + dynamic quality threshold + a post-index mean-merge is the correct, low-risk fix. The `isLowQuality` exclusion and the 0.55 threshold should both be removed/replaced.

---

*Sources: `ente/mobile/.../face_clustering_service.dart`, `ente/mobile/.../face_filtering_constants.dart`, `ente/mobile/.../face_clustering/face_db_info_for_clustering.dart`, `ente/rust/crates/ml/src/face/{embed,align,detect}.rs`, `ente/web/packages/new/photos/services/ml/cluster.ts`, `ente/mobile/.../person/person_service.dart`.*
