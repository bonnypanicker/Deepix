# Offline People Albums System (Gallery Go–style) — v6, Phased Implementation

This spec is split into 5 phases. Each phase is self-contained enough to hand to Claude Code (or another coding agent) as its own prompt/session. Do not attempt all phases in one prompt — verify each phase's checkpoint before starting the next. Model files (YuNet, MobileFaceNet ONNX) must be sourced manually before Phase 1 — no agent can produce the binary weights.

---

## Before Phase 1 — Manual Prerequisites (not a coding task)

- Download YuNet ONNX export (OpenCV Zoo).
- Download MobileFaceNet ONNX export (PINTO0309 model zoo, or InsightFace-derived ArcFace-mobilefacenet export).
- Validate both load correctly in ONNX Runtime for Android in isolation before wiring into the real project — confirms input tensor shape/layout matches what you'll write in Phase 1.

---

## Phase 1 — Core Inference Plumbing

**Goal:** given a photo, produce stored face embeddings. Nothing else. No clustering, no UI, no background workers yet.

**Scope:**
- YuNet detector wrapper: input decode (720p cap), output bbox + confidence + 5 landmarks.
- Face alignment utility: landmarks → similarity transform → 112×112 crop.
- MobileFaceNet inference wrapper: crop → normalize → embed → L2-normalize.
- **Head-pose estimation, two-tier:**
  - Primary: solvePnP against a canonical 3D face model using the 5 landmarks.
  - **Fallback (new):** solvePnP is sensitive to noise with only 5 tightly-clustered 2D points — bounding-box jitter or a slightly off landmark can produce erratic pitch/yaw. If solvePnP output is unstable (e.g. values outside plausible range, or high frame-to-frame variance on video-adjacent bursts), fall back to a simple 2D geometric heuristic: ratio of left-eye-to-nose distance vs. right-eye-to-nose distance as a frontal-ness proxy. This doesn't need to be as precise as solvePnP — it's only ever used for cover-selection ranking, not filtering, so a coarse signal is acceptable.
- Face quality scoring: size, blur (variance-of-Laplacian), confidence composite.
- Room schema: Photo (phash, clip_person_score fields added but unused this phase), Person (full schema, unused this phase), Face (full schema including embedding_model_version, is_low_quality, is_exemplar — unused this phase beyond storage).
- ONNX Runtime session management: shared environment, 2–4 intra-op threads, single-process (do not declare separate `android:process`).

**Explicitly out of scope for this phase:** CLIP-gate, dedup, exemplar matching/clustering, WorkManager, any UI, MediaStore observer.

**Verification checkpoint before Phase 2:**
- Run against ~20–50 manually chosen test photos (mix of single-face, multi-face, no-face, small/blurry faces, and a few extreme angles to stress-test the pose fallback).
- Confirm bbox/landmark output visually correct (overlay on the source image).
- Confirm embeddings for the same person across different photos are closer (cosine similarity) than embeddings across different people.
- Confirm the pose fallback actually triggers and produces sane output on the extreme-angle test cases.
- Confirm memory stays within budget on a real mid-range device for a single image.

---

## Phase 2 — Indexing Pipeline

**Goal:** wire Phase 1 into a real background indexing pipeline over the actual photo library, with the pre-filtering stages that make it efficient.

**Scope:**
- Perceptual-hash duplicate detector (phash on Photo) with burst-window (<2s) / dimension-delta (<10%) guard.
  - **Refined duplicate handling:** never clone face records on a duplicate match, and only skip full re-processing if face count and bbox proportions also match. But even when a photo is guard-matched as a duplicate (e.g. one frame of a burst sequence), still compute its quality_score and let it **replace an existing exemplar or cover** if it scores higher — burst sequences commonly contain one sharper/better-expression frame among several near-identical ones, and skipping quality evaluation entirely on "duplicate" frames risks permanently keeping a worse exemplar.
- CLIP person-gate: reuse existing MobileCLIP-S2 embeddings, permissive/recall-biased threshold, store raw score not just boolean.
- Centroid pre-filter + top-k exemplar-vote matching against existing Person records (fixed 8–10 exemplars per person).
- Flat cosine-similarity vector index: memory-mapped, copy-on-write, persisted, checksummed (CRC32/xxHash).
- **Room ↔ vector-index consistency (new):** a Face row and its corresponding vector-index entry must not be able to diverge on crash. Write the embedding to the vector index (or a durable staging structure) *before* committing the Face row that references it, so a crash mid-write leaves at worst an orphaned unused vector (harmless) rather than a Face row pointing at a missing embedding (breaks matching silently). Additionally, add a lightweight startup consistency check: on cold start, sample-check (or fully check, if cheap enough at your scale) that every non-null-person Face row has a corresponding valid vector-index entry; if not, re-embed from the stored crop/bbox or flag for re-indexing.
- WorkManager indexing worker: battery-gated, same process as UI, pipelined across images.
- Explicit throughput SLA target (e.g. 30–60 photos/min while charging).

