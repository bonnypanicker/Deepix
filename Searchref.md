# Deepix Search Refactor Guide — Part 1 of 3

01. Purpose: refactor Deepix search so AI CLIP and metadata results work together cleanly.
02. Scope: this guide is for a local LLM/code editor to apply changes inside the cloned Android repo.
03. No source code was edited while creating this guide.
04. Target repo inspected: `https://github.com/bonnypanicker/Deepix.git` cloned at `/app/Deepix`.
05. Output location requested by user: `/app/memory/` only.
06. App type: Kotlin Android XML Views gallery app inspired by Windows Phone / Windows Mobile 10 gallery.
07. Current package: `com.devomind.gallerysearch`.
08. Main search files inspected: `MainActivity.kt`, `GalleryRepository.kt`, `ImageAdapter.kt`, `SearchModels.kt`.
09. Current search problem: search mixes concerns inside `MainActivity`, with inconsistent source handling.
10. Current UI problem: search can show album/folder results when user wants only photos in search results.
11. Required result behavior: search results should contain only photos, not folders/albums.
12. Required source behavior: AI/CLIP results and metadata results should both contribute.
13. Required badge behavior: AI matches show a small AI badge.
14. Required badge behavior: metadata matches show a small metadata badge.
15. Required badge behavior: photos found by both sources show both badges.
16. Required first-index behavior: if indexing just started, metadata search must still work.
17. Required first-index behavior: if AI index is partial, AI results should use currently indexed photos only.
18. Required first-index behavior: UI should explain that AI results improve as indexing continues.
19. Current `SearchModels.kt` already has `SearchMatchSource.Ai`, `SearchMatchSource.Metadata`, and `SearchMatch`.
20. Reuse that intent, but make the model carry enough data for ranking and badges.
21. Avoid broad rewrites of gallery, viewer, selection, delete, favorites, and indexing flows.
22. Preferred approach: isolate search composition into a small service/helper class.
23. Suggested new file: `SearchCoordinator.kt`.
24. Suggested changed files: `SearchModels.kt`, `GalleryRepository.kt`, `MainActivity.kt`, `ImageAdapter.kt`, `item_image.xml`.
25. Optional changed files: `colors.xml`, new drawable files for badges, `strings.xml` if localizing badge labels.
26. Do not replace MobileCLIP inference code unless search score API needs a small adaptation.
27. Keep WorkManager indexing architecture intact.
28. Keep current UI visual language: black Metro-style surfaces, light typography, simple rectangular photo tiles.
29. Keep video search metadata-only unless explicitly adding semantic video indexing later.
30. For this task, photo search means `GalleryRepository.MediaType.Image` only in results.
31. If active section is Videos, search should either show metadata-only videos or ideally say photo search is not available there.
32. User explicitly said only photos, so the safest behavior is: global search returns photos only.
33. Search should no longer branch into `buildAlbumSearchCells` from the Albums section.
34. In Albums section, tapping search should search photos, not album folders.
35. If current album is open, search may be scoped to that album but still return photo tiles only.
36. If favorites section is active, search may be scoped to favorite photos only.
37. If collection section is active, search should search photo items in current scope.
38. Do not include `GalleryCell.AlbumCell` from search output.
39. Do not include `GalleryCell.Collage` from search output.
40. Search output should be simple `GalleryCell.Photo` tiles with badge source flags.
41. Existing `GalleryCell.Photo` currently has `item` and `featured` only.
42. Extend it with `searchSources: Set<SearchMatchSource> = emptySet()`.
43. Alternatively add a nullable `searchMatch: SearchMatch? = null`.
44. Recommended: add `searchSources` for adapter simplicity and keep ranking data outside UI.
45. Existing `ImageAdapter.PhotoViewHolder.bindSelection` controls selection badge and video badge.
46. Add source badge binding in `PhotoViewHolder` after video badge visibility.
47. Since search results are photos only, video badge should normally be hidden during search.
48. Badge layout should be added to `item_image.xml` only for regular photo tiles.
49. Collage badge support is not needed because search should not render collages.
50. Existing metadata matching checks display name, bucket name, mime type, date text, day text, and type text.
51. Bucket name currently means folder/album name, but matching by bucket name can still return photos.
52. User said folders shown on search are not needed; matching bucket name is acceptable only if output remains photo tiles.
53. If metadata search feels too folder-like, reduce bucket name score below filename/date matches.
54. Current `GalleryRepository.search(query): List<Uri>` loses AI scores and source metadata.
55. Add a score-returning AI API instead of only URI list.
56. Recommended function name: `searchAiMatches(query: String): List<SearchMatch>`.
57. Keep old `search(query): List<Uri>` temporarily if other code depends on it.
58. New AI search should return matches with `uri`, `aiScore`, `metadataScore = 0`, `sources = setOf(Ai)`.
59. New metadata search should return matches with `uri`, `metadataScore`, `aiScore = null`, `sources = setOf(Metadata)`.
60. Search coordinator merges these by URI.
61. Merge rule: if same URI appears in both, combine into one `SearchMatch` with both sources.
62. Ranking should be deterministic and stable enough to avoid flicker while indexing.
63. AI score is float cosine similarity around threshold 0.19.
64. Metadata score is integer relevance score.
65. Normalize combined ranking carefully; do not compare raw metadata score and AI score directly.
66. Recommended combined ranking tuple: both-source boost first, then AI score, then metadata score, then recency.
67. Example order key: `sourceRank`, `normalizedAi`, `normalizedMetadata`, `dateMillis`.
68. `sourceRank`: both = 3, ai only = 2, metadata only = 1.
69. This prioritizes results confirmed by both AI and metadata.
70. If user expects exact filename/date matches first, use metadata exact-match boost before sourceRank.
71. Better ranking formula: `combined = aiComponent + metadataComponent + bothBonus + recencyTinyBonus`.
72. `aiComponent = ((aiScore - threshold) / (bestAi - threshold)).coerceIn(0,1) * 0.65`.
73. `metadataComponent = (metadataScore / bestMetadata).coerceIn(0,1) * 0.35`.
74. `bothBonus = 0.20`.
75. `recencyTinyBonus = 0.02` max, only as tie-breaker.
76. For first indexing, `bestAi` may be null because no AI results exist.
77. In that case, rank metadata results normally and show an indexing status message.
78. Existing `maybeRefreshLiveIndex` reloads cached index periodically and re-submits search.
79. Keep that behavior, but ensure it merges AI results into existing metadata result list.
80. Current `repo.search(query)` returns empty if `textEncoder == null` or index empty.
81. Current `submitSearch()` blocks search with “Models still warming up — try again in a moment.”
82. Change that: metadata search should run even when `textEncoder == null`.
83. Only AI part should be skipped when text encoder is unavailable.
84. This is critical for first launch and first indexing conditions.
85. Current `submitSearch()` returns early at lines around 622-627 when `textEncoder == null`.
86. Remove that early return and let search coordinator know AI is unavailable.
87. Keep a status message like: `Metadata results shown · AI search warming up`.
88. Current `buildMediaSearchCells(query, baseItems, semanticResults)` receives only semantic URIs.
89. Replace with `buildMediaSearchCells(matches, byUri)` or move cell building into coordinator output.
90. Recommended separation: coordinator returns `List<SearchMatch>`, MainActivity maps to `GalleryCell.Photo`.
91. `SearchMatch` should not store `MediaItem`; keep model small and repository-independent.
92. But `SearchCoordinator` can accept `baseItems: List<MediaItem>` to score metadata and scope results.
93. Current `currentSearchItems()` may return videos or albums depending section.
94. Replace it for search with `currentSearchPhotoItems()`.
95. `currentSearchPhotoItems()` must always filter `mediaType == Image`.
96. For current album: `albumDetailItems.filter { Image }`.
97. For favorites: `favoriteItems.filter { Image }`.
98. For videos: return empty list or collection images depending intended UX.
99. For albums section: return `imageItems`, not `albums`.
100. For collection: return `imageItems`, not `collectionItems`, because collection includes videos.
101. This single function prevents folder/video search leakage.
102. Existing `activeSection == Section.Albums` branch in `submitSearch()` builds album cells.
103. Delete that branch or guard it off.
104. Replace with photo search across `imageItems` or selected album scope.
105. Existing `searchPlaceholderText()` says “Search albums” when active section is albums.
106. Change it to “Search photos” or “Search photos in your gallery”.
107. Existing `updateSearchMetaText()` says “Live album search” for Albums.
108. Change it to “Photo search · AI badges appear as indexing completes”.
109. For Videos section, change to “Photo search is available from Collection, Albums, or Favorites” if returning empty.
110. Or auto-switch search base to all photos; choose one consistent behavior.
111. Recommended: search always searches photos in current gallery scope, independent of bottom tab.
112. That means Albums tab search still searches photos, not album cells.
113. Videos tab search should probably search videos only if user is in Videos, but user asked only photos.
114. Therefore, use photo scope and display “Searching photos only”.
115. Search status should include indexed count when AI index is not complete.
116. Example: `24 metadata matches · 7 AI matches · indexing 138 / 900`.
117. Existing `selectionSummaryText()` only shows indexed count and album scope.
118. Add a search-specific status builder; do not overload general status too much.
119. Recommended data class: `SearchUiSummary` with counts for total, ai, metadata, both, aiAvailable, indexedCount, photoCount.
120. But to keep edits small, compute counts directly from matches inside `submitSearch()`.
121. Current result count uses `cells.size`, but if empty cell inserted then count could be wrong if not careful.
122. Use `matches.size`, not `cells.size`, for resultCount.
123. If no matches: `No photo results`.
124. If metadata-only because AI unavailable: `X photo results · AI warming up`.
125. If indexing partial: `X photo results · AI indexing in progress`.
126. If complete: `X photo results · AI + metadata`.
127. Badge text should be short: `AI` and `META`.
128. User requested “apt badge for metadata results”; likely means an appropriate badge.
129. Use `META` unless user later asks for “APT” literal.
130. If interpreting “apt badge” literally as apt/appropriate, `META` is clearer.
131. If wanting exact user wording, badge could be `TXT` or `INFO`; avoid ambiguity by documenting choice.
132. Badge UI should be visually small and not cover photo content too much.
133. Suggested position: bottom-start inside photo tile.
134. If both badges, show horizontal row: `[AI] [META]`.
135. AI badge: accent blue/purple from existing `metroAccentLight` or a new cyan.
136. Metadata badge: neutral dark translucent with white text or amber accent.
137. Use existing `video_badge_bg` as model for shape.
138. Add new drawables: `search_badge_ai_bg.xml`, `search_badge_metadata_bg.xml`.
139. Add new colors if needed: `metroAiBadge`, `metroMetadataBadge`.
140. Do not use emoji for badge icons.
141. User asked “ai symbol small badge”; text `AI` is the simplest symbol.
142. If using an icon, add vector drawable, but text badge is less invasive.
143. Ensure badges are hidden outside search mode.
144. Achieve this by leaving `searchSources` empty for normal browse cells.
145. `PhotoViewHolder.bindSelection` can set AI/META badge visibility based on `cell.searchSources`.
146. Existing stable ID for photo is just URI.
147. If search sources change for same URI, DiffUtil `areContentsTheSame` sees data class changed if Photo includes sources.
148. Stable ID can remain URI.
149. This lets badges update without recreating identity.
150. Existing `selected` logic extracts URI from Photo and Collage.
151. Adding sources to Photo does not affect selection.
152. Existing viewer item collection in search extracts Photo item.
153. Adding sources does not affect viewer.
154. Existing `ImageAdapter` uses `GalleryCell.Photo(item, featured=false)` in many places.
155. Because `searchSources` has default emptySet, old calls keep compiling.
156. Change only search result mapping to pass actual sources.
157. Search coordinator should be pure Kotlin and easy to unit test.
158. Avoid Android UI imports in SearchCoordinator except `android.net.Uri` if needed.
159. Metadata scoring likely needs `SimpleDateFormat` if moved out of MainActivity.
160. To avoid date formatter duplication, keep metadata scoring functions in MainActivity initially.
161. But current messiness comes from MainActivity doing too much.
162. Better: create `MetadataSearch.kt` helper with date formatting inside.
163. Suggested minimal new helper: `SearchCoordinator.kt` containing metadata scoring and merge logic.
164. It can accept lambdas for formatted date strings to reduce Android formatter concerns.
165. Simpler: use existing `MediaItem` fields only: displayName, bucketName, mimeType, mediaType, dateMillis.
166. Use Java date formatting in helper if needed.
167. Do not create a database for metadata search; use in-memory `MediaItem` list.
168. Metadata search should be fast for typical gallery sizes.
169. For very large galleries, simple sequence filtering is okay for first refactor.
170. Existing semantic search loops all embeddings and query variants.
171. Keep its performance characteristics unchanged.
172. To get AI scores, modify `GalleryRepository.search` internals to return score-bearing objects.
173. Option A: add `fun searchMatches(query: String): List<SearchMatch>`.
174. Option B: add `fun searchScores(query: String): Map<Uri, Float>`.
175. Recommended Option B for clean merging: `searchAiScores(query): Map<Uri, Float>`.
176. Then coordinator builds SearchMatch.
177. But existing `SearchMatch` already has aiScore, so Option A is also fine.
178. Recommended final API: `fun searchAiMatches(query: String): List<SearchMatch>`.
179. Keep `fun search(query: String): List<Uri> = searchAiMatches(query).map { it.uri }` for compatibility.
180. Use `SearchTuning.DefaultTopK` if you add limit support.
181. Current `SearchTuning.DefaultTopK = Int.MAX_VALUE`; no cap.
182. For UX, cap final displayed matches to `DesignTokens.DISPLAY_CAP` or 200.
183. Existing metadata `.take(80)` cap may hide valid results.
184. Replace hard-coded `take(80)` with `SearchTuning.MetadataMaxResults` if adding constant.
185. Suggested constants in `SearchTuning.kt`: `MetadataMaxResults = 200`, `BothSourceBonus = 0.20f`.
186. Do not over-tune scores in first refactor; correctness and badges matter more.
187. Search should be debounced because `doAfterTextChanged` submits every keystroke.
188. Existing `submitSearch()` cancels previous job, which is acceptable.
189. Optional improvement: delay 120 ms before executing for smoother typing.
190. Do not add this unless local LLM has time; it is not required.
191. Existing `SearchResultManager` is unused in current flow.
192. Decide whether to delete it later; not needed for this refactor.
193. Do not delete unused files in first pass unless build tooling flags them.
194. Existing `SearchModels.kt` is underused; make it central.
195. Ensure import conflicts are resolved after changing models.
196. Kotlin enum names currently `Ai` and `Metadata`.
197. Keep them to minimize changes.
198. Badge display can map `Ai -> "AI"`, `Metadata -> "META"`.
199. Test plan must include search before indexing completes.
200. Test plan must include search after index completes.
201. Test plan must include query matching filename only.
202. Test plan must include query matching image content only.
203. Test plan must include query matching both filename/folder/date and content.
204. Test plan must verify no album cells in search results.
205. Test plan must verify no video cells in search results.
206. Test plan must verify badges disappear in normal browse mode.
207. Test plan must verify both badges appear together for shared-source result.
208. Build command likely requires Android Studio/local Gradle environment.
209. Repository README notes shell may not have Android SDK or Gradle available.
210. Local LLM should still run Android Studio Gradle sync and build after edits.
211. Detekt plugin is present; keep code reasonably clean.
212. ONNX asset names in README mention fp16 vision and int8 text; code uses current assets.
213. This search refactor should not modify model assets.
214. Do not change package name or Gradle config.
215. Do not modify permissions for this task.
216. Do not modify `IndexWorker` except if progress status needs minor wording.
217. Part 2 contains concrete file-by-file implementation prompts.
216. Do not modify `IndexWorker` except if progress status needs minor wording.
217. Part 2 contains concrete file-by-file implementation prompts.
218. Part 3 contains testing checklist, edge cases, and local LLM execution prompts.

