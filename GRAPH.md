# Deepix — File Dependency Graph

Use this file to navigate cross-file impact before editing. Each entry shows what a file imports and what imports it.

---

## God Nodes (highest connectivity)

| Node | Used by | Uses |
|---|---|---|
| `GalleryRepository` | MainActivity, IndexWorker, DbRepository | ImageEncoder, TextEncoder, MetadataSearch, QueryExpander, EmbeddingUtils, IndexPreferences, ActivityManager |
| `MainActivity` | (entry point) | GalleryRepository, DbRepository, ImageAdapter, FavoritesStore, AlbumPinStore, CollectionAlbumStore, AlbumRules, IndexPreferences, StructuredSearch, DesignTokens, SearchResultManager, ViewerActivity, ThreadBenchmark |
| `DesignTokens` | MainActivity, IndexPreferences, ImageAdapter, FastScrollIndicator | colors.xml |
| `IndexPreferences` | MainActivity, IndexWorker, IndexControlReceiver, GallerySearchApp, GalleryRepository, ThreadBenchmark | DesignTokens |

---

## Dependency Map

### AI / ML Layer
```
GallerySearchApp
  └── SharedEncoders (lazy; reads IndexPreferences.getOptimalThreadCount, fallback OnnxSessionOptions.DefaultThreadCount)
        ├── ImageEncoder(context, threadCount)  ←  AssetUtils, OnnxSessionOptions, OnnxOutput, EmbeddingUtils
        │     └── vision_model_fp16.onnx, preprocessor_config.json
        │     └── internal resolveVisionModelAssetName() — shared with ThreadBenchmark
        └── TextEncoder(context, threadCount)   ←  ClipTokenizer, OnnxSessionOptions, OnnxOutput, EmbeddingUtils
              └── text_model_int8.onnx, tokenizer.json, tokenizer_config.json

ThreadBenchmark (one-time, mutex-guarded; result cached in IndexPreferences)
  ├── called by MainActivity.ensureEncodersLoaded + IndexWorker.doWork (before encoder construction)
  ├── ImageEncoder.resolveVisionModelAssetName  (benchmarks the shipped model asset)
  ├── ImageEncoder.ImageSize  (synthetic input shape)
  └── IndexPreferences.getOptimalThreadCount / saveOptimalThreadCount
```

### Indexing Pipeline
```
IndexWorker
  ├── ThreadBenchmark.getOrBenchmark()  (one-time ORT thread tuning, cached)
  ├── GalleryRepository  (getImageUrisForAlbumIds[scope], buildIndex[reconciles: prune+add], rebuildMetadataIndex)
  │     ├── computeBatchSize()  (IndexPreferences override → ActivityManager isLowRamDevice/memoryClass → 2/4/6)
  │     ├── loadBitmap() → decodeOrientedBitmap()  (two-pass bounds decode ≤512px + EXIF orientation)
  │     ├── ImageEncoder  (encodeBatch via BatchEncoding; per-image fallback on batch failure, OOM escapes)
  │     ├── EmbeddingFreshness  (decide(embedded, recorded, current) → Encode | Current | Backfill)
  │     ├── MetadataSearch  (buildDocuments, indexFromDocuments)
  │     └── EmbeddingUtils  (l2Normalize, cosineSimilarity)
  ├── DbRepository  (upsertMedia, embeddingSignatures() before the pass, recordEmbeddingSources() per batch)
  ├── IndexPreferences  (isIndexPaused, saveLastIndexedTime, saveIndexBatchSizeOverride on OOM)
  ├── ForegroundBudget  (hasRoom() pre-flight and per-batch → IndexWaitReason.WaitingForForegroundBudget;
  │     the whole FGS span is written back in doWork()'s finally, by every foreground worker)
  └── IndexControlReceiver  (pause/resume PendingIntents)

GalleryRepository.saveIndex / saveMetadataIndex
  └── IndexCheckpoint  (payload → `*.tmp` → rename over base → delete journal; promotable() recovers a killed rename)
```

### Search Pipeline
```
MainActivity.submitSearch()
  ├── StructuredSearch.parse()  → ParsedQuery
  │     └── Filter subclasses: FavoriteFilter, AlbumFilter, ExtensionFilter,
  │         MimeFilter, DateFilter, TagFilter, MakeFilter, ModelFilter,
  │         IsoFilter, FocalLengthFilter, OrientationFilter, VideoFilter
  ├── parsedQuery.filterItems()
  ├── MetadataSearch.Index.search()  ← MetadataSearch.Document (per MediaItem)
  ├── GalleryRepository.search()
  │     └── QueryExpander.buildWeightedEmbedding()
  │           └── WordNetExpansionDictionary  (photo_synonyms.json.gz)
  │           └── TextEncoder.encode()  (per term + query variants)
  └── buildMergedPhotoSearchResults()  (no cap) → applySortAndShow()
        ├── Relevance → flat ranked grid, paginate 30 (infinite)
        └── Newest/Oldest → month-grouped timeline cells (cap 1500)

Search UI: search bar (× clear / query-image thumb) + "Photos · N" header + Sort&filter funnel
  └── sheet_search_filter.xml  (Sort: Relevance/Newest/Oldest · Match: SearchMode · Show: All/Favorites/Screenshots)
  active filter chips (activeFilters) + quick suggestion pills; effectiveQuery() = text + chips + showFilterToken()
  on-image badges: sparkle (semantic) + tag (text match)
  Filter subclasses incl. new StructuredSearch.ScreenshotFilter (is=screenshot)

Image-to-image: ViewerActivity top-bar image-search button (similarBtn) → whole image OR region crop → ExtraFindSimilarUri [+ ExtraFindSimilarCrop]
  → MainActivity.searchSimilarImage(uri, cropRect?) → repo.imageEmbedding()/imageEmbeddingForRegion() + repo.searchByEmbedding() (all embeddings)
```

