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
| `MainActivity` | God activity: browse/search/album/smart-album/folder modes, WorkManager orchestration, all UI state, Smart Cleanup launch, revamped search (filter chips + sort/filter sheet + image-to-image). Owns the **persistent top search box** (used in both browse + search), full-screen **loading overlay** (faded out on first render), **justified-rows collage** builder, **auto-pin defaults** + albums **onboarding card** |
| `ViewerActivity` | Full-screen pager: spring-physics swipe-to-dismiss (single-finger gated), Metro **key-value Info bottom sheet** (filename/date/size/dims/duration/location/device/lens/settings/path/tags), top-bar title+date, favorite/info/overflow actions, flat bottom action row (Share/Edit/Wallpaper/Delete), **Find similar** (image-to-image), Metro delete dialog, video controls |
| `SmartCleanupActivity` | Dedicated Metro screen: CLIP-detected clutter tiles + storage summary, per-category selectable grid, MediaStore delete. Reads `CleanupResultStore`, observes `CleanupWorker`; progress bar + pause/resume/stop |
| `SettingsActivity` | Metro preferences screen: Collage layout, Grid columns, Pinned-in-collections, Index-only-while-charging, Clear cleanup cache, About. Writes `IndexPreferences`; MainActivity re-applies on return |
| `CleanupAnalyzer` | Pure logic: near-dup + similar grouping (embeddings), zero-shot category classify, blur/dark/bright via `ImageStats`, low-res via metadata. Resumable (scannedUris) + streaming (onPartial) |
| `CleanupWorker` | `CoroutineWorker` (foreground, parallel to `IndexWorker`). Full-library scan, writes `CleanupResultStore` incrementally, resumes from scanned set, pausable via `IndexPreferences.isCleanupPaused` |
| `CleanupResultStore` | JSON file (`filesDir/cleanup_results.json`): per-category uris + suggested-delete subset + scannedUris + progress/complete |
| `CleanupHandoff` | Strong-ref hand-off of candidate image list + indexedCount to `SmartCleanupActivity` (mirrors `ViewerItemsHolder`) |
| `GalleryRepository` | Data + AI core: MediaStore queries, embedding index (binary file), metadata index, search |
| `DbRepository` | Room facade: media metadata, EXIF, favorites, tags, tag-media cross-refs |
| `ImageEncoder` | MobileCLIP S2 FP16 vision ONNX. Input: `[N,3,256,256]` CHW float. Output: L2-norm embedding |
| `TextEncoder` | MobileCLIP text INT8 ONNX. Wraps `ClipTokenizer`. Output: L2-norm embedding |
| `ClipTokenizer` | Pure-Kotlin BPE tokenizer. Context length 77. Prepends `"a photo of "` automatically |
| `IndexWorker` | `CoroutineWorker` (WorkManager). Foreground service. Pause/resume via `IndexControlReceiver` |
| `QueryExpander` | Weighted embedding from WordNet synonyms/hypernyms/hyponyms (weights: 1.0/0.85/0.6/0.5) |
| `StructuredSearch` | Query parser: extracts filter chips (favorite, album, mime, ext, date, tag, EXIF, ISO, focal) |
| `MetadataSearch` | TF-IDF-style keyword search over filename/bucket/mime/date fields |
| `ImageAdapter` | RecyclerView adapter: grid + **justified-rows collage** layouts (per-photo span/height precomputed from aspect ratio), sticky date headers, selection, album rows, **smart-album onboarding card** (`onCreateSmartAlbum` cb) |
| `AlbumPinStore` | SharedPreferences JSON array of pinned album IDs (ordered). Tracks an `initialized` flag so default pins apply only once |
| `SmartAlbumStore` | SharedPreferences JSON array of smart album definitions (name, prompt, member Uris, cover) |
| `FavoritesStore` | Wraps Room `FavoriteDao`. Migrates legacy SharedPrefs on first run |
| `IndexPreferences` | SharedPrefs: selected albums, last-indexed timestamp, thread count, grid columns, layout mode |
| `DesignTokens` | All constants: sizes, durations, caps, column counts, thresholds |
| `WordNetExpansionDictionary` | Loads `photo_synonyms.json.gz` from assets at runtime |
| `OnnxSessionOptions` | Creates ORT sessions: 4 threads, ALL_OPT, NNAPI intentionally disabled |
| `EmbeddingUtils` | `l2Normalize()` + `cosineSimilarity()` (dot product on pre-normalized vectors) |
| `FastScrollIndicator` | Custom View: animated thumb, track line, 64dp touch target from right edge. Tracks on every scroll (drag/fling/programmatic); drag jumps via `scrollToPositionWithOffset` (smooth on variable-height grids) |
| `StickyHeaderDecoration` | RecyclerView `ItemDecoration` for floating date headers |
| `MediaPagerAdapter` | ViewPager2 adapter for viewer pages: Glide image load (spinner via `RequestListener`), pooled ExoPlayer per holder tracked in `SparseArray`, **center play/pause/replay button**, **mute toggle** (session-wide), video scrubber (250ms poll + live seek), `onPlayStateChanged` callback |
| `ViewerItemsHolder` | Strong-ref hand-off of the media list to `ViewerActivity` (avoids Binder size limit); cleared via `release()` |

