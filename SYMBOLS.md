# Deepix — Symbol Reference (Token-Efficient)

Use this for targeted lookups. Format: `SymbolName` | file | signature/notes

---

## Classes

```
GallerySearchApp          GallerySearchApp.kt      Application; owns SharedEncoders (lazy)
SharedEncoders            GallerySearchApp.kt      getImageEncoder(): ImageEncoder; getTextEncoder(): TextEncoder — passes cached ThreadBenchmark count (fallback DefaultThreadCount) to both ctors
ImageEncoder              ImageEncoder.kt          ctor(context, threadCount=DefaultThreadCount); encode(Bitmap): FloatArray; encodeBatch(List<Bitmap>): List<FloatArray>; preprocess(Bitmap): FloatArray; internal resolveVisionModelAssetName(context) (shared with ThreadBenchmark)
TextEncoder               TextEncoder.kt           ctor(context, threadCount=DefaultThreadCount); encode(query: String): FloatArray; val tokenizer: ClipTokenizer
ClipTokenizer             ClipTokenizer.kt         encode(query: String): TokenizedText; ContextLength=77; prepends "a photo of "
GalleryRepository         GalleryRepository.kt     loadSnapshot(); search(); buildIndex(items, signatures, onProgress, onEmbeddingsStored, onUnsignedEmbeddings); semanticSearch(); loadIndex(); instance batchSize/pipelineBuffer via computeBatchSize()
  MediaItem               GalleryRepository.kt     data class: uri,bucketId,bucketName,dateMillis,width,height,mimeType,displayName,mediaType,sizeBytes,durationMillis,path,orientationDegrees,dateModifiedMillis; embeddingSignature(): MediaSignature
  Album                   GalleryRepository.kt     data class: id,name,count,coverUri,isSmart
  SemanticSearchHit       GalleryRepository.kt     data class: uri: Uri, score: Float
  Snapshot                GalleryRepository.kt     data class: albums,imageItems,collectionItems,videoItems
DbRepository              DbRepository.kt          upsertMedia(); toggleFavorite(); upsertExif(); addTag(); setTagsForMedia(); embeddingSignatures(): Map<String,MediaSignature>; recordEmbeddingSources(items); invalidatePhotoForReanalysis(uri) [stale marker + person_photos reset + face rows removed + exemplar pointers cleared]
IndexWorker               IndexWorker.kt           CoroutineWorker; WorkName="gallery_background_index"; runs ThreadBenchmark before encoder load; OOM → batch override 2 + retry
IndexControlReceiver      IndexControlReceiver.kt  ActionPause/ActionResume broadcasts; pendingIntent()
IndexPreferences          IndexPreferences.kt      save/loadLastIndexedTime; isIndexPaused/setIndexPaused; getGridColumnCount; getSafeStorageRoot/setSafeStorageRoot (SAFE_ROOT_PICTURES|SAFE_ROOT_DOCUMENTS); getOptimalThreadCount/saveOptimalThreadCount; getIndexBatchSizeOverride/saveIndexBatchSizeOverride (0=auto)
IndexScopeStore           IndexScopeStore.kt       getFolderIds/setFolderIds/isAllFolders (empty=all); AI-index folder scope, independent of gallery view
IndexedFoldersActivity    IndexedFoldersActivity.kt  Settings folder picker → IndexScopeStore + IndexController.rescan
PhotoEditorActivity       PhotoEditorActivity.kt   in-app editor (crop/perspective/draw/adjust); Save + Save a copy; ExtraUri/ExtraName/ExtraEdited; overwrite → DbRepository.invalidatePhotoForReanalysis
PhotoEditOps              PhotoEditOps.kt          rotate/flip/crop/perspective(setPolyToPoly)/colorMatrix/documentMatrix/composite
MediaImageSaver           MediaImageSaver.kt       overwrite(uri) [RecoverableSecurityException/createWriteRequest] + saveCopy → Pictures/Deepix
EditorCropView / EditorQuadView / EditorDrawView   crop rect+aspect / 4-corner perspective quad / freehand draw overlays
AlbumPinStore             AlbumPinStore.kt         pin/unpin/isPinned/getPinnedAlbumIds/setPinnedOrder/cleanup/isInitialized/markInitialized
SmartAlbumStore           SmartAlbumStore.kt       getAll(); get(id); upsert(album); delete(id); isSmartId(id)
  SmartAlbum              SmartAlbumStore.kt       data class: id,name,prompt,searchMode,memberUris,coverUri,createdAt,updatedAt; toAlbum(): Album
FavoritesStore            FavoritesStore.kt        all(); isFavorite(Uri); toggle(Uri): Boolean — wraps DbRepository
QueryExpander             QueryExpander.kt         buildWeightedEmbedding(query: String): FloatArray
WordNetExpansionDictionary WordNetExpansionDictionary.kt  lookup(term): Expansion?; isAvailable; WeightOriginal=1.0/Synonym=0.85/Hypernym=0.6/Hyponym=0.5
SearchResultManager       SearchResultManager.kt   firstPage(); nextPage(); isLastPage; totalCount
MetadataSearch            MetadataSearch.kt        object; search(); buildDocuments(); indexFromDocuments(); Document data class
StructuredSearch          StructuredSearch.kt      object; parse(query): ParsedQuery; Filter sealed interface
  ParsedQuery             StructuredSearch.kt      textQuery, filters, hasAnyCriteria, needsFilterLookup, filterItems()
  FilterLookup            StructuredSearch.kt      tagNameToUris, exifByUri
PhotoSearchResult         MetadataSearch.kt        item: MediaItem, sources: SearchSources, score: Float
SearchSources             MetadataSearch.kt        data class: ai: Boolean, metadata: Boolean
EmbeddingUtils            EmbeddingUtils.kt        l2Normalize(FloatArray): FloatArray; cosineSimilarity(a,b): Float
OnnxOutput                OnnxOutput.kt            flattenFloatArray(value: Any): FloatArray
OnnxSessionOptions        OnnxSessionOptions.kt    DefaultThreadCount=4 (public); create(tag,threadCount=DefaultThreadCount): OrtSession.SessionOptions — NNAPI disabled
DesignTokens              DesignTokens.kt          (see CONTEXT.md for key values)
StickyHeaderDecoration    StickyHeaderDecoration.kt  RecyclerView.ItemDecoration
ThumbnailScaleGestureListener  ThumbnailScaleGestureListener.kt  pinch-to-resize; emits onZoom(zoomIn) step (grid columns OR collage scale)
ImageAdapter              ImageAdapter.kt          RecyclerView.Adapter; useCollageLayout; gridColumnCount; spanSizeAt(); replaceCells(); ctor cb onCreateSmartAlbum; selection: setSelection/toggle/selectAll/clearSelection/selectedUris; bindSelectionVisual() = accent frame + squared vector tick (highlight-selected-only, animate only on real toggle)
  GalleryCell             ImageAdapter.kt          sealed: Header|Photo(collageSpan,collageHeightPx)|Collage|AlbumCell|FolderCell|PinnedAlbumsHeader|SmartAlbumOnboarding|Empty
FastScrollIndicator       FastScrollIndicator.kt   attach(RecyclerView, ImageAdapter); tracks all scrolls; drag → scrollToPositionWithOffset
MediaPagerAdapter         MediaPagerAdapter.kt     RecyclerView.Adapter for ViewPager2; ctor cb: onInitialImageLoaded/onMediaTap/onMediaLongClick/onVideoCompleted/onScrubbingChanged; releaseAll()
  PageViewHolder          MediaPagerAdapter.kt     bind(); start/pause/stopPlayback(); isPlaying(); isZoomed(); setScrubberVisible(); cleanup(); player tracked in SparseArray
ViewerItemsHolder         ViewerItemsHolder.kt     object; store()/retrieve(uri)/release(); strong ref (was WeakReference)
FolderNode                FolderNode.kt            data class: name, path, children, mediaCount
ExifData                  ExifData.kt              data class; hasCameraInfo: Boolean; hasGps: Boolean
ExifExtractor             ExifExtractor.kt         extract(context, uri): ExifData
TagPickerDialog           TagPickerDialog.kt       AlertDialog subclass for tag assignment
ThreadBenchmark           ThreadBenchmark.kt       getOrBenchmark(context): Int — one-time ORT intra-op tuning (1/2/4/6, synthetic input, mutex-guarded), cached in IndexPreferences; called from MainActivity.ensureEncodersLoaded + IndexWorker.doWork before encoder construction; benchmarks ImageEncoder.resolveVisionModelAssetName's asset
SmartCleanupActivity      SmartCleanupActivity.kt   dedicated cleanup screen; reads CleanupResultStore, observes CleanupWorker; tiles + selectable grid; pause/resume/stop
CleanupAnalyzer           CleanupAnalyzer.kt        object; analyze(items,embeddings,sizeByUri,encodeText,imageStats,dhashOf,onProgress,onPartial,resumeQuality,scannedUris): Report
  Category                CleanupAnalyzer.kt        DUPLICATES|SIMILAR|BURSTS|LIKELY_CLUTTER|SCREENSHOTS|DOCUMENTS|RECEIPTS|QR_CODES|NSFW|BLURRY|DARK|BRIGHT|LOW_RESOLUTION|COMPRESSIBLE
  SuggestionGated         CleanupAnalyzer.kt        DUPLICATES|SIMILAR|BURSTS — tiles where an empty suggestion set means *nothing offered*; read by Report.deletableUris and SmartCleanupActivity.categoryDeletable
  ImageStats              CleanupAnalyzer.kt        data class: variance, meanLuma, fractionNearWhite
  Report                  CleanupAnalyzer.kt        categoryItems, suggestedDeleteUris, sizeByUri, dedupAnalyzed, dedupEligible; count(); reclaimableBytes(); totalReclaimableBytes()
DuplicateVerifier         DuplicateVerifier.kt      object; verify(group, sizeOf, dhashOf, threshold): Verdict(keep, confirmed) — generic over the item type so it is host-testable; the only gate from "looks similar" to "pre-selected for deletion"
CleanupWorker             CleanupWorker.kt          CoroutineWorker; WorkName="gallery_smart_cleanup"; foreground; full scan → CleanupResultStore; resumable; ProgressCurrent/TotalKey
CleanupResultStore        CleanupResultStore.kt     save()/load()/clear(); Result(categoryUris,suggestedUris,scannedUris,done,total,dedupAnalyzed,dedupEligible,complete,updatedAt) → cleanup_results.json (absent dedup counters load as 0)
CleanupHandoff            CleanupHandoff.kt         object; items, indexedCount, release() — hand-off to SmartCleanupActivity
SettingsActivity          SettingsActivity.kt       prefs screen: collage/grid columns/pinned/charging-only/clear cleanup/about + SAFE section (storage location Pictures|Documents → moveVault; file-path subtitle); writes IndexPreferences
SafeActivity              SafeActivity.kt           encrypted photo locker; biometric-first lock → decrypted thumb grid → overflow (show password/add/fingerprint/lock/remove); lock-screen "Forgot password?" → offerResetOrphanedVault; FLAG_SECURE; ExtraImportUris/ExtraImportedUris
SafeManager               SafeManager.kt            object; session password + single-archive vault ops at CONFIGURABLE public path (Pictures|Documents via IndexPreferences.getSafeStorageRoot; All-files access): setUpVault(ctx,pw)/unlock/lock/listItems/listFolder/createFolder/importPhotos/decryptToBitmap/decryptToTemp/restoreToGallery/removeItem/thumbnail; moveVault(ctx,oldRoot,newRoot)/purgeVault(ctx) [hard delete — forgotten-password escape]/archiveExists(ctx)/vaultLocationLabel(ctx)/hasAccess/isConfigured; thumbnail decodes from encrypted entry stream
  VaultItem               SafeManager.kt            data class: entryName (full path within the zip), displayName
  VaultFolder             SafeManager.kt            data class: path (ends '/'), name
  FolderListing           SafeManager.kt            data class: folders, photos
  ImportResult            SafeManager.kt            data class: imported, failed, importedSources
  SetupOutcome            SafeManager.kt            enum: CREATED | ADOPTED | WRONG_PASSWORD | NO_ACCESS
SafeCrypto                SafeCrypto.kt             object; zip4j AES-256 addFileToZip/listEntryNames/extractEntry/removeEntry/verifyPassword; pbkdf2/thumbKey; encryptBytes/decryptBytes (AES-GCM); makeThumbnailJpeg(ctx,uri) | (zip,entry,pw) decode straight from encrypted entry
SafeKeystore              SafeKeystore.kt           object; biometric-gated Keystore key: encryptCipher()/decryptCipher(iv)/deleteKey(); setUserAuthenticationRequired + setInvalidatedByBiometricEnrollment
SafeStore                 SafeStore.kt              object; SharedPrefs: savePasswordVerifier/verifyPassword, saltOrNull, isBiometricEnabled/saveBiometricPassword/getBiometricBlob/getBiometricIv/clearBiometric, reset
SafeItemAdapter           SafeItemAdapter.kt        RecyclerView.Adapter; submit(list)/itemAt(pos); async bindThumb, tag-guarded
```