**Explicitly out of scope for this phase:** MediaStore observer for live updates, clustering maintenance/repair pass, any UI beyond a debug screen, first-run orchestration.

**Verification checkpoint before Phase 3:**
- Run a full backfill over a real (or realistic sample) photo library.
- Confirm CLIP-gate isn't silently dropping obvious face photos.
- Confirm duplicate guard doesn't skip legitimately-different photos, and confirm a higher-quality duplicate frame does correctly replace a lower-quality exemplar in a simulated burst-sequence test.
- Confirm throughput SLA is met.
- Confirm checksum validates correctly after a forced kill mid-write.
- **Confirm the Room/vector consistency check catches a deliberately-induced mismatch** (e.g. manually delete a vector entry and confirm the startup check flags/repairs the orphaned Face row).

---

## Phase 3 — Clustering, Maintenance, and Resilience

**Goal:** make cluster quality self-correcting over time, and make the system recover gracefully from failure states, without user-facing UI yet.

**Scope:**
- Split detection: max pairwise exemplar distance exceeds merge threshold → agglomerative re-cluster on full face set → surface split candidates to a log/debug view.
- Merge detection: near-threshold person-pairs → surface merge candidates similarly.
- **PersonMergeLog as an append-only event log (clarified):** every merge/split action, system-suggested or user-confirmed, is appended, never mutated or deleted in place. Undo is implemented as appending a compensating "undo" event referencing the original log entry, not by deleting the original row. This keeps the log a reliable audit trail and makes multi-step undo (undo, then undo-the-undo) straightforward to reason about later if ever needed.
- Corruption recovery: on checksum mismatch, incremental background rebuild in chunks with a status flag the UI can later read.
- **Memory budget enforcement, background-job-aware:** background WorkManager execution has tighter effective memory headroom than a foreground activity — the OS is more willing to kill a background process under memory pressure. Treat the RSS-threshold unload of the MobileFaceNet session as more conservative during background runs specifically (lower threshold when running in WorkManager context vs. if ever run from an active foreground flow).
- Nightly battery-gated WorkManager job for the above, separate from per-photo indexing.

**Explicitly out of scope for this phase:** any user-facing merge/split UI, first-run orchestration.

**Verification checkpoint before Phase 4:**
- Manually seed a few "should split" and "should merge" test cases and confirm the maintenance pass correctly flags them.
- Confirm PersonMergeLog append-only + compensating-undo-event pattern actually reverses state correctly when replayed (test manually, even without UI).
- Confirm corruption-recovery path doesn't block app startup.
- Confirm background-context RSS threshold is measurably lower than any foreground-context threshold, and that the app survives a simulated low-memory scenario during a background run without getting killed mid-write.

---

## Phase 4 — Person Management UI + First-Run Orchestration

**Goal:** the user-facing surface — this is what makes Phases 1–3 a trustworthy product rather than an invisible pipeline.

**Scope:**
- People Album grid UI: cover crop (eager) → name/label → photo count. Use a `LazyVerticalGrid` (Compose) with placeholder states for lazy-loaded faces so the UI thread isn't blocked while Room queries resolve.
- Person detail view: first ~20 member faces eager, remainder lazy with placeholder during generation.
- Person Management actions: rename, merge, split (manual face selection into a new person), remove face from person, hide/archive person (`is_hidden`).
- Undo affordance for merge/split, backed by the append-only PersonMergeLog from Phase 3 — persistent, not a disappearing snackbar.
- **MediaStore ContentObserver, explicit debounce (tightened):** ContentObserver fires multiple times for a single file write. Implement a strict debounce window of 5–10 seconds before pushing a URI to the WorkManager queue, to avoid thrashing the detector on redundant triggers.
- First-run orchestration: immediate initial burst (first 500–1000 photos) regardless of charging state with a battery-usage notice; "Scanning your photos…" progress state; CLIP-gate miss sweep guaranteed complete within 24h of install; fallback to standard battery-gated indexing after.