### Smart Cleanup Pipeline
```
MainActivity drawer "smart cleanup" → CleanupHandoff → SmartCleanupActivity
  ├── CleanupResultStore.load()  (instant tiles)
  ├── WorkManager.enqueueUniqueWork("gallery_smart_cleanup", KEEP, CleanupWorker)
  └── observe CleanupWorker LiveData → reload store live → renderTiles + progress bar

CleanupWorker (foreground, parallel to IndexWorker)
  ├── GalleryRepository.getImageItemsForAlbumIds(emptySet()) + allEmbeddings() + encodeText()
  ├── decodeDhash(uri) → memoized dhashOf callback (one ≤128px decode per duplicate candidate)
  ├── CleanupAnalyzer.analyze(... dhashOf, onPartial, resumeQuality, scannedUris)
  │     ├── EmbeddingUtils.cosineSimilarity  (duplicates ≥0.97 / similar ≥0.93)
  │     ├── DuplicateVerifier.verify(group, sizeOf, dhashOf)  → confirmed deletables
  │     │     └── PhashUtils.distance (dHash, threshold 8) — resemblance lists, measurement pre-selects
  │     ├── zero-shot prompt vectors → Likely clutter / Screenshots / Documents / Receipts / QR
  │     └── ImageStats (decode) → Blurry / Dark / Bright ; metadata → Low-res
  ├── CleanupResultStore.save()  (incremental, throttled; carries dedupAnalyzed/dedupEligible)
  └── IndexPreferences.isCleanupPaused()  (pause/resume/stop)
```

### Saved collections (the three album kinds)
```
MainActivity.addAlbumBtn → showCreateAlbumMenu()  [MetroDropdownMenu, three rows]
  ├── "Smart album"  → FreeTier.smartAlbumsBlocked(count of kind==Smart) → ProPaywall.open | showCreateSmartAlbumDialog()
  │     └── createSmartAlbum(name, prompt)
  │           ├── runSearchPipeline(query, mode, candidateItems)
  │           │     ├── GalleryRepository.search()  + MetadataSearch
  │           │     └── buildMergedPhotoSearchResults()
  │           └── saveCollectionAlbum(album, pin = true)
  ├── "Folder album" → askFolderAlbumName()
  │     ├── MetroDialog.input(title,label,positive=Create,hint=folder_album_name_hint) → name
  │     ├── AlbumRules.albumFolderPath(albumFolderRootSegment(), AlbumFolderParent, name)
  │     │     ├── albumFolderRootSegment() ← IndexPreferences.getAlbumFolderRoot  ["Pictures"|"Documents"]
  │     │     └── null (name cleaned to nothing) → folder_album_bad_name message
  │     ├── collectionAlbums.find { id == folderIdFor(path) } → folder_album_exists message   [one folder, one album]
  │     └── createFolderAlbum(name) → saveCollectionAlbum(album, pin = true)
  │           └── MetroBanner folder_album_made(<path>)   [the folder is not on disk yet — see #39]
  └── "Tag collection" → showTagAlbumDialog(null) → refreshTagCache() first
        └── openTagAlbumDialog(existing)  [dialog_tag_album.xml, chips via createSearchPillView]
              └── saveTagAlbum(existing, name, chosen) → saveCollectionAlbum(album, pin = true)

saveCollectionAlbum(album, pin)              [the one write path for all three kinds]
  ├── CollectionAlbumStore.upsert(album)  → SharedPreferences persistence (JSON via org.json)
  ├── collectionAlbums = store.getAll()
  ├── AlbumPinStore.pin(album.id)  [an unpinned collection is an invisible one]
  └── renderCollectionAlbumDetail(album) | renderCurrentSection()

MainActivity.renderAlbums() / buildPinnedAlbumStrip()
  ├── refreshTagCacheForAlbums()   [only when a Tags album exists and tagCacheStale]
  ├── collectionAlbumViews() → GalleryRepository.Album(kind = …)
  │     ├── albumFolderRoots(): one tree shared by every Folder album, cached against the exact
  │     │     collectionItems list — node.itemCount / node.coverUri
  │     ├── Tags: ONE pass over the library fills every album's count + first cover (no per-album copy)
  │     └── Smart: the stored memberUris and stored cover
  └── AlbumPinStore.cleanup(validIds = device album ids + collectionAlbums ids)

Album-detail (any kind): Mode.CollectionAlbumDetail
  albumDetailItems → collectionAlbumItems(album)          [resolved from the library on every render]
    ├── Folder → AlbumRules.findFolderNode(...) → flattenFolderItems(node)   [same walk as the Folders page]
    ├── Tags   → AlbumRules.tagUris(album.tagNames, allTags, tagUriMap)      [union by NAME]
    └── Smart  → stored memberUris, in the order the search ranked them
  collectionAlbumScopeKey: "smart:<id>" for Smart, plain id otherwise   [keeps sort preference per album]

Refresh (Smart only — the live kinds have nothing stale to refresh)

Putting photos into a Folder album  [the one kind that can be written; the other two are rules, not files]
  folderOptionsBtn on Mode.CollectionAlbumDetail (nothing selected) → showCollectionAlbumMenu(record, anchor)
  └── "Add photos" → showAlbumAddPhotosDialog(album)
        ├── alreadyInAlbum = collectionAlbumItems(album) uris → candidates = imageItems - those
        │     └── candidates empty → MetroDialog.message(album_nothing_to_add)
        └── openAlbumAddPhotosDialog(album, candidates)          [dialog_album_add_photos.xml]
              ├── ImageAdapter: tap toggles selection; columns from Responsive.gridColumns,
              │     width from addOnLayoutChangeListener            [same wiring as SmartCleanupActivity]
              └── Copy here | Move here → AlbumFolderOps.copyInto(context, picked, album.folderPath)  [IO]
                    ├── BinLedger.hasRoomFor(usableSpace, Σ sizeOf) — the whole batch refused before a byte
                    └── copyOne() per photo: MediaStore insert (Q+: RELATIVE_PATH + IS_PENDING;
                          pre-Q: absolute DATA via publicDirFor + uniqueIn) → stream copy →
                          forceToDisk (MediaFileOps.resolvePath, only with StoragePermissions
                          .hasAllFilesAccess) → countBytes read-back → AlbumFolderOps.copyVerified
                          (written == expected && expected > 0) → publish IS_PENDING = 0
                          [any step that fails → discard() the row and count the photo as failed]
              → applyAlbumAddPhotos(result, move)
                    ├── result.copied empty → album_copy_none banner, nothing deleted
                    ├── move → refreshVisibleItems()?.join()   [album fills while originals still exist]
                    │           → performManagedDelete(result.copiedSources, outcomeLabel)
                    │             [ordinary policy: Recycle Bin → direct → createDeleteRequest consent]
                    ├── copy → banner + refreshVisibleItems()
                    └── maybeStartBackgroundIndexing()          [the copies are new files, so unindexed]
```

