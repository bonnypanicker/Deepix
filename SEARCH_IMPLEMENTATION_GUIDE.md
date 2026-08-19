# Gallery Search Implementation Guide

## Quick Reference

### Search Flow Overview

```
User enters query
    ↓
submitSearch() [Main thread, lifecycleScope]
    ↓
Parse query (StructuredSearch.parse)
    ↓
Build filter lookup [Dispatchers.IO]
    ↓
Filter items [Dispatchers.Default]
    ↓
Search metadata [Dispatchers.Default]
    ↓
Render intermediate results [Main thread]
    ↓
Build search sections [Background threads]
    ↓  
Search CLIP embeddings [Dispatchers.Default]
    ↓
Merge results [Dispatchers.Default]
    ↓
Render final results [Main thread]
    ↓
Update section tabs [Main thread]
```

---

## Search Section Tabs

### What Sections Are Available?

1. **Smart** - AI-powered semantic search using CLIP embeddings
   - Shows when: `semanticHitCount > 0`
   - Searches: Image embeddings vs text query embedding
   
2. **Metadata** - Traditional text-based search
   - Shows when: `metadataHitCount > 0`
   - Searches: Filenames, album names, EXIF data, dates
   
3. **Albums** - Albums matching the query text
   - Shows when: Query is not blank AND albums match
   - Searches: Album names containing query text (case-insensitive)
   
4. **Tags** - User-created tags matching the query
   - Shows when: Query is not blank AND tags match
   - Searches: Tag names containing query text (case-insensitive)
   
5. **People** - People detected in the results
   - Shows when: Results have photos with recognized faces
   - Counts: Distinct person IDs in result set
   
6. **Locations** - Photos with GPS location data
   - Shows when: Results have photos with EXIF GPS data
   - Counts: Photos with `hasGps = true` in result set

---

## Loading States

### Initial Search State
```kotlin
searchSectionLoading = true
currentSearchSections = emptyList()
renderSearchSectionTabs() // Shows "Searching…"
```

### After Metadata Search (Partial Results)
```kotlin
finishSearchSectionBuild(metadataResults, 0, metadataHits.size)
// Shows Metadata tab only, Smart tab will appear after CLIP search completes
```

### After Full Search (Final Results)
```kotlin
finishSearchSectionBuild(finalResults, semanticResults.size, metadataHits.size)
// Shows all applicable tabs based on results
```

---

## Thread Safety

### Background Operations (Dispatchers.IO)
- Database queries
- File I/O
- EXIF data access
- Face recognition queries
- Location data queries

### Background Operations (Dispatchers.Default)
- CLIP embedding calculations
- Text tokenization
- Similarity scoring
- Result merging and sorting
- Metadata indexing
- Album/tag filtering

### Main Thread Operations (Dispatchers.Main)
- UI updates (binding.*)
- View manipulation (addView, removeAllViews)
- Adapter operations (replaceCells)
- Toast/banner messages
- Activity lifecycle methods

---

## Error Handling Strategy

### Search Errors
```kotlin
try {
    // Search operations
} catch (cancelled: CancellationException) {
    Log.d(TAG, "Search job cancelled.")
    // Don't show error, user likely typed new query
} catch (error: Throwable) {
    Log.e(TAG, "Search error", error)
    withContext(Dispatchers.Main) {
        showFatalError(error)
    }
} finally {
    withContext(Dispatchers.Main) {
        binding.progressBar.visibility = View.GONE
        searchSectionLoading = false
        if (!isFinishing) renderSearchSectionTabs()
    }
}
```

### Database Errors
```kotlin
return withContext(Dispatchers.IO) {
    runCatching {
        // Database operation
    }.getOrDefault(0) // Safe fallback
}
```

---

## Performance Considerations

### Debouncing
- Search input debounced at 180ms (`SEARCH_INPUT_DEBOUNCE_MS`)
- Previous search jobs are cancelled before starting new search
- Prevents excessive search operations during rapid typing

### Pagination
- Results displayed in pages of 30 (`SearchTuning.PageSize`)
- Maximum 800 results displayed (`DesignTokens.DISPLAY_CAP`)
- Infinite scroll for loading more results

### Caching
- CLIP embeddings cached in memory (`GalleryRepository.embeddings`)
- Metadata index cached (`GalleryRepository.metadataSearchIndex`)
- Face data persisted to database
- EXIF data cached in `DbRepository`

### Lazy Loading
- CLIP encoders loaded on-demand for first AI search
- Can be pre-warmed via `ensureEncodersLoaded()`
- Models stay loaded for session lifetime

