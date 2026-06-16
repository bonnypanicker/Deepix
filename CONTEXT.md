# Deepix — AI Session Context

**Package:** `com.devomind.gallerysearch`  
**App name:** Deepix (internal: GallerySearch)  
**Stack:** Kotlin · XML Views · ViewBinding · Room · WorkManager · ONNX Runtime · Glide · ExoPlayer  
**minSdk:** 26 · **compileSdk:** 35 · **targetSdk:** 35  
**Design:** AMOLED Metro/WP10 — pure black (#000000) surfaces, accent #3B9EFF, Fluent icons

---

## Architecture — One-Line Roles

| File | Role |
|---|---|
| `GallerySearchApp` | Application class. Owns `SharedEncoders` (lazy singleton for ONNX sessions) |
| `MainActivity` | God activity: browse/search/album/smart-album/folder modes, WorkManager orchestration, all UI state |
| `ViewerActivity` | Full-screen pager: spring-physics swipe-to-dismiss, EXIF panel, in-app ExoPlayer video |
| `GalleryRepository` | Data + AI core: MediaStore queries, embedding index (binary file), metadata index, search |
| `DbRepository` | Room facade: media metadata, EXIF, favorites, tags, tag-media cross-refs |
| `ImageEncoder` | MobileCLIP S2 FP16 vision ONNX. Input: `[N,3,256,256]` CHW float. Output: L2-norm embedding |
| `TextEncoder` | MobileCLIP text INT8 ONNX. Wraps `ClipTokenizer`. Output: L2-norm embedding |
| `ClipTokenizer` | Pure-Kotlin BPE tokenizer. Context length 77. Prepends `"a photo of "` automatically |
| `IndexWorker` | `CoroutineWorker` (WorkManager). Foreground service. Pause/resume via `IndexControlReceiver` |
| `QueryExpander` | Weighted embedding from WordNet synonyms/hypernyms/hyponyms (weights: 1.0/0.85/0.6/0.5) |
| `StructuredSearch` | Query parser: extracts filter chips (favorite, album, mime, ext, date, tag, EXIF, ISO, focal) |
| `MetadataSearch` | TF-IDF-style keyword search over filename/bucket/mime/date fields |
| `ImageAdapter` | RecyclerView adapter: grid/collage layouts, sticky date headers, selection, album rows |
| `AlbumPinStore` | SharedPreferences JSON array of pinned album IDs (ordered) |
| `SmartAlbumStore` | SharedPreferences JSON array of smart album definitions (name, prompt, member Uris, cover) |
| `FavoritesStore` | Wraps Room `FavoriteDao`. Migrates legacy SharedPrefs on first run |
| `IndexPreferences` | SharedPrefs: selected albums, last-indexed timestamp, thread count, grid columns, layout mode |
| `DesignTokens` | All constants: sizes, durations, caps, column counts, thresholds |
| `WordNetExpansionDictionary` | Loads `photo_synonyms.json.gz` from assets at runtime |
| `OnnxSessionOptions` | Creates ORT sessions: 4 threads, ALL_OPT, NNAPI intentionally disabled |
| `EmbeddingUtils` | `l2Normalize()` + `cosineSimilarity()` (dot product on pre-normalized vectors) |
| `FastScrollIndicator` | Custom View: animated thumb, track line, 64dp touch target from right edge |
| `StickyHeaderDecoration` | RecyclerView `ItemDecoration` for floating date headers |

---

## Key Data Flows

### Indexing Flow
```
IndexWorker.doWork()
  → GalleryRepository.getNewImageUris() / getImageUrisForAlbumIds()  [MediaStore]
  → GalleryRepository.buildIndex(uris, onProgress)
      → chunked into batches of 4 (BatchSize)
      → producer coroutine: loads bitmaps, scales to max 512px edge
      → ImageEncoder.encodeBatch(bitmaps) → [N,3,256,256] ORT inference
      → saves every 20 items (SaveEvery)
  → GalleryRepository.rebuildMetadataIndex(allImages)
  → DbRepository.upsertMedia(allImages)
  → IndexPreferences.saveLastIndexedTime()
```

### Search Flow
```
MainActivity.submitSearch()
  → StructuredSearch.parse(query) → ParsedQuery (filters + textQuery)
  → parsedQuery.filterItems(items, favorites, lookup)  [structured filters]
  → MetadataSearch (unless AiOnly mode)
  → repo.search(textQuery) [semantic, unless MetadataOnly mode]
      → QueryExpander.buildWeightedEmbedding(query)  [if WordNet loaded]
      → TextEncoder.encode() × N query variants
      → cosine similarity over embedding map
      → threshold: 0.19f (SearchTuning.ScoreThreshold)
  → buildMergedPhotoSearchResults()  [merge + dedupe]
  → renderSearchResults() → paginate 20 at a time (cap 80)
```

### Smart Album Flow
```
MainActivity.createSmartAlbum(name, prompt)
  → runSearchPipeline(query, mode, candidateItems)  [shared search core]
      → MetadataSearch + repo.search() → merged results
  → SmartAlbumStore.upsert(SmartAlbum(name, prompt, memberUris, coverUri, …))
  → AlbumPinStore.pin(id)  → appears in PINNED section

MainActivity.renderAlbums()
  → albumPinStore.cleanup(realAlbumIds + smartAlbumIds)  [prevents pruning]
  → pinnedAlbums = pinnedIds.mapNotNull { albumById[it] }  [real + smart]

MainActivity.renderMediaSection() (Collections page)
  → albumPinStore.cleanup(realAlbumIds + smartAlbumIds)
  → pinnedAlbums = pinnedIds.mapNotNull { albumById[it] }  [same merge as Albums page]

Album-detail for smart albums:
  → albumDetailItems resolves from stored smartAlbum.memberUris (engine-rank order)
  → No re-search on open — instant from SharedPreferences data

Refresh: re-runs runSearchPipeline, updates stored memberUris + cover
```

### Index Storage
- **Embedding index:** `filesDir/embedding_index.bin` — custom binary (magic `0x47534958`, v2)
  - Format: `[magic][version][count]` then per-entry `[uriByteLen][uriBytes][embDim][floats...]`
- **Metadata index:** `filesDir/metadata_index.bin` — custom binary (magic `0x474d4458`, v1)
- Both use atomic write (`.tmp` → rename)

---

## Search Tuning Constants (`SearchTuning.kt`)
```kotlin
ScoreThreshold   = 0.19f   // cosine sim floor — real quality gate
PageSize         = 30      // per-page in paginator
DefaultTopK      = Int.MAX_VALUE  // no artificial cap
FallbackCount    = 0
MaxScoreDropRatio = 0.75f
```

## Design Tokens (selected critical ones, `DesignTokens.kt`)
```kotlin
GRID_DEFAULT_COLUMNS = 4     GRID_MIN_COLUMNS = 2     GRID_MAX_COLUMNS = 6
GRID_SPAN_COUNT = 6          // collage mode span
DISPLAY_CAP = 800            // max items shown in browse
SEARCH_METADATA_HARD_CAP = 80
SEARCH_INPUT_DEBOUNCE_MS = 180L
INDEX_BACKOFF_SECONDS = 10L
INDEX_LIVE_REFRESH_STEP = 20
SCREEN_TITLE_SIZE = 40f      // Metro-style large typography
```

---

## Room DB Schema (`gallery_metadata.db`, v2)
| Table | PK | Key columns |
|---|---|---|
| `media_metadata` | `uri` | bucketId, bucketName, mimeType, dateTaken, width, height, orientation |
| `exif_metadata` | `uri` | make, model, lensModel, fNumber, iso, gpsLat/Lng, dateTimeOriginal |
| `favorites` | `uri` | addedAt |
| `tags` | `id` (autoGen) | name (unique), color, createdAt |
| `media_tag_cross_ref` | composite(mediaUri, tagId) | — |

---

## ONNX Model Details
| Model | File | Precision | Input | Notes |
|---|---|---|---|---|
| Vision | `vision_model_fp16.onnx` | FP16 | `[N,3,256,256]` CHW floats | Fallback order: fp16→android_int8→base→int8 |
| Text | `text_model_int8.onnx` | INT8 | `input_ids[1,77]`, `attention_mask[1,77]` LongBuffers | |

**Preprocessing:** resize shortest edge → 256, center crop 256×256, CHW layout, `do_normalize=false` (rescale factor 0.00392... = /255).  
**Normalization constants (if enabled):** mean `[0.48145466, 0.4578275, 0.40821073]`, std `[0.26862954, 0.26130258, 0.27577711]`

---

## Permissions
```xml
FOREGROUND_SERVICE · FOREGROUND_SERVICE_DATA_SYNC · POST_NOTIFICATIONS
READ_MEDIA_IMAGES · READ_MEDIA_VIDEO · READ_MEDIA_VISUAL_USER_SELECTED
READ_EXTERNAL_STORAGE (maxSdkVersion=32)
```

---

## UI Modes & Navigation
- **Mode enum:** `Browse | Search | AlbumDetail | FolderDetail | SmartAlbumDetail`
- **Section enum:** `Collection | Videos | Albums | Favorites | Folders`
- **SearchMode enum:** `Hybrid | AiOnly | MetadataOnly`
- Navigation: DrawerLayout (left) + bottom nav bar (5 tabs) + back stack via `OnBackPressedCallback`
- Viewer: launched via `viewerLauncher` (ActivityResultContracts), returns `ExtraContentChanged` bool

---

## Solved Issues (do NOT re-introduce these fixes)
1. **NNAPI disabled** — software fallback caused WorkManager timeout → `OnnxSessionOptions` uses CPU/XNNPACK only
2. **Video shared element** — `startPostponedEnterTransition()` must be called after thumbnail load in `ViewerActivity`
3. **Glide black square thumbnails** for videos — must use `RequestOptions.frameOf(0)` or equivalent
4. **Index corrupt recovery** — `loadIndex()` wraps in `runCatching`, deletes corrupt file, returns empty map
5. **Batch OOM fallback** — `IndexWorker` catches `OutOfMemoryError`, returns `Result.failure()`
6. **FavoritesStore migration** — legacy SharedPrefs → Room migration runs in `init` block on IO dispatcher
7. **Smart albums not visible in Collections page pinned header** — `renderMediaSection` was building `pinnedAlbums` from `albums` only (MediaStore), missing smart albums. Fixed by using `albumById` merge (real + smart) same as `renderAlbums()`.

---

## Active Roadmap Phase (as of last commit)
**Phase 4 complete — Smart Albums (prompt-backed persistent albums).** Smart albums appear in the PINNED section of both the Albums page and the Collections page (when "pinned in collections" is enabled). Created via a two-field popup dialog (name + prompt), persisted in SharedPreferences, with Refresh/Rename/Edit Prompt/Delete/Unpin long-press menu. Members resolved from stored URIs in engine-rank order.

**Last commit:** fix: smart albums now visible in Collections page pinned header