### Safe (encrypted photo locker) Pipeline
```
MainActivity drawer "safe" / multi-select "safe" → SafeActivity (safeLauncher, StartActivityForResult)
  ├── SafeManager  (session password in-memory; single-archive vault orchestration)
  │     ├── SafeStore  (SharedPrefs: salt+verifier, biometric blob/iv)
  │     ├── SafeCrypto (zip4j AES-256 add/list/extract/remove/verify; PBKDF2; AES-GCM thumbs;
  │     │              thumbnail decode straight from encrypted entry stream)
  │     ├── SafeKeystore (biometric-gated Keystore key → wraps password)
  │     ├── IndexPreferences.getSafeStorageRoot (pictures|documents) → vaultDir/masterZip path
  │     └── java.io.File  (DeepixSafe.zip at Pictures|Documents /Deepix Safe/ via All-files access)
  ├── WRONG_PASSWORD (orphaned zip / forgotten pw) → offerResetOrphanedVault → SafeManager.purgeVault
  │     (deletes zip across all roots + SafeStore.reset + thumbs) → fresh CREATED setup
  ├── Settings › Safe storage location → SafeManager.moveVault (relocate existing vault on root change)
  ├── BiometricPrompt (CryptoObject) → recover password → unlock
  ├── SafeItemAdapter (grid of decrypted thumbnails; async bindThumb)
  └── returns ExtraImportedUris → MainActivity.deleteUris() (delete originals = "move")
```

### UI Layer
```
MainActivity
  ├── ImageAdapter  (RecyclerView)
  │     ├── StickyHeaderDecoration
  │     ├── FastScrollIndicator
  │     └── ThumbnailScaleGestureListener
  ├── ViewerActivity  (via viewerLauncher)
  │     ├── ViewerItemsHolder  (strong-ref hand-off of media list; release()d in onCreate + onDestroy)
  │     ├── MediaPagerAdapter  (ViewPager2)
  │     │     ├── Glide (image, RequestListener → spinner + shared-element start)
  │     │     ├── ExoPlayer (per holder, SparseArray-tracked, releaseAll in onDestroy)
  │     │     ├── center play/pause/replay button + mute toggle (session-wide)
  │     │     └── scrubber: videoSeekBar + videoElapsed/videoTotal (onPlayStateChanged → activity auto-hide)
  │     ├── Info bottom sheet (item_info_row.xml rows) + dim scrim
  │     ├── top-bar image-search button (similarBtn) → whole-image OR region crop (CropOverlayView) → ExtraFindSimilarUri [+ ExtraFindSimilarCrop] → MainActivity.searchSimilarImage()
  │     ├── Gesture handling (angle-aware classification)
  │     │     ├── GestureDirection enum (UNDETERMINED → HORIZONTAL_PAGE | VERTICAL_DISMISS | VERTICAL_INFO)
  │     │     ├── downX/downY tracking, 10dp slop threshold, 1.2x ratio lock
  │     │     └── metadataJob cancellation (prevents stale captions)
  │     ├── WallpaperManager  (set-as-wallpaper, images only)
  │     ├── FavoritesStore → DbRepository
  │     └── ExifExtractor → ExifData
  ├── TagPickerDialog → DbRepository (TagDao)
  ├── SmartCleanupActivity  (via cleanupLauncher)
  │     ├── CleanupHandoff (items hand-off)
  │     ├── CleanupResultStore (load/observe live)
  │     └── CleanupWorker (WorkManager, foreground, parallel to IndexWorker)
  └── FolderNode (folder tree construction)
```

