# Search Latency (Locations / EXIF) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Stop the Locations section from running 150 blocking EXIF reads on the UI thread on every text search, so semantic results paint immediately.

**Architecture:** `LocationSearch` is reached from `MainActivity.buildSearchSections`, which runs on the Main dispatcher inside every results render. The pass re-reads EXIF for rows it already has (`.plus(existing)` then `.take(150)`), so the same 150 files are re-parsed on every search and every intermediate render, forever. The fix is four independent changes: gate the pass on the query actually naming a place, replace the repeating re-read with a persisted one-way cursor, move the whole pass to `Dispatchers.IO`, and append the Locations card after the first paint instead of inside it.

**Tech Stack:** Kotlin, Android, coroutines, SharedPreferences, Room, JUnit 4 (plain JVM — no Robolectric, so plan logic must stay free of `android.*`).

**Spec:** This document (diagnosis verified by reading `LocationSearch.kt`, `MainActivity.submitSearch/renderSearchResults/buildSearchSections`, `ExifExtractor.kt`, `DbRepository.kt`).

## Global Constraints

- No behaviour change to what Locations returns for an actual place query; only when it runs and how often.
- No re-index, no Room schema migration, no new dependency.
- `versionCode` stays 8.
- Existing 37 suites / 197 tests must keep passing; release build (`assembleRelease`) and `lintVitalRelease` must stay clean.
- Unit tests run as plain JVM tests: no `android.net.Uri`, no `Context`, no Robolectric in test code.

## Review Focus

- A library where every photo already has an EXIF row (the shipped state): searching any word must not read a single EXIF stream. Verified by `planExifFill` tests + the `revisitsAllowed` path.
- Permission revoked after a grant: the old code re-read with `setRequireOriginal` and upserted an all-null row, clobbering good GPS. Now no revisit happens while the permission is missing.
- Repeated renders of one query (interim → final, and every debounce tick): only the deferred pass may run the EXIF work, never the render.
- A user who drills into a section while the deferred pass runs: the pass must not yank the open grid back to the landing.
- A place query with an empty result set: no orphan Locations card on an otherwise empty landing.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/devomind/gallerysearch/LocationSearch.kt` | place resolution + EXIF fill; gains the pure `planExifFill` selector |
| `app/src/main/java/com/devomind/gallerysearch/StoragePermissions.kt` | one new predicate: can original EXIF be read |
| `app/src/main/java/com/devomind/gallerysearch/IndexPreferences.kt` | two new prefs: revisit cursor + last-seen permission |
| `app/src/main/java/com/devomind/gallerysearch/MainActivity.kt` | Locations leaves the blocking section list; deferred append job |
| `app/src/test/java/com/devomind/gallerysearch/ExifFillPlanTest.kt` | the selector's tests (new) |
| `CONTEXT.md` | search-flow note: Locations is a deferred pass |

---

### Task 1: Pure EXIF fill planner

**Files:**
- Modify: `app/src/main/java/com/devomind/gallerysearch/LocationSearch.kt`
- Test: `app/src/test/java/com/devomind/gallerysearch/ExifFillPlanTest.kt`

**Interfaces:**
- Produces: `internal data class ExifFillPlan(val fresh: List<String>, val revisits: List<String>, val nextCursor: Int)` with `val uris: List<String> get() = fresh + revisits`
- Produces: `internal fun planExifFill(libraryUris: List<String>, existing: Set<String>, revisitCursor: Int, limit: Int, revisitsAllowed: Boolean): ExifFillPlan`

- [x] **Step 1: Write the failing test**

`ExifFillPlanTest.kt`, plain JUnit4, string URIs:

```kotlin
class ExifFillPlanTest {
    private val limit = 150

    @Test fun aColdLibraryFillsTheNewestPhotosUpToTheLimit() {
        val plan = planExifFill((1..300).map { "u$it" }, emptySet(), 0, limit, true)
        assertEquals((1..150).map { "u$it" }, plan.fresh)
        assertEquals(emptyList<String>(), plan.revisits)
        assertEquals(0, plan.nextCursor)
    }

    @Test fun onceEveryRowExistsTheSearchRevisitsRowsItHasNotRevisitedYet() {
        val lib = (1..300).map { "u$it" }
        val plan = planExifFill(lib, lib.toSet(), 0, limit, true)
        assertEquals(emptyList<String>(), plan.fresh)
        assertEquals((1..150).map { "u$it" }, plan.revisits)
        assertEquals(150, plan.nextCursor)
    }

    @Test fun theNextSearchContinuesFromTheCursorInsteadOfTheFirstRow() {
        val lib = (1..300).map { "u$it" }
        val plan = planExifFill(lib, lib.toSet(), 150, limit, true)
        assertEquals((151..300).map { "u$it" }, plan.revisits)
        assertEquals(300, plan.nextCursor)
    }

    @Test fun aFinishedSweepStopsRevisitingAndLeavesTheCursorAlone() {
        val lib = (1..300).map { "u$it" }
        val plan = planExifFill(lib, lib.toSet(), 300, limit, true)
        assertEquals(emptyList<String>(), plan.revisits)
        assertEquals(emptyList<String>(), plan.uris)
        assertEquals(300, plan.nextCursor)
    }