---

## Common Pitfalls to Avoid

### ❌ DON'T
```kotlin
// NEVER use runBlocking inside a coroutine
lifecycleScope.launch {
    val result = runBlocking { database.query() } // CRASH!
}

// NEVER update UI from background thread
withContext(Dispatchers.IO) {
    binding.progressBar.visibility = View.VISIBLE // CRASH!
}

// NEVER assume thread context
fun someFunction() {
    binding.textView.text = "..." // Might crash if not on Main
}
```

### ✅ DO
```kotlin
// Use withContext for thread switching
lifecycleScope.launch {
    val result = withContext(Dispatchers.IO) {
        database.query()
    }
    // Back on Main thread automatically
    binding.textView.text = result
}

// Explicitly switch to Main for UI updates
withContext(Dispatchers.Main) {
    binding.progressBar.visibility = View.VISIBLE
}

// Make functions suspend and let caller handle threading
suspend fun someFunction() {
    withContext(Dispatchers.Main) {
        binding.textView.text = "..."
    }
}
```

---

## Debugging Tips

### Enable Search Logging
Look for these log tags:
- `MainActivity` - General search flow
- `GalleryRepository` - CLIP search operations
- `MetadataSearch` - Text-based search
- `ImageEncoder` - Vision model operations
- `TextEncoder` - Text model operations

### Check Search State
```kotlin
Log.d(TAG, "Search sections: ${currentSearchSections.joinToString { "${it.section.name}(${it.count})" }}")
Log.d(TAG, "Search loading: $searchSectionLoading")
Log.d(TAG, "Image search active: $imageSearchActive")
```

### Monitor Performance
```kotlin
val startTime = System.currentTimeMillis()
// ... search operation ...
Log.d(TAG, "Search took ${System.currentTimeMillis() - startTime}ms")
```

---

## UI Customization

### Change Section Order
Edit `SearchSection` enum in MainActivity.kt:
```kotlin
private enum class SearchSection {
    Smart,      // Order matters!
    Metadata,   // Appears in this order
    Albums,     // in the horizontal scroll
    Tags,
    People,
    Locations
}
```

### Customize Section Labels
Edit `app/src/main/res/values/strings.xml`:
```xml
<string name="search_section_smart">Smart</string>
<string name="search_section_metadata">Metadata</string>
<!-- etc -->
```

### Change Accent Color
Done automatically via `AccentPalette` - user selectable in Settings

### Adjust Tab Styling
Edit `app/src/main/res/drawable/search_filter_chip_bg.xml` and `search_filter_chip_active_bg.xml`

---

## Testing Checklist

- [ ] Search with no query returns to gallery view
- [ ] Search with text-only query shows Metadata tab
- [ ] Search with AI-indexed photos shows Smart tab
- [ ] Albums tab appears when album names match
- [ ] Tags tab appears when tag names match
- [ ] People tab appears when faces are detected
- [ ] Locations tab appears when GPS data exists
- [ ] Loading indicator shows while searching
- [ ] Tabs update when AI search completes
- [ ] No crashes when rapidly typing/deleting
- [ ] No crashes when switching between sections
- [ ] No crashes during indexing
- [ ] No crashes when backgrounding app during search
- [ ] Empty state shows appropriate messages
- [ ] Theme colors applied correctly to tabs
- [ ] Selected tab highlighted properly
- [ ] Tabs scroll horizontally when many sections

---

## Future Enhancements

### Possible Improvements
1. **Search history** - Save recent queries
2. **Search suggestions** - Auto-complete based on metadata
3. **Filter chips** - Visual representation of active filters
4. **Sort options** - Sort by date, relevance, size, etc.
5. **Advanced filters** - Date range picker, location picker
6. **Export results** - Share/export search result list
7. **Saved searches** - Bookmark complex queries
8. **Search analytics** - Track popular queries

### Architecture Improvements
1. **ViewModel** - Move search logic to dedicated ViewModel
2. **Repository pattern** - Better separation of concerns
3. **Flow/StateFlow** - Reactive search state updates
4. **Paging 3** - Better pagination handling
5. **WorkManager** - Background search pre-caching
6. **Room indexes** - Optimize database queries

---

## Support

For questions or issues:
1. Check logcat for error messages
2. Review SEARCH_REFACTORING_FIXES.md for recent changes
3. Check IndexPreferences for indexing status
4. Verify encoder models are loaded (`textEncoder != null`)
5. Confirm database migrations completed successfully

Last Updated: Current refactoring (2024)