### Window shape (landscape)
```
Responsive  (pure decisions; the *For forms are host-tested in ResponsiveTest)
  ├── gridColumns(preference × width/smallestScreenWidthDp)  → ImageAdapter.gridColumnCount, GridLayoutManager.spanCount
  ├── albumCardSpan(preference)                             → ImageAdapter.albumCardSpanBase → AlbumViewHolder cover size
  ├── collageExtentWidthPx(rowWidth, referenceWidth)        → MainActivity.appendJustifiedRows (baked spans: collage must be rebuilt, not just rebound)
  ├── tileSizePx / cardsFitting                             → Bin/Safe squares, PersonAlbums people cards
  ├── bodyHeightPx                                          → MetroDialog body + option list, ViewerActivity info sheet, ImageAdapter empty/loading rows
  ├── titleTextSp / applyTitleText                          → Settings, Indexing, IndexedFolders, SmartCleanup, Bin, Safe, PersonAlbums hero titles
  └── cramped                                               → MainActivity bottom bar 64→56dp, grid bottom padding 84→68dp

Trigger: view.addOnLayoutChangeListener keyed on width (onConfigurationChanged runs before measure,
so widths read there are stale). Activities declare configChanges and are never recreated — except
VideoEditorActivity, which stays portrait-locked.

MainActivity.onGridWidthChanged → applyDensityPreferences → applyChromeForHeight →
  applySpanCountForLayout → relayoutCurrentListing (displayed slice only) → currentGridAnchor →
  scrollGridTo  → updateFastScrollVisibility
MediaPagerAdapter.reloadImageForSizeChange ← ViewerActivity.onConfigurationChanged
FirstRunActivity.rescaleForWindow → OnboardingPanelAdapter.rescaleVisiblePages
```

### Persistence Layer
```
RoomBatching  (db/RoomBatching.kt)
  ├── reads  MediaMetadataEntity/EmbeddingSourceEntity/FaceEntity .BindVariables
  └── used by DbRepository + FaceIndexWorker + FaceAnalyzer (workers hold DAO handles, not a repository)

DbRepository
  └── GalleryDatabase (Room singleton; SchemaVersion, MIGRATIONS, DestructiveFallbackFrom = 1,2)
        ├── MediaMetadataDao → MediaMetadataEntity
        ├── ExifMetadataDao  → ExifMetadataEntity
        ├── FavoriteDao      → FavoriteEntity
        ├── TagDao           → TagEntity + MediaTagCrossRef
        ├── PersonPhotoDao   → PersonPhotoEntity  (invalidateForReanalysis(uri) resets one photo's face state)
        ├── FaceDao          → FaceEntity         (idsForPhoto/deleteByPhoto; pairs with PersonDao.clearExemplarFaces)
        └── EmbeddingSourceDao → EmbeddingSourceEntity  (what each stored CLIP embedding was encoded from)

BinManager → BinLedger  (bin/data + bin/entries + *.binmeta; reconcile() at app start and before every move)
BinManager → MediaFileOps.copyToFile  (flush → fd.sync() → close(): the copy is on the platter before the original is deleted)
SafeManager → SafeWorkGuard  (import/encrypt vs lock; end() returns the lock duty to the last worker out)
res/xml/backup_rules.xml + data_extraction_rules.xml  (gallery_metadata.db/-wal/-shm excluded from cloud backup and device transfer — see DECISION_GATES Gate C)

AlbumPinStore  (SharedPreferences / JSONArray)
CollectionAlbumStore  (SharedPreferences "smart_albums" / JSONArray — all three saved kinds, one store)
IndexPreferences  (SharedPreferences; incl. isCleanupPaused, optimal_thread_count, index_batch_size_override)
CleanupResultStore  (JSON file: filesDir/cleanup_results.json)
ForegroundBudget  (SharedPreferences "foreground_budget" / "hourly_millis": `hour:millis` pairs, rolled 24 h window)
```

---

## Change Impact Matrix