---

## Key Data Flows

### Indexing Flow
**Consent-gated:** indexing never auto-starts. First launch shows a one-time plain-language consent dialog (battery note + "search by describing your photos") with Start now / Choose folders / Not now. `IndexPreferences.isIndexConsentGiven` gates `maybeStartBackgroundIndexing()`. Side panel item toggles start ↔ pause ↔ resume (`onIndexDrawerAction` + `updateIndexDrawerLabel`). Settings "index only while charging" adds a `Constraints.setRequiresCharging(true)` to the work request. **All** enqueue paths (initial start, settings re-apply, notification "Resume") build the request through the single `IndexWorker.buildWorkRequest(context, selection)`, which reads `IndexPreferences.isChargingOnlyIndexing` at build time.
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
  → query = effectiveQuery()  [free text + active filter chips + Show filter token]
  → StructuredSearch.parse(query) → ParsedQuery (filters + textQuery)
  → parsedQuery.filterItems(items, favorites, lookup)  [structured filters]
  → MetadataSearch (unless AiOnly mode)
  → repo.search(textQuery) [semantic, unless MetadataOnly mode]
      → QueryExpander.buildWeightedEmbedding(query)  [if WordNet loaded]
      → TextEncoder.encode() × N query variants
      → cosine similarity over embedding map; threshold 0.19f
  → buildMergedPhotoSearchResults()  [merge + dedupe, NO hard cap]
  → renderSearchResults() → applySortAndShow()
      → Relevance: flat ranked grid, paginate 30 at a time (infinite)
      → Newest/Oldest: month-grouped timeline cells (cap 1500), pagination off

Search UI (revamp):
  - Search bar: magnifier / query-image thumbnail + input + × clear
  - Header row: "Photos · N" summary + "Sort & filter" funnel button
  - Active filter chips (removable) + quick suggestion pills (tap to add)
  - Sort & filter BOTTOM SHEET (sheet_search_filter.xml): Sort (Relevance/Newest/Oldest),
    Match (Smart+text / Smart only / Text only → SearchMode), Show (All/Favorites/Screenshots)
  - On-image source badges = sparkle icon (semantic) + tag icon (text match)

Image-to-image search:
  ViewerActivity top-bar image-search button (similarBtn) → PopupMenu:
    • "Search whole image"  → finish with ExtraFindSimilarUri
    • "Search part of image" → crop mode (CropOverlayView: drag corner-to-corner, resize handles,
        rule-of-thirds; zoom/rotation reset, chrome hidden) → finish with ExtraFindSimilarUri +
        ExtraFindSimilarCrop (FloatArray [l,t,r,b] normalized 0..1 in displayed-image space)
  → MainActivity.searchSimilarImage(uri, cropRect?)
      → cropRect == null: repo.imageEmbedding(uri)  [stored, or encode on demand]
      → cropRect != null: repo.imageEmbeddingForRegion(uri, region)  [decodeOrientedBitmap @2048px,
          EXIF-applied, crop region, CLIP encode live — never cached]
      → repo.searchByEmbedding(emb, floor 0.55, limit 500) over ALL embeddings
      → renderSearchResults(); search bar shows full or cropped-region thumbnail
```


### Smart Cleanup Flow
```
Drawer "smart cleanup" → CleanupHandoff.items/indexedCount set → SmartCleanupActivity
  onCreate → load CleanupResultStore (instant tiles) → enqueue CleanupWorker (KEEP) → observe