    @Test fun withoutLocationPermissionNoRowIsRevisited() {
        val lib = (1..300).map { "u$it" }
        val plan = planExifFill(lib, lib.toSet(), 40, limit, revisitsAllowed = false)
        assertEquals(emptyList<String>(), plan.revisits)
        assertEquals(40, plan.nextCursor)
    }

    @Test fun freshRowsAreReadBeforeRevisits() {
        val lib = (1..300).map { "u$it" }
        val existing = lib.filter { it.toInt() > 100 }.toSet()
        val plan = planExifFill(lib, existing, 0, limit, true)
        assertEquals((1..100).map { "u$1" }, plan.fresh) // 100 fresh
        assertEquals((101..150).map { "u$it" }, plan.revisits) // remaining 50 from the cursor
        assertEquals(50, plan.nextCursor)
    }

    @Test fun revisitsDoNotDependOnTheLibraryOrder() {
        val lib = (1..300).map { "u$it" }
        val shuffled = lib.shuffled(Random(7))
        val a = planExifFill(lib, lib.toSet(), 150, limit, true).revisits
        val b = planExifFill(shuffled, lib.toSet(), 150, limit, true).revisits
        assertEquals(a, b)
    }

    @Test fun anEmptyLibraryPlansNothing() {
        val plan = planExifFill(emptyList(), emptySet(), 0, limit, true)
        assertEquals(emptyList<String>(), plan.uris)
        assertEquals(0, plan.nextCursor)
    }

    @Test fun aCursorPastTheEndIsClamped() {
        val lib = (1..10).map { "u$it" }
        val plan = planExifFill(lib, lib.toSet(), 99, limit, true)
        assertEquals(emptyList<String>(), plan.revisits)
        assertEquals(10, plan.nextCursor)
    }

    @Test fun aZeroLimitPlansNothing() {
        val plan = planExifFill((1..10).map { "u$it" }, emptySet(), 3, 0, true)
        assertEquals(emptyList<String>(), plan.uris)
        assertEquals(3, plan.nextCursor)
    }
}
```

Note the `freshRowsAreReadBeforeRevisits` expectation: 100 fresh + 50 revisits, cursor 50 — write the assertion as `(1..100)` / `(101..150)` and `50`; the `"u$1"` typo above is a placeholder, use `(1..100).map { "u$it" }`.

- [x] **Step 2: Run the test to verify it fails**

`.\gradlew.bat --console=plain testDebugUnitTest --tests "*ExifFillPlanTest*"`
Expected: compile failure — `planExifFill` unresolved.

- [x] **Step 3: Implement `planExifFill` in `LocationSearch.kt`**

Semantics (all decided; no open choices):
- `limit <= 0` or empty library → `ExifFillPlan(emptyList(), emptyList(), revisitCursor.coerceAtLeast(0))`.
- `fresh` = `libraryUris.filter { it !in existing }.take(limit)`, input order preserved.
- `candidates` = `libraryUris.filter { it in existing }.distinct().sorted()`.
- `start` = `revisitCursor.coerceIn(0, candidates.size)`.
- if `revisitsAllowed` and `fresh.size < limit`: take `min(limit - fresh.size, candidates.size - start)` from `candidates` at `start`; `nextCursor = start + taken`.
- otherwise `revisits` empty and `nextCursor = start` (clamped, never advanced).

- [x] **Step 4: Run the tests to verify they pass**

`.\gradlew.bat --console=plain testDebugUnitTest --tests "*ExifFillPlanTest*"` → PASS (10 tests).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/devomind/gallerysearch/LocationSearch.kt app/src/test/java/com/devomind/gallerysearch/ExifFillPlanTest.kt
git commit -m "perf(search): plan EXIF refills with a one-way cursor"
```

---

### Task 2: Bound the revisit sweep and stop clobbering on a revoked permission

**Files:**
- Modify: `app/src/main/java/com/devomind/gallerysearch/IndexPreferences.kt`
- Modify: `app/src/main/java/com/devomind/gallerysearch/StoragePermissions.kt`
- Modify: `app/src/main/java/com/devomind/gallerysearch/LocationSearch.kt:63-79`

**Interfaces:**
- Consumes: `planExifFill` (Task 1)
- Produces: `IndexPreferences.exifRevisitCursor(context): Int`, `IndexPreferences.setExifRevisitCursor(context, cursor: Int)`, `IndexPreferences.hadExifRepairPermission(context): Boolean`, `IndexPreferences.setExifRepairPermission(context, had: Boolean)`
- Produces: `StoragePermissions.canReadOriginalExif(context): Boolean`