| If you change... | Also check... |
|---|---|
| `SearchTuning.ScoreThreshold` | `GalleryRepository.search()`, `buildMergedPhotoSearchResults()` in MainActivity |
| `ImageEncoder.ImageSize` (256) | `preprocessor_config.json`, `ImageEncoder.preprocess()`, `GalleryRepository.buildIndex()`, `ThreadBenchmark` synthetic input |
| `ClipTokenizer.ContextLength` (77) | `TextEncoder.encode()` shape, `QueryExpander.getEmbedding()` |
| `GalleryDatabase` version (`SchemaVersion`) | Add the matching `Migration(n, n+1)` to `MIGRATIONS` and commit the new `app/schemas/com.devomind.gallerysearch.db.GalleryDatabase/<n>.json`; `MigrationGraphTest` fails the build when the chain stops short |
| `embedding_source` columns / `MediaSignature` fields | `EmbeddingFreshness.decide`, `DbRepository.recordEmbeddingSources`, `MediaItem.embeddingSignature()` — a field added here must be recorded, and an existing library's rows read back as `null` (Backfill), not as a mismatch |
| `EmbeddingFreshness.StaleSignature` | `DbRepository.invalidatePhotoForReanalysis` (the editor's overwrite) — it must stay impossible for a real file to match, or edited photos stop re-encoding |
| `IndexCheckpoint` staging name (`*.tmp`) | `GalleryRepository.loadIndex`/`loadMetadataIndex` promote a staged base when `promotable()`; `verifyModelAssets` and the journal's own `.bin.journal` suffix are separate files |
| `BinLedger` layout (`bin/data`, `bin/entries`, `.binmeta`) | Existing bins on a device are read by `reconcile()`/`list()`; a rename strands photos that are already in the bin — `adoptLegacyIndex` is the precedent for converting, not dropping |
| `BinLedger.copyComplete` / quota margin | `BinManager.moveToBin` ordering (copy → verify → delete original) and `hasRoomFor`; loosening either re-opens the lost-photo path |
| `SafeWorkGuard` duty semantics | `SafeManager.guarded`/`lock`/`applyLock` and `onStop` — `end()` returning true means a worker owes the lock; a second implementation of "busy" would let an import race the lock again |
| `BinManager.RETENTION_MS` | `bin_retention_note` (string resources promise the same length) and `purgeExpired()` which runs on every cold start — shortening it deletes photos people are still expecting to find, and no test can recover them |
| `ForegroundBudget.CapMillis` / `StopReserveMillis` | The platform's own 6 h per rolling 24 h grant (`dataSync` FGS, Android 14+) — raising the cap past it trades the deferred run for the `RemoteServiceException` crash again; the reserve is the time left to checkpoint and stop cleanly |
| `ForegroundBudget` window (`WindowHours`) | `IndexWorker.scheduleResumeAfterForegroundRefill()`'s delay and every worker's `finally` bookkeeping — the ledger must roll like the platform's window or old spend is counted twice |
| `GalleryRepository.computeBatchSize()` (2/4/6 + override) | Memory pressure on low-RAM devices; `buildIndex()` chunking; OOM override persisted via `IndexPreferences.saveIndexBatchSizeOverride` (IndexWorker OOM path) |
| `MainActivity.BROWSE_PAGE_SIZE/MAX` (120/320) | Browse timeline page size; grid is paged (no hard item cap) |
| `DesignTokens.SEARCH_METADATA_HARD_CAP` (80) | Search pagination cap in `MainActivity` |
| `CollectionAlbumStore.MAX_SMART_MEMBERS` (800) | Stored URI count per smart album in `createSmartAlbum()` |
| `CollectionAlbumStore.SMART_PREFIX` ("smart:") / `FOLDER_PREFIX` ("folder:") / `TAGS_PREFIX` ("tag:") | ID prefix — the fallback that reads `kind` off a record written before the field existed, and `folderIdFor(path)`'s one-folder-one-album key |
| `CollectionAlbum` data class fields | JSON serialization in `albumToJson()`/`parseAlbum()` — `kind`, `folderPath` and `tagNames` included |
| `IndexPreferences` SharedPrefs keys | Cannot rename without migration — stored on device |
| `AlbumPinStore` JSON format | Stored in SharedPrefs — changing breaks existing pins |
| `CollectionAlbumStore` JSON format (prefs "smart_albums" / key "smart_album_data") | Stored in SharedPrefs — changing it breaks every saved collection on the device, smart albums included. `parseAlbum` tolerates a record with no `kind` by falling back to its ID prefix |
| `IndexMagic` / `IndexVersion` in GalleryRepository | Will invalidate all existing embedding indexes on user devices (v2→3 did exactly this for EXIF-oriented embeddings) |
| `MetadataIndexMagic` / `MetadataIndexVersion` | Will invalidate metadata indexes |
| `OnnxSessionOptions.DefaultThreadCount` (4) | Fallback only — `SharedEncoders` prefers the cached `ThreadBenchmark` result; benchmark trigger points are `MainActivity.ensureEncodersLoaded` + `IndexWorker.doWork` |
| `ImageEncoder.resolveVisionModelAssetName` | Also used by `ThreadBenchmark` — priority order must match the shipped assets |
| `ViewerActivity` gesture logic | Test all 6 gesture scenarios: diagonal swipes, info panel tap-close, fast swiping captions, panel drag smoothness, paging disabled while panel open |
| `SafeManager` MasterName (`DeepixSafe.zip`) / VaultFolderName | Vault archive name/folder; root dir is `IndexPreferences.getSafeStorageRoot` (pictures\|documents). Changing the root via Settings **moves** the existing vault (`moveVault`); `purgeVault` wipes it across all roots |
| `AlbumRules.AlbumFolderParent` (`Deepix Albums`) | Every album folder the app makes lives under this one segment, inside `IndexPreferences.getAlbumFolderRoot` (pictures\|documents, Settings › ALBUMS). Unlike the Safe the root is **not** followed for albums that already exist — their `folderPath` is stored, so changing the setting moves no photos. `isAlbumFolderPath(path)` reads the parent segment back, which is how an empty album knows whether it is unfilled or abandoned |
| `IndexPreferences` safe_storage_root (`pictures`\|`documents`) | Stored in `index_prefs` (survives `SafeStore.reset`); drives `SafeManager.vaultDir`/`masterZip`/`archiveExists`/`vaultLocationLabel` |
| `SafeStore` SharedPrefs keys / `SafeKeystore` KeyAlias | Stored on device — renaming breaks existing Safe config + biometric unlock |
| `SafeCrypto` zip params (AES-256 / STORE) | Interop contract — the vault must stay a standard AES zip openable by external tools |
| `GestureDirection` enum values | Update `handleViewerTouch()` classification logic and `onMediaTap` callback |
| `Responsive.CRAMPED_HEIGHT_DP` / `MAX_GRID_COLUMNS` | Every screen that trims chrome or counts columns: `MainActivity.applyChromeForHeight`, hero titles through `applyTitleText`, and `gridColumns` — the span canvas is shared with card rows, so a cap re-sizes album cards too. `ResponsiveTest` holds the boundaries |
| An activity's `configChanges` declaration | Dropping it makes that screen recreate on rotation: the layout re-inflates, but in-memory state goes with it (search results, selection, `FirstRunActivity`'s asked-before flags). Keeping it means every XML size is only ever the size the window started with, so a `-land` variant cannot help — the number has to be re-applied from code |
| `ImageAdapter.gridColumnCount` semantics | It is the **resolved** canvas (preference × window ratio), not the user's preference. Read by `spanSizeAt`, the `GridLayoutManager` constructions in PersonDetail/SmartCleanup, `albumCardSpanBase`, and each `addOnLayoutChangeListener` that re-resolves it |
| A dialog layout's root | `AlertDialog.setView` never re-inflates and clips whatever passes a sideways phone's ~360dp. The tall panels (`dialog_tag_picker`, `dialog_safe_setup`, `dialog_smart_album`, `dialog_tag_album`, `dialog_bottom_bar_order`, `metroDialogScroll`) scroll instead; a new fixed stack will clip its own buttons |
| An entity's column list | Its `BindVariables` companion — `RoomBatchingTest` reflects over the declared fields and fails the build when the two drift. Room binds one variable per column per row, so a 13-column bulk insert was already past Android 8–10's SQLite ceiling at 77 rows |
| `RoomBatching.MaxBoundVariables` (900) | `DbRepository.upsertMedia`/`recordEmbeddingSources`/the chunked `IN (:list)` reads, and the chunking in `FaceIndexWorker`/`FaceAnalyzer` — the margin under 999 is what a query's own scalar arguments get |
| a `CleanupAnalyzer` category's suggestion set | `SuggestionGated` — read by both `Report.deletableUris` and `SmartCleanupActivity.categoryDeletable`. A new similarity tile outside that set inherits "the empty suggestion list *is* the offer", so accepting it deletes everything listed |
| whether a duplicate is pre-selected | `DuplicateVerifier.verify` + `CleanupWorker.decodeDhash`/`dhashCache` (fail closed: an unmeasurable photo is listed, never offered) and `DuplicateVerifierTest` — the chain case (A–B=2, B–C=8, A–C=10) is what stops transitive grouping from condemning the middle of a chain |
| a manifest `android:name` | `ManifestClassTest` parses `src/main` (+ `src/debug`) against those source sets. A component whose class lives in `src/debug` must be declared in `src/debug/AndroidManifest.xml`, or release builds carry a ghost that crashes on the tap that reaches it |
| an exported filter's action | `ExternalMediaEntryTest` — the resolver names an entry after the activity answering it, so `ACTION_VIEW` (`.OpenDispatchActivity`) and `ACTION_EDIT` (`.EditDispatchActivity`) cannot share one activity. Merging them reintroduces the bug the split fixed: a view request landing in the editor, whose Save overwrites the original |
| `MediaFileOps.copyToFile` | `BinManager.moveToBin`'s copy → verify → **fsync** → ledger → delete ordering — the `flush()` must stay before `fd.sync()`, and the sync before `close()`; move either and a crash can leave a ledger entry pointing at a zero-length copy |
| `StoragePermissions.hasAllFilesAccess`'s API floor | Every direct-file path reads it: `DeleteCoordinator.shouldAskForStorageAccess` (the three delete entry points), `usesBin`, `BinManager.moveToBin`/restore, `SafeManager.hasAccess`/`isConfigured`, `CompressionActivity`'s commit gates, `AlbumFolderOps.forceToDisk`, the tour's All-files row. Answering `true` where the OS will not honor it makes a failing operation look like the app's own bug (that was Android 10); answering `false` where no setting exists strands the user in Settings, which is why `allFilesAccessRequestable` is the second half of the rule — delete falls back to per-item consent there, the Safe and compression say "needs Android 11" |
| the backup rule XMLs | Both `backup_rules.xml` (API ≤30) and `data_extraction_rules.xml`, and inside the latter **every** section (`cloud-backup` *and* `device-transfer`): a `domain="database"` exclude listed in one only restores the DB through the other door |
| `AccentPalette.Choice` | the picker (`SettingsActivity.showAccentColorDialog` via `choicesFor`), every activity's `AccentPalette.apply(this)`, the Canvas-paint callers of `solid()`/`light()` — and `Accent.System`, which exists twice: `values/themes.xml` (Azure, so pre-31 resolves) and `values-v31/themes.xml` (framework tones). A new choice needs both halves, or the overlay style is missing where the OS cannot supply one. **Declaration order is also pricing**: `FreeTier.accentLocked` counts from the front of `choices`, so inserting a swatch before the fourth silently makes a paid color free (or a free one paid) for everyone already looking at the picker |
| `BillingGateway.ProProductId` | the product must exist in Play Console as a one-time INAPP with exactly this id, or every catalogue query answers OK with an empty list (the gateway reports it as `Play does not know the product …`). Renaming strands purchases already made against the old id — nothing here can migrate them |
| The `Entitlements.next` verdict rule | `PurchaseRulesTest` pins both halves: a successful query overwrites (so a refund on another device eventually re-locks), an unreachable one leaves the stored verdict alone (so no-Play/no-network cannot read as a refund). Loosening either either loses paid access or hands a gated feature to someone who did not pay |
| Any new gated feature | call `Entitlements.guard(activity, reason) { … }`, or the read-only `Entitlements.isUnlocked` when the gate has to shape a batch or a list rather than refuse an action — never `BillingGateway` directly, and never `IndexPreferences.getProEntitlement`. `guard` is the only place the locked path opens `ProPaywall`, so a feature that reads the verdict itself silently diverges from the rest when the offer changes |
| `FreeTier`'s numbers (`FreeAccents`, `FreeSafePhotos`, `FreeSmartAlbums`, `FreeCompressionPhotos`, `PaidCleanupCategories`) | the accent picker's locked rows, `AccentPalette.current`'s demotion of a paid stored key, `SafeActivity.runImport`'s clamp, `SmartCleanupActivity.renderTiles`/`openCategory`/`launchCompressionReview`, `MainActivity.showCreateSmartAlbumDialog`, `PersonIdentityEditor.show`, and the `gate_*` strings, which take the limit as an argument so the sentence cannot drift from the rule. A limit restated on a screen is a limit that can disagree with the cap that enforces it — `FreeTierTest` holds each boundary |
| whether compression is *locked* or *metered* | `FreeTier.PaidCleanupCategories` deliberately excludes COMPRESSIBLE, so a free user opens the tile and hits `compressionRoom` on the batch instead; the encoder's crash-safe commit makes one smaller photo a finished job, which is what distinguishes it from the two tiles that are closed at the door. Moving COMPRESSIBLE back into the locked set would leave `launchCompressionReview`'s clamp unreachable; removing the clamp would hand the free tier whole-library batches |
| where the Safe cap is applied | `SafeActivity.runImport` clamps the list **before** `SafeManager.importPhotos` writes anything, because only `ImportResult.importedSources` is handed back for deleting originals. Move the check inside the import loop — or let a partial move proceed and delete the whole pick — and a free user's over-cap photo is destroyed without ever reaching the vault, breaking the crash-safety invariant for a pricing rule |
| the first-run tour's page order | `FirstRunActivity` treats the **last** page as the permissions one: the CTA relabels there and the dismiss X jumps to it via `goTo(pageCount - 1)`. A new panel inserted after it silently loses the asks — the Pro benefits page therefore sits before it, and `permissionRows` is found by content, not position. The CTA is never held shut: on the last page it asks the **first outstanding required** permission (`requestFirstOutstandingRequired`) and only `complete()`s once none are left, so one system dialog appears at a time instead of a dead button that says "Get started" |
| `drawerPro`'s label and glyph | `MainActivity.updateDrawerState` (which the drawer's `onDrawerOpened` re-runs) reads `Entitlements.isUnlocked`, so the row is the verdict's public face in the nav list. The glyph must be re-tinted in code — `setCompoundDrawablesRelative*` replaces the drawable and with it the XML `drawableTint` — and `drawer_pro` / `drawer_pro_owned` are the two states, not a hidden row: an owned user tapping it is still taken to `ProActivity`, which reports the unlock in its footer instead of offering the purchase again |
| `MetroDialog` button callbacks | every helper dismisses on any button press before invoking the callback, so a caller cannot keep a dialog open from inside its own callback — rebuilding it there is the only way, and it reads as a flash. That is one reason the purchase left the dialog: on `ProActivity` a `purchase()` that returns false simply leaves the footer standing while `refresh()` runs |
| the Pro page's free-tier numbers | `ProActivity.renderLimits` formats them out of `FreeTier` at runtime (`pro_add_accents_detail`, `pro_add_safe_detail`, `pro_add_compression_detail`, `pro_add_albums_detail`, `pro_free_compression`), and those five rows carry the string as `tools:text` only — design-time, so no raw `%1$d` can reach a screen and code is the only writer. A cap typed into the offer is a cap that can disagree with the cap that enforces it, and the paywall is where that disagreement costs the user a purchase |
| a `MetroDropdownMenu.Item` with `children` | The flyout is a **column of the same PopupWindow**, never a second popup: the menu's window is modal, so it would swallow the child's touches. Group menus therefore open the window `MATCH_PARENT` wide, and the empty slack beside a closed flyout has to dismiss on tap or it becomes dead space that behaves like neither inside nor outside |
| who dismisses a `MetroDropdownMenu` popup | `MetroDropdownMenu` itself, always: it holds the one live popup (`openPopup`, so a second open replaces the first) and dismisses on the anchor activity's `ON_DESTROY`. A caller that keeps its own popup reference — or a menu that dismisses only on tap — leaves a window holding a dead activity token, which is the `WindowLeaked` the "+" button produced twice in one logcat |
| a large asset read into memory | one array, sized from `available()`, filled in place (`AssetUtils.readAssetBytes`). A `ByteArrayOutputStream` + `toByteArray()` pair holds ~2× a 60 MB ONNX model at the same instant — at the exact moment a first search loads it, on a heap already holding the grid, which is what turns a search into a 40-second GC storm |