CleanupWorker.doWork()  [foreground, parallel to IndexWorker, unique "gallery_smart_cleanup"]
  → if IndexPreferences.isCleanupPaused → return
  → repo.getImageItemsForAlbumIds(emptySet()) [all images] + repo.allEmbeddings() + sizes
  → resume: load store (prior quality findings + scannedUris)
  → CleanupAnalyzer.analyze(items, embeddings, sizes, encodeText, imageStats, onProgress, onPartial, resumeQuality, scannedUris)
      → Duplicates (cosine ≥0.97) / Similar (≥0.93) via union-find
      → zero-shot classify → Likely clutter (memes+stickers+emoji), Screenshots, Documents, Receipts, QR
      → onPartial(report) after fast pass → store.save (streaming)
      → quality pass: decode each remaining photo → Blurry/Dark/Bright; Low-res via metadata
      → store.save every ~1.5s + setProgress(done,total)
  → final store.save(complete=true)

SmartCleanupActivity observes worker progress → reload store → renderTiles (live grow)
  Categories sorted by reclaimable size; tap → selectable grid (suggested pre-selected) → delete
  Pause/Resume (cancel + IndexPreferences.setCleanupPaused; resume re-enqueues, continues from scannedUris) · Stop
  Auto re-scans (resume) when indexing completes
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
- **Cleanup results:** `filesDir/cleanup_results.json` — per-category uris + suggested + scannedUris + progress (written by `CleanupWorker`)
- Both binary indexes use atomic write (`.tmp` → rename)

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
GRID_SPAN_COUNT = 6          // legacy collage span (unused by justified builder)
COLLAGE_SPAN_COUNT = 12      // justified-rows collage grid resolution
COLLAGE_TARGET_ROWS_PER_WIDTH = 2.3f  // ~images per row baseline (lower = bigger thumbnails)
COLLAGE_MIN_ASPECT = 0.55f   COLLAGE_MAX_ASPECT = 2.4f  // aspect clamps
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
`POST_NOTIFICATIONS` is requested at runtime on API 33+ (`MainActivity.ensureNotificationPermission`) so index/cleanup foreground-progress notifications appear (off by default otherwise).

---

## UI Modes & Navigation
- **Mode enum:** `Browse | Search | AlbumDetail | FolderDetail | SmartAlbumDetail`
- **Section enum:** `Collection | Videos | Albums | Favorites | Folders`
- **SearchMode enum:** `Hybrid | AiOnly | MetadataOnly` (selected in the Sort & filter sheet "Match")
- **SortMode enum:** `Relevance | Newest | Oldest` (search results)
- **ShowFilter enum:** `All | Favorites | Screenshots` (search "Show" → fav=yes / is=screenshot tokens)
- Navigation: DrawerLayout (left, incl. "smart cleanup") + bottom nav bar (5 tabs) + back stack via `OnBackPressedCallback`
- Viewer: launched via `viewerLauncher` (ActivityResultContracts), returns `ExtraContentChanged` bool + optional `ExtraFindSimilarUri`
- Smart Cleanup: launched via `cleanupLauncher`, returns `ExtraContentChanged` bool

---

