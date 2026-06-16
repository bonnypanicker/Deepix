# Feature Implementation Spec — Smart Albums (Prompt-Backed Albums)

**Project:** Deepix Gallery Search (`com.devomind.gallerysearch`)
**Document type:** Engineering implementation specification
**Supersedes:** `POPUP_SEARCH_FEATURE_SPEC.md` (the pop-up is now only a small create dialog, not a search overlay).

---

## 1. What we're building (corrected understanding)

A **Smart Album** is a real, persistent album that lives **alongside normal albums in the Albums page (Pinned section)** and looks identical to them (cover thumbnail + name + photo count). Its difference is only *how its contents are determined*: instead of a MediaStore bucket, its members are the **results of a saved text prompt** run through the existing search engine.

User flow:
1. User taps a "＋ Smart Album" affordance.
2. A small **pop-up dialog** appears with exactly two inputs:
   - **Album name** (the header shown on the album card), and
   - **Prompt** (the search query, e.g. "sunset at the beach").
3. On **Create**, the app runs the prompt through the existing search pipeline, stores the album definition (name + prompt + resolved member URIs + cover), and shows it as a normal-looking album card in the **Pinned** section of the Albums page.
4. Tapping the Smart Album opens it like any album (timeline grid of its photos). It can be refreshed (re-run the prompt) and deleted/unpinned.

The pop-up is **just a create form** — no live preview, no search overlay. The deliverable of the feature is a **real album object**.

---

## 2. Grounding in current code

| Concern | Existing symbol | File |
|---|---|---|
| Album model | `data class Album(val id, val name, val count, val coverUri: Uri?)` | `GalleryRepository.kt` |
| Albums page render (PINNED/OTHERS) | `renderAlbums()` builds `GalleryCell.Header("PINNED") + GalleryCell.AlbumCell(album)` | `MainActivity.kt` |
| Album card UI | `GalleryCell.AlbumCell`, `AlbumViewHolder.bind(album)`, `item_album.xml` (`albumCover`, `albumName`, `albumCount`) | `ImageAdapter.kt`, `item_album.xml` |
| Album open | `openAlbum(album)` → `renderAlbumDetail(album)` | `MainActivity.kt` |
| Album-detail membership | `albumDetailItems = collectionItems.filter { it.bucketId == album.id }` | `MainActivity.kt` |
| Pin persistence | `AlbumPinStore` (SharedPreferences, ordered ids) | `AlbumPinStore.kt` |
| Pinned filtering in render | `albumPinStore.getPinnedAlbumIds()`, pinned vs normal split | `MainActivity.kt` (`renderAlbums`) |
| Search engine | `StructuredSearch.parse`, `GalleryRepository.search(query)` (CLIP), `searchMetadata`, merge | `MainActivity.submitSearch`, `GalleryRepository.kt` |
| Candidate items | `currentSearchPhotoItems()` / `collectionItems` | `MainActivity.kt` |
| Long-press album menu | `showAlbumPinMenu(album, view)` | `MainActivity.kt` |
| Design tokens | `DesignTokens.*`, `R.color.metro*`, drawables | `DesignTokens.kt` |

**Key design lever:** Album cards already render purely from an `Album(id, name, count, coverUri)` and album-detail filters `collectionItems` by `bucketId == album.id`. So a Smart Album can be modeled as an `Album` whose `id` is a synthetic id (e.g. `smart:<uuid>`) and whose members are an explicit set of URIs, with album-detail resolving membership by that URI set instead of `bucketId`.

---

## 3. Data model

### 3.1 New persistent definition: `SmartAlbumStore.kt`
A SharedPreferences-backed store (mirrors `AlbumPinStore` style) persisting a JSON array of:

```kotlin
data class SmartAlbum(
    val id: String,          // "smart:" + UUID
    val name: String,        // album header / card title
    val prompt: String,      // saved search query
    val searchMode: String,  // SearchMode name at creation (AiOnly/MetadataOnly/Both)
    val memberUris: List<String>, // resolved result URIs (engine order preserved)
    val coverUri: String?,   // first member uri (album cover)
    val createdAt: Long,
    val updatedAt: Long
)
```

Store API:
```kotlin
class SmartAlbumStore(context: Context) {
    fun getAll(): List<SmartAlbum>
    fun get(id: String): SmartAlbum?
    fun upsert(album: SmartAlbum)
    fun delete(id: String)
    fun isSmartId(id: String): Boolean = id.startsWith(SMART_PREFIX) // "smart:"
}
```
Persist as JSON via `org.json` (same approach as `AlbumPinStore`). Member URIs are stored as strings; cap stored members (e.g. `MAX_SMART_MEMBERS = 800`, matching `DesignTokens.DISPLAY_CAP`) to bound prefs size.

### 3.2 Mapping to the existing `Album` type
A Smart Album is surfaced to the UI as a normal `Album`:
```kotlin
fun SmartAlbum.toAlbum(): GalleryRepository.Album = GalleryRepository.Album(
    id = id,
    name = name,
    count = memberUris.size,
    coverUri = coverUri?.let(Uri::parse)
)
```
No change to `item_album.xml` or `AlbumViewHolder` — it renders exactly like other albums. (Optional: a tiny "AI" badge overlay using existing `search_ai_badge_bg` to distinguish smart albums; nice-to-have, not required.)