---

## Assets Map
```
app/src/main/assets/
├── vision_model_fp16.onnx    ← ImageEncoder (primary, Git LFS) + ThreadBenchmark
├── text_model_int8.onnx      ← TextEncoder  (Git LFS)
├── tokenizer.json            ← ClipTokenizer (2.2MB HuggingFace vocab+merges)
├── tokenizer_config.json     ← ClipTokenizer metadata
├── preprocessor_config.json  ← ImageEncoder.ProcessorConfig (do_normalize flag)
├── config.json               ← model_type="clip" (informational)
└── photo_synonyms.json.gz    ← WordNetExpansionDictionary (optional, graceful fallback)
```

## Layout → Activity Map
```
activity_main.xml       → MainActivity (ViewBinding: ActivityMainBinding)
activity_viewer.xml     → ViewerActivity (ViewBinding: ActivityViewerBinding)
item_image.xml          → ImageAdapter (image grid cell)
item_album.xml          → ImageAdapter (album row)
item_collage.xml        → ImageAdapter (collage cell)
item_folder.xml         → ImageAdapter (folder row)
item_timeline_header.xml → ImageAdapter (sticky date header)
item_pinned_album_chip.xml  → ImageAdapter (pinned album chip)
item_pinned_albums_header.xml → ImageAdapter (pinned section header)
item_viewer_page.xml    → MediaPagerAdapter (photoView, playerView, videoControls scrubber: scrubber_thumb/scrubber_progress)
viewer_bottom_gradient.xml → activity_viewer.xml (bottomGradient)
info_sheet_bg.xml       → activity_viewer.xml (info sheet surface: metroBgSecondary fill + 1dp metroBgSurface top hairline)
scrubber_thumb.xml / scrubber_progress.xml → item_viewer_page.xml (video SeekBar)
dialog_tag_picker.xml   → TagPickerDialog
dialog_smart_album.xml  → MainActivity (smart album create dialog, Metro Theme.GallerySearch.Dialog)
dialog_tag_album.xml    → MainActivity (tag collection create/edit dialog — name plus the tag chip row; Metro Theme.GallerySearch.Dialog)
dialog_album_add_photos.xml → MainActivity (folder album "Add photos": ImageAdapter grid + Copy here / Move here footer; Metro Theme.GallerySearch.Dialog)
item_smart_album_onboarding.xml → ImageAdapter (albums onboarding card; onCreateSmartAlbum)
item_empty.xml          → ImageAdapter (empty state)
activity_smart_cleanup.xml → SmartCleanupActivity (overview tiles + progress + detail grid)
item_cleanup_tile.xml   → SmartCleanupActivity (Metro category tile)
sheet_search_filter.xml → MainActivity (Sort & filter bottom sheet; 20dp inset)
item_info_row.xml       → ViewerActivity (Info sheet key-value row, via <include>)
activity_settings.xml   → SettingsActivity (Metro preferences screen; incl. SAFE section: storage location + file-path, ALBUMS section: new-album folder location)
activity_safe.xml       → SafeActivity (lock overlay + forgotPasswordBtn + decrypted thumbnail grid + add-photos bar)
item_safe_photo.xml     → SafeItemAdapter (decrypted vault thumbnail cell)
dialog_safe_setup.xml   → SafeActivity (password create + confirm + warning + resolved storage path)
dialog_safe_password.xml → SafeActivity ("Show password" reveal + copy)
activity_pro.xml        → ProActivity (full-page Pro offer: hero illustration, WHAT PRO ADDS / WHAT STAYS FREE / HOW THE PURCHASE WORKS, purchase footer; rows styled by ProRow/ProRowGlyph)
```