## Solved Issues (do NOT re-introduce these fixes)
1. **NNAPI disabled** — software fallback caused WorkManager timeout → `OnnxSessionOptions` uses CPU/XNNPACK only
2. **Video shared element** — `startPostponedEnterTransition()` must be called after thumbnail load in `ViewerActivity`
3. **Glide black square thumbnails** for videos — must use `RequestOptions.frameOf(0)` or equivalent
4. **Index corrupt recovery** — `loadIndex()` wraps in `runCatching`, deletes corrupt file, returns empty map
5. **Batch OOM fallback** — `IndexWorker` catches `OutOfMemoryError`, returns `Result.failure()`
6. **FavoritesStore migration** — legacy SharedPrefs → Room migration runs in `init` block on IO dispatcher
7. **Smart albums not visible in Collections page pinned header** — `renderMediaSection` was building `pinnedAlbums` from `albums` only (MediaStore), missing smart albums. Fixed by using `albumById` merge (real + smart) same as `renderAlbums()`.
8. **Viewer image spinner timing** — `photoView.post {}` hid the spinner before Glide finished decoding. Now driven by a Glide `RequestListener` (`onResourceReady`/`onLoadFailed`), which also fires `startPostponedEnterTransition()`.
9. **Pinch-to-zoom vs swipe-to-dismiss** — dismiss drag is gated on `event.pointerCount == 1`; a second finger (`ACTION_POINTER_DOWN`) aborts an in-progress drag and snaps the media back.
10. **ExoPlayer leak on off-screen pages** — players are tracked in a `SparseArray` in `MediaPagerAdapter`; `onDestroy` calls `adapter.releaseAll()` instead of iterating only attached RecyclerView children. Double-bind during prefetch is guarded by `boundUri` check.
11. **`ViewerItemsHolder` GC under memory pressure** — switched from `WeakReference` to a strong reference cleared via `release()` (called in `onCreate` after copy and again in `onDestroy`).
12. **Video duration overloaded `locationName`** — `PhotoMetadata` now has a dedicated `durationMillis` field shown in its own info-panel duration row.
13. **Gesture conflicts in viewer** — diagonal swipes misread as dismiss (only checked `deltaY`, never angle); info panel tap didn't close; stale captions during fast swiping (uncancelled metadata coroutines); info panel drag freeze at start. Fixed via: angle-aware gesture classification with `GestureDirection` enum locked at 10dp slop threshold (1.2x horizontal-to-vertical ratio); `onMediaTap` checks `infoVisible` first; `viewPager.isUserInputEnabled` toggled with panel state; cancellable `metadataJob` with position guard; info panel touch listener always returns `true` to consume from first frame.
14. **Video play/pause auto-hide was inverted** — controls used to stay forever while playing and vanish while paused. Now images + playing videos auto-hide after 3s; a paused/ended video keeps controls visible. Play/pause/replay icons are driven by the player's `onIsPlayingChanged` (never desync). Initial video autoplays via `playWhenReady`.
15. **Cleanup froze on "scanning N/total"** — full-library quality decode ran before any tile showed. Now `CleanupAnalyzer` streams partial results (fast embedding categories first) and the scan runs in `CleanupWorker` (background, resumable) writing `CleanupResultStore` incrementally; UI loads the store live.
16. **Notifications off by default (API 33+)** — `POST_NOTIFICATIONS` was declared but never requested at runtime → request added in `MainActivity`.
17. **Search showed raw query syntax** — pills used to inject `orientation=portrait`-style tokens into the EditText. Filters are now removable chips backed by `activeFilters`; the box holds only free text. `effectiveQuery()` composes text + chips + Show filter at search time.
18. **Collage layout misaligned** — old approach mixed a full-width "featured" photo, a fixed 3-tile collage cell, and 1/3-span photos with tile sizes computed in the ViewHolder that didn't match the grid span widths (gaps, non-square tiles). Replaced with a **justified-rows** builder (`appendJustifiedRows`): photos grouped into rows by aspect ratio (`MediaItem.width/height`), each row scaled to a uniform height filling the width, spans distributed across `COLLAGE_SPAN_COUNT` (12). `GalleryCell.Photo` carries `collageSpan` + `collageHeightPx`. Last partial row stays left-aligned at target height. `GalleryCell.Collage` + featured are no longer emitted.
19. **No loading state on launch** — added a full-screen `loadingOverlay` (accent spinner + "Loading your gallery") shown on open, faded out via one-shot `dismissLoadingOverlay()` on first content layout (and on fatal error).
20. **Fast scroll bugged / scroll jerky** — thumb only updated while actively dragging the page (jumped on fling); now tracks on every scroll. Drag now jumps via `scrollToPositionWithOffset` (reliable on variable-height grids) instead of `scrollBy` against an estimated range. Grid smoothness: `setHasFixedSize(true)`, larger view cache, Glide `dontAnimate()`.
21. **Oval pills + weak selection color** — `search_filter_chip_bg`/`_active_bg` had an 18dp radius (oval) and a washed-out navy selected fill. Now 6dp rounded-square with a solid accent (`#3B9EFF`) selected state.
22. **Smart album dialog was bland** — replaced the default `AlertDialog` (system title/buttons) with a custom Metro window (`Theme.GallerySearch.Dialog` + `dialog_metro_bg`): sparkle title, helper text, labelled name/description fields, tappable prompt suggestion chips, flat accent Create/Cancel.
23. **Sort & filter sheet misaligned** — inconsistent insets (root 8dp + child 16dp = 24dp vs buttons 22dp). Unified to a 20dp inset across title/headers/options/footer; selected option label now accent-colored; APPLY uses the accent pill button; divider above footer.
24. **Collage first render showed thin stale-span strips** — GridLayoutManager caches span index/group lookups and didn't refresh on `notifyDataSetChanged`, so on the first pass collage tiles laid out with stale (span-1) widths before correcting on relayout. Fixed in `resetGridToTop()`: disable + invalidate `spanSizeLookup` index/group caches on every cell replace. Also bumped collage thumbnail size (`COLLAGE_TARGET_ROWS_PER_WIDTH` 3.1 → 2.3).
25. **"Index only while charging" ignored on notification Resume** — `IndexControlReceiver.resumeIndexing()` built its own work request with no constraints, so resuming from the notification bypassed the charging preference. Each enqueue path had its own request-builder, inviting drift. Fixed by centralizing on `IndexWorker.buildWorkRequest(context, selection)` (reads the pref at build time, applies `setRequiresCharging`); `MainActivity` and `IndexControlReceiver` both route through it. Settings toggle while indexing re-enqueues with `ExistingWorkPolicy.REPLACE` so the constraint swaps on the running job.
26. **Screen titles centered/inconsistent** — the shared `screenTitle` was `gravity=center` with 56dp side padding. Now `start`-aligned with a 16dp start inset (the app standard, matching the Pinned Albums header), so collections/albums/favorites/videos/folders headers all left-align consistently. `paddingEnd=56dp` kept so the title clears the `+` add button on the Albums page.
27. **Fast-scroll bar missing on Collections + spanned full screen** — visibility was gated on month-`Header` count (`> 2`), so a Collections page of recent photos (≤2 months) never showed the bar. Now `updateFastScrollVisibility()` posts after layout and shows when the scroll range exceeds the viewport by `FAST_SCROLL_MIN_RATIO` (1.5×) — works on every image listing regardless of date spread. Also the `FastScrollIndicator` track spanned the whole screen (behind the search bar / nav); it now follows the RecyclerView's top/bottom padding (`trackTop()`/`trackBottom()`) so the thumb + touch area stay within the visible listing in every mode.
28. **Smart-album onboarding card not dismissable** — the Albums onboarding card had no close affordance. Added a top-right ✕ (`onboardingDismissBtn`) wired through `ImageAdapter.onDismissSmartAlbumOnboarding`; dismissal persists via `IndexPreferences.isSmartAlbumOnboardingDismissed`/`set…` and re-renders the page. `renderAlbums()` shows the card only when `smartAlbums.isEmpty() && !dismissed`.

