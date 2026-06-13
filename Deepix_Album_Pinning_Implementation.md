# Deepix – Album Pinning Feature Implementation Specification

## Current Architecture Observations

- Single RecyclerView: `imageGrid`
- Main screen controlled by `MainActivity`
- Sections:
  - Collection
  - Albums
  - Favorites
  - Videos
- Album UI uses:
  - `GalleryRepository.Album`
  - `GalleryCell.AlbumCell`
  - `ImageAdapter`
- No existing album pinning persistence found.
- Collections screen currently renders through the same RecyclerView and adapter pipeline.

---

# Feature 1: Pin Albums

## User Story

User can:
- Long press or open menu on album
- Select "Pin"
- Album moves to top of Albums page
- Multiple albums can be pinned
- Pinned order preserved
- User can unpin

---

## Persistence Layer

Create:

```kotlin
AlbumPinStore.kt
```

Implementation pattern should mirror `FavoritesStore.kt`.

### API

```kotlin
class AlbumPinStore(context: Context) {

    fun pin(albumId: String)

    fun unpin(albumId: String)

    fun isPinned(albumId: String): Boolean

    fun getPinnedAlbumIds(): List<String>

    fun setPinnedOrder(albumIds: List<String>)
}
```

### Storage

SharedPreferences

```kotlin
private const val PREFS = "album_pins"
private const val KEY_PINNED = "pinned_album_ids"
```

Store as ordered JSON array.

Reason:
- order must survive app restart.

---

# Feature 2: Albums Screen Ordering

Current:

```text
Albums
 ├─ Camera
 ├─ WhatsApp
 ├─ Screenshots
 └─ Downloads
```

New:

```text
Albums
 ├─ 📌 Camera
 ├─ 📌 Downloads
 ├─────────────
 ├─ WhatsApp
 └─ Screenshots
```

## Sorting Logic

Before adapter receives albums:

```kotlin
val pinnedIds = albumPinStore.getPinnedAlbumIds()

val pinnedAlbums =
    albums.filter { it.id in pinnedIds }
          .sortedBy { pinnedIds.indexOf(it.id) }

val normalAlbums =
    albums.filterNot { it.id in pinnedIds }

val finalAlbums =
    pinnedAlbums + normalAlbums
```

Never modify repository output.

Apply only in UI layer.

---

# Feature 3: Collection Page Pinned Albums Strip

## Required Behavior

Collections page gets:

```text
--------------------------------
COLLECTIONS
--------------------------------

[ Camera ]
[ Downloads ]
[ WhatsApp ] ---> horizontal scroll

--------------------------------
Timeline Content
--------------------------------
June 2026
Photos...

May 2026
Photos...
```

Important:

The horizontal pinned albums area behaves as ONE HEADER BLOCK.

When user scrolls:

```text
Pinned Strip
Timeline
```

must move upward together.

No floating.
No sticky.
No independent scrolling vertically.

Only horizontal scrolling inside strip.

---

# Recommended Architecture

DO NOT create second RecyclerView outside main content.

Instead use:

```kotlin
GalleryCell.PinnedAlbumsHeader
```

inside existing RecyclerView.

---

## New Cell Type

```kotlin
sealed class GalleryCell {

    ...

    data class PinnedAlbumsHeader(
        val albums: List<GalleryRepository.Album>
    ) : GalleryCell()
}
```

Benefits:

- scrolls naturally
- becomes part of RecyclerView
- no sync issues
- no nested vertical scrolling

---

# New Layout

Create:

```text
item_pinned_albums_header.xml
```

Structure:

```text
HorizontalScrollView
    LinearLayout
        Album Chip
        Album Chip
        Album Chip
```

OR

```text
RecyclerView(horizontal)
```

Preferred:

```kotlin
RecyclerView.HORIZONTAL
```

Better performance.

---

# Pinned Albums Header Adapter

Create:

```kotlin
PinnedAlbumAdapter.kt
```

ViewHolder:

```kotlin
class PinnedAlbumViewHolder
```

Click:

```kotlin
onAlbumClick(album)
```

Opens album detail exactly like Albums page.

---

# Collection Section Injection

Inside collection rendering pipeline.

Current flow:

```kotlin
cells += timeline headers
cells += photos
```

Change:

```kotlin
if (
    settings.showPinnedAlbumsInCollections &&
    pinnedAlbums.isNotEmpty()
) {
    cells += GalleryCell.PinnedAlbumsHeader(
        pinnedAlbums
    )
}
```

Insert BEFORE first timeline header.

Result:

```text
Pinned Albums Header

June 2026
Photos

May 2026
Photos
```

---

# Feature 4: Settings Toggle

Add setting:

```text
Show pinned albums in Collections
```

Default:

```text
ON
```

---

## Persistence

Create preference:

```kotlin
KEY_SHOW_PINNED_COLLECTIONS
```

inside existing preferences layer.

Example:

```kotlin
var showPinnedAlbumsInCollections: Boolean
```

---

# Settings UI

```text
Collections
--------------------------------
☑ Show pinned albums in Collections
```

When OFF:

```text
Collection page
    ↓
Pinned header not rendered
```

Albums page pinning remains functional.

---

# Feature 5: Album Context Menu

Inside Album ViewHolder.

Add menu:

```text
Pin Album
Unpin Album
```

Logic:

```kotlin
if (albumPinStore.isPinned(album.id))
    show Unpin
else
    show Pin
```

After action:

```kotlin
refreshVisibleItems()
```

or equivalent render method.

---

# Feature 6: Visual Design

Pinned album chip:

```text
┌────────────────┐
│ 📌 Camera      │
└────────────────┘
```

Metro style:

- same typography
- same dark theme
- same album cover thumbnail
- subtle border
- no elevation

---

# Adapter Changes

ImageAdapter.kt

Add:

```kotlin
ViewTypePinnedAlbumsHeader
```

Update:

```kotlin
getItemViewType()
onCreateViewHolder()
onBindViewHolder()
spanSizeAt()
stableIdFor()
```

Span:

```kotlin
totalSpanCount
```

Full width.

---

# Data Flow

```text
Repository
    ↓
Albums
    ↓
AlbumPinStore
    ↓
Pinned + Unpinned Split
    ↓
Albums Screen

and

Collection Screen
    ↓
Inject PinnedAlbumsHeader
    ↓
RecyclerView
```

---

# Edge Cases

## Deleted Album

If album no longer exists:

```kotlin
remove from pinned list
```

during load.

---

## Empty Pinned List

Do not render header.

---

## Setting OFF

Do not render header.

---

## Album Renamed

Pin remains.

Use:

```kotlin
album.id
```

Never album name.

---

## Large Pin Count

Limit:

```kotlin
20 pinned albums
```

recommended.

Horizontal scroll handles overflow.

---

# Implementation Order

1. AlbumPinStore
2. Pin/Unpin menu
3. Albums page sorting
4. Settings preference
5. New GalleryCell.PinnedAlbumsHeader
6. PinnedAlbumAdapter
7. item_pinned_albums_header.xml
8. Inject header into Collection section
9. Edge case cleanup
10. QA testing

This design matches the current Deepix architecture because it keeps pinned albums inside the existing RecyclerView rendering system rather than introducing a second independently scrolling content area.