## Selected Drawables
```
search_filter_chip_bg.xml / search_filter_chip_active_bg.xml → search/quick/onboarding pills (6dp rounded-square; active = solid accent)
onboarding_card_bg.xml / onboarding_button_bg.xml → albums onboarding card + smart-album dialog Create button; the Pro page's Buy pill reuses onboarding_button_bg
dialog_metro_bg.xml     → Metro dialog window background (smart album dialog)
selection_badge_bg.xml  → multi-select tick chip (3dp rounded-square accent + black hairline; holds vector checkmark) → item_image.xml / item_collage.xml
selection_frame.xml     → 3dp accent frame over a selected thumbnail (item_image.xml / item_collage.xml)
pill_bg.xml             → rounded pill; now used only by activity_settings.xml (no longer the selection bar)
illustration_pro_hero.xml → activity_pro.xml hero: the mosaic — four recessed metroBgTertiary tiles (free) beside four solid-accent tiles (Pro), each carrying a tour glyph at scale 1.25
ic_pro_accents.xml / ic_pro_batch.xml → ProActivity benefit rows: the accent picker drawn as the row of *dots* MetroDialog.singleChoice really shows, and two overlapping cards for the batch limit
pro_reason_bg.xml       → activity_pro.xml reason line: a 2dp accent rule as a layer-list background on the text itself, because a sibling View at match_parent height inside a ScrollView measures to 0 and disappears
```
