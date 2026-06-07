I’ll quickly verify the guide exists and has the expected structure.
Action: file_editor view /app/Deepix/search_refactor_plan.md
Observation: [Showing lines 1-120 of 838 total] /app/Deepix/search_refactor_plan.md:
1|# Deepix Search Refactor Plan: Unified CLIP + Metadata Photo Search
2|
3|This guide is written for a local coding LLM or developer to refactor Deepix search without changing the app’s overall Windows Phone / Metro-inspired gallery direction.
4|
5|## Goal
6|
7|Refactor the current search implementation so that:
8|
9|1. Search results show **photos only**.
10|2. Album/folder results are **not shown in search**.
11|3. CLIP semantic results and metadata text results work **in tandem**.
12|4. Each result clearly shows why it matched:
13|   - **AI** badge for MobileCLIP semantic matches.
14|   - **TXT** or **META** badge for metadata matches.
15|   - Both badges if the same photo matched both sources.
16|5. Search remains useful while first-time indexing is still running:
17|   - Metadata results should appear immediately.
18|   - CLIP results should appear progressively as the embedding index becomes available.
19|   - UI copy should explain when AI results are still warming up/indexing.
20|
21|> Recommended metadata badge text: use `TXT` if you want a compact Metro tile look; use `META` if you prefer clearer wording. This guide uses `TXT` in code snippets because the requested “apt badge” likely means a suitable/appropriate badge, and `TXT` is visually small enough for thumbnails.
22|
23|---
24|
25|## Current Search Problems Found in Code
26|
27|Relevant files:
28|
29|- `app/src/main/java/com/devomind/gallerysearch/MainActivity.kt`
30|- `app/src/main/java/com/devomind/gallerysearch/GalleryRepository.kt`
31|- `app/src/main/java/com/devomind/gallerysearch/ImageAdapter.kt`
32|- `app/src/main/res/layout/item_image.xml`
33|- `app/src/main/res/drawable/video_badge_bg.xml`
34|
35|Current behavior:
36|
37|- `MainActivity.submitSearch()` branches into album search when `activeSection == Section.Albums`.
38|- `buildAlbumSearchCells()` returns `GalleryCell.AlbumCell`, which is why folders/albums appear during search.
39|- `buildMediaSearchCells()` merges semantic URIs first, then metadata matches, but loses source information.
40|- `GalleryCell.Photo` has only `item` and `featured`; it cannot carry match-source badges.
41|- `ImageAdapter.PhotoViewHolder` only shows selection and video badges.
42|- Search blocks all results if `textEncoder == null`, even though metadata search does not need the model.
43|
44|---
45|
46|## Desired Architecture
47|
48|Introduce a small typed search layer instead of passing plain `Uri` lists around.
49|
50|### New Search Model
51|
52|Add source-aware result types, preferably in a new file:
53|
54|`app/src/main/java/com/devomind/gallerysearch/SearchModels.kt`
55|
56|```kotlin
57|package com.devomind.gallerysearch
58|
59|import android.net.Uri
60|
61|enum class SearchMatchSource {
62|    Ai,
63|    Metadata
64|}
65|
66|data class SearchMatch(
67|    val uri: Uri,
68|    val aiScore: Float? = null,
69|    val metadataScore: Int = 0,
70|    val sources: Set<SearchMatchSource>
71|) {
72|    val hasAi: Boolean get() = SearchMatchSource.Ai in sources
73|    val hasMetadata: Boolean get() = SearchMatchSource.Metadata in sources
74|}
75|```
76|
77|### Search Responsibilities
78|
79|Keep responsibilities split:
80|
81|- `GalleryRepository`
82|  - Query MediaStore.
83|  - Build/load CLIP index.
84|  - Return semantic search matches with scores.
85|- `MainActivity`
86|  - Decide the current searchable photo scope.
87|  - Run metadata matching.
88|  - Merge CLIP + metadata results.
89|  - Render `GalleryCell.Photo` with badges.
90|- `ImageAdapter`
91|  - Display badges only; avoid search logic here.
92|
93|---
94|
95|## Step 1: Make CLIP Search Return Scored Matches
96|
97|In `GalleryRepository.kt`, keep the existing `search(query: String): List<Uri>` only if other callers still need it, but add a new source-aware method.
98|
99|### Add Method
100|
101|Place near the existing `search()` function:
102|
103|```kotlin
104|fun searchSemantic(query: String): List<SearchMatch> {
105|    val textEncoder = textEncoder ?: return emptyList()
106|    var snapshot = snapshotIndex()
107|    if (snapshot.isEmpty()) {
108|        synchronized(indexLock) {
109|            if (embeddings.isEmpty()) embeddings = loadIndex()
110|            snapshot = LinkedHashMap(embeddings)
111|        }
112|    }
113|    if (snapshot.isEmpty()) return emptyList()
114|
115|    val variants = buildQueryVariants(query)
116|    val bestScores = HashMap<String, Float>(snapshot.size)
117|
118|    for (variant in variants) {
119|        val queryEmbedding = textEncoder.encode(variant)
120|        for ((uri, embedding) in snapshot) {
 [120 lines shown. Remaining: lines 121-838 (718 lines). Use view_range parameter to continue.]