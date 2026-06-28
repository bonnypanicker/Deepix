# Image Viewer — Production-Ready Overhaul

Make the `ViewerActivity` production-grade: fix functional bugs, harden the architecture, and refine the WIN Metro/AMOLED aesthetic to match the polish level of the main gallery.

---

## Current State Analysis

### What Already Works Well
- **Spring-physics swipe-to-dismiss** — natural gesture with velocity-based threshold
- **EXIF panel** with camera info, location geocoding, and tags — slides up with spring animation
- **ExoPlayer video** inline with play/pause/completion flow
- **Shared element transition** from grid thumbnails
- **Delete flow** with scoped-storage-aware `RecoverableSecurityException` handling
- **Auto-hide controls** after 3s timeout

### Functional Bugs & Missing Features

| # | Issue | Severity |
|---|-------|----------|
| 1 | **No pinch-to-zoom on video** — `PlayerView` does not support zoom, but there is no gesture conflict guard either. Downward swipe-to-dismiss fires even during a two-finger zoom on `PhotoView`, causing jumpy behavior. | High |
| 2 | **Loading spinner never hides reliably for images** — `binding.photoView.post {}` fires after the view is laid out, not after Glide finishes decoding. The spinner disappears before the full-res image is ready, or stays if the post runs late. | High |
| 3 | **Swipe-to-dismiss intercepts horizontal pager swiping** — the touch listener is on `viewerRoot` (parent), competing with `ViewPager2`'s internal `RecyclerView`. Quick diagonal swipes get misinterpreted. | Medium |
| 4 | **Info panel reopens on same page** — swiping to the next page then swiping back doesn't re-close the panel if it was left open, because `bindPage()` only resets `infoVisible` without checking if the panel animation completed. | Medium |
| 5 | **No "set as wallpaper" action** — a standard gallery feature that users expect. | Medium |
| 6 | **No image counter / position indicator** — users can't tell which photo they're on or how many exist (e.g. "23 / 147"). | Medium |
| 7 | **Date shown only in info panel** — the top bar shows only the filename; the date (the most useful metadata) is hidden. | Low |
| 8 | **Delete dialog uses stock AlertDialog** — not styled to match the Metro AMOLED design. | Low |
| 9 | **Video duration not shown in info panel** — `locationName` is overloaded with duration string for videos, which is a hack. | Low |
| 10 | **No back gesture animation** — `finish()` is called with no exit transition, breaking the shared-element return. | Medium |

### Architecture Issues

| # | Issue |
|---|-------|
| A1 | **`ViewerItemsHolder` uses `WeakReference`** — items can be GC'd between `store()` and `retrieve()` if memory pressure spikes during the transition. Should use a strong reference with explicit release. |
| A2 | **`MediaPagerAdapter` creates a new `ExoPlayer` per bind** — no pool, no lifecycle awareness. RecyclerView can call `onBindViewHolder` multiple times for the same position during prefetch. |
| A3 | **`onDestroy` cleanup iterates `RecyclerView` children** — misses off-screen holders. Should track active players in the adapter. |
| A4 | **`loadMetadata()` queries MediaStore twice** for images (direct URI then by ID) — the fallback is fine but the first query often fails silently for content:// URIs, wasting IO. |
| A5 | **Geocoder call is synchronous on IO dispatcher** — it does network I/O. Should have a timeout and be wrapped in `withTimeout`. |

---

## Proposed Changes

### Phase 1 — Bug Fixes & Stability

#### [MODIFY] [ViewerActivity.kt](file:///c:/Users/HOME-PC/Documents/Devomind%20Projects/Gallery/Inference%20model/app/src/main/java/com/devomind/gallerysearch/ViewerActivity.kt)

1. **Fix swipe-to-dismiss vs. zoom conflict** — gate `draggingToDismiss` on `event.pointerCount == 1` so multi-touch (pinch-to-zoom) never triggers dismiss.
2. **Fix info panel state on page change** — in `bindPage()`, force-close the panel with direct `translationY` set (skip animation) and reset `infoVisible = false`.
3. **Add `supportFinishAfterTransition()`** — replace `finish()` in the back button handler to enable shared-element return animation.
4. **Add `withTimeout(3000)` around geocoder** — prevent ANR on slow networks.
5. **Fix video metadata** — store duration as a proper field in `PhotoMetadata` instead of overloading `locationName`.

#### [MODIFY] [MediaPagerAdapter.kt](file:///c:/Users/HOME-PC/Documents/Devomind%20Projects/Gallery/Inference%20model/app/src/main/java/com/devomind/gallerysearch/MediaPagerAdapter.kt)

1. **Use Glide listener for spinner dismissal** — replace `post {}` with a `RequestListener` that hides `loadingSpinner` on `onResourceReady` and triggers the shared-element callback.
2. **Track active ExoPlayers** — maintain a `SparseArray<ExoPlayer>` keyed by adapter position. Release in `onViewRecycled` and provide a `releaseAll()` method.
3. **Guard against double-bind** — check if the player for this position already exists before creating a new one.

#### [MODIFY] [ViewerItemsHolder.kt](file:///c:/Users/HOME-PC/Documents/Devomind%20Projects/Gallery/Inference%20model/app/src/main/java/com/devomind/gallerysearch/ViewerItemsHolder.kt)

