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
CollectionAlbumStore      CollectionAlbumStore.kt  getAll(); get(id); upsert(album); delete(id). Prefs file "smart_albums" and key "smart_album_data" keep their old names — an upgrade must still read the albums a user already made
  CollectionAlbum         CollectionAlbumStore.kt  data class: id,name,prompt,searchMode,memberUris,coverUri,createdAt,updatedAt,kind,folderPath,tagNames; toAlbum(count,coverUri): Album — a live kind passes what the library says, a smart one reports its stored list
  AlbumKind               CollectionAlbumStore.kt  Device | Smart | Folder | Tags. Smart keeps the URIs a search returned; Folder and Tags keep a rule and are resolved from the library on every render, so their counts cannot go stale and they are never offered Refresh
AlbumRules                AlbumRules.kt            object; AlbumFolderParent ("Deepix Albums" — the parent every album folder the app makes sits in); albumFolderPath(root,parent,name) — cleaned (no separators/control chars), `.`/`..` impossible, 60-char cap, cannot climb out of parent, null when the name holds no letter or digit; isAlbumFolderPath(path) — the app's own album folders vs one an older album was pointed at, i.e. unfilled vs gone; findFolderNode(roots,path); tagUris(tagNames,tags,urisByTag) — the union, matched by tag NAME because a rowid can be handed to a different tag. Pure: no Context, no library, JVM-tested
AlbumFolderOps            AlbumFolderOps.kt        object; copyInto(context,sources,relativePath,onProgress): Result(copied = original→new uri pairs, failed) — whole-batch BinLedger.hasRoomFor first, then per photo: MediaStore insert (Q+ RELATIVE_PATH + IS_PENDING; pre-Q absolute DATA with uniqueIn) → stream copy → forceToDisk (fsync, needs MediaFileOps.resolvePath + All-files access) → countBytes read-back → only a copyVerified match is published, anything else is discard()ed and counted failed. copyVerified(expected,written) = expected>0 && written==expected — stricter than BinLedger.copyComplete because the caller's next step deletes the original. Images only (MediaStore wants video rows under Movies). JVM-tested on that one pure rule
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
MetroDropdownMenu         MetroDropdownMenu.kt      object; show(anchor, items) — one flat PopupWindow, rows built in code; Item(label, selected, danger, children, onClick). A row with children is a header: its children open as a second column to the menu's left, inside the same window (a second PopupWindow gets no touches — the modal parent eats them), top-aligned with the header row, width clamped against the app window, pulled up when it would pass the menu's bottom. One menu at a time, app-wide: show() dismisses the popup it last opened, and a LifecycleEventObserver dismisses on ON_DESTROY (the window holds the activity's token — leaving it up leaks it) and unregisters itself from the dismiss listener
ThumbnailScaleGestureListener  ThumbnailScaleGestureListener.kt  pinch-to-resize; emits onZoom(zoomIn) step (grid columns OR collage scale)
ImageAdapter              ImageAdapter.kt          RecyclerView.Adapter; useCollageLayout; gridColumnCount; spanSizeAt(); replaceCells(); ctor cb onCreateSmartAlbum; selection: setSelection/toggle/selectAll/clearSelection/selectedUris; bindSelectionVisual() = accent frame + squared vector tick (highlight-selected-only, animate only on real toggle)
  GalleryCell             ImageAdapter.kt          sealed: Header|Photo(collageSpan,collageHeightPx)|Collage|AlbumCell|FolderCell|PinnedAlbumsHeader|SmartAlbumOnboarding|Empty
  PinnedAlbumsHeaderViewHolder  ImageAdapter.kt  the Collections strip. People is always index 0 and reaches the strip after the first frame (stage-2 library snapshot), where RecyclerView keeps the child already at the start edge anchored — a prepended chip lands left of that edge and reads as absent. bind() therefore re-anchors to position 0/offset 0 from submitList's commit callback, but only when the front chip is new and the strip was not scrolled, so a viewport the user scrolled into survives
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
ViewerActivity.showTopDate()/hideTopDate()                         // the top bar's date+time is bound from MediaItem.dateMillis in bindPage, NOT from the metadata query — bindMetadata only fills it while it is still hidden (a Uri the index has no row for). Hiding it per page and waiting on IO made the date blank and return on every swipe
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
dialog_tag_picker / dialog_safe_setup / dialog_smart_album / dialog_tag_album / dialog_bottom_bar_order  roots are ScrollViews — their stacks exceed a 360dp-tall sideways window and used to clip the footer buttons
FirstRunActivity.scaleFactor()                                   reads the measured root (+inset padding) once laid out; rescaleForWindow() re-applies chrome, progress track and the visible pages on rotation
OnboardingMetrics(var factor) / OnboardingPanelAdapter.rescaleVisiblePages()  the tour bakes units in at bind time, so a shape change rebinds the live pages (no notifyDataSetChanged — on ViewPager2 it can leave a stale page)
BinActivity/SafeActivity.gridColumns()/tileSizePx()/applyResponsiveChrome()  fixed 3-column squares: side = measured width ÷ columns, so sideways widens the tile with the row instead of overflowing it
PersonAlbumsActivity.peopleColumns()/applyResponsiveChrome()     Responsive.cardsFitting(92dp cover + label, max 6) — a face card is counted by what fits, not by a ratio; applyResponsiveChrome sizes the hero screenTitle and onConfigurationChanged re-runs it
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
EditDispatchActivity                                AndroidManifest.xml  answers ACTION_EDIT only — both ACTION_VIEW filters deleted (a VIEW registration dropped a thumbnail tap from another gallery into the editor, where Save overwrites the original); VIEW is answered by OpenDispatchActivity now
FaceValidationActivity                              src/debug/java/... + src/debug/res/layout/... + src/debug/AndroidManifest.xml  debug-only; its in-app launcher had no callers
ManifestClassTest            (test)                 reads every android:name in main (and main+debug for the debug manifest) and requires the class file on that source path. Deliberately not lint MissingClass: CI never runs lint and a lint-only severity change is unverifiable offline
```

---

## "Open with" entry point pass additions

```
OpenDispatchActivity       AndroidManifest.xml + OpenDispatchActivity.kt  answers ACTION_VIEW for image/* only: resolves the incoming Uri through GalleryRepository.getImageItemsForUris, then opens ViewerActivity on that one photo — the same ExtraMarker + ViewerItemsHolder contract MainActivity.openMedia uses for a photo with no on-screen list. ExtraAlbumId/Name come from the resolved row, so "Set as album cover" still works; the album's other photos are deliberately not enumerated, because that query walks the whole library. A Uri MediaStore has no row for — a FileProvider handover — still opens: it is shown as a stand-in item with no date, dimensions or album, and the viewer reads what metadata it can from the granted Uri itself
ExternalMediaEntryTest   (test)                 the edit dispatcher must never answer ACTION_VIEW, and the open dispatcher must answer VIEW + DEFAULT + image/* under @string/open_with_pixa. The resolver names an entry after the activity answering it, so one activity cannot be both "Open with" and "Edit with" — the merged pair once sent a view request to the editor, whose Save overwrites the original
```

---

## People identity chrome pass additions

```
PersonAlbumsActivity screenTitle     activity_person_albums.xml  the hero title (@string/people_title_lower, 40sp sans-serif-light) every other secondary screen opens with — this page went from the back arrow straight into the grid
PersonDetailActivity identityAction  activity_person_detail.xml  the visible way into PersonIdentityEditor (name field + single-select relationship chips, one popup). The editor was reachable only by tapping the title, which gives no sign of being tappable. Label is state-driven — "Add name" while PersonIdentity.displayName() is null, "Edit name" once there is something to change, GONE when the person row no longer exists. The title tap is kept; writes still go through PersonDao.updateIdentity
```

---

## System accent pass additions

```
AccentPalette.Choice.SYSTEM   AccentPalette.kt  key "system", display "System accent", overlay Accent.System. Declared last so the six fixed swatches keep their positions. Its solid/light are Azure literals — a fallback, never the value it paints with, because no compile-time constant can hold a per-user color
AccentPalette.isSystemAccentAvailable(context)  cached (process-stable) probe: systemAccentSupported(SDK_INT ≥ 31) AND readDynamicAccent() returned a tone with full alpha. Both halves are internal and pure so a host test can drive them without a Context
AccentPalette.choicesFor(context)               what the picker lists — AccentPalette.choices minus SYSTEM when the probe says no. Hiding rather than degrading: a choice that paints Azure promises the user's palette and delivers the app's
AccentPalette.resolveChoice(key, available)     the stored key plus the availability veto: "system" on a device without the tones reads AZURE. Covers the pref arriving through Auto Backup / device transfer on an older OS
AccentPalette.solid/light(activity)             now resolve through DesignTokens.accent/accentLight (i.e. the applied overlay) instead of returning the enum constant, so Canvas-paint callers follow the dynamic tone. Same numbers as before for the six fixed swatches
AccentPalette.swatchColor(context, choice)      the picker dot: the constant for a fixed swatch, one android.R.color.system_accent1_400 read for SYSTEM
Accent.System               values/themes.xml + values-v31/themes.xml  the base copy is Azure so every attribute resolves on every OS version; v31 redefines the whole style as framework tone references (accent1_400 solid / _200 light / _800 for accentColorAlpha14 / _600 for _Alpha80 / colorAccent = _400). The two alpha attributes take tones because XML cannot blend a color *reference* with alpha — tones 0..1000 are the public set; 20/30/40/50-style intermediates do not exist for accent1
SettingsActivity.showAccentColorDialog          reads AccentPalette.choicesFor(this) and swatchColor(), so the row appears only where it works
AccentPaletteTest           (test)              choice order + key contract, the SDK-31 gate, the alpha rejection, and the unavailable-key fallback
```

---

## Play Billing pass additions (paywall seam)

```
BillingGateway.ProProductId  "pixa_pro_unlock" — the one-time INAPP id. Renaming it here without creating the product (and keeping the old id owned by anyone who bought it) strands every existing purchase
BillingGateway               object; one process-wide BillingClient built in GallerySearchApp.onCreate on the main thread (the library delivers callbacks on the caller's Looper). connect / refresh / purchase(activity) / isReachable / priceLabel() / statusMessage(). Every entry point is runCatching-wrapped and reports through lastMessage instead of throwing — a billing failure is a UI condition, the gallery still has to open
  · observe/unobserve(listener: () -> Unit)   the only notification point (Handler(Looper.getMainLooper()) + CopyOnWriteArraySet, each listener runCatching). Screens MUST hold the lambda as a property: a fresh lambda per register is one leaked observer per pause/resume
  · purchase() returns false + a message when there is no client, no catalogue answer yet, or Play is not ready — the page leaves its own footer standing instead of pretending a sheet opened
  · purchasesUpdated  USER_CANCELED clears the message; ITEM_ALREADY_OWNED re-syncs (heals a purchase paid on another device); otherwise the verdict is recorded. Pending purchases are enabled, so a cash/carrier payment that has not settled can arrive
  · verifyOwnership   OK→QueryReply.Ok, ITEM_NOT_OWNED→NotOwned, anything else Failed; a thrown query is NOT recorded — an outage must not read as a refund
  · acknowledgePending  Play auto-cancels an unacknowledged purchase in a few days, so this is what keeps the purchase; a Pending one is left for Play to acknowledge at settlement
  · onBillingServiceDisconnected  one retry after ReconnectDelayMillis (3 s). A loop would spin against a Play that is genuinely absent; every paying screen reconnects via refresh() on resume
PurchaseRules           the billing-free decision layer, so the verdicts can be tested on a host JVM without Play types
  · PurchaseFact(productId, PurchaseState{Unspecified,Purchased,Pending}, acknowledged)   the boundary type BillingGateway maps Purchase into (products.firstOrNull(), purchaseState, isAcknowledged)
  · QueryReply{Ok,NotOwned,Failed} → verdictOf() → PurchaseVerdict{Owned,NotOwned,Unreachable}   Failed is never folded into NotOwned
  · owns() requires state == Purchased, so Pending is never ownership; needsAcknowledgment() only for a settled unacknowledged purchase
Entitlement             Unknown (fresh install / Play never answered) | Owned | NotOwned
Entitlements.state/isUnlocked(context)   reads the persisted verdict only — no Play, no network, so a bought feature keeps working on a plane
Entitlements.record(context, verdict)    folds Entitlements.next(current, verdict) and persists it. Unreachable leaves the stored verdict alone; a successful query overwrites (that is how a refund eventually locks the feature again)
Entitlements.guard(activity, reason) { ... }     THE SEAM every gated feature calls: runs the body when owned, otherwise opens ProPaywall with that reason and returns false. Features never see a product id, a price, or a response code. `reason` is the per-gate string already formatted with its own limit, so the sentence shown cannot drift from the rule enforced
ProPaywall.open(activity, reason)                the whole paywall seam: startActivity(ProActivity.intent(...)). It used to be a MetroDialog — see "Pro page pass additions" below for what replaced it and why the name stayed
SettingsActivity                         rowPro/proSubtitle (PRO section, above DISPLAY): bindPro + updateProSubtitle read Entitlements, onResume observes billingListener + refresh(), onPause unobserves. billingListener is a property for the reason above
IndexPreferences.get/setProEntitlement   key "pro_entitlement" in "index_prefs", stores Entitlement.name, unparseable → Unknown. Held in a @Volatile cachedEntitlement and read by the same warm-up as the accent — see the gate section below
build.gradle                             com.android.billingclient:billing-ktx:8.0.0 (ktx gives the suspend queryProductDetails / queryPurchasesAsync / acknowledgePurchase; 8.0.0 is Play's enforced floor and the last version before the Kotlin 2.2 metadata). No manifest edit: the library's own AAR manifest supplies com.android.vending.BILLING and the <queries> block, and the app still needs no INTERNET permission — billing is IPC to com.android.vending, and Play answers ownership queries from the Google app's cache
PurchaseRulesTest           (test)        ownership excludes Pending and foreign products, the acknowledgment rule, verdictOf's four replies, and both halves of the persistence rule (an outage cannot strip Owned; a proof overwrites)
```

---

## Free tier gates pass additions (six limits on one seam)

```
FreeTier                  the whole free/paid boundary in one file: a limit stated on five screens is five chances to disagree about what the user paid for
  · FreeAccents = 3, FreeSafePhotos = 50, FreeSmartAlbums = 5, FreeCompressionPhotos = 1, PaidCleanupCategories = {BURSTS, NSFW}
  · accentLocked(choice, unlocked)   uses AccentPalette.choices.indexOf(choice), so the enum's declaration order is pricing — see GRAPH.md. SYSTEM (declared last) is locked like any swatch: offering the OS's own palette free would make the paid half of the list depend on the phone
  · safeRoom(inVault, unlocked) → slots left, never negative; unlocked returns Int.MAX_VALUE so a 500-photo move stays one batch
  · smartAlbumsBlocked(existing, unlocked) → true AT the cap, not past it (the 5th is allowed)
  · cleanupLocked(category, unlocked) → the two tiles whose lists only exist because the scan did work past metadata (the O(n²) burst grouping, the classifier)
  · compressionRoom(requested, unlocked) → how much of a pick may go into this run; bought gets the whole list
  Every predicate takes `unlocked` and is false when it is true, so nothing here decides HOW a lock is shown
AccentPalette.current(activity)     now also demotes a paid stored choice to AZURE. Enforced at read/apply time rather than only in the picker because this is what every activity's apply() goes through: a stored Rose arriving by Auto Backup, or bought then refunded, must not paint the app in a color nobody owns. The stored key is untouched, so buying unlocks it without a re-pick
MetroDialog.singleChoice(…, locked: Set<Int>)   locked rows stay listed, dimmed: dot setAlpha(110), label metroTextTertiary, a lock glyph in place of the checkmark (a picker showing three colors reads as a three-color app, not a bought one). The tap still reports through onSelect — the widget knows nothing about entitlements, the caller decides
SettingsActivity.showAccentColorDialog  passes the paid indexes from FreeTier.accentLocked; the check mark can never sit on a locked row because AccentPalette.current already demoted the stored choice
SettingsActivity.applyAccent(choice)    Entitlements.guard(this, gate_accent) wrapping the pref write + accentChanged flag
SettingsActivity.appliedAccent + onResume check  recreate() when the accent IN FORCE moved while this screen was open — the case where buying PRO re-paints a swatch the user picked before the gate existed, with no pref write to notice
MainActivity.settingsLaunchAccent (was …AccentKey)  now holds AccentPalette.Choice, not the raw key, so the same verdict-change recreate fires on the gallery side; the drawer's Settings click stores AccentPalette.current(this)
PersonIdentityEditor.show(activity, person, onSaved)  one guard over the renamed private openIdentityEditor, covering all three roads in (PersonAlbumsActivity long-press, PersonDetailActivity title tap, identityAction button) — asked before the keyboard, not after Save. Naming and relationship share PersonDao.updateIdentity and this dialog, so both are the paid identity
SafeActivity.runImport(uris, isMove)   the only door into the vault (gallery move, cleanup move, this screen's picker), so the cap is enforced once: SafeManager.listItems().size on IO → FreeTier.safeRoom → uris.take(...) BEFORE any byte is written. Empty → paywall and return, nothing lost. Partial → the batch imports, the banner reports, THEN the offer shows; and since only ImportResult.importedSources is set as ExtraImportedUris, a photo left out of the clamp is never deleted from the gallery. Restoring OUT of the Safe is never gated
item_cleanup_tile.xml tileLock   18dp top|end lock glyph, gone by default, alpha 0.9, contentDescription @string/locked
SmartCleanupActivity.renderTiles / onResume   a lock on a paid tile's corner, and re-render when Play's sheet backgrounds the screen, so a purchase made here takes the locks off without a restart
SmartCleanupActivity.openCategory(category)   gated at the door with gate_cleanup + categoryTitle(category): the tile still counts the photos, but the user never lands in a detail view whose select-all/delete/Safe bars they cannot use. currentCategory is not in instance state, so a gated category cannot be restored across a recreation
SmartCleanupActivity.launchCompressionReview()   compression is metered, not locked, so COMPRESSIBLE's tile opens and the cap lands on the batch instead: selectedUris().take(FreeTier.compressionRoom(...)) is the one place a fresh batch is formed (CompressionActivity either adopts a running batch or this hand-off), and the picks left behind stay selected. What was held back is remembered in compressionHeldBack and the offer shows from the compressionLauncher result — after the run is reported, never over it, and read-and-clear before the RESULT_OK test so a cancelled review cannot carry a stale promise
MainActivity.showCreateSmartAlbumDialog()   blocked before the dialog opens (the top-bar add reaches it through showCreateAlbumMenu, and the albums onboarding card still goes straight there) — the count is the in-memory collectionAlbums list filtered to `kind == Smart`, so no JSON parse on a tap. Refreshing/renaming an existing album stays free
IndexPreferences.warmStartupKeys(context)  was warmAccentCache: one prefs handle now fills both @Volatile caches (cachedAccentKey, cachedEntitlement) because AccentPalette.apply() reads the accent and the entitlement before super.onCreate() — the measured cold-start block was ~400–500 ms when either hit disk
GallerySearchApp               the startup pre-warm thread calls warmStartupKeys, "accent and the entitlement go first"
strings.xml gate_accent / gate_people / gate_safe(%1$d) / gate_cleanup(%1$s) / gate_compression(%1$d) / gate_smart_albums(%1$d)  what the user reached for, said back as the Pro page's first line under its accent rule; each passes its own FreeTier limit so copy and rule cannot drift
FreeTierTest            (test)  first three swatches free and everything after locked (SYSTEM last and paid), unlock frees all, safeRoom counts what is inside and never goes negative, unlocked safeRoom truncates nothing, the album boundary blocks at exactly 5 and not before, only the two work-doing cleanup tiles are locked (COMPRESSIBLE explicitly free), a free compression run takes one photo of forty and bought takes all forty
```

---

## Saved album kinds pass additions (the "+" now offers three ways to collect)

```
MainActivity.showCreateAlbumMenu()  the top-bar "+" and the Albums empty state's action both open this MetroDropdownMenu: Smart album / Folder album / Tag collection. Was a direct jump to the smart dialog
MainActivity.askFolderAlbumName()  a folder album is made by naming it, not by browsing the device for a folder to mirror: MetroDialog.input → createFolderAlbum(name) → AlbumRules.albumFolderPath over the Settings root. Empty library no longer matters — there was never a folder to pick
MainActivity.createFolderAlbum(name) / saveTagAlbum(existing,name,tagNames)  both end in saveCollectionAlbum. A folder album's id IS its folder, so an existing record for that path stops the flow with folder_album_exists instead of making a second album of one folder; the album is named for the cleaned leaf, so the tile and the directory say the same thing
MainActivity.saveCollectionAlbum(album,pin)  the one write path: upsert → refresh the in-memory list → pin when new → re-render the detail page if it is showing this album. A saved collection is only visible through the pinned strip, so pin=false would hide what was just made
MainActivity.showTagAlbumDialog(existing)  refreshes the tag cache first (the viewer writes tags straight to Room), then openTagAlbumDialog over dialog_tag_album.xml — chips from createSearchPillView, accent fill = selected, rebuilt on every tap; Create becomes Save and the chips come pre-ticked when editing. No tags at all → tag_album_none
MainActivity.albumFolderRoots()  the Albums page's folder tree, built at most once per collectionItems list (cached against the list's identity; a media change or a hide installs a new list and rebuilds it). Separate from folderTreeRoots on purpose: that one carries the Folders page's expansion state and is patched in place as photos are hidden
MainActivity.collectionAlbumItems(album)  what the album holds at this moment. Folder: the Folders page's own tree node flattened, so an album and the folder row it came from cannot disagree. Tags: the tag-name union against allTags/tagUriMap. Smart: stored memberUris in their ranked order
MainActivity.collectionAlbumViews()  the grid cells. One tree pass (albumFolderRoots) serves every folder album, and one pass over the library serves every tag album's count + first cover — the grid needs those two facts, not the membership, and a copy of each album's items was what made the Albums page stall the main thread
MainActivity.collectionAlbumEmptyCell(album)  why it is empty says what to do: a folder album the app made itself says folder_album_empty (not filled yet — the folder only exists once a photo is written into it), one pointed at a device folder that has since emptied says folder_album_gone; collection_album_empty_tags / the smart album's refresh hint
MainActivity.collectionAlbumScopeKey(album)  the paging key that doubles as the sort scope. Smart keeps its historic "smart:"+id so an order picked before folder albums existed is not silently reset; the live kinds use their already-prefixed id (a folder album shares its folder's order)
MainActivity.refreshTagCacheForAlbums()  renderAlbums calls it: stale cache + at least one tag album → refresh off-thread, re-remember the scroll anchor, render once. Without it a tag album looks empty until the next search unstales the cache
MainActivity.albumKindLabel(kind) / collectionAlbumIconRes(kind)  the word and the glyph for a kind. The AI orb stays a smart-album mark — a folder album was not gathered by a model, and the same rule drives smartOrb in ImageAdapter
MainActivity.showCollectionAlbumMenu(record,anchor)  rows are built per kind: Add photos is folder-album only (the other kinds hold a rule, so a hand-added member would be erased by the next render), Refresh and Edit prompt are smart only (a live album has nothing to rebuild), Edit tags is tag-album only, Rename/Unpin/Delete are shared. Takes the CollectionAlbum, not the grid's Album view
AlbumRules                AlbumRules.kt            see ## Classes for the current shape. This pass replaced folderChoices(roots)/FolderChoice — the chooser that ranked the device's folders by count — with albumFolderPath(root,parent,name) and, later, isAlbumFolderPath(path)
AlbumRulesTest            (test)                   path lookup, tag union and de-dup, a vanished tag, id prefixes, live vs stored counts; the folder rules now: an album folder sits under the chosen root and its parent and round-trips through isAlbumFolderPath, an app-made folder is told apart from one an album was pointed at, a name cannot escape the parent, a long name is capped rather than truncated mid-path
dialog_tag_album.xml      ScrollView shell cloned from dialog_smart_album.xml — TAGS chip row above NAME, because the choice is the work and the name is a label
strings.xml album_kind_* / folder_album_* / tag_album_* / collection_album_empty_tags* / delete_album_title(%1$s) / delete_album_message(%1$s)  the three kinds named, and every dead end said out loud
FreeTier.smartAlbumsBlocked  still counts smart albums ONLY, which is what gate_smart_albums promises: a folder or tag album stores no embeddings and runs no search, so it is not the thing the cap prices
```

---

## Album folders pass additions (name the folder instead of browsing for it, and put photos into it)

```
AlbumRules.AlbumFolderParent  "Deepix Albums" — the one parent segment every app-made album folder sits under, and the thing isAlbumFolderPath reads back off a path
AlbumRules.albumFolderPath(root,parent,name)  "$root/$parent/$leaf" or null. The typed name is cleaned, not trusted: / \ : and control chars become spaces, runs of spaces collapse, the ends are trimmed of whitespace then of dots, so "." / ".." / "/etc" yield nothing that could climb; take(60) caps the leaf because RELATIVE_PATH is a column. Tested: cannot escape the parent, a long name is capped rather than cut mid-path
AlbumRules.isAlbumFolderPath(path)  the parent segment of a folder path == AlbumFolderParent. A folder album of the app's own that is empty has not been filled; a device folder an older album was pointed at that is empty has lost its photos. One boolean, two different sentences (folder_album_empty vs folder_album_gone)
IndexPreferences.getAlbumFolderRoot/setAlbumFolderRoot  "album_folder_root" in index_prefs, reusing the Safe's SAFE_ROOT_PICTURES/SAFE_ROOT_DOCUMENTS values, default pictures. Applies to albums made AFTER the change only — following it for existing albums would mean moving the user's photos, which is not what a setting is for (contrast getSafeStorageRoot, which moves the one-zip vault)
MainActivity.albumFolderRootSegment()  the pref as the path segment MediaStore writes ("Pictures"/"Documents")
SettingsActivity.bindAlbums/showAlbumLocationDialog/updateAlbumLocationSubtitle  Settings › ALBUMS › "Album folders location", the Safe's two-option dialog (MetroDialog.singleChoice), subtitle through the existing rootLabel(). Deliberately NOT the Safe's free-folder SAF picker: an album folder is a MediaStore RELATIVE_PATH, which can only name a public tree
MainActivity.createFolderAlbum(name)  id = folderIdFor(path) so a repeated name is the same album; name = the cleaned leaf so the tile and the directory agree; banner folder_album_made(path) because the directory does not exist on disk yet — MediaStore makes it with the first photo written in
MainActivity.collectionAlbumEmptyCell(album)  the Folder branch now splits on isAlbumFolderPath
MainActivity.showAlbumAddPhotosDialog(album)  imageItems minus collectionAlbumItems(album) — a folder album's own photos are already in its folder, so only photos from elsewhere are worth offering; nothing left → album_nothing_to_add
MainActivity.openAlbumAddPhotosDialog(album,candidates)  dialog_album_add_photos.xml over the app's own ImageAdapter (tap = toggle, columns from Responsive.gridColumns, width from addOnLayoutChangeListener — the SmartCleanup wiring), Copy here / Move here share one start(move) guarded by `running`, progress in albumPickStatus
MainActivity.applyAlbumAddPhotos(result,move)  the ordering is the safety: refreshVisibleItems()?.join() FIRST (the album fills while the originals still exist), performManagedDelete(result.copiedSources, outcomeLabel) SECOND. Only copies that read back whole are in copiedSources, so a delete can never be handed a photo that did not land. Empty result → album_copy_none and no delete at all
MainActivity.performManagedDelete(uris,outcomeLabel)  the optional label exists so a move reports "3 photos moved into the album" rather than "3 photos deleted" — the same banner slot, the honest words. Bin Undo, failure re-render and hiding are unchanged
AlbumFolderOps.copyInto(context,sources,relativePath,onProgress)  whole-batch BinLedger.hasRoomFor(usableSpace, Σ SIZE) before a byte is written (a volume that fills mid-run would fail whichever photos happen to be last), then copyOne per photo with runCatching so one bad file cannot abandon the rest
AlbumFolderOps.copyOne  Q+: insert(DISPLAY_NAME,MIME,RELATIVE_PATH="$path/",IS_PENDING=1). pre-Q: no RELATIVE_PATH, so DATA = publicDirFor(...)/uniqueIn(dir,name) — a DATA path already held by another photo is written OVER, which is why the name has to be free first. Then stream copy → forceToDisk → countBytes → publish IS_PENDING=0 ONLY after the match; any step that fails → discard() the row and report failure
AlbumFolderOps.forceToDisk  fsync of the copy before it is counted, through MediaFileOps.resolvePath + FileInputStream(fd).sync(). Needs All-files access for the path (MediaStore gives no way to force a row it owns); without it the copy stands unforced, which is the same posture MediaFileOps.copyToFile documents for the bin
AlbumFolderOps.copyVerified(expected,written) = expected > 0 && written == expected  stricter than BinLedger.copyComplete, which accepts "non-empty" when the source size is unknown. There the next step changes nothing; here it deletes the original, so a size nobody can name is a reason to stop
AlbumFolderOps.Result  copied = original→new uri pairs in pick order, failed = originals whose files were not touched. copiedSources is what the move hands to the delete
dialog_album_add_photos.xml  ScrollView shell like dialog_tag_album.xml (the grid + intro exceed a sideways window); albumPickCopy is the accent pill (crop_search_pill), albumPickMove the accent-outlined chip (search_filter_chip_bg) — Move is the one with a consequence, so it is not the filled button
strings.xml folder_album_{name_title,name_hint,bad_name,exists,made,empty,empty_hint} / album_{add_photos,add_photos_intro,options,nothing_to_add,pick_none,pick_count,copy_here,move_here,copy_progress,move_progress,copy_failed,copy_none} / plurals album_photos_{copied,moved} / section_albums / album_folder_location / album_location_{dialog_title,documents,changed}
Removed with the folder picker  AlbumRules.folderChoices + FolderChoice, MetroDialog.searchableList + MaxSearchRows, MainActivity.showFolderAlbumPicker, strings folder_picker_{title,intro,hint,none} / picker_more / folder_album_none. Nothing else called them; the loss is the ability to point an album at a folder the app did not make — existing albums that already do keep working, they just cannot be made any more
```

---

## Pro surface pass additions (the tour's benefits page, the drawer's offer row, the offer itself)

```
OnboardingPanel.benefits  the tour's third page shape: feature (glyph + copy), permissions (ruled *live* rows), benefits (accent tile + ruled, inert rows). OnboardingPanelAdapter.bind dispatches permissions → benefits → feature, and [FirstRunActivity] still treats the LAST page as the permissions one — "Get started", `requestFirstOutstandingRequired()` and the dismiss X's goTo(pageCount - 1) all hang on that — so a new panel goes before it
OnboardingBenefit(label)  one included thing, said once. The six strings are the FreeTier gates restated for a reader who has not hit one yet — including compression, which is a batch size rather than a locked screen
item_onboarding_benefit_row.xml  18-unit accent check + 13sp label + hairline; deliberately narrower than item_onboarding_perm_row, which also carries a summary and a state word and can be a button
bindBenefitsPage  gives the tile a FIXED-height area (weight 0) and lets the ruled list absorb the slack below the copy, the way the permissions page does. Weighting both would have the glyph and the list compete for the same leftover, and on a short or landscape screen the list is the part that must not clip
ic_onboarding_pro.xml  a brilliant-cut gem in the tour's own line-art language (24-unit viewport, 1.5 stroke, transparent fill), drawn white because it is tinted metroTextPrimary *on* the accent tile rather than accent-on-black like every other glyph
onboarding_pro_tile_bg.xml  flat accent square, 2dp corners — the same fill and radius as onboarding_button_bg, without the ripple: this is a badge, not a button. White-on-accent is the pairing the tour's own CTA already uses
MainActivity.drawerPro  the drawer's only row that reports a fact instead of a place: last row of the navigation list, accent-colored, tap closes the drawer and opens ProPaywall with no gate reason (an offer, not a refusal). updateDrawerState() labels it drawer_pro / drawer_pro_owned and glyphs it lock / checkmark from Entitlements.isUnlocked — setCompoundDrawablesRelative* drops the XML drawableTint, so the accent tint is re-applied in code. A SimpleDrawerListener on onDrawerOpened re-runs updateDrawerState(), because the verdict can move while the drawer is shut (Play's sheet, another screen's paywall, a refund)
```

---

## Pro page pass additions (the offer left the popup)

```
ProActivity                 ProActivity.kt  the whole offer as a screen: hero mosaic → WHAT PRO ADDS (6 rows) → WHAT STAYS FREE (6 rows) → HOW THE PURCHASE WORKS (4 prose rows) → footer. Launched only through ProPaywall.open; manifest-declared configChanges + exported="false"
  · intent(context, reason) / ExtraReason "pro_reason"  the only way in, and the only way a gate's sentence travels. Blank or absent → no reason line at all
  · renderLimits()  formats pro_add_accents_detail / pro_add_safe_detail / pro_add_compression_detail / pro_add_albums_detail / pro_free_compression from FreeTier at runtime, and those five rows hold their string as tools:text — design-time, so the layout has no runtime text to disagree with the cap and no raw `%1$d` can ever be painted
  · renderReason()  gone → visible, only when a gate sent the user here; the drawer row and Settings row arrive with nothing
  · renderOffer()  Entitlements.isUnlocked for the footer's two postures, then ProOffer.priceCaption for both the price line and the Buy label — one verdict read once, so the two fonts cannot disagree about whether Play has named a price. BillingGateway.statusMessage paints below them. onResume observes billing, paints, then refresh()es (Play's sheet backgrounds the app, so coming back is how a finished purchase is noticed); onPause unobserves — billingListener is a property for the reason BillingGateway documents
  · buy  `if (!BillingGateway.purchase(this)) BillingGateway.refresh()` — a sheet that could not open must say why on the page that pressed it
  · restore  refresh() + renderOffer(): a one-time unlock belongs to the Play account, so re-querying ownership IS the restore flow
  · applyResponsiveChrome()  Responsive.applyTitleText on the 40sp title, and the mosaic gets min(window − 40dp, 420dp) — 220dp when Responsive.cramped — because the vector is drawn for the reference phone and extra width would enlarge tiles rather than the picture
ProOffer                    ProOffer.kt  the page's one rule with Android out of it: priceCaption(price, state) → Known|Unavailable|Checking. A null price before any verdict is Checking, never "Price unavailable" — that sentence would be about the product when the truth is about this moment. Ownership is NOT in here: Entitlements.isUnlocked is the one predicate, and Unknown is not ownership, which is why an install that never reached Play still sees the purchase
ProPaywall.open(activity, reason)  startActivity, nothing else. All nine gated call sites changed their verb and not their shape, so Entitlements.guard, the drawer and Settings still have exactly one way to offer
activity_pro.xml            proRoot → topBar/backBtn → ScrollView (20dp sides) → heroIllustration, proEyebrow, proTitle (sans-serif-light 40sp, −0.02 tracking), proBody, reasonText, three SettingsSectionHeader blocks divided by SettingsDivider, proFooter (purchaseBar | ownedBar, statusCaption). Buy = onboarding_button_bg at 52dp; Restore = the secondary idiom (metro_row_pressed TextView), the pair activity_safe.xml's add-photos bar already uses
ProRow / ProRowGlyph        themes.xml  the settings row's metrics with its press state and clickability removed: these rows explain, they are not buttons. Benefit glyphs are 24dp tinted metroTextPrimary and the free section repeats the SAME glyph set at alpha 0.55, so free and paid read as one system instead of two icon sets. Accent appears only where it is a state — the paid half of the hero, the Buy pill, the section labels, the owned checkmark
illustration_pro_hero.xml   320×152 mosaic: four metroBgTertiary tiles under a metroBgSurface hairline (free) and four solid ?attr/accentColor tiles (Pro), 66-unit tiles on an 84-unit pitch with the gutter doing the separating. Glyphs are the tour's 24-unit line art inside a group at scale 1.25 drawn at 1.2 stroke, which lands at the 1.5 weight the rest of the app carries; tiles are inset 1 unit or a centred edge stroke clips. The Safe glyph is an OPEN padlock — the shackle's right leg is left off, because "the cap is off" is the claim
ic_pro_accents.xml          the accent picker as it actually appears: MetroDialog.singleChoice paints each swatch as a 16dp GradientDrawable.OVAL, so this is three dots (r 2.7, pitch 7.4, middle one filled) — not the squares it was drawn as first
ic_pro_batch.xml            two overlapping cards, for a limit that is a batch size; no existing glyph says "queue"
pro_reason_bg.xml           layer-list carrying the 2dp accent rule as the text's own background. The obvious construction — a sibling View at match_parent height — measures to zero inside a ScrollView, because the wrap_content LinearLayout above it is measured UNSPECIFIED; same idiom as info_sheet_bg.xml's top hairline
strings.xml pro_*           pro_body is the offline promise plus "takes nothing away"; pro_section_adds/free/how the headers; pro_add_*_detail and pro_free_* the rows (%1$d wherever a FreeTier number is printed); pro_how_once/play/restore/refund + _body the four answers a one-time, Play-mediated, refundable unlock owes; pro_price_line / pro_price_unknown / pro_checking / pro_buy(_price) / pro_restore / pro_owned_body the footer's three states. pro_title/pro_row_title/pro_owned_subtitle/pro_not_purchased and every gate_* survived the rewrite unchanged
ProOfferTest                (test) a price prints the moment Play names one, and silence is never reported as unavailability — the two halves of the one claim the page can get wrong without a network
```

## Android 10 storage truth pass additions (the permission stopped lying about a device that cannot grant it)

```
StoragePermissions.hasAllFilesAccess   now `SDK_INT < Q` on the legacy side, not `true` for every pre-R OS. Every caller of it is a direct-file path, and Android 10 puts an app targeting API 29+ in scoped storage whatever the manifest declares — so claiming access there made failing writes look like the app's own bugs
StoragePermissions.allFilesAccessRequestable()   `SDK_INT >= R`: does this device have the switch at all? Ask it before sending anyone to Settings, and before promising a toggle in copy
StoragePermissions.manageAllFilesIntent   unchanged, and reached on fewer devices now — its pre-R app-details branch is the fallback it always was
DeleteCoordinator.shouldAskForStorageAccess   !canDeleteDirectly && requestable. The gate at MainActivity.requestDelete, ViewerActivity.requestDelete and SmartCleanupActivity.confirmDeleteSelected: on Android 10 it is false, so the delete falls through to Outcome.NeedsSystemDelete and the per-item consent dialog (contentResolver.delete → RecoverableSecurityException → launchDeleteConsent) runs at last. Bulk on Q is still refused by the existing "Android 11 or newer" banner — one dialog per photo is not a delete
SafeActivity.promptStorageAccess   branches on requestable: Q gets a MetroDialog.message "Safe needs Android 11 or newer" that finishes on Close, instead of a Settings screen with nothing in it. The two "All-files access is required for Safe" banners are gone — beginSetupFlow shows the right dialog for the OS, and the old wording promised a switch Android 10 does not have
CompressionActivity.onReplaceClicked / onKeepBothClicked   same branch, banner only ("Replacing originals needs Android 11 or newer" / "Saving copies …"), no Settings launch
FirstRunActivity.statusOf(AllFiles)   UNAVAILABLE + onboarding_perm_state_not_available on Q (a row that could never be tapped off; UNAVAILABLE rows are excluded from finishBlocked by design), and request(AllFiles) returns without opening Settings
onboarding_perm_state_not_available   strings.xml "Not available"
```

---

## API 36 + Billing 8 pass additions (two Play gates; one builder call and one screen)

```
build.gradle (root)                     AGP 8.11.0 — compileSdk 36 is not supported below 8.9.1. Gradle wrapper 8.9 → 8.14.3 moves with it
app/build.gradle                        compileSdk 36 / targetSdk 36, billing-ktx 8.0.0. 8.0.0 is the OLDEST version Play accepts, taken so Kotlin stays at 2.0.20: 8.1.0+ ships Kotlin 2.2 metadata this plugin cannot read, and 9.0.0 asks for targetSdk 35 anyway
versionCode                             still 1 — a second APK at that number is refused by Console, but the number is a release decision, not a code fix
BillingGateway.connect                  .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()). Billing 8.0.0 deleted the no-arg overload, and the params object defaults to off for one-time products — the only kind this app sells, so an empty builder would have silently un-armed pending purchases
PhotoEditorActivity.applyInsets()       the last screen that let the decor consume its insets is now on the idiom the other 14 use: topBar top = bars.top + 10dp, bottomArea bottom = bars.bottom, setDecorFitsSystemWindows(false), transparent status bar
activity_photo_editor.xml               bottomArea is wrap_content and carries an explicit 64dp on toolBar/toolActionBar. A fixed-height container + match_parent children means the nav-bar inset would have been subtracted from the bar's own height; the bar now keeps 64dp and the inset lands below it
CompareFullscreenActivity               deliberately unchanged — immersive, nothing to pad
statusBarColor / navigationBarColor     kept in themes and in code, ignored by the framework at 36. Bars are black because the content under them is black
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
Mode          MainState.kt   Browse | Search | AlbumDetail | FolderDetail | CollectionAlbumDetail
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
SafeManager.MasterName = "PixaSafe.zip"   // single vault archive at Pictures|Documents /Pixa Safe/ (root via IndexPreferences.getSafeStorageRoot; All-files access)
SafeManager.LegacyMasterName = "DeepixSafe.zip"   LegacyVaultFolderName = "Deepix Safe"   // every vault predating the rename; zipNames/vaultDirs resolve the first archive that exists, and purgeVault erases both names
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
ProActivity.ExtraReason             // String — the gate's sentence, shown above the price; absent when the user came looking rather than being refused
```

---

## Notification IDs

```kotlin
IndexWorker.NotificationId       = 1001   // indexing progress
IndexWorker.PausedNotificationId = 1002   // indexing paused
IndexWorker.ChannelId            = "gallery_index_channel"
```
