# Deepix — File Dependency Graph

Use this file to navigate cross-file impact before editing. Each entry shows what a file imports and what imports it.

---

## God Nodes (highest connectivity)

| Node | Used by | Uses |
|---|---|---|
| `GalleryRepository` | MainActivity, IndexWorker, DbRepository | ImageEncoder, TextEncoder, MetadataSearch, QueryExpander, EmbeddingUtils |
| `MainActivity` | (entry point) | GalleryRepository, DbRepository, ImageAdapter, FavoritesStore, AlbumPinStore, SmartAlbumStore, IndexPreferences, StructuredSearch, DesignTokens, SearchResultManager, ViewerActivity |
| `DesignTokens` | MainActivity, IndexPreferences, ImageAdapter, FastScrollIndicator | colors.xml |
| `IndexPreferences` | MainActivity, IndexWorker, IndexControlReceiver | DesignTokens |

---

## Dependency Map

### AI / ML Layer
```
GallerySearchApp
  └── SharedEncoders (lazy)
        ├── ImageEncoder  ←  AssetUtils, OnnxSessionOptions, OnnxOutput, EmbeddingUtils
        │     └── vision_model_fp16.onnx, preprocessor_config.json
        └── TextEncoder   ←  ClipTokenizer, OnnxSessionOptions, OnnxOutput, EmbeddingUtils
              └── text_model_int8.onnx, tokenizer.json, tokenizer_config.json
```

### Indexing Pipeline
```
IndexWorker
  ├── GalleryRepository  (getNewImageUris, buildIndex, rebuildMetadataIndex)
  │     ├── ImageEncoder  (encodeBatch)
  │     ├── MetadataSearch  (buildDocuments, indexFromDocuments)
  │     └── EmbeddingUtils  (l2Normalize, cosineSimilarity)
  ├── DbRepository  (upsertMedia)
  ├── IndexPreferences  (isIndexPaused, saveLastIndexedTime)
  └── IndexControlReceiver  (pause/resume PendingIntents)
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
  └── buildMergedPhotoSearchResults()  → SearchResultManager → paginate
```

### Smart Album Pipeline
```
MainActivity.createSmartAlbum(name, prompt)
  ├── runSearchPipeline(query, mode, candidateItems)
  │     ├── GalleryRepository.search()  + MetadataSearch
  │     └── buildMergedPhotoSearchResults()
  └── SmartAlbumStore.upsert(SmartAlbum)
        └── SharedPreferences persistence (JSON via org.json)

MainActivity.refreshSmartAlbum(smart)
  └── runSearchPipeline()  → SmartAlbumStore.upsert()

MainActivity.renderAlbums()  [smart albums in PINNED section]
  ├── SmartAlbumStore.getAll()  → smartAlbums list
  ├── SmartAlbum.toAlbum()  → GalleryRepository.Album(isSmart=true)
  └── AlbumPinStore.cleanup(realAlbumIds + smartAlbumIds)

Album-detail (smart):
  albumDetailItems  → SmartAlbumStore.get(id)  → memberUris resolved from collectionItems
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
  │     │     └── scrubber: videoSeekBar + videoElapsed/videoTotal (onScrubbingChanged → activity auto-hide)
  │     ├── WallpaperManager  (set-as-wallpaper, images only)
  │     ├── FavoritesStore → DbRepository
  │     └── ExifExtractor → ExifData
  ├── TagPickerDialog → DbRepository (TagDao)
  └── FolderNode (folder tree construction)
```

### Persistence Layer
```
DbRepository
  └── GalleryDatabase (Room singleton)
        ├── MediaMetadataDao → MediaMetadataEntity
        ├── ExifMetadataDao  → ExifMetadataEntity
        ├── FavoriteDao      → FavoriteEntity
        └── TagDao           → TagEntity + MediaTagCrossRef

AlbumPinStore  (SharedPreferences / JSONArray)
SmartAlbumStore  (SharedPreferences / JSONArray)
IndexPreferences  (SharedPreferences)
```

---

## Change Impact Matrix

| If you change... | Also check... |
|---|---|
| `SearchTuning.ScoreThreshold` | `GalleryRepository.search()`, `buildMergedPhotoSearchResults()` in MainActivity |
| `ImageEncoder.ImageSize` (256) | `preprocessor_config.json`, `ImageEncoder.preprocess()`, `GalleryRepository.buildIndex()` |
| `ClipTokenizer.ContextLength` (77) | `TextEncoder.encode()` shape, `QueryExpander.getEmbedding()` |
| `GalleryDatabase` version | Add migration or `fallbackToDestructiveMigration()` is already set |
| `IndexWorker.BatchSize` (4) | Memory pressure on low-RAM devices. `GalleryRepository.buildIndex()` chunking |
| `DesignTokens.DISPLAY_CAP` (800) | Browse mode item cap in `MainActivity` |
| `DesignTokens.SEARCH_METADATA_HARD_CAP` (80) | Search pagination cap in `MainActivity` |
| `SmartAlbumStore.MAX_SMART_MEMBERS` (800) | Stored URI count per smart album in `createSmartAlbum()` |
| `SmartAlbumStore.SMART_PREFIX` ("smart:") | ID parsing in `isSmartId()`, `albumDetailItems`, `renderAlbums()` |
| `SmartAlbum` data class fields | JSON serialization in `albumToJson()`/`parseSmartAlbum()` |
| `IndexPreferences` SharedPrefs keys | Cannot rename without migration — stored on device |
| `AlbumPinStore` JSON format | Stored in SharedPrefs — changing breaks existing pins |
| `SmartAlbumStore` JSON format | Stored in SharedPrefs — changing breaks existing smart albums |
| `IndexMagic` / `IndexVersion` in GalleryRepository | Will invalidate all existing embedding indexes on user devices |
| `MetadataIndexMagic` / `MetadataIndexVersion` | Will invalidate metadata indexes |
| `OnnxSessionOptions.DefaultThreadCount` (4) | Benchmark in `ThreadBenchmark.kt` determines optimal count |

---

## Assets Map
```
app/src/main/assets/
├── vision_model_fp16.onnx    ← ImageEncoder (primary, Git LFS)
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
info_drag_handle.xml    → activity_viewer.xml (info panel handle)
scrubber_thumb.xml / scrubber_progress.xml → item_viewer_page.xml (video SeekBar)
dialog_tag_picker.xml   → TagPickerDialog
dialog_smart_album.xml  → MainActivity (smart album create dialog)
item_empty.xml          → ImageAdapter (empty state)
```