# Deepix Search Refactor Guide — Part 2 of 3

01. This part gives concrete code-edit instructions for the local LLM/model.
02. Apply changes incrementally and build after the core model/adapter/activity edits.
03. First edit: `app/src/main/java/com/devomind/gallerysearch/SearchModels.kt`.
04. Keep package line unchanged: `package com.devomind.gallerysearch`.
05. Keep `SearchMatchSource` enum with values `Ai` and `Metadata`.
06. Update `SearchMatch` so `sources` has a default value derived from scores if useful, or keep explicit.
07. Recommended final model:
08. `data class SearchMatch(val uri: Uri, val aiScore: Float? = null, val metadataScore: Int = 0, val sources: Set<SearchMatchSource>, val combinedScore: Float = 0f)`.
09. Keep `hasAi` and `hasMetadata` computed properties.
10. Add helper `fun withMerged(other: SearchMatch): SearchMatch` if desired.
11. Merge helper should require same URI or handle same URI only.
12. Merge helper should keep max `aiScore` and max `metadataScore`.
13. Merge helper should union sources.
14. Combined score may be recomputed after merging.
15. If keeping model simple, do not add helper; coordinator can merge.
16. Second edit: create `app/src/main/java/com/devomind/gallerysearch/SearchCoordinator.kt`.
17. Purpose: build metadata matches, merge AI matches, rank final photo results.
18. Suggested constructor: no constructor; use `object SearchCoordinator`.
19. Main function signature:
20. `fun mergeSearchResults(query: String, baseItems: List<GalleryRepository.MediaItem>, aiMatches: List<SearchMatch>): List<SearchMatch>`.
21. The function must only return matches for `MediaType.Image` items.
22. Build `byUri = baseItems.filter { it.mediaType == Image }.associateBy { it.uri }`.
23. Drop any AI match whose URI is not in `byUri`.
24. This enforces current section/scope and prevents stale index results.
25. Build metadata matches from the same filtered photo list.
26. Metadata scoring function signature:
27. `private fun metadataScore(item: MediaItem, normalizedQuery: String, locale: Locale): Int`.
28. If query blank, score 0.
29. For displayName exact contains: add 60.
30. For displayName token startsWith: add 20.
31. For bucketName contains: add 25.
32. For mimeType contains: add 10.
33. For media type terms `photo`, `image`, `picture`: add 12 if query includes them.
34. For month/year/day matching: add 20.
35. For dimensions matching, optional: if query contains width/height number add 5.
36. Metadata match qualifies if score > 0.
37. Do not return album cells; bucketName only boosts photos.
38. Date matching can use `SimpleDateFormat("MMMM yyyy", locale)` and `SimpleDateFormat("EEE, d", locale)`.
39. Also consider year-only: `Calendar.getInstance().apply { timeInMillis = item.dateMillis }.get(Calendar.YEAR).toString()`.
40. Normalize query with `.trim().lowercase(locale).replace(Regex("\\s+"), " ")`.
41. Split query into tokens with `.split(" ").filter { it.length >= 2 }`.
42. Token matching helps filenames like `IMG_20250104_beach.jpg`.
43. AI matches should be converted to map keyed by Uri.
44. Metadata matches should be converted to map keyed by Uri.
45. Merge into `LinkedHashMap<Uri, MutableSearchAggregate>` or direct `SearchMatch` map.
46. Add all AI matches first, then metadata matches, or vice versa; final ranking will decide.
47. For duplicate URI, union sources and keep scores.
48. Compute `bestAi = max aiScore` from merged matches.
49. Compute `bestMetadata = max metadataScore` from merged matches.
50. For each match compute combined score.
51. Suggested score formula:
52. `val aiPart = if (aiScore != null && bestAi > 0f) (aiScore / bestAi).coerceIn(0f, 1f) * 0.62f else 0f`.
53. `val metaPart = if (bestMetadata > 0) (metadataScore.toFloat() / bestMetadata).coerceIn(0f, 1f) * 0.38f else 0f`.
54. `val bothBonus = if (hasAi && hasMetadata) 0.18f else 0f`.
55. `val exactBonus = if metadataScore >= 60 then 0.08f else 0f`.
56. `combinedScore = aiPart + metaPart + bothBonus + exactBonus`.
57. Sort descending by `combinedScore`.
58. Tie-break by `hasAi && hasMetadata` first.
59. Tie-break by `aiScore ?: 0f`.
60. Tie-break by `metadataScore`.
61. Tie-break by `item.dateMillis` descending.
62. Return all sorted matches, optionally capped by `SearchTuning.DefaultTopK` if it is sane.
63. Because `DefaultTopK` is `Int.MAX_VALUE`, no cap is effectively applied.
64. Third edit: `GalleryRepository.kt`.
65. Keep current `search(query: String): List<Uri>` for compatibility if desired.
66. Add new public function near current `search`:
67. `fun searchAiMatches(query: String): List<SearchMatch>`.
68. Copy current semantic search logic from `search(query)`.
69. Preserve `textEncoder ?: return emptyList()`.
70. Preserve lazy index load if snapshot is empty.
71. Preserve query variants.
72. Preserve best score per URI.
73. Preserve relative cutoff and `SearchTuning.ScoreThreshold` filters.
74. Instead of `.map { Uri.parse(it.first) }`, map to `SearchMatch`.
75. Each AI match: `SearchMatch(uri = Uri.parse(uriString), aiScore = score, metadataScore = 0, sources = setOf(SearchMatchSource.Ai))`.
76. Ensure import not needed because same package.
77. Change old `search(query)` to:
78. `fun search(query: String): List<Uri> = searchAiMatches(query).map { it.uri }`.
79. This keeps any existing callers safe.
80. Do not throw when textEncoder is missing; return empty AI matches.
81. If no index exists, return empty AI matches.
82. This enables metadata-only search during first indexing.
83. Fourth edit: `ImageAdapter.kt`, model section.
84. Change `GalleryCell.Photo` from:
85. `data class Photo(val item: MediaItem, val featured: Boolean = false)`
86. To:
87. `data class Photo(val item: MediaItem, val featured: Boolean = false, val searchSources: Set<SearchMatchSource> = emptySet())`.
88. This is source-compatible because default is empty.
89. In `PhotoViewHolder.bindSelection`, after videoBadge visibility, call new helper.
90. Add private function inside `PhotoViewHolder`:
91. `private fun bindSearchBadges(sources: Set<SearchMatchSource>)`.
92. It should set `binding.aiBadge.visibility` visible only if `SearchMatchSource.Ai in sources`.
93. It should set `binding.metadataBadge.visibility` visible only if `SearchMatchSource.Metadata in sources`.
94. If both hidden, the badge row should be gone.
95. Since view binding IDs will exist after XML edit, use `binding.searchBadgeRow`.
96. If local LLM wants minimal XML, it can use two TextViews directly without row visibility.
97. Add tiny pop-in animation only if not too complex; not required.
98. Ensure recycled view holders hide badges when sources are empty.
99. Existing `videoBadge` remains independent.
100. Search results are photos only, so video badge should stay gone anyway.
101. Fifth edit: `item_image.xml`.
102. Add a horizontal badge row near bottom-start above video badge.
103. Insert after `videoBadge` or before it; visual order does not matter if positions differ.
104. Recommended XML block:
105. `<LinearLayout android:id="@+id/searchBadgeRow" ... android:layout_gravity="bottom|start" android:orientation="horizontal" android:visibility="gone">`.
106. Use `android:layout_marginStart="8dp"` and `android:layout_marginBottom="8dp"`.
107. Add `TextView @+id/aiBadge` with text `AI`.
108. Add `TextView @+id/metadataBadge` with text `META`.
109. Each badge should use `wrap_content`, small horizontal padding, 11sp text size.
110. Use `includeFontPadding="false"`.
111. Use all caps already in text; `textAllCaps` optional.
112. Use `@drawable/search_badge_ai_bg` and `@drawable/search_badge_metadata_bg`.
113. Set text color `@color/metroTextPrimary` for AI badge.
114. Set text color `@color/metroTextInverse` or primary depending background for META badge.
115. Keep row small so it does not obscure thumbnails.
116. Sixth edit: add drawable `res/drawable/search_badge_ai_bg.xml`.
117. Shape rectangle with corner radius 8dp.
118. Solid color can be `@color/metroAccentMuted`.
119. Optional stroke: 1dp `@color/metroAccentLight`.
120. Seventh edit: add drawable `res/drawable/search_badge_metadata_bg.xml`.
121. Shape rectangle with corner radius 8dp.
122. Solid color can be `#DDEAEAEA` or add color resource.
123. If using light solid, text inverse black.
124. If using dark solid, text primary white.
125. Existing style uses black cards, so AI blue + META light gray will stand out.
126. Eighth edit: optional `colors.xml` additions.
127. Add `<color name="metroAiBadge">#EE3B9EFF</color>`.
128. Add `<color name="metroMetadataBadge">#E6EAEAEA</color>`.
129. Add `<color name="metroMetadataBadgeText">#FF000000</color>`.
130. Ninth edit: `MainActivity.kt` search flow.
131. Add helper function near `currentSearchItems()`:
132. `private fun currentSearchPhotoItems(): List<GalleryRepository.MediaItem>`.
133. Function should return photos only.
134. Suggested implementation logic:
135. If `currentAlbum != null`: `albumDetailItems.filter { it.mediaType == Image }`.
136. Else if `activeSection == Section.Favorites`: `favoriteItems.filter { Image }`.
137. Else: `imageItems` because it already contains images only.
138. This means Albums/Videos/Collection all search photos, not folder cells.
139. Add helper `private fun isAiSearchAvailable(): Boolean = textEncoder != null && repository?.indexedCount ?: 0 > 0` if desired.
140. Modify `submitSearch()`.
141. Remove early return block that shows “Models still warming up — try again in a moment.”.
142. Keep `repo = repository ?: return`.
143. If query blank, keep placeholder behavior.
144. For nonblank query, use `baseItems = currentSearchPhotoItems()`.
145. In IO context, compute `aiMatches = if (textEncoder != null) repo.searchAiMatches(query) else emptyList()`.
146. Then compute `matches = SearchCoordinator.mergeSearchResults(query, baseItems, aiMatches)`.
147. Back on main thread, map matches to cells:
148. `val byUri = baseItems.associateBy { it.uri }`.
149. `val cells = matches.mapNotNull { match -> byUri[match.uri]?.let { GalleryCell.Photo(it, featured = false, searchSources = match.sources) } }`.
150. If cells empty, show `GalleryCell.Empty("No matching photos")`.
151. Update adapter with cells.
152. Update result count from `matches.size`.
153. Result count examples:
154. If matches empty: `No photo results`.
155. If one: `1 photo result`.
156. Else: `$count photo results`.
157. Add source counts to status text, not result count, to avoid clutter.
158. Compute `aiCount = matches.count { it.hasAi }`.
159. Compute `metadataCount = matches.count { it.hasMetadata }`.
160. Compute `bothCount = matches.count { it.hasAi && it.hasMetadata }`.
161. Status text when `textEncoder == null`: `Metadata search ready · AI warming up`.
162. Status text when `repo.indexedCount == 0`: `Metadata results · AI indexing has not produced searchable photos yet`.
163. Status text when `repo.indexedCount < baseItems.size`: `AI indexing in progress · $aiCount AI · $metadataCount metadata · $bothCount both`.
164. Status text when complete: `$aiCount AI · $metadataCount metadata · $bothCount both`.
165. Do not show folders or albums in any of these states.
166. Remove or stop using `buildAlbumSearchCells` from search flow.
167. You may leave `buildAlbumSearchCells` unused temporarily.
168. Better cleanup: delete `buildAlbumSearchCells` after compile confirms no references.
169. Replace `buildMediaSearchCells` with a new function that accepts `matches`.
170. Recommended new function:
171. `private fun buildMediaSearchCells(baseItems: List<MediaItem>, matches: List<SearchMatch>): List<GalleryCell>`.
172. It should map `SearchMatch.sources` into `GalleryCell.Photo(searchSources = sources)`.
173. Delete old `semanticResults: List<Uri>` parameter.
174. Delete ordered `LinkedHashSet<Uri>` logic because coordinator now merges.
175. Delete `matchesSearch()` from MainActivity if moved to coordinator.
176. Delete date metadata helpers only if not used elsewhere.
177. `safeFormat()` is used by timeline; keep it.
178. `monthFormat` and `dayFormat` are still needed for timeline.
179. Update `updateSearchMetaText()`.
180. Replace albums branch text with no album-result wording.
181. Suggested text:
182. `Photo results only · AI and metadata badges show why each result matched`.
183. For favorites:
184. `Photo results from favorites · AI and metadata badges show match source`.
185. For current album:
186. `Photo results in ${album.name.lowercase(locale)} · AI + metadata`.
187. For videos section:
188. `Photo search only · switch to videos for browsing videos`.
189. Update `searchPlaceholderText()`.
190. For albums: return `Search photos, not folders`.
191. For videos: return `Search photos` or `Search photo library`.
192. For default: return `Search photos`.
193. Tenth edit: ensure search is not blocked by `activeSection == Section.Albums`.
194. Existing branch around `if (currentAlbum == null && activeSection == Section.Albums)` must be removed.
195. This is the main cause of folder results.
196. Eleventh edit: ensure AI results are scoped.
197. Because repository index may include all selected album images, coordinator must filter AI matches to baseItems.
198. This prevents a search inside Favorites from showing non-favorite AI matches.
199. It also prevents stale deleted media from appearing.
200. Twelfth edit: ensure indexing state stays friendly.
201. `maybeRefreshLiveIndex(current)` currently reloads cached index and calls `submitSearch()`.
202. Keep it; badges will update as AI matches appear.
203. In `observeIndexWorker`, during RUNNING, status text will be overwritten by indexing progress.
204. That is okay, but during active search consider appending query context.
205. Optional: if `currentMode == Mode.Search`, set `statusText` to `Indexing: current / total · search updating live`.
206. Do not block user while indexing.
207. Thirteenth edit: optional remove `SearchResultManager` if unused.
208. It currently paginates URI list only and does not support source badges.
209. Leave it for now to reduce risk.
210. Fourteenth edit: build and fix import errors.
211. Likely imports needed: `java.text.SimpleDateFormat`, `java.util.Locale`, `java.util.Date`, `java.util.Calendar` in coordinator.
212. If coordinator uses nested type alias for `MediaItem`, qualify `GalleryRepository.MediaItem`.
213. If metadata helper uses `GalleryRepository.MediaType.Image`, fully qualify or import nested enum.
214. Kotlin nested enum import syntax can be avoided by full qualification.
215. Check ViewBinding names after XML edit: `searchBadgeRow`, `aiBadge`, `metadataBadge`.
216. If binding fails, clean/rebuild Android project.
217. After compile, run through Part 3 tests.
218. Keep changes limited to search and badge behavior.