**Verification checkpoint before Phase 5:**
- Fresh install against a large existing library — confirm first-run doesn't show an empty screen for an extended period.
- Manually create a bad cluster, use the UI to fix it, confirm undo actually restores prior state via the compensating-event pattern.
- Confirm hide/remove-face don't destroy underlying Face rows.
- Confirm the 5–10s debounce actually collapses a burst of ContentObserver callbacks from a single write into one indexing job (test by writing a file and logging callback count vs. jobs enqueued).

---

## Phase 5 — Privacy Compliance Package

**Goal:** mostly not code — the launch-blocking product/legal work that has to exist before shipping.

**Scope:**
- Draft Play Console Data Safety form entries: biometric/face data, on-device processing only, no third-party sharing, retention tied to photo/app-data lifecycle.
- Draft privacy policy clause: plain-language on-device-only processing, no data leaves device, how to disable/delete.
- Research target-jurisdiction runtime consent requirements; decide explicit opt-in dialog vs. opt-out settings toggle.
- Implement in-app feature toggle: fully disable People + delete all Face/Person data, independent of uninstalling the app.
- **Closed-testing rollout note:** use a closed testing track before wide release specifically to monitor battery drain from the First-Run Orchestration burst (Phase 4) — high battery consumption during initial scan is a plausible early-uninstall driver worth catching before general availability. **Verify current Play Console closed-testing tester-count and duration requirements at actual submission time** rather than assuming a fixed number — these have changed across Play policy revisions and shouldn't be hardcoded into planning this far ahead of submission.

**Verification checkpoint before release:**
- Legal/policy language reviewed against actual current Play Console Data Safety requirements at submission time.
- Confirm the in-app delete-all-data toggle actually removes Face/Person/embedding data, not just hides it from UI.

---

## Thresholds (seed values, tune empirically — applies across phases)

| Threshold | Default | Note |
|---|---|---|
| CLIP person-gate score | permissive/low | Phase 2 |
| Detector confidence | 0.6 | Phase 1 |
| Minimum face size | 40px | Phase 1 |
| Decode resolution cap | 720p | Phase 1 |
| Blur (quality filter) | tune from sample set | Phase 1 |
| Recognition similarity | 0.55–0.65 | Phase 2, log near-threshold matches |
| Merge threshold | ~0.7 | Phase 3 |
| Exemplars per person | 8–10 fixed | Phase 2 |
| Duplicate burst window | <2s or <10% dimension delta | Phase 2 |
| MediaStore debounce window | 5–10s | Phase 4 |

---

## Why phased, not single-prompt

- Each phase has an independent verification checkpoint — catching a broken embedding pipeline in Phase 1 costs a re-run of Phase 1, not a rebuild of clustering and UI built on top of bad embeddings.
- Model files, threshold tuning, and privacy/legal work aren't things a prompt can produce regardless of phasing — phasing isolates where human intervention is required from where an agent can run mostly unsupervised.
- Smaller, focused prompts against this same spec file are cheaper to iterate on and easier to review than one 20-deliverable session.

## Deliverables Index (cross-referenced to phases)

1. YuNet detector wrapper — Phase 1
2. Face alignment utilities — Phase 1
3. MobileFaceNet inference wrapper — Phase 1
4. Head-pose estimation, solvePnP + 2D-ratio fallback — Phase 1
5. Face quality filter — Phase 1
6. Room schema (Photo/Person/Face) — Phase 1
7. ONNX session management — Phase 1
8. Perceptual-hash duplicate detector with quality-based exemplar override — Phase 2
9. CLIP person-gate — Phase 2
10. Person representation module (centroid + exemplar-vote) — Phase 2
11. Flat-array vector index (mapped, copy-on-write, checksummed) — Phase 2
12. Room/vector-index consistency guarantee + startup check — Phase 2
13. Background indexing worker — Phase 2
14. Clustering maintenance worker (split/merge detection) — Phase 3
15. Append-only PersonMergeLog + compensating-undo pattern — Phase 3
16. Corruption recovery — Phase 3
17. Background-context-aware memory budget enforcement — Phase 3
18. Person Management UI — Phase 4
19. First-Run Orchestration — Phase 4
20. MediaStore observer with 5–10s debounce — Phase 4
21. Privacy Compliance package + closed-testing battery monitoring plan — Phase 5
22. Beta instrumentation (threshold/gate-miss logging) — spans Phases 2–3