## Search / cleanup additions (this session)

```
GalleryRepository.allEmbeddings(): Map<String,FloatArray>           // snapshot (loads index if empty)
GalleryRepository.encodeText(text): FloatArray?                     // delegate to TextEncoder
NsfwClassifier(textEncoder).isSensitive(imageEmbedding)            // Beta zero-shot NSFW: sensitive vs safe CLIP prompt margin
ImageAdapter.setSensitiveState(enabled, flaggedUris)               // blur NSFW tiles until tapped (revealSensitive on tap)
IndexPreferences.isBlurSensitive()/setBlurSensitive()              // settings toggle for the blur feature
GalleryRepository.imageEmbedding(uri): FloatArray?                  // stored, or encode on demand
GalleryRepository.imageEmbeddingForRegion(uri, RectF): FloatArray?  // region crop (EXIF-oriented @2048px), CLIP encode live; RectF normalized 0..1
GalleryRepository.regionThumbnail(uri, RectF): Bitmap?             // oriented cropped preview for the search bar thumb
GalleryRepository.searchByEmbedding(query, excludeUri, floor=0.5, limit=500): List<SemanticSearchHit>  // image-to-image
ImageAdapter.setSelection(uris)                                     // pre-select a set
ImageAdapter.toggle(uri)                                            // public selection toggle (cleanup tap)
MainActivity.renderSelectionState(count)                            // long-press select: top bar "N selected" + back; toggles selectionBar (Metro command bar: select all/share/delete) ↔ bottomPanel nav
MediaPagerAdapter ctor cb += onPlayStateChanged(position, playing)  // play/pause/replay + mute sync
MediaPagerAdapter.PageViewHolder.togglePlayback()/setVideoControlsVisible()
StructuredSearch.ScreenshotFilter                                   // is=screenshot (name/path heuristic)
IndexPreferences.isCleanupPaused()/setCleanupPaused()
IndexPreferences.isIndexConsentGiven()/setIndexConsentGiven()
IndexPreferences.isChargingOnlyIndexing()/setChargingOnlyIndexing()
MainActivity: startIndexingIfAllowed()/onIndexDrawerAction()/pauseIndexing()/resumeIndexing()/enqueueIndexWork(policy)
IndexWorker.buildWorkRequest(context, selection)                   // SINGLE source of truth for index work request (applies charging constraint); used by MainActivity + IndexControlReceiver
IndexController.pause/resume/stop/start(context)                   // shared indexing lifecycle; stop clears notification (uses IndexPreferences.isIndexStopped)
IndexPreferences.getIndexProgressPercent()/setIndexProgressPercent()  // last progress %, shown in Settings while paused/idle
IndexPreferences.isIndexStopped()/setIndexStopped()                // explicit stop: no auto-restart, no notification
MainActivity SortMode enum   Relevance | Newest | Oldest
MainActivity ShowFilter enum All | Favorites | Screenshots
MainActivity: ensureDefaultPins()/albumRelevanceScore(name)        // auto-pin 4 most relevant albums on first run
MainActivity: appendJustifiedRows(cells,dayItems,rowWidthPx)        // justified-rows collage builder (uses collageScaleLevel)
MainActivity: adjustGridColumns(zoomIn,lm)/adjustCollageScale(zoomIn) // pinch step: grid columns / collage thumbnail scale
MainActivity: rerenderForDisplayChange()                           // rebuild current view's cells in-memory (no library reload)
IndexPreferences.getCollageScale()/setCollageScale(level 1..5)     // collage thumbnail scale, default COLLAGE_SCALE_DEFAULT
DesignTokens.collageRowsPerWidth(level): Float                      // level 1..5 → images-per-row baseline
MainActivity: startSearchHintCycle()/stopSearchHintCycle()/cycleSearchHint() // "alive" search bar: crossfades AI/metadata/indexing hints while empty
MainActivity: searchHints(): List<CharSequence>                    // AI(sparkle) + Metadata + live "Indexing • N%" when a pass runs
MainActivity: hintWithSparkle(text)                                // prepends accent ic_fluent_sparkle ImageSpan to a hint
MainActivity: ensureEncodersLoaded(warmupDelayMs): CompletableDeferred<Boolean> // idempotent lazy CLIP load; awaited by search paths; runs ThreadBenchmark first
MainActivity: shouldRunBackgroundIndexing(): Boolean               // true when index pass should run (also gates eager encoder warm-up)
MainActivity: renderPagedTimeline(items,emptyText,contextKey,prefix) // incremental browse grid — first page fast, rest on scroll
MainActivity: paginateBrowse()                                     // appends next timeline page near bottom
MainActivity: buildTimelinePage(items,from,to,continuingMonth,collage) // header+day rows for a slice (no repeated month headers)
MainActivity: nextPageEnd(from)                                    // page end extended to day boundary (cap BROWSE_PAGE_MAX)
MainActivity: resetGridToTop()                                     // invalidates GridLayoutManager span caches (fixes collage first-render)
MainActivity: updateSearchTrailingIcon()                           // search box trailing icon search↔dismiss
MainActivity: dismissLoadingOverlay()                              // one-shot fade of launch loading overlay
ViewerActivity.ExtraFindSimilarUri                                  // returned to launch image-to-image search
ViewerActivity.ExtraFindSimilarCrop                                // FloatArray [l,t,r,b] normalized crop for region search
CropOverlayView.setImageBounds(RectF)/normalizedSelection()        // interactive crop rect (draw/resize/move); region image-search
RotatablePhotoView.resetRotation()                                 // PhotoView subclass: two-finger twist rotates photo, snaps to nearest 90° (View.rotation, about centre); reset on bind
RotationGestureDetector(Listener)                                  // two-finger twist detector → onRotationBegin/onRotation(deltaDeg)/onRotationEnd

---

## Indexing perf pass additions

```
GalleryRepository.loadBitmap(uri): Bitmap?                          // delegates to decodeOrientedBitmap(uri, MaxBitmapEdge): two-pass bounds decode ≤512px + EXIF orientation
GalleryRepository.computeBatchSize(): Int                           // override (IndexPreferences) > isLowRamDevice→2 > memoryClass≥192→6 > else 4
GalleryRepository.batchSize / pipelineBuffer                        // instance vals (pipelineBuffer = 1 when batchSize ≤ 2, else 2)
IndexPreferences.getIndexBatchSizeOverride()/saveIndexBatchSizeOverride()  // 0 = auto-scale; set to 2 on OOM in IndexWorker
SharedEncoders.optimalThreadCount()                                 // cached ThreadBenchmark pref, fallback OnnxSessionOptions.DefaultThreadCount
ImageEncoder.resolveVisionModelAssetName(context)                   // now internal — shared by ThreadBenchmark
```

---

## P0 hardening pass additions (crash-safety tier)

```
IndexCheckpoint           IndexCheckpoint.kt       write(target, staging, payload): Checkpoint(replaced, error); promotable(base, staging); no Log calls (host-testable) — base/.tmp staging rename is what makes an index save crash-atomic
BatchEncoding             BatchEncoding.kt         encode(inputs, encodeBatch, encodeOne, onBatchFailure, onImageFailure): List<FloatArray?> — OOM escapes uncaught, per-image fallback preserves every slot
EmbeddingFreshness        EmbeddingFreshness.kt    decide(embedded, recorded, current): Encode | Current | Backfill; StaleSignature = MediaSignature(-1,-1,-1,-1); MediaSignature(dateModifiedMillis,sizeBytes,width,height)
BinLedger                 BinLedger.kt             bin state machine, Android-free: write/list/discard/claimData/copyDataOut/reconcile(root, originalPresent) + SidecarSuffix ".binmeta", dataDir="bin/data", entriesDir="bin/entries", QuotaMarginBytes=32MB, hasRoomFor(), copyComplete()
BinManager                BinManager.kt            moveToBin/restore/deleteForever/emptyBin/purgeExpired/reconcile — every copy is verified byte-for-byte before the original is deleted; a killed op is repaired by reconcile, never by guessing
SafeWorkGuard             SafeWorkGuard.kt         begin()/end(): Boolean/requestLock()/cancelPendingLock()/isBusy — parks a lock request made while import/encrypt is in flight, last worker out applies it
MigrationGraphTest        (test)                   walks GalleryDatabase.MIGRATIONS to SchemaVersion; guards duplicate edges, backwards chains, and destructive fallback limited to never-released versions
ForegroundBudget          ForegroundBudget.kt      CapMillis=6h, StopReserveMillis=20m, WindowHours=24 — hour-bucketed ledger of the app's dataSync foreground time: usedMillis/remainingMillis/hasRoom/refillAtMillis/encode/decode (pure) + Context overloads reading prefs; the arithmetic is host-tested because a rolling-window boundary error is invisible until the process dies
IndexWorker foreground budget   IndexWorker.kt     pre-flight deferral (scheduleResumeAfterForegroundRefill — re-enqueues APPEND_OR_REPLACE with the delay the window says it needs, skipped when paused/stopped) + a per-batch hasRoom check that throws IndexWaitingException(WaitingForForegroundBudget); doWork books the whole run's stretch once, in a finally
CleanupWorker / CompressionWorker   same bookkeeping in a finally: minutes-long runs, so they draw on the pool without bounding themselves by it
EmbeddingSourceEntity     db/EmbeddingSourceEntity.kt  table: embedding_source; PK: uri; dateModifiedMs,sizeBytes,width,height,recordedAt — what each stored CLIP embedding was encoded from
EmbeddingSourceDao        db/EmbeddingSourceDao.kt upsert(List|one); getAll(); getByUri(uri); deleteByUri(uri)
PersonPhotoDao.invalidateForReanalysis(uri)        db/PersonPhotoDao.kt  one-photo version of resetForEmbeddingModel (status unprocessed, dhash/faceCount/exemplar cleared)
FaceDao.idsForPhoto(uri) / PersonDao.clearExemplarFaces(faceIds)   drop a photo's faces without leaving persons.exemplarFaceId pointing at a deleted row
```

---

## Landscape pass additions

Rotations are handled **in place**: every activity but `VideoEditorActivity` (still portrait-locked)
declares `configChanges="orientation|screenSize|screenLayout|smallestScreenSize"`, so nothing is
recreated and no `-land` layout variant is ever re-inflated. Every size a window decides is therefore
applied from code, and the trigger for a width-driven one is a layout listener, not the config
callback — `onConfigurationChanged` runs before the measure pass, where a view still reports the old
width.

```
Responsive                    Responsive.kt                    object; the whole window-shape seam. sideways/widthDp/heightDp/cramped(CRAMPED_HEIGHT_DP=480) · gridColumns(For) · albumCardSpan · collageExtentWidthPx(For) · referenceWidthPx · widthPx · bodyHeightPx(For) · cardSpan · titleTextSp(For)/applyTitleText · tileSizePx · cardsFitting · widthOf · MAX_GRID_COLUMNS=12
ResponsiveTest                (test)                           10 cases over the pure *For forms — a boundary that is wrong by one dp is a screen of oversized tiles on a phone and invisible in a preview
ImageAdapter.gridColumnCount                                    now the *resolved* canvas width (preference × window), not the raw preference
ImageAdapter.gridWidthPx / albumCardSpanBase                     measured grid width / card span chosen at the preference; both re-set on a width change
ImageAdapter.widthOr(fallbackPx)                                 the measured width, or the window's while pre-layout
ImageAdapter.albumCardWidthPx(gridWidthPx)                       px side a card's cover gets from its span
ImageAdapter.stateRowHeightPx(context)                           item_empty / item_search_loading rows capped to the window (they were a hard 360dp)
bind(cell…, gridWidth)/bind(cell, selected, gridWidth)/bind(album, showFolderSize, cardWidthPx)   PhotoViewHolder/CollageViewHolder/AlbumViewHolder take width as an argument — the holders are nested, so they have no adapter instance to read
payload "grid_change"                                            falls through onBindViewHolder(payloads) to a full bind: how a tile is re-sized
MainActivity.onGridWidthChanged(widthPx)                         the rotation entry point: record width → re-resolve columns/card span → trim chrome → set spanCount → rebuild the displayed slice → re-anchor
MainActivity.applyDensityPreferences/applySpanCountForLayout/spanCountForLayout/currentGridAnchor/relayoutCurrentListing/scrollGridTo  in-memory relayout that keeps the viewport (Search re-paginates via applySortAndShow(preserveViewport=true); a paged timeline rebuilds 0…pagedDisplayedCount with pagedSortLabel)
MainActivity.pagedSortLabel                                      the affordance label the current slice was rendered with, so a rebuild matches instead of inventing
MainActivity.applyChromeForHeight()                              bottom bar 64→56dp and grid bottom padding 84→68dp when the window is cramped
binding.imageGrid.addOnLayoutChangeListener { width != oldWidth }  the rotation trigger used by Main/PersonDetail/SmartCleanup/Bin/Safe/PersonAlbums grids
MediaPagerAdapter.decodeOverridePx()/reloadImageForSizeChange()  Glide decodes at the page's measured size, not the window's; ViewerActivity.onConfigurationChanged calls it (video pages are skipped)
ViewerActivity.clampInfoScrollHeight()                           info sheet capped to the window, floor 140dp
MetroDialog body.maxHeight / Views.showList(context, naturalPx)  dialog body and option lists capped via Responsive.bodyHeightPx; dialog_metro_generic's metroDialogContent now sits in metroDialogScroll
dialog_tag_picker / dialog_safe_setup / dialog_smart_album / dialog_bottom_bar_order  roots are ScrollViews — their stacks exceed a 360dp-tall sideways window and used to clip the footer buttons
FirstRunActivity.scaleFactor()                                   reads the measured root (+inset padding) once laid out; rescaleForWindow() re-applies chrome, progress track and the visible pages on rotation
OnboardingMetrics(var factor) / OnboardingPanelAdapter.rescaleVisiblePages()  the tour bakes units in at bind time, so a shape change rebinds the live pages (no notifyDataSetChanged — on ViewPager2 it can leave a stale page)
BinActivity/SafeActivity.gridColumns()/tileSizePx()/applyResponsiveChrome()  fixed 3-column squares: side = measured width ÷ columns, so sideways widens the tile with the row instead of overflowing it
PersonAlbumsActivity.peopleColumns()                             Responsive.cardsFitting(92dp cover + label, max 6) — a face card is counted by what fits, not by a ratio
```

---

## P0 leftovers + P1 batch 1 additions

Round against the pasted fix plan, with its constraint honoured: **completely offline app, no model
swaps**. Two of its items (bin retention, fsync ordering) were already fixed and already test-guarded.

```
RoomBatching              db/RoomBatching.kt        object; MaxBoundVariables=900 · chunkSizeFor(variablesPerItem) · chunks(items, variablesPerItem=1) — SQLite's 999-variable ceiling (Android 8–10) expressed per table instead of per query
MediaMetadataEntity.BindVariables = 13 · EmbeddingSourceEntity = 6 · FaceEntity = 14   db/*.kt  variables Room binds per row; FaceEntity skips its autoGenerate PK. RoomBatchingTest reflects over the declared fields so a new column cannot drift away from the constant
DbRepository.upsertMedia / recordEmbeddingSources   DbRepository.kt  chunked by their entity's BindVariables — a bulk INSERT binds one variable per column per row, so the end-of-pass library write broke at 77 photos
DbRepository.getExifForUris                        DbRepository.kt  chunked batch read through ExifMetadataDao.getByUris (was one query per uri — the EXIF N+1)
DbRepository.existingExifUris / photoUrisWithLocation / recognizedPeopleForPhotoUris   chunked IN (:list) reads; each element binds one variable
DbRepository.invalidatePhotoForReanalysis           chunked personDao.clearExemplarFaces
ExifMetadataDao.getByUris(uris)                     db/ExifMetadataDao.kt  SELECT * FROM exif_metadata WHERE uri IN (:uris)
FaceIndexWorker / FaceAnalyzer                      RoomBatching.chunks(...) at the call sites (setBurstExemplar ×2, faceDao.insertAll) — workers hold DAO handles, not a DbRepository, so they chunk themselves instead of gaining repository wrappers
RoomBatchingTest              (test)                ceiling math per table, chunk coverage at 1/899/900/901/5000, and the reflection guard tying each BindVariables to its entity
MediaFileOps.copyToFile                             MediaFileOps.kt  flush() → fd.sync() → close(); only caller BinManager.kt — the bin copy is durable before the original is deleted
backup_rules.xml / data_extraction_rules.xml        res/xml  gallery_metadata.db + -wal + -shm excluded from cloud backup and device transfer (DECISION_GATES Gate C)
DuplicateVerifier                                   DuplicateVerifier.kt  verify(group, sizeOf, dhashOf, threshold=NearDuplicateHammingThreshold): Verdict(keep, confirmed) — generic over the item type, takes callbacks instead of Uris, so it runs on the host
CleanupAnalyzer.SuggestionGated                     DUPLICATES|SIMILAR|BURSTS — for these an empty suggestion set means nothing is offered; every other tile's empty set means the tile itself is the offer
CleanupAnalyzer.analyze(…, dhashOf)                 dhashOf: (Uri) -> Long? = { null }  — duplicate groups go through DuplicateVerifier; SIMILAR pre-selects nothing; BURSTS lists, never pre-selects
CleanupAnalyzer.MAX_DEDUP_ITEMS = 2000              cap on the pairwise pass; overflow is reported, not hidden
Report.dedupAnalyzed / dedupEligible                how many photos the pairwise pass compared, and how many it was capped away from
CleanupWorker.decodeDhash(uri): Long?               one bounds-sampled ≤DhashEdgePx(128) decode → PhashUtils.hash, memoized in dhashCache and handed to analyze() as dhashOf; runCatching → null, which fails closed
CleanupResultStore.Result.dedupAnalyzed / dedupEligible   persisted as "dedupAnalyzed"/"dedupEligible"; absent in an old file → 0
SmartCleanupActivity.dedupCoverageSuffix()          " · checked N of M" on the duplicates hint when the pass was capped; the counters ride through saveCurrentToStore so a resume keeps them
DuplicateVerifierTest           (test)               6 cases: largest kept · A–B=2/B–C=8/A–C=10 confirms only B · 8 bits in, 20 bits out · hashless member never offered · hashless pivot confirms nothing · singleton group
EditDispatchActivity                                AndroidManifest.xml  answers ACTION_EDIT only — both ACTION_VIEW filters deleted (a VIEW registration dropped a thumbnail tap from another gallery into the editor, where Save overwrites the original)
FaceValidationActivity                              src/debug/java/... + src/debug/res/layout/... + src/debug/AndroidManifest.xml  debug-only; its in-app launcher had no callers
ManifestClassTest            (test)                 reads every android:name in main (and main+debug for the debug manifest) and requires the class file on that source path. Deliberately not lint MissingClass: CI never runs lint and a lint-only severity change is unverifiable offline
```

---

## DB Entities & DAOs

```
MediaMetadataEntity   db/MediaMetadataEntity.kt  table: media_metadata; PK: uri; BindVariables = 13
ExifMetadataEntity    db/ExifMetadataEntity.kt   table: exif_metadata; PK: uri
FavoriteEntity        db/FavoriteEntity.kt        table: favorites; PK: uri
TagEntity             db/TagEntity.kt             table: tags; PK: id (autoGen); unique: name
MediaTagCrossRef      db/MediaTagCrossRef.kt      table: media_tag_cross_ref; PK: (mediaUri, tagId)
GalleryDatabase       db/GalleryDatabase.kt       singleton; DB name: gallery_metadata.db; v9 (SchemaVersion const); schemas/ committed, no blanket destructive fallback (DestructiveFallbackFrom = 1,2)
MediaMetadataDao      db/MediaMetadataDao.kt      upsert(List<MediaMetadataEntity>)
ExifMetadataDao       db/ExifMetadataDao.kt       upsert(ExifMetadataEntity); getByUri(uri); getByUris(uris) [chunked by the caller]
FavoriteDao           db/FavoriteDao.kt           getAllUris(); isFavorite(uri); insert(); delete()
TagDao                db/TagDao.kt                getAll(); getTagsForMedia(uri); getMediaUrisForTag(tagId); clearTagsForMedia(); addMediaTagCrossRef()
```

---

## Enums & State

```
Mode          MainState.kt   Browse | Search | AlbumDetail | FolderDetail | SmartAlbumDetail
Section       MainState.kt   Collection | Videos | Albums | Favorites | Folders
SearchMode    MainActivity   Hybrid | AiOnly | MetadataOnly  (private enum; chosen in Sort & filter sheet)
SortMode      MainActivity   Relevance | Newest | Oldest  (private enum; search result order)
ShowFilter    MainActivity   All | Favorites | Screenshots  (private enum; → fav=yes / is=screenshot)
MediaType     GalleryRepository.kt  Image | Video  (@Parcelize)
TokenizedText ClipTokenizer.kt  data class: inputIds: LongArray, attentionMask: LongArray
InitResult    MainState.kt   imageEncoder, textEncoder, repository, snapshot
LibrarySnapshot MainState.kt albums, imageItems, collectionItems, videoItems, selectedAlbumIds
GestureDirection ViewerActivity.kt  UNDETERMINED | HORIZONTAL_PAGE | VERTICAL_DISMISS | VERTICAL_INFO  (gesture classification)
```

---

## Constants (All in one place)

```kotlin
// SearchTuning.kt
ScoreThreshold    = 0.19f
PageSize          = 30
DefaultTopK       = Int.MAX_VALUE
MaxScoreDropRatio = 0.75f

// ImageEncoder.kt
ImageSize         = 256
Mean              = [0.48145466, 0.4578275, 0.40821073]
Std               = [0.26862954, 0.26130258, 0.27577711]

// ClipTokenizer.kt
ContextLength     = 77
PhotoPrefix       = "a photo of "

// GalleryRepository.kt
batchSize         = 2|4|6   // instance val: isLowRamDevice→2, memoryClass≥192→6, else 4; IndexPreferences override wins
pipelineBuffer    = 1|2     // instance val: 1 when batchSize ≤ 2, else 2
SaveEvery         = 20
MaxBitmapEdge     = 512
IndexMagic        = 0x47534958   IndexVersion = 3   // v3: EXIF-oriented, bounds-sampled decodes
MetadataIndexMagic = 0x474d4458  MetadataIndexVersion = 1

// SmartAlbumStore.kt
SMART_PREFIX      = "smart:"
MAX_SMART_MEMBERS = 800

// OnnxSessionOptions.kt
DefaultThreadCount = 4   // public; fallback until ThreadBenchmark cache exists

// ThreadBenchmark.kt
ThreadCandidates  = [1, 2, 4, 6]   WarmUpRuns = 3   MeasureRuns = 5

// Safe (encrypted photo locker)
SafeStore.PbkdfIterations = 120_000   SafeStore.SaltBytes = 16
SafeCrypto.ThumbMaxPx = 320   SafeCrypto.VaultExtension = ".zip"   // AES-256, STORE
SafeManager.MasterName = "DeepixSafe.zip"   // single vault archive at Pictures|Documents /Deepix Safe/ (root via IndexPreferences.getSafeStorageRoot; All-files access)
SafeKeystore.KeyAlias = "photo_safe_biometric_key"   // AES/GCM, user-auth-required

// WordNetExpansionDictionary.kt
WeightOriginal    = 1.00f
WeightSynonym     = 0.85f
WeightHypernym    = 0.60f
WeightHyponym     = 0.50f

// DesignTokens.kt (key ones)
GRID_DEFAULT_COLUMNS = 4
GRID_SPAN_COUNT = 6
COLLAGE_SPAN_COUNT = 60
COLLAGE_TARGET_ROWS_PER_WIDTH = 2.3f   // level-3 anchor
COLLAGE_SCALE_MIN/MAX/DEFAULT = 1/5/3  // collageRowsPerWidth: 1.4/1.8/2.3/2.8/3.3f
COLLAGE_MIN_ASPECT = 0.55f   COLLAGE_MAX_ASPECT = 2.4f
COLLAGE_LAST_ROW_FILL_THRESHOLD = 0.7f   COLLAGE_MIN/MAX_ROW_HEIGHT_RATIO = 0.6f/1.7f
DISPLAY_CAP = 800  (legacy; browse now paged, not capped)
BROWSE_PAGE_SIZE = 120   BROWSE_PAGE_MAX = 320   PAGE_PREFETCH_CELLS = 12  (MainActivity paging)
SEARCH_METADATA_HARD_CAP = 80
SEARCH_INPUT_DEBOUNCE_MS = 180L
INDEX_BACKOFF_SECONDS = 10L
INDEX_LIVE_REFRESH_STEP = 20
SCREEN_TITLE_SIZE = 40f

// Room batching & cleanup verification
RoomBatching.MaxBoundVariables = 900   // under SQLite's 999 on Android 8–10, with slack for a query's own scalars
CleanupWorker.DhashEdgePx = 128        // the decode size the duplicate check's dHash is measured at
CleanupAnalyzer.MAX_DEDUP_ITEMS = 2000 // pairwise cap; the remainder is reported as dedupEligible - dedupAnalyzed
```

---

## WorkManager Keys

```kotlin
IndexWorker.WorkName             = "gallery_background_index"
IndexWorker.ProgressCurrentKey   = "progress_current"
IndexWorker.ProgressTotalKey     = "progress_total"
IndexWorker.ProgressPercentKey   = "progress_percent"
IndexControlReceiver.ActionPause  = "com.devomind.gallerysearch.action.PAUSE_INDEXING"
IndexControlReceiver.ActionResume = "com.devomind.gallerysearch.action.RESUME_INDEXING"
```

---

## Intent Extras

```kotlin
ViewerActivity.ExtraContentChanged  // Boolean — returned to MainActivity on result
SafeActivity.ExtraImportUris        // ArrayList<Uri> — photos to move into the Safe
SafeActivity.ExtraImportedUris      // ArrayList<Uri> — originals successfully locked → MainActivity deletes
```

---

## Notification IDs

```kotlin
IndexWorker.NotificationId       = 1001   // indexing progress
IndexWorker.PausedNotificationId = 1002   // indexing paused
IndexWorker.ChannelId            = "gallery_index_channel"
```