# Deepix Search Refactor Guide — Part 3 of 3

01. This part contains local LLM prompts, validation checklist, and expected behavior.
02. Use after applying Part 1 and Part 2 design decisions.
03. Local LLM master prompt:
04. “Refactor Deepix Android search so search results are photo tiles only, never album/folder cells.”
05. “Merge MobileCLIP AI results and metadata results by URI.”
06. “Show small `AI` badge for AI matches and `META` badge for metadata matches.”
07. “If both match sources find the same photo, show both badges on that tile.”
08. “Metadata search must work while AI models are warming up or the first index is still running.”
09. “AI results should appear progressively as cached/indexed embeddings become available.”
10. “Keep the existing Metro/Windows Phone-inspired gallery UI.”
11. “Do not rewrite viewer, deletion, sharing, favorites, or WorkManager indexing except where search status needs minor wording.”
12. “Make minimal targeted Kotlin/XML changes and keep the project buildable.”
13. Suggested implementation order for local LLM:
14. Step 1: Update `SearchModels.kt`.
15. Step 2: Add `SearchCoordinator.kt`.
16. Step 3: Add `GalleryRepository.searchAiMatches`.
17. Step 4: Extend `GalleryCell.Photo` and bind source badges in `ImageAdapter.kt`.
18. Step 5: Add badge views to `item_image.xml`.
19. Step 6: Add badge drawables/colors.
20. Step 7: Refactor `MainActivity.submitSearch()` and search helper methods.
21. Step 8: Build, fix imports, run manual tests.
22. Specific prompt for `SearchModels.kt`:
23. “Update SearchMatch to support merged AI and metadata search ranking.”
24. “Keep enum values Ai and Metadata.”
25. “Add combinedScore defaulting to 0f.”
26. “Keep hasAi and hasMetadata properties.”
27. Specific prompt for `SearchCoordinator.kt`:
28. “Create an object that accepts query, scoped photo media items, and AI SearchMatch list.”
29. “It must score metadata matches from filename, bucket name, mime type, date/month/year, and photo/image terms.”
30. “It must merge AI and metadata by URI.”
31. “It must filter everything to MediaType.Image and to the supplied baseItems scope.”
32. “It must compute combinedScore and sort best results first.”
33. “It must never create album/folder cells.”
34. Specific prompt for `GalleryRepository.kt`:
35. “Add searchAiMatches(query) by adapting the existing search(query) semantic logic.”
36. “Return SearchMatch with source Ai and aiScore.”
37. “Keep search(query) as a URI wrapper around searchAiMatches for compatibility.”
38. “Return empty list if textEncoder or index is unavailable; do not throw.”
39. Specific prompt for `ImageAdapter.kt`:
40. “Extend GalleryCell.Photo with searchSources default emptySet.”
41. “Bind aiBadge and metadataBadge visibility from searchSources.”
42. “Ensure recycled holders hide badges when searchSources is empty.”
43. “Keep selection badge, video badge, and click behavior unchanged.”
44. Specific prompt for `item_image.xml`:
45. “Add bottom-start badge row containing AI and META TextViews.”
46. “Keep it gone by default.”
47. “Use compact pill backgrounds and 11sp text.”
48. “Do not change thumbnail scale/cropping.”
49. Specific prompt for `MainActivity.kt`:
50. “Remove Albums-section search branch that returns GalleryCell.AlbumCell.”
51. “Create currentSearchPhotoItems() and always search photos only.”
52. “Do not early-return when textEncoder is null.”
53. “Run metadata search regardless of AI availability.”
54. “Call repo.searchAiMatches only when textEncoder exists.”
55. “Merge with SearchCoordinator.”
56. “Map SearchMatch to GalleryCell.Photo with searchSources.”
57. “Update resultCount using match count, not placeholder cell count.”
58. “Update status text to explain AI warming/indexing when relevant.”
59. Build validation command if local Gradle is available:
60. `./gradlew :app:assembleDebug` from repo root.
61. If Gradle wrapper is missing, open in Android Studio and run Gradle sync/build.
62. If Android SDK is missing in shell, Android Studio build is acceptable.
63. Compile errors to watch for:
64. ViewBinding cannot find `aiBadge`, `metadataBadge`, or `searchBadgeRow`.
65. Cause: XML IDs not added or build cache stale.
66. Fix: verify IDs in `item_image.xml`, then clean/rebuild.
67. Compile error: `SearchMatchSource` unresolved in `ImageAdapter.kt`.
68. Cause: same package should resolve; otherwise confirm package declaration.
69. Compile error: `Date` or `SimpleDateFormat` unresolved in coordinator.
70. Cause: missing imports.
71. Compile error: `MediaType` unresolved.
72. Fix: use `GalleryRepository.MediaType.Image`.
73. Compile error: old `buildMediaSearchCells` call mismatch.
74. Fix: update all call sites in `submitSearch()`.
75. Compile error: `buildAlbumSearchCells` unused is not an error, but detekt may warn depending config.
76. If detekt fails on unused private method, delete `buildAlbumSearchCells`.
77. If detekt fails on long method, split search status text into helper.
78. Manual test setup:
79. Use a device/emulator with a mixed gallery.
80. Include photos with known filenames like `beach_dog.jpg`, screenshots, food, people, and documents.
81. Include albums/folders with names that match queries.
82. Include at least one video to verify videos do not appear in photo search.
83. Fresh install test is important because first index starts empty.
84. Test 1: Fresh install, grant permissions.
85. Expected: gallery loads before AI index completes.
86. Expected: status may show indexing queued/running.
87. Test 2: Immediately open search while models/index are warming.
88. Query a filename or folder term that metadata can match.
89. Expected: photo tiles appear even if AI is not ready.
90. Expected: metadata-matched tiles show `META` badge.
91. Expected: no album/folder tile appears.
92. Expected: no “Models still warming up — try again” blocking message.
93. Test 3: Search content query like `dog`, `food`, `beach`, or `document` while indexing partial.
94. Expected: AI matches may be few or none at first.
95. Expected: status explains AI indexing/warming if AI count is zero.
96. Expected: metadata results still appear if metadata matches exist.
97. Test 4: Wait for some indexing progress.
98. Search same content query again.
99. Expected: AI badge appears on AI-derived photo results.
100. Expected: if same photo also matches filename/date/folder metadata, both `AI` and `META` badges appear.
101. Test 5: After full indexing completes.
102. Search semantic-only query with no filename match, e.g. `sunset`, `cat`, `food`.
103. Expected: AI-badged results appear.
104. Expected: META badge does not appear unless metadata also matches.
105. Test 6: Search metadata-only query, e.g. exact filename fragment, month, year, or folder name.
106. Expected: META-badged photo results appear.
107. Expected: no album/folder cells appear even when folder name matched.
108. Test 7: Search in Albums tab.
109. Expected: results are photo tiles only.
110. Expected: no `GalleryCell.AlbumCell` results.
111. Expected: placeholder says photos, not albums/folders.
112. Test 8: Search inside an album detail screen.
113. Expected: results are photo tiles only from that album.
114. Expected: AI results outside that album are filtered out.
115. Test 9: Search in Favorites section.
116. Expected: results are favorite photos only.
117. Expected: non-favorite AI matches are filtered out.
118. Test 10: Search in Videos section.
119. Expected chosen behavior must be consistent.
120. If implementing photo-only global search, videos should not appear.
121. If showing a message, it should clearly say photo search only.
122. Test 11: Clear search query.
123. Expected: search placeholder/empty state appears.
124. Expected: browse mode tiles do not show AI/META badges.
125. Test 12: Close search.
126. Expected: normal section rendering returns.
127. Expected: badges are hidden in normal timeline/collage views.
128. Test 13: Selection mode on search result.
129. Expected: long press selection still works.
130. Expected: check badge appears independently of AI/META badges.
131. Expected: share/delete still work.
132. Test 14: Tap search result.
133. Expected: viewer opens at correct item.
134. Expected: swiping viewer uses current search result ordering.
135. Test 15: Rotate device or recreate activity if supported.
136. Expected: no crash due to new badge state.
137. Ranking validation:
138. A photo matching both AI and metadata should usually rank above source-only matches.
139. Exact filename metadata matches should remain easy to find.
140. AI-only semantic matches should not be buried under weak folder-name matches.
141. If weak folder matches dominate, reduce bucketName metadata score.
142. If AI-only matches dominate exact filename searches, increase displayName score or exactBonus.
143. If too many metadata matches appear for generic terms like `image`, lower type term score.
144. If no AI results appear after indexing completes, verify `searchAiMatches` threshold and index loading.
145. If AI results ignore active scope, verify coordinator filters AI URI against `baseItems`.
146. If album cells still appear, search for `buildAlbumSearchCells` references.
147. There should be no active call path from `submitSearch()` to `GalleryCell.AlbumCell`.
148. If videos appear, verify `currentSearchPhotoItems()` filters `MediaType.Image`.
149. If badges appear during browsing, verify non-search `GalleryCell.Photo` calls use default empty sources.
150. If badges stick on recycled cells, verify binding hides them on empty sources.
151. If result count says 1 when empty, ensure count uses `matches.size`, not `cells.size` after empty placeholder.
152. Suggested final code shape summary:
153. `GalleryRepository` owns AI index search and returns AI matches.
154. `SearchCoordinator` owns metadata scoring, merge, ranking, and photo-only filtering.
155. `MainActivity` owns UI state and maps matches to photo cells.
156. `ImageAdapter` owns visual badge rendering.
157. `item_image.xml` owns badge views.
158. Do not put ranking logic inside adapter.
159. Do not put badge visibility logic inside repository.
160. Do not let repository know about current UI section beyond supplied base items.
161. Keep local LLM changes small enough to review in one diff.
162. Recommended diff review checklist:
163. `SearchModels.kt` updated with combined score support.
164. `SearchCoordinator.kt` added and readable.
165. `GalleryRepository.searchAiMatches` returns source-tagged AI matches.
166. `MainActivity.submitSearch` no longer blocks metadata when AI is unavailable.
167. `MainActivity.submitSearch` no longer calls `buildAlbumSearchCells`.
168. `currentSearchPhotoItems` exists and filters to images.
169. `GalleryCell.Photo` carries search source metadata.
170. `item_image.xml` has AI and META badge IDs.
171. `ImageAdapter.PhotoViewHolder` hides/shows badges correctly.
172. New drawables/colors exist and are referenced correctly.
173. No unrelated formatting churn across large files.
174. No changes to ONNX assets or tokenizer assets.
175. No permission changes unless separately required.
176. No new external dependencies required.
177. Suggested acceptance criteria:
178. Searching never displays album/folder cards.
179. Searching returns photo tiles from metadata while index is empty or partial.
180. Searching returns AI photo tiles once index/model are available.
181. Same photo can display both AI and META badges.
182. Result ordering is stable and understandable.
183. Search works from Collection, Albums, Favorites, and album detail without folder cards.
184. Normal gallery browsing remains visually unchanged except no search badges.
185. Optional enhancement after this refactor:
186. Add a small legend in search meta text: `AI = visual match · META = filename/date/location info`.
187. Keep legend short to preserve Metro minimalism.
188. Optional enhancement after this refactor:
189. Add a filter chip to toggle AI/META sources.
190. Do not add source filters in the first pass unless requested.
191. Optional enhancement after this refactor:
192. Add indexed percentage in search status when WorkManager reports progress.
193. Current status already has enough indexing messages; avoid clutter.
194. Optional enhancement after this refactor:
195. Store metadata index for faster search on very large libraries.
196. Not needed for current task.
197. Important edge case: MediaStore item deleted after index was saved.
198. Coordinator filtering via `baseItems.associateBy` should drop stale AI URI.
199. Important edge case: selected album scope changed.
200. Base items should reflect current selected scope, so stale AI matches are dropped.
201. Important edge case: model warm-up failed.
202. Metadata search must still work; status should not fatal-error solely for text encoder failure.
203. Important edge case: AI index partially loaded from cache.
204. Some AI badges appear, more may appear after live refresh.
205. Important edge case: query is blank.
206. Do not show full folder list; keep current empty/placeholder behavior in search mode.
207. Important edge case: query is very short like `a`.
208. Metadata scoring should avoid matching every filename too broadly; token length filter helps.
209. Important edge case: folder name matches every image in a folder.
210. This is acceptable only as photo results, but lower bucket score if it overwhelms ranking.
211. Final local commit message suggestion:
212. `Refactor search to merge AI and metadata photo results with source badges`.
213. Final user-facing summary after local implementation:
214. `Search now returns photos only, combines AI and metadata matches, and labels each result with AI/META badges.`
215. `Metadata results are available immediately while AI indexing continues.`
216. `Photos found by both systems display both badges.`
217. Re-run manual tests after any future search ranking tweak.
218. Keep this guide near the implementation until the refactor is merged.