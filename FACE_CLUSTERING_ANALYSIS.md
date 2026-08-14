# Face Clustering Pipeline Analysis

## Complete Pipeline Trace

### 1. Photo → Face Detection
- **File**: `FaceAnalyzer.kt` - `analyze()` method
- **Detector**: `YuNetDetector` (shared via `GallerySearchApp.sharedEncoders`)
- **Input**: Bitmap loaded via `GalleryRepository.loadBitmapForFaceDetection()` 
  - EXIF orientation is applied correctly via `decodeOrientedBitmap()` or `decodeOrientedBitmapForIndexing()`
  - Max edge: 1536px (`FaceDetectionMaxEdge`)

### 2. Face Crop/Alignment
- **File**: `FaceAligner.kt` - `align()` method
- Uses 5-point landmarks from YuNet
- Applies similarity transform to map to canonical 112x112 ArcFace template
- Has fallback to landmark-bounds crop if RMSE > 6.5px

### 3. Face Quality Filtering
- **File**: `FaceHeuristics.kt` - `FaceQualityScorer.score()`
- Composite score: size (45%) + blur variance (45%) + confidence (10%)
- Low quality threshold: 0.35 (`LowQualityThreshold` in `FaceAnalyzer.kt`)

### 4. Face Embedding Model
- **File**: `FaceEmbedder.kt`
- **Model**: MobileFaceNet w600k_mbf (ArcFace)
- **Asset**: `mobilefacenet_w600k_mbf.onnx`
- **Output**: 512-D embedding, L2-normalized

### 5. Embedding Normalization
- **File**: `FaceNormalizer.kt` - `toTensor()`
- Input: 112x112 bitmap
- Output: NCHW [1, 3, 112, 112], BGR order
- Normalization: `(pixel / 255 - 0.5) / 0.5` → range [-1, 1]
- **L2 normalization**: Applied in `FaceEmbedder.embed()` via `EmbeddingUtils.l2Normalize()`

### 6. Similarity/Distance Calculation
- **File**: `FaceEmbedder.kt` - `cosineSimilarity()`
- **Method**: Dot product (correct for L2-normalized embeddings)
- Cosine similarity = dot(a, b) when both are L2-normalized

### 7. Clustering Algorithm
- **File**: `PersonMatcher.kt`
- **Algorithm**: Incremental single-linkage clustering with threshold-based assignment
- For each new face:
  1. Compare against ALL faces in each existing person cluster
  2. Take MAX cosine similarity (single-linkage support)
  3. If best support >= threshold → assign to that person
  4. Otherwise → create new person

### 8. Cluster Merging (Reconciliation)
- **File**: `PersonMatcher.kt` - `reconcileSplits()`
- **Algorithm**: Iterative mean-cluster merging
- Compares normalized cluster means (weighted by cluster size)
- Merge threshold: 0.70 (`MeanMergeThreshold`)
- Runs AFTER each indexing pass

### 9. Person/Album Assignment
- **File**: `PersonMatcher.kt` - `assignFaceToPerson()` / `createPerson()`
- Faces stored in Room `faces` table with `personId` foreign key
- Exemplar faces tracked per person (up to 10)

### 10. Persistence/Database
- **Files**: `db/FaceEntity.kt`, `db/PersonEntity.kt`, `db/FaceDao.kt`, `db/PersonDao.kt`
- Embeddings stored as JSON in `embeddingJson` column
- Fast-read overlay: `FaceVectorIndex` (memory-mapped binary file)

### 11. UI
- **Files**: `PersonDetailActivity.kt`, `PersonAlbumsActivity.kt`

---

## Current Thresholds

| Threshold | Value | Location | Purpose |
|-----------|-------|----------|---------|
| GoodFaceMatchThreshold | 0.76 | PersonMatcher.kt:383 | Incremental clustering for good-quality faces |
| BadFaceMatchThreshold | 0.84 | PersonMatcher.kt:386 | Stricter threshold for low-quality/sideways faces |
| MeanMergeThreshold | 0.70 | PersonMatcher.kt:392 | Post-clustering merge of split clusters |
| SplitThreshold | 0.70 | ClusterMaintenance.kt:252 | Detect bimodal clusters for split suggestions |
| MergeThreshold | 0.70 | ClusterMaintenance.kt:255 | Suggest merges between person centroids |
| LowQualityThreshold | 0.35 | FaceAnalyzer.kt:162 | Flag low-quality faces |

---

## Identified Issues

### ROOT CAUSE: Overly Strict Incremental Clustering + Order-Dependent Assignment

The current algorithm has several problems that cause same-person fragmentation:

1. **Single-linkage with high threshold (0.76)**: While single-linkage (max similarity to any member) should help connect varied faces, the 0.76 threshold is too high for challenging cases like:
   - Frontal vs profile faces
   - Different lighting conditions
   - Glasses vs no glasses
   - Aging/appearance changes

2. **No multi-stage matching**: The current algorithm makes a binary decision at the incremental threshold. There's no "borderline" zone that triggers secondary verification.

3. **Order-dependent clustering**: Processing order matters significantly. Early faces set the cluster centroids, and later faces must match those specific faces rather than the overall identity distribution.

4. **Mean-merge threshold (0.70) is LOWER than incremental threshold (0.76)**: This is backwards! The reconciliation pass can merge clusters at 0.70, but during incremental clustering, faces need 0.76 to join. This creates a hysteresis where:
   - Face A creates Person 1
   - Face B (similarity 0.73 to A) creates Person 2
   - Later reconciliation sees A↔B centroid similarity of 0.72 and merges them
   - BUT: if reconciliation hasn't run yet, user sees two separate people

5. **Bad face threshold (0.84) is extremely strict**: Poor quality faces need 0.84 similarity to join an existing cluster, which is very high for ArcFace embeddings.

6. **Cluster representative selection**: While the code stores multiple exemplars, the matching compares against ALL faces equally. A single poor-quality face in a cluster can distort perception of the identity.

---

## Recommended Fix Strategy

### Primary Fix: Lower the Incremental Clustering Threshold

Based on typical ArcFace/MobileFaceNet performance:
- Same-person similarities typically range 0.65-0.95 depending on pose/lighting
- Different-person similarities typically range 0.10-0.50
- A threshold around 0.60-0.65 provides good separation

**Change**: `GoodFaceMatchThreshold` from 0.76 → 0.62

This is NOT a blind change - it's based on:
1. ArcFace literature showing 0.60-0.65 as effective threshold
2. The existing `MeanMergeThreshold` of 0.70 already allows merges at that level
3. Single-linkage clustering naturally connects chains of similar faces

### Secondary Fix: Improve Reconciliation

The reconciliation pass should be more aggressive about merging borderline cases:
- Keep `MeanMergeThreshold` at 0.70 for confident merges
- Add a secondary check for clusters with few faces (< 3) that are close (0.65-0.70)

### Tertiary Fix: Quality-Weighted Matching

When comparing a new face against an existing person:
- Weight similarities by the quality score of the reference face
- High-quality reference faces should have more influence than poor-quality ones

---

## Files to Modify

1. **PersonMatcher.kt**: Adjust thresholds, potentially add quality-weighted matching
2. **ClusterMaintenance.kt**: Potentially adjust merge thresholds
3. Optionally add diagnostic logging for similarity distributions