---

## 4. Creation flow (the pop-up = create dialog)

### 4.1 New layout: `res/layout/dialog_smart_album.xml`
A simple dialog body (reuse `search_bg`, `metro*` tokens):
```
LinearLayout (vertical, padding PADDING_SCREEN, background search_bg)
 ├─ TextView   "New Smart Album" (textPrimary, HEADER_SUBTITLE_SIZE-ish)
 ├─ EditText   id=smartAlbumName   hint="Album name"
 ├─ EditText   id=smartAlbumPrompt hint="Describe what to find (e.g. sunset at the beach)"
 └─ (dialog buttons via AlertDialog: Cancel / Create)
```
Present with `MaterialAlertDialogBuilder`/`AlertDialog` (already available) — no new overlay infra needed. This is intentionally tiny.

### 4.2 Trigger
Add a "＋ Smart Album" entry. Options (pick one, default = a):
- **a.** A header action on the PINNED section / an item in the existing album long-press menu (`showAlbumPinMenu`) is not ideal since that's per-album; instead add a small "＋" affordance in the Albums top bar or as a special first cell.
- **b.** A drawer entry next to existing `drawerAlbums`.

Default: a dedicated "＋" button in the Albums screen top bar that calls `showCreateSmartAlbumDialog()`.

### 4.3 Create logic (`MainActivity`)
```kotlin
private fun showCreateSmartAlbumDialog() {
    // inflate dialog_smart_album, build AlertDialog
    // on Create:
    val name = nameInput.text.toString().trim()
    val prompt = promptInput.text.toString().trim()
    if (name.isEmpty() || prompt.isEmpty()) { /* inline error */ return }
    createSmartAlbum(name, prompt)
}

private fun createSmartAlbum(name: String, prompt: String) {
    val repo = repository ?: return
    binding.progressBar.visibility = View.VISIBLE
    lifecycleScope.launch {
        val resultUris = runSearchPipeline(            // shared core (see §6)
            query = prompt,
            mode = searchMode,
            candidateItems = currentSearchPhotoItems(),
            favoriteKeys = favoritesStore.all()
        ).take(SmartAlbumStore.MAX_SMART_MEMBERS)

        val album = SmartAlbum(
            id = "smart:" + UUID.randomUUID(),
            name = name,
            prompt = prompt,
            searchMode = searchMode.name,
            memberUris = resultUris.map { it.toString() },
            coverUri = resultUris.firstOrNull()?.toString(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        smartAlbumStore.upsert(album)
        albumPinStore.pin(album.id)                    // appears in PINNED section
        binding.progressBar.visibility = View.GONE
        navigateToSection(Section.Albums)
        renderCurrentState()                            // re-render Albums -> shows new card
    }
}
```
Empty results are allowed (album with 0 items, placeholder cover) — surface a toast "No matches yet — you can refresh later".

---

## 5. Rendering Smart Albums in the Albums page

### 5.1 Merge into `renderAlbums()`
`renderAlbums()` currently builds pinned vs normal from `albums` (MediaStore buckets) filtered by `albumPinStore`. Extend it so Smart Albums are injected into the **PINNED** section:

```kotlin
private fun renderAlbums() {
    currentMode = Mode.Browse
    ...
    albumPinStore.cleanup(validPinIds())   // valid = real album ids + smart album ids
    val pinnedIds = albumPinStore.getPinnedAlbumIds()

    val smartById = smartAlbumStore.getAll().associateBy { it.id }
    // Build the union: real albums + smart albums, both lookupable by id
    val albumById: Map<String, GalleryRepository.Album> =
        (albums + smartById.values.map { it.toAlbum() }).associateBy { it.id }

    val pinnedAlbums = pinnedIds.mapNotNull { albumById[it] }      // keeps user order
    val normalAlbums = albums.filter { it.id !in pinnedIds.toSet() }

    val cells = mutableListOf<GalleryCell>()
    if (pinnedAlbums.isNotEmpty()) {
        cells += GalleryCell.Header("PINNED", "")
        pinnedAlbums.forEach { cells += GalleryCell.AlbumCell(it) }
    }
    if (normalAlbums.isNotEmpty()) {
        cells += GalleryCell.Header("OTHERS", "")
        normalAlbums.forEach { cells += GalleryCell.AlbumCell(it) }
    }
    adapter.replaceCells(if (cells.isEmpty()) listOf(GalleryCell.Empty("No albums yet")) else cells)
    ...
}
```

**Critical:** `albumPinStore.cleanup(...)` currently prunes pins whose id is not a real MediaStore album id. It MUST be updated to treat smart album ids as valid:
```kotlin
private fun validPinIds(): Set<String> =
    albums.map { it.id }.toSet() + smartAlbumStore.getAll().map { it.id }.toSet()
```
Without this, smart albums get pruned on every render. This is the single most important integration point.

