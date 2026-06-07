# Deepix Engineering Audit (Phase 0)

> This document is a production-readiness blueprint for Deepix. It focuses on architecture, AI pipeline quality, maintainability, performance, testing, scalability, and Android best practices.

## Current Risk Assessment

| Area | Score | Risk |
|--------|--------|--------|
| Architecture | 6/10 | High |
| Maintainability | 5/10 | High |
| AI Pipeline | 8/10 | Medium |
| Android Performance | 7/10 | Medium |
| Testing | 2/10 | High |
| Scalability | 5/10 | High |
| Production Readiness | 6/10 | Medium |

---

# Refactor Targets

## MainActivity

Current state:

- Extremely large
- Owns business logic
- Owns search execution
- Owns navigation
- Owns permissions
- Owns indexing
- Owns rendering

Target size:

- 250–400 lines maximum

Move logic into:

```
ui/main/MainActivity
ui/main/GalleryViewModel
ui/search/SearchCoordinator
ui/navigation/NavigationCoordinator
ui/selection/SelectionManager
ui/render/GalleryRenderer
```

---

# Package Layout

Target package structure:

```
com.devomind.gallerysearch
│
├── ai
│   ├── encoder
│   ├── tokenizer
│   ├── search
│   └── indexing
│
├── data
│   ├── repository
│   ├── datasource
│   └── cache
│
├── domain
│   ├── model
│   └── usecase
│
├── ui
│   ├── main
│   ├── albums
│   ├── viewer
│   ├── search
│   └── settings
│
├── worker
├── util
└── di
```

---

# AI Pipeline Review

## Text Encoder

Required fixes:

- Singleton lifecycle
- Warmup support
- Background initialization
- Explicit shutdown
- Benchmark logging

Add:

```kotlin
interface TextEncoder {
    suspend fun encode(text: String): FloatArray
    suspend fun warmup()
    fun close()
}
```

---

## Image Encoder

Add:

- batching support
- cancellation support
- progress callback
- model version metadata

---

## Tokenizer

Required tests:

```
plural words
unicode
emoji
empty strings
very long text
special characters
```

---

# Search Pipeline

Current likely flow:

```
Query
→ TextEncoder
→ Similarity Search
→ Results
```

Target:

```
Query
→ Query Expansion
→ TextEncoder
→ ANN Search
→ Re-ranking
→ Results
```

---

# Embedding Index Format

Store:

```kotlin
IndexHeader(
 modelVersion,
 embeddingDimension,
 tokenizerVersion,
 createdAt,
 galleryVersion
)
```

Reject mismatched indices.

---

# Repository Layer

Repository should be only source of truth.

Avoid:

```kotlin
repository?.search()
repository?.loadAlbums()
repository?.delete()
```

inside Activity.

Move to UseCases.

---

# ViewModel State Management

Create:

```kotlin
data class GalleryUiState(...)
```

Use:

```kotlin
MutableStateFlow
StateFlow
```

Avoid mutable UI state inside Activity.

---

# RecyclerView Audit

Requirements:

- ListAdapter
- DiffUtil
- Stable IDs
- Payload updates

Avoid full refreshes.

---

# Memory Audit

Investigate:

- Bitmap allocations
- Glide cache size
- ONNX tensor allocations
- Large heap dependency

Goal:

Remove:

```xml
android:largeHeap="true"
```

---

# ONNX Runtime Audit

Ensure:

```kotlin
OrtSession.close()
OnnxTensor.close()
OrtEnvironment reuse
```

No leaked tensors.

---

# Coroutines Audit

Replace ad-hoc jobs with:

```kotlin
viewModelScope
supervisorScope
StateFlow
```

Avoid lifecycle leaks.

---

# WorkManager Audit

Requirements:

- unique work names
- exponential backoff
- progress reporting
- cancellation support

---

# Testing Roadmap

Unit Tests

```
TokenizerTest
TextEncoderTest
ImageEncoderTest
SearchUseCaseTest
GalleryRepositoryTest
```

Integration Tests

```
MediaStoreIntegrationTest
IndexPersistenceTest
SearchPipelineTest
```

Benchmark Tests

```
SearchLatencyBenchmark
IndexingBenchmark
StartupBenchmark
```

---

# Production Metrics

Track:

- startup time
- indexing time
- search latency
- memory usage
- crash rate

---

# Release Gate

Do not release until:

- MainActivity split complete
- Hilt integrated
- ViewModel architecture complete
- Unit tests >70% on AI pipeline
- Memory profile completed
- ONNX resources verified leak-free
- Crash reporting integrated

---

# Expected Outcome

After full implementation:

- Cleaner architecture
- Faster future development
- Lower bug rate
- Better AI search quality
- Easier model upgrades
- Production-ready Play Store foundation