---

## Active Roadmap Phase (as of last commit)
**Smart Cleanup (CLIP-backed declutter) — complete & live.** Dedicated `SmartCleanupActivity` reached via drawer "smart cleanup". A background `CleanupWorker` (foreground, parallel to indexing) scans the whole library and streams results to `CleanupResultStore`; the screen shows tiles instantly and grows them live with a progress bar + pause/resume/stop. Categories: Duplicates, Similar photos, **Likely clutter** (memes+stickers+emoji), Screenshots, Documents, Receipts, QR codes, Blurry, Too dark, Overexposed, Low quality. Storage-recoverable summary card; smart pre-selection; reuses MediaStore delete. Never auto-deletes; no `.onnx`/tokenizer changes.

**Video player overhaul — complete.** Center play/pause/replay button, session-wide mute toggle, live scrubber, fixed auto-hide, consistent autoplay + player-driven icon state.

**Viewer Metro redesign — complete.** Filename+date in top bar (extension stripped), favorite/info/overflow actions, flat bottom action row (Share/Edit/Wallpaper/Delete), content-sized key-value **Info bottom sheet** with dim scrim, **Find similar** (image-to-image) in overflow.

**Search revamp — complete.** Icon source badges (sparkle/tag), no result cap (infinite pagination), Sort & filter **bottom sheet** (Sort/Match/Show), date-grouped results for date sorts, removable filter chips, image-to-image search with source thumbnail in the bar.

**Albums & UI polish — complete.** Persistent top search box shared by browse + search (trailing icon swaps search↔dismiss, hides during multi-select); smaller timeline (22sp) and pinned-albums (13sp) headers. Justified-rows collage layout option. Full-screen loading overlay on launch. Fast-scroll tracks all scrolls + position-based drag jumps. Auto-pin of the 4 most relevant device albums on first run (`ensureDefaultPins`/`albumRelevanceScore`). Albums onboarding card to create a smart album. Metro-redesigned smart-album creation dialog. Rounded-square pills with accent selection. Aligned Sort & filter sheet.

**Last commit:** Fix sort/filter sheet alignment, collage first-render span-cache glitch, and larger collage thumbnails