- Replace `WeakReference` with a strong reference. Add a `release()` call in `ViewerActivity.onDestroy()` as a safety net.

---

### Phase 2 — UI & Metro Polish

#### [MODIFY] [activity_viewer.xml](file:///c:/Users/HOME-PC/Documents/Devomind%20Projects/Gallery/Inference%20model/app/src/main/res/layout/activity_viewer.xml)

1. **Add position counter** — a `TextView` (e.g. "12 / 147") centered in the top bar, `fontFamily="sans-serif-light"`, `textSize="13sp"`, `textColor="#8A8A8A"`.
2. **Add date text below filename** — a second `TextView` in the top bar showing the date, `textSize="12sp"`, `textColor="#6F6F6F"`.
3. **Add bottom gradient** — mirror the top gradient at the bottom to protect the pill controls against bright images.
4. **Widen pill to 260dp** — add a "wallpaper" button (existing set-as icon pattern).
5. **Refine info panel** — add a small drag handle bar (24dp wide, 3dp tall, `#333333` rounded), add a dedicated duration row for videos, and use `FlowLayout` / wrapping for tags instead of horizontal-only `LinearLayout`.

#### [NEW] [viewer_bottom_gradient.xml](file:///c:/Users/HOME-PC/Documents/Devomind%20Projects/Gallery/Inference%20model/app/src/main/res/drawable/viewer_bottom_gradient.xml)

- Inverted gradient: transparent → 80% black, bottom-up.

#### [MODIFY] [ViewerActivity.kt](file:///c:/Users/HOME-PC/Documents/Devomind%20Projects/Gallery/Inference%20model/app/src/main/java/com/devomind/gallerysearch/ViewerActivity.kt) *(Phase 2 additions)*

1. **Bind position counter** — update in `bindPage()` and `onDeleteCompleted()`: `"${position + 1} / ${items.size}"`.
2. **Bind date to top bar** — show the date from `PhotoMetadata` directly in the top bar subtitle (no need to open info panel for basic info).
3. **Add "set as wallpaper"** action — use `WallpaperManager.getCropAndSetWallpaperIntent()` with fallback to `ACTION_ATTACH_DATA`.
4. **Metro-style delete confirmation** — replace `AlertDialog.Builder` with a custom dialog using AMOLED colors (#0A0A0A background, accent buttons, `sans-serif-light` title).
5. **Animate controls with `translationY`** instead of alpha-only — pill slides down 20dp, top bar slides up 20dp, for a more premium feel.

---

### Phase 3 — Video Player Refinement

#### [MODIFY] [item_viewer_page.xml](file:///c:/Users/HOME-PC/Documents/Devomind%20Projects/Gallery/Inference%20model/app/src/main/res/layout/item_viewer_page.xml)

1. **Add a video scrubber** — a minimal `SeekBar` overlay at the bottom of the player view (thin 2dp track, accent-colored thumb, appears only when controls are visible).
2. **Add elapsed / total time labels** — `"0:42 / 3:15"` style, positioned to the left/right of the scrubber.

#### [MODIFY] [MediaPagerAdapter.kt](file:///c:/Users/HOME-PC/Documents/Devomind%20Projects/Gallery/Inference%20model/app/src/main/java/com/devomind/gallerysearch/MediaPagerAdapter.kt) *(Phase 3 additions)*

1. **Bind scrubber to ExoPlayer** — update progress via `Player.Listener.onPlaybackStateChanged` + a periodic `Handler` callback (every 250ms while playing).
2. **Seek on scrubber drag** — call `player.seekTo()`.

#### [MODIFY] [ViewerActivity.kt](file:///c:/Users/HOME-PC/Documents/Devomind%20Projects/Gallery/Inference%20model/app/src/main/java/com/devomind/gallerysearch/ViewerActivity.kt) *(Phase 3 additions)*

1. **Show/hide scrubber with controls** — sync visibility with `setControlsVisible()`.
2. **Don't auto-hide while scrubbing** — cancel the auto-hide runnable when the user is touching the scrubber.

---

## Open Questions

> [!IMPORTANT]
> **Wallpaper action scope** — Should "Set as wallpaper" be available for videos too (using the current frame), or images only?

> [!IMPORTANT]
> **Video scrubber complexity** — Phase 3 adds a custom scrubber for videos. If you'd prefer to keep it simpler (e.g., just the play/pause button, no scrubbing), I can skip Phase 3 entirely and keep video controls minimal.

> [!IMPORTANT]
> **Position counter style** — should the counter be the classic `"12 / 147"` format, or would you prefer dots/a thin progress bar? The classic counter fits the Metro typography style better.

---

## Verification Plan

### Manual Verification
1. **Swipe-to-dismiss**: verify pinch-to-zoom on a photo does NOT trigger dismiss
2. **Loading spinner**: open a high-res photo — spinner should stay visible until the full image renders
3. **Page counter**: swipe through images and confirm counter updates correctly
4. **Delete flow**: delete a photo and verify the counter updates, and the pager advances properly
5. **Shared element return**: tap back and verify the image animates back to its grid thumbnail
6. **Video playback**: verify play/pause, scrubber (if Phase 3), and auto-hide interaction
7. **Info panel**: swipe up to open, drag down to close, verify it resets on page change
8. **Wallpaper**: set a photo as wallpaper via the new button
9. **Metro styling**: verify delete dialog matches AMOLED theme, gradients protect controls on bright images