- [x] **Step 1: Add the preferences** (keys `exif_revisit_cursor`, `exif_repair_permission`, same `index_prefs` store, `apply()` writes, one-line KDoc each).
- [x] **Step 2: Add `StoragePermissions.canReadOriginalExif`** — true when `SDK_INT >= Q` and `ACCESS_MEDIA_LOCATION` is granted; below Q there is nothing to repair, so false.
- [x] **Step 3: Rewrite `fillMissingExif`** to use the plan: read `existing`, compute `allowed`, handle the grant edge (granted now and not seen before → reset cursor to 0; missing now and previously seen → mark unseen), call `planExifFill(all, existing, cursor, MaxExifExtractionsPerSearch, allowed)`, extract only `plan.uris`, persist `nextCursor` only when `revisits` is non-empty.
- [x] **Step 4: Build + run the suite** — `testDebugUnitTest` (no new tests here; the logic they gate is Task 1's).
- [x] **Step 5: Commit** — `perf(search): cap EXIF repair at one sweep of the library`

---

### Task 3: Resolve the place first and run the whole pass off the UI thread

**Files:**
- Modify: `app/src/main/java/com/devomind/gallerysearch/LocationSearch.kt:40-60`

**Interfaces:**
- Consumes: Task 2's `fillMissingExif`
- Produces: `LocationSearch.matchingUris` unchanged signature, now IO-dispatched

- [x] **Step 1: Wrap the body in `withContext(Dispatchers.IO)`** and replace every `return emptySet()` with `return@withContext emptySet()`.
- [x] **Step 2: Move `resolveTarget` above `fillMissingExif`** so a query that names no place (`resolveTarget` null, cached including misses) returns before any EXIF read.
- [x] **Step 3: Re-throw `CancellationException`** before the general `catch (error: Throwable)` so a superseded search stops mid-sweep.
- [x] **Step 4: Build + tests** — full `testDebugUnitTest`.
- [x] **Step 5: Commit** — `perf(search): keep location EXIF off the UI thread`

---

### Task 4: Append the Locations card after the first paint

**Files:**
- Modify: `app/src/main/java/com/devomind/gallerysearch/MainActivity.kt`
  - `buildSearchSections` (~3527-3595): delete the Locations block and its `db` local
  - field near `searchJob` (~160): `private var locationSectionJob: Job? = null`
  - new `scheduleLocationSection(...)` near `buildSearchSections`
  - `submitSearch`: cancel at the top (~2038); call after the render in the bare-person branch (~2128), the metadata-only branch (~2175) and the main branch (~2233)
  - `clearSearchSections` (~3372): cancel

**Interfaces:**
- Produces: `private fun scheduleLocationSection(query: String, sessionMode: SearchMode, sessionSection: Section, sessionAlbumId: String?)`

- [x] **Step 1: Delete the Locations block** from `buildSearchSections`; `itemsByUri` stays for Albums/Tags.
- [x] **Step 2: Add the job field** next to `searchJob`.
- [x] **Step 3: Implement `scheduleLocationSection`** — parse text, bail on blank/`db == null`; capture `pool = currentSearchPhotoItems()`; launch, `withContext(Dispatchers.IO) { LocationSearch.matchingUris(...) }`; then require non-empty, `isSearchSessionCurrent`, `searchLandingVisible`, non-empty `searchSectionResults`, no existing Locations section; map through `pool.associateBy { it.uri }`; append `SearchSectionResult(SearchSection.Locations, n, results)`; call `showSearchLanding()`.
- [x] **Step 4: Wire the three call sites and the two cancels.**
- [x] **Step 5: Build + full test suite + lint + R8** — the verification command below.
- [x] **Step 6: Commit** — `perf(search): resolve Locations after the first paint`

---

### Task 5: Document and verify

**Files:**
- Modify: `CONTEXT.md` (Search Flow block, ~97)

- [x] **Step 1: Add one line** to the search flow: semantic/metadata render first; the Locations section is a deferred pass over cached GPS, EXIF-filled with a persisted one-way cursor, so a text search never re-reads the same files.
- [x] **Step 2: Full verification** — `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat --console=plain testDebugUnitTest lintVitalRelease assembleRelease`; expect `BUILD SUCCESSFUL` and 38 suites / 207 tests, 0 failures.
- [x] **Step 3: Commit and push** (user's standing instruction once they say so).

---

## Implementation notes (where the code differs from the plan)

- **11 planner tests, not 10.** `thePlanIsOneListInFreshThenRevisitOrder` was added because `uris` is the list the loop actually reads; the other tests would pass on `fresh`/`revisits` alone.
- **Test fixtures are zero-padded URIs.** The sweep sorts candidates so the repair order is independent of the pool order, and lexicographic sorting of `u1..u300` is not numeric � the fixture had to encode the order the planner uses.
- **The card still appears on the "No matching results" screen.** `renderSearchResults` returns before the landing when no section exists, so the guard is `selectedSearchSection == null` rather than `searchLandingVisible`: a place query with no semantic hits ("goa" in a library the AI does not know) still gets its Locations card, and the grid is only left alone when the user has drilled into a section.
- **The EXIF sweep uses `imageItems`, not the section pool.** The cursor is an index into the candidate list, and a Favorites- or folder-scoped pool would advance it over a shorter list and skip rows. Matched photos are still mapped through the section pool, so what the card can show is unchanged.
- **Verification:** 38 suites / 208 tests, `lintVitalRelease` clean, R8 `assembleRelease` builds.