### 5.2 The card looks identical
Because a Smart Album is an `Album(id, name, count, coverUri)`, `AlbumViewHolder.bind` renders its cover/name/count exactly like a normal album. No adapter changes required (optional AI badge aside).

---

## 6. Opening / membership resolution

### 6.1 Shared search core (still required)
Extract `runSearchPipeline(query, mode, candidateItems, favoriteKeys): List<Uri>` from `submitSearch()` (same as the earlier spec, §5.1 there). It is reused by **creation** and **refresh**. Full-screen `submitSearch()` is refactored to call it too, so semantics stay identical.

### 6.2 Album-detail for Smart Albums
`renderAlbumDetail(album)` derives items via `albumDetailItems = collectionItems.filter { it.bucketId == album.id }`. Extend membership resolution to handle smart ids:

```kotlin
private val albumDetailItems: List<GalleryRepository.MediaItem>
    get() {
        val album = currentAlbum ?: return emptyList()
        return if (smartAlbumStore.isSmartId(album.id)) {
            val sa = smartAlbumStore.get(album.id) ?: return emptyList()
            val order = sa.memberUris.withIndex().associate { (i, u) -> u to i }
            collectionItems
                .filter { it.uri.toString() in order.keys }
                .sortedBy { order[it.uri.toString()] ?: Int.MAX_VALUE } // preserve engine rank
        } else {
            collectionItems.filter { it.bucketId == album.id }
        }
    }
```
This makes Smart Albums open and behave exactly like normal albums (selection, viewer, share all reuse existing photo cells), with members resolved from the saved URI set in engine-rank order. URIs no longer present on device are naturally dropped (filtered against `collectionItems`).

---

## 7. Manage: refresh, rename, delete

Reuse the existing long-press menu path. In `showAlbumPinMenu(album, view)`, if `smartAlbumStore.isSmartId(album.id)`, show smart-specific actions:
- **Refresh** — re-run `runSearchPipeline(prompt, savedMode, …)`, update `memberUris`, `coverUri`, `count`, `updatedAt`; re-render.
- **Rename** — edit `name`.
- **Edit prompt** — edit `prompt` then refresh.
- **Delete** — `smartAlbumStore.delete(id)` + `albumPinStore.unpin(id)`; re-render.

Normal albums keep their current menu unchanged.

---

## 8. New / Touched Files (≤ 8)

| File | Change | Type |
|---|---|---|
| `SmartAlbumStore.kt` | New persistent store + `SmartAlbum` model + `toAlbum()` | New |
| `res/layout/dialog_smart_album.xml` | New create dialog (name + prompt) | New |
| `MainActivity.kt` | `runSearchPipeline` extract; create/refresh/open logic; `renderAlbums` + `cleanup` + `albumDetailItems` + menu integration; instantiate `smartAlbumStore` | Edit |
| `activity_main.xml` | Add "＋ Smart Album" trigger (top bar button) | Edit |
| (optional) `item_album.xml` | Optional small AI badge overlay | Edit (optional) |

No new dependencies, no backend, no changes to the embedding index/encoders/`SearchTuning`. `ImageAdapter`/`AlbumViewHolder` unchanged.

---

## 9. Constants

```kotlin
// SmartAlbumStore
const val SMART_PREFIX = "smart:"
const val MAX_SMART_MEMBERS = 800   // align with DesignTokens.DISPLAY_CAP
```

---

## 10. Acceptance Criteria

1. A "＋ Smart Album" trigger opens a small pop-up with **only** an album-name field and a prompt field.
2. On Create, the prompt is run through the **same** engine as full-screen search (`runSearchPipeline`), and a persistent Smart Album is created with the result URIs, a cover (first result), and a count.
3. The Smart Album appears in the **Pinned** section of the Albums page and is **visually identical** to other album cards (cover + name + count).
4. The Smart Album survives app restart (persisted in SharedPreferences) and is **not** pruned by `albumPinStore.cleanup` (cleanup treats smart ids as valid).
5. Tapping the Smart Album opens an album-detail timeline of exactly its member photos, in engine-rank order; selection/viewer/share work via existing photo cells.
6. Long-press exposes Refresh / Rename / Edit prompt / Delete; Refresh re-runs the prompt and updates members + cover + count.
7. Members no longer on device are dropped gracefully; an empty Smart Album shows a placeholder and can be refreshed later.
8. No regression to normal albums, pinning of normal albums, or full-screen search; CLIP thresholds/merge ranking unchanged.

---

## 11. Test Plan (manual, on-device)

- **Create:** Make a Smart Album "Beach" with prompt "sunset at the beach" → card appears in PINNED with a cover and count == result size.
- **Parity:** Open the smart album; its photos match the top results of the same query in full-screen search (same order).
- **Persistence:** Kill & relaunch app → smart album still present in PINNED (verifies `cleanup` fix).
- **Open/select/share:** Enter smart album, multi-select, share — same behavior as a normal album.
- **Refresh:** Add a new matching photo, Refresh → count/cover update.
- **Delete:** Delete smart album → removed from PINNED and store.
- **Edge:** Prompt with no matches → empty album + toast; device with empty CLIP index in AiOnly mode → graceful empty.