# Deepix — Symbol Reference (Token-Efficient)

Use this for targeted lookups. Format: `SymbolName` | file | signature/notes

---

## Classes

```
GallerySearchApp          GallerySearchApp.kt      Application; owns SharedEncoders (lazy)
SharedEncoders            GallerySearchApp.kt      getImageEncoder(): ImageEncoder; getTextEncoder(): TextEncoder
ImageEncoder              ImageEncoder.kt          encode(Bitmap): FloatArray; encodeBatch(List<Bitmap>): List<FloatArray>; preprocess(Bitmap): FloatArray
TextEncoder               TextEncoder.kt           encode(query: String): FloatArray; val tokenizer: ClipTokenizer
ClipTokenizer             ClipTokenizer.kt         encode(query: String): TokenizedText; ContextLength=77; prepends "a photo of "
GalleryRepository         GalleryRepository.kt     loadSnapshot(); search(); buildIndex(); semanticSearch(); loadIndex()
  MediaItem               GalleryRepository.kt     data class: uri,bucketId,bucketName,dateMillis,width,height,mimeType,displayName,mediaType,durationMillis,path
  Album                   GalleryRepository.kt     data class: id,name,count,coverUri,isSmart
  SemanticSearchHit       GalleryRepository.kt     data class: uri: Uri, score: Float
  Snapshot                GalleryRepository.kt     data class: albums,imageItems,collectionItems,videoItems
DbRepository              DbRepository.kt          upsertMedia(); toggleFavorite(); upsertExif(); addTag(); setTagsForMedia()
IndexWorker               IndexWorker.kt           CoroutineWorker; WorkName="gallery_background_index"; BatchSize via GalleryRepository
IndexControlReceiver      IndexControlReceiver.kt  ActionPause/ActionResume broadcasts; pendingIntent()
IndexPreferences          IndexPreferences.kt      save/loadLastIndexedTime; isIndexPaused/setIndexPaused; getGridColumnCount
IndexScopeStore           IndexScopeStore.kt       getFolderIds/setFolderIds/isAllFolders (empty=all); AI-index folder scope, independent of gallery view
IndexedFoldersActivity    IndexedFoldersActivity.kt  Settings folder picker → IndexScopeStore + IndexController.rescan
PhotoEditorActivity       PhotoEditorActivity.kt   in-app editor (crop/perspective/draw/adjust); Save + Save a copy; ExtraUri/ExtraName/ExtraEdited
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
OnnxSessionOptions        OnnxSessionOptions.kt    create(tag,threadCount=4): OrtSession.SessionOptions — NNAPI disabled
DesignTokens              DesignTokens.kt          (see CONTEXT.md for key values)
StickyHeaderDecoration    StickyHeaderDecoration.kt  RecyclerView.ItemDecoration
ThumbnailScaleGestureListener  ThumbnailScaleGestureListener.kt  pinch-to-resize; emits onZoom(zoomIn) step (grid columns OR collage scale)
ImageAdapter              ImageAdapter.kt          RecyclerView.Adapter; useCollageLayout; gridColumnCount; spanSizeAt(); replaceCells(); ctor cb onCreateSmartAlbum
  GalleryCell             ImageAdapter.kt          sealed: Header|Photo(collageSpan,collageHeightPx)|Collage|AlbumCell|FolderCell|PinnedAlbumsHeader|SmartAlbumOnboarding|Empty
FastScrollIndicator       FastScrollIndicator.kt   attach(RecyclerView, ImageAdapter); tracks all scrolls; drag → scrollToPositionWithOffset
MediaPagerAdapter         MediaPagerAdapter.kt     RecyclerView.Adapter for ViewPager2; ctor cb: onInitialImageLoaded/onMediaTap/onMediaLongClick/onVideoCompleted/onScrubbingChanged; releaseAll()
  PageViewHolder          MediaPagerAdapter.kt     bind(); start/pause/stopPlayback(); isPlaying(); isZoomed(); setScrubberVisible(); cleanup(); player tracked in SparseArray
ViewerItemsHolder         ViewerItemsHolder.kt     object; store()/retrieve(uri)/release(); strong ref (was WeakReference)
FolderNode                FolderNode.kt            data class: name, path, children, mediaCount
ExifData                  ExifData.kt              data class; hasCameraInfo: Boolean; hasGps: Boolean
ExifExtractor             ExifExtractor.kt         extract(context, uri): ExifData
TagPickerDialog           TagPickerDialog.kt       AlertDialog subclass for tag assignment
ThreadBenchmark           ThreadBenchmark.kt       benchmarks optimal ORT thread count
SmartCleanupActivity      SmartCleanupActivity.kt   dedicated cleanup screen; reads CleanupResultStore, observes CleanupWorker; tiles + selectable grid; pause/resume/stop
CleanupAnalyzer           CleanupAnalyzer.kt        object; analyze(items,embeddings,sizeByUri,encodeText,imageStats,onProgress,onPartial,resumeQuality,scannedUris): Report
  Category                CleanupAnalyzer.kt        DUPLICATES|SIMILAR|LIKELY_CLUTTER|SCREENSHOTS|DOCUMENTS|RECEIPTS|QR_CODES|BLURRY|DARK|BRIGHT|LOW_RESOLUTION
  ImageStats              CleanupAnalyzer.kt        data class: variance, meanLuma, fractionNearWhite
  Report                  CleanupAnalyzer.kt        categoryItems, suggestedDeleteUris, sizeByUri; count(); reclaimableBytes(); totalReclaimableBytes()
CleanupWorker             CleanupWorker.kt          CoroutineWorker; WorkName="gallery_smart_cleanup"; foreground; full scan → CleanupResultStore; resumable; ProgressCurrent/TotalKey
CleanupResultStore        CleanupResultStore.kt     save()/load()/clear(); Result(categoryUris,suggestedUris,scannedUris,done,total,complete,updatedAt) → cleanup_results.json
CleanupHandoff            CleanupHandoff.kt         object; items, indexedCount, release() — hand-off to SmartCleanupActivity
SettingsActivity          SettingsActivity.kt       prefs screen: collage/grid columns/pinned/charging-only/clear cleanup/about; writes IndexPreferences
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
MediaPagerAdapter ctor cb += onPlayStateChanged(position, playing)  // play/pause/replay + mute sync
MediaPagerAdapter.PageViewHolder.togglePlayback()/setVideoControlsVisible()
StructuredSearch.ScreenshotFilter                                   // is=screenshot (name/path heuristic)
IndexPreferences.isCleanupPaused()/setCleanupPaused()
IndexPreferences.isIndexConsentGiven()/setIndexConsentGiven()/wasIndexConsentAsked()/setIndexConsentAsked()
IndexPreferences.isChargingOnlyIndexing()/setChargingOnlyIndexing()
MainActivity: maybePromptIndexingConsent()/showIndexingStartedDialog()/onIndexDrawerAction()/pauseIndexing()/resumeIndexing()/enqueueIndexWork(policy)
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
MainActivity: ensureEncodersLoaded(warmupDelayMs): CompletableDeferred<Boolean> // idempotent lazy CLIP load; awaited by search paths
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

## DB Entities & DAOs

```
MediaMetadataEntity   db/MediaMetadataEntity.kt  table: media_metadata; PK: uri
ExifMetadataEntity    db/ExifMetadataEntity.kt   table: exif_metadata; PK: uri
FavoriteEntity        db/FavoriteEntity.kt        table: favorites; PK: uri
TagEntity             db/TagEntity.kt             table: tags; PK: id (autoGen); unique: name
MediaTagCrossRef      db/MediaTagCrossRef.kt      table: media_tag_cross_ref; PK: (mediaUri, tagId)
GalleryDatabase       db/GalleryDatabase.kt       singleton; DB name: gallery_metadata.db; v2; fallbackToDestructiveMigration
MediaMetadataDao      db/MediaMetadataDao.kt      upsert(List<MediaMetadataEntity>)
ExifMetadataDao       db/ExifMetadataDao.kt       upsert(ExifMetadataEntity); getByUri(uri)
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
BatchSize         = 4
SaveEvery         = 20
MaxBitmapEdge     = 512
IndexMagic        = 0x47534958   IndexVersion = 2
MetadataIndexMagic = 0x474d4458  MetadataIndexVersion = 1

// SmartAlbumStore.kt
SMART_PREFIX      = "smart:"
MAX_SMART_MEMBERS = 800

// OnnxSessionOptions.kt
DefaultThreadCount = 4

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
```

---

## Notification IDs

```kotlin
IndexWorker.NotificationId       = 1001   // indexing progress
IndexWorker.PausedNotificationId = 1002   // indexing paused
IndexWorker.ChannelId            = "gallery_index_channel"
```
