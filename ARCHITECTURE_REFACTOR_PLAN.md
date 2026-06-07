# Deepix Architecture Refactor Plan

## Goal

Transform the current codebase from a large Activity-driven architecture into a maintainable, scalable, production-ready Android application while preserving current functionality.

---

# Priority 1: Split MainActivity

## Current Problem

MainActivity exceeds 1500 lines and currently owns:

- Permission handling
- Search UI
- Search execution
- Gallery navigation
- Album navigation
- Selection state
- Delete operations
- Share operations
- WorkManager observation
- Index progress UI
- Repository lifecycle
- Encoder lifecycle

## Target Structure

```
MainActivity
├── GalleryViewModel
├── SearchController
├── NavigationController
├── SelectionController
├── PermissionManager
├── IndexingManager
└── GalleryRenderer
```

---

# GalleryUiState

Create:

```kotlin
package com.devomind.gallerysearch.ui

data class GalleryUiState(
    val albums: List<GalleryRepository.Album> = emptyList(),
    val mediaItems: List<GalleryRepository.MediaItem> = emptyList(),
    val currentAlbum: GalleryRepository.Album? = null,
    val selectedAlbumIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val activeSection: Section = Section.Collection,
    val searchMode: SearchMode = SearchMode.Hybrid,
    val progress: Int = 0,
    val indexedCount: Int = 0,
    val error: String? = null
)
```

---

# GalleryViewModel

Create:

```kotlin
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val repository: GalleryRepository,
    private val searchImagesUseCase: SearchImagesUseCase,
    private val loadAlbumsUseCase: LoadAlbumsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState = _uiState.asStateFlow()
}
```

Move all:

- searchJob
- searchDebounceJob
- renderJob
- repository access

from Activity into ViewModel.

---

# Use Cases Layer

Create package:

```
core/usecase/
```

Files:

```
SearchImagesUseCase.kt
IndexGalleryUseCase.kt
DeleteMediaUseCase.kt
LoadAlbumsUseCase.kt
ShareMediaUseCase.kt
```

Example:

```kotlin
class SearchImagesUseCase(
    private val repository: GalleryRepository,
    private val textEncoder: TextEncoder
) {
    suspend operator fun invoke(query: String): List<SearchResult> {
        val embedding = textEncoder.encode(query)
        return repository.search(embedding)
    }
}
```

---

# Dependency Injection

Add Hilt.

Gradle:

```gradle
implementation "com.google.dagger:hilt-android:2.57"
kapt "com.google.dagger:hilt-compiler:2.57"
```

Application:

```kotlin
@HiltAndroidApp
class GallerySearchApp : Application()
```

Module:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideGalleryRepository(
        @ApplicationContext context: Context
    ): GalleryRepository {
        return GalleryRepository(context)
    }
}
```

---

# Search Engine Improvements

## Embedding Metadata Versioning

Store:

```kotlin
data class IndexMetadata(
    val modelVersion: String,
    val embeddingDimension: Int,
    val createdAt: Long
)
```

Reject incompatible caches.

---

# Query Expansion

Create:

```kotlin
class QueryExpansionService {

    fun expand(query: String): List<String> {
        return when(query.lowercase()) {
            "dog" -> listOf(
                "dog",
                "dogs",
                "puppy",
                "canine",
                "pet"
            )
            else -> listOf(query)
        }
    }
}
```

Future:

- MiniLM
- BGE Small
- WordNet

---

# RecyclerView Performance

Replace adapter with:

```kotlin
class ImageAdapter : ListAdapter<MediaItem, ViewHolder>(DiffCallback())
```

DiffUtil:

```kotlin
object DiffCallback : DiffUtil.ItemCallback<MediaItem>() {

    override fun areItemsTheSame(
        oldItem: MediaItem,
        newItem: MediaItem
    ) = oldItem.uri == newItem.uri

    override fun areContentsTheSame(
        oldItem: MediaItem,
        newItem: MediaItem
    ) = oldItem == newItem
}
```

---

# Glide Optimization

```kotlin
Glide.with(imageView)
    .load(uri)
    .thumbnail(0.25f)
    .override(400)
    .centerCrop()
    .into(imageView)
```

---

# Remove largeHeap

Current:

```xml
android:largeHeap="true"
```

Goal:

Remove after memory profiling.

Use:

- LeakCanary
- Android Studio Profiler

---

# Crash Monitoring

Add:

- Firebase Crashlytics
- ANR monitoring

---

# Testing Strategy

Unit tests:

```
Tokenizer
TextEncoder
ImageEncoder
EmbeddingCache
GalleryRepository
SearchImagesUseCase
```

Target:

Minimum 70% coverage for AI pipeline.

---

# Phase Roadmap

## Phase 1

- Create GalleryViewModel
- Create UiState
- Split MainActivity
- Move search logic

## Phase 2

- Hilt
- Use Cases
- Repository cleanup
- Tests

## Phase 3

- Query Expansion
- ANN Search
- Benchmarking
- Crashlytics

## Phase 4

- Production optimization
- Memory tuning
- Startup optimization
- Play Store release readiness
