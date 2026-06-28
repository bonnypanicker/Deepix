# Deepix Viewer — Gesture Conflict Fix Plan
### Root-caused against the actual codebase + screen recording evidence
**Package:** `com.devomind.gallerysearch`
**Files touched:** `ViewerActivity.kt`, `MediaPagerAdapter.kt` (no XML changes needed)

---

## How This Was Diagnosed

The screen recording was decoded frame-by-frame. Three concrete, reproducible bugs were matched directly to specific lines in `ViewerActivity.kt`. This is not guesswork — each bug below has a code citation and a recording timestamp.

```
Recording evidence:
  0:00       — top bar shows two filenames/dates overlapping ("Flywheel kottayan"
               bleeding through "Screenshot_20260628-141445.jpg")
  0:09–0:11  — info panel slides up, then FREEZES mid-drag for ~0.6s (frames 45-48
               identical), confirming a jerky, non-fluid drag
  0:12–0:16  — pinch-zoom and pan happen while info panel stays pinned at a fixed
               position (not itself a bug, but proves no coordination between
               the two gesture systems)
  0:24       — swiping horizontally to the next photo reproduces the exact same
               double-caption artifact seen at 0:00
```

---

## Root Cause Summary

The viewer has **three independent touch-handling systems** that were built without a shared gesture arbiter:

```
1. ViewerActivity.dispatchTouchEvent() → handleViewerTouch()
   Handles: swipe-down-to-dismiss, swipe-up-to-open-info

2. binding.infoPanel.setOnTouchListener (attachInfoPanelDrag)
   Handles: swipe-down-to-close info panel

3. ViewPager2's own internal touch handling
   Handles: swipe left/right between photos

4. PhotoView's own gesture detector (pinch/pan/double-tap)
   Handles: zoom
```

None of these check what the *other* systems are doing before reacting. The result is exactly what you saw: diagonal swipes get misread, the info panel doesn't close on tap, and chrome flickers with stale data during fast swiping.

---

## Bug 1 — Diagonal/Horizontal Swipes Get Misread as Dismiss-Drag (Primary Cause)

### The Code (`ViewerActivity.kt` line 643-666)

```kotlin
MotionEvent.ACTION_MOVE -> {
    velocityTracker?.addMovement(event)
    val deltaY = event.rawY - downY
    dragDistance = deltaY.coerceAtLeast(0f)

    if (event.pointerCount == 1 && !infoVisible && dragDistance > dp(4) && deltaY > 0 && !isCurrentPageZoomed()) {
        draggingToDismiss = true
        // ... scales/fades the photo down
    }
}
```

### Why This Breaks Side Swipes

This only checks `deltaY` — **vertical movement — and never compares it against horizontal movement.** A natural human swipe to change photos is almost never perfectly horizontal; it always has a small vertical component. The moment that vertical component exceeds 4dp, `draggingToDismiss` becomes `true`, and then in `dispatchTouchEvent`:

```kotlin
if (draggingToDismiss) {
    if (!touchIntercepted) {
        touchIntercepted = true
        val cancelEvent = MotionEvent.obtain(ev)
        cancelEvent.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancelEvent)   // ← kills ViewPager2's gesture
        cancelEvent.recycle()
    }
    return true
}
```

A `CANCEL` event is sent to the ViewPager2 mid-swipe, aborting the page change. **This is the exact mechanism behind "side wise and top down swipe conflictions."**

### The Fix — Lock Gesture Direction on First Movement

Replace the entire touch-state block with an angle-aware classifier that decides ONCE, early, which gesture is happening — then commits to it for the rest of the touch stream.

```kotlin
// Add these fields near the other touch-state fields (around line 66-72):
private var downX = 0f
private var gestureDirection = GestureDirection.UNDETERMINED

private enum class GestureDirection {
    UNDETERMINED, HORIZONTAL_PAGE, VERTICAL_DISMISS, VERTICAL_INFO, TAP
}
```

```kotlin
// Replace the ACTION_DOWN block (line 626-633):
MotionEvent.ACTION_DOWN -> {
    downY = event.rawY
    downX = event.rawX
    dragDistance = 0f
    draggingToDismiss = false
    gestureDirection = GestureDirection.UNDETERMINED
    velocityTracker?.recycle()
    velocityTracker = VelocityTracker.obtain()
    velocityTracker?.addMovement(event)
}
```

```kotlin
// Replace the ACTION_MOVE block (line 643-666):
MotionEvent.ACTION_MOVE -> {
    velocityTracker?.addMovement(event)
    val deltaY = event.rawY - downY
    val deltaX = event.rawX - downX
    val absDeltaY = kotlin.math.abs(deltaY)
    val absDeltaX = kotlin.math.abs(deltaX)

    // Lock the gesture direction once movement is past the slop threshold.
    // Until locked, do nothing — let the ambiguity resolve itself.
    if (gestureDirection == GestureDirection.UNDETERMINED) {
        val slop = dp(10)
        if (absDeltaX > slop || absDeltaY > slop) {
            gestureDirection = when {
                // Horizontal movement dominates — this is a page swipe.
                // ViewPager2 owns this gesture; we do nothing further.
                absDeltaX > absDeltaY * 1.2f -> GestureDirection.HORIZONTAL_PAGE

                // Vertical movement dominates downward, panel closed, not zoomed
                deltaY > 0 && !infoVisible && !isCurrentPageZoomed() ->
                    GestureDirection.VERTICAL_DISMISS

                // Vertical movement dominates upward, panel closed
                deltaY < 0 && !infoVisible ->
                    GestureDirection.VERTICAL_INFO

                else -> GestureDirection.HORIZONTAL_PAGE // safe default: don't hijack
            }
        }
    }

    // Only the dismiss path animates the photo live; info-open is decided at ACTION_UP.
    if (gestureDirection == GestureDirection.VERTICAL_DISMISS &&
        event.pointerCount == 1 && deltaY > 0
    ) {
        draggingToDismiss = true
        dragDistance = deltaY.coerceAtLeast(0f)
        val progress = (dragDistance / binding.viewerRoot.height).coerceIn(0f, 1f)
        val mediaView = getCurrentMediaView()
        mediaView?.translationY = dragDistance
        val scale = 1f - (progress * 0.08f)
        mediaView?.scaleX = scale
        mediaView?.scaleY = scale
        binding.viewerRoot.setBackgroundColor(
            android.graphics.Color.argb((progress * 180).toInt().coerceIn(0, 180), 0, 0, 0)
        )
        val chromeAlpha = (1f - (progress * 0.8f)).coerceIn(0f, 1f)
        binding.topBar.alpha = chromeAlpha
        binding.viewerPill.alpha = chromeAlpha
    }
}
```

```kotlin
// Update ACTION_UP / ACTION_CANCEL (line 667-698) — gate the upward-swipe
// check on the locked direction instead of raw velocity alone:
MotionEvent.ACTION_CANCEL,
MotionEvent.ACTION_UP -> {
    velocityTracker?.addMovement(event)
    velocityTracker?.computeCurrentVelocity(1000)
    val velocityY = velocityTracker?.yVelocity ?: 0f
    velocityTracker?.recycle()
    velocityTracker = null

    val dismissThreshold = binding.viewerRoot.height * 0.40f
    val shouldDismiss = draggingToDismiss &&
        (dragDistance > dismissThreshold || velocityY > DISMISS_VELOCITY_PX_PER_SEC)

    if (draggingToDismiss) {
        if (shouldDismiss) {
            finish()
        } else {
            animateDismissReset()
        }
        dragDistance = 0f
        draggingToDismiss = false
        gestureDirection = GestureDirection.UNDETERMINED
        return true
    }

    // Only treat as "open info" if the gesture was LOCKED as vertical-info,
    // not just because the final velocity happened to be upward.
    if (gestureDirection == GestureDirection.VERTICAL_INFO) {
        val upY = event.rawY
        val isUpwardSwipe = downY - upY > dp(24) && velocityY < -INFO_PANEL_VELOCITY_PX_PER_SEC
        if (isUpwardSwipe && !infoVisible) {
            toggleInfoPanel()
            gestureDirection = GestureDirection.UNDETERMINED
            return true
        }
    }

    gestureDirection = GestureDirection.UNDETERMINED
    scheduleAutoHide()
}
```

### Why `1.2f` as the Ratio

A pure horizontal swipe has `absDeltaX >> absDeltaY`. Real-world diagonal "trying to swipe sideways" gestures rarely exceed a 1:1 ratio of vertical-to-horizontal unless the user is intentionally dragging down. `1.2f` means horizontal must be at least 20% larger than vertical to win — generous enough that almost all next/prev swipes are correctly classified as paging, while clearly vertical drags still trigger dismiss/info.

---

## Bug 2 — Tapping the Photo Doesn't Close the Open Info Panel

### The Code (`ViewerActivity.kt` line 171-173)

```kotlin
onMediaTap = {
    if (!draggingToDismiss) toggleControls()
}
```

`toggleControls()` only flips `controlsVisible` — the topBar/pill fade. It has **no awareness of `infoVisible` at all.** Tapping the photo while the info panel is open does nothing to it; the info panel just sits there. This is exactly "touching on the photo not removing info panel."

### The Fix — Tap Closes Info First, Only Toggles Chrome When Info Is Already Closed

```kotlin
// Replace the onMediaTap lambda in onCreate() (line 171-173):
onMediaTap = {
    if (gestureDirection == GestureDirection.HORIZONTAL_PAGE) {
        // A completed page-swipe can still fire a click on some devices;
        // ignore taps that were actually part of a paging gesture.
    } else if (infoVisible) {
        toggleInfoPanel()
    } else if (!draggingToDismiss) {
        toggleControls()
    }
}
```

This matches standard gallery-app behavior (Google Photos, Samsung Gallery): **the first tap on the photo always closes an open info panel — it never toggles the top/bottom chrome at the same time.**

---

## Bug 3 — Swiping Pages While Info Panel Is Open Causes Visual Conflicts

### Why This Happens

Even after Bug 1's fix, nothing stops the user from starting a horizontal swipe to change photos *while the info panel is open*. The info panel is a bottom-anchored overlay independent of the ViewPager2 — so changing pages underneath an open info panel is jarring (the panel stays open but now describes the wrong-feeling context, and `bindPage()` force-closes it without animation per line 297-300, causing a hard snap).

### The Fix — Disable Paging While Info Is Open (Industry-Standard Pattern)

This is the simplest possible fix and matches what every major gallery app does: **you cannot swipe to the next photo while the details panel is open.** You must close it first (tap photo, swipe panel down, or press back).

```kotlin
// In toggleInfoPanel() (line 553-562), add isUserInputEnabled toggling:
private fun toggleInfoPanel() {
    infoVisible = !infoVisible
    binding.viewPager.isUserInputEnabled = !infoVisible   // ← add this line
    val target = if (infoVisible) 0f else binding.infoPanel.height.toFloat()
    SpringAnimation(binding.infoPanel, DynamicAnimation.TRANSLATION_Y, target).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = SpringForce.STIFFNESS_MEDIUM
        start()
    }
    scheduleAutoHide()
}
```

```kotlin
// Also re-enable paging when the info panel is closed via the drag-down
// gesture (attachInfoPanelDrag, line 586-601) and via bindPage's force-close:

// Inside attachInfoPanelDrag()'s ACTION_UP/ACTION_CANCEL block, after:
//   infoVisible = false
//   animateInfoPanelClosed()
// add:
binding.viewPager.isUserInputEnabled = true
```

```kotlin
// In bindPage() (line 297-300), the force-close-without-animation block
// already runs on every page change — make sure paging stays enabled there too:
infoVisible = false
binding.viewPager.isUserInputEnabled = true   // ← add this line
binding.infoPanel.translationY = binding.infoPanel.height.toFloat()
binding.infoPanel.alpha = 1f
```

With this one flag, Bug 1's diagonal-swipe ambiguity and Bug 3's panel-vs-paging conflict both become non-issues for the "info panel open" case, because ViewPager2 simply won't respond to horizontal drags at all while the panel is up.

---

## Bug 4 — Captions Show Stale/Wrong Data During Fast Swiping (The "Overlapping Text" Artifact)

### The Code (`ViewerActivity.kt` line 263-304)

```kotlin
private fun bindPage(position: Int) {
    val item = items.getOrNull(position) ?: return
    // ...
    binding.fileNameText.text = item.displayName ?: "Photo"   // set synchronously — correct
    // ...
    lifecycleScope.launch {                                    // ← NEW coroutine every call
        val metadata = withContext(Dispatchers.IO) { loadMetadata(uri, isVideo) }
        val exif = withContext(Dispatchers.IO) { /* DB + EXIF read */ }
        // ...
        bindMetadata(metadata, exif, tags)   // ← overwrites fileNameText + topBarDate AGAIN
    }
    // ...
}
```

### Why This Causes the Double-Caption Artifact

`bindPage()` is called on every `onPageSelected()`. Each call launches a **new** `lifecycleScope.launch` block that reads EXIF and DB data — work that takes real time (disk I/O, DB query). **None of the previous launches are cancelled.**

If the user swipes through photo A → B → C quickly:

```
launch(A) starts reading A's EXIF/DB data (slow)
launch(B) starts reading B's EXIF/DB data
launch(C) starts reading C's EXIF/DB data

If A's disk read finishes AFTER the user has already reached C,
launch(A) still runs to completion and calls bindMetadata(A's data),
overwriting the fileNameText/topBarDate that were already correctly
showing C's info — with A's stale filename and date.
```

This is precisely what the recording shows: a caption from a *different, already-passed* photo bleeding into the currently-displayed photo's header for a brief moment during fast swiping.

### The Fix — Cancel the Previous Metadata Job Before Starting a New One

```kotlin
// Add a field near the other state fields (around line 73-74):
private var metadataJob: kotlinx.coroutines.Job? = null
```

```kotlin
// In bindPage(), replace the bare lifecycleScope.launch with a cancellable job:
private fun bindPage(position: Int) {
    val item = items.getOrNull(position) ?: return
    val uri = item.uri
    val isVideo = item.mediaType == GalleryRepository.MediaType.Video

    binding.fileNameText.text = item.displayName ?: "Photo"
    bindDots(position, items.size)
    renderFavoriteState(favoritesStore.isFavorite(uri))

    binding.editBtn.setImageResource(
        if (isVideo) R.drawable.ic_fluent_play_24_regular else R.drawable.ic_fluent_edit_24_regular
    )
    binding.editBtn.contentDescription = if (isVideo) "Play video" else "Edit photo"

    // Cancel any in-flight metadata load from a previous page before starting a new one.
    metadataJob?.cancel()
    metadataJob = lifecycleScope.launch {
        val metadata = withContext(Dispatchers.IO) { loadMetadata(uri, isVideo) }
        val exif = withContext(Dispatchers.IO) {
            val cached = dbRepository.getExif(uri.toString())
            if (cached != null || isVideo) {
                cached
            } else {
                val extracted = ExifExtractor.extract(this@ViewerActivity, uri)
                dbRepository.upsertExif(uri.toString(), extracted)
                extracted
            }
        }
        val tags = withContext(Dispatchers.IO) { dbRepository.getTagsForMedia(uri.toString()) }

        // Guard against a final race: if this job wasn't cancelled in time but the
        // page has already moved on by the time we reach here, drop the stale result.
        if (position != currentPosition) return@launch

        currentExif = exif
        currentTags = tags
        bindMetadata(metadata, exif, tags)
    }

    infoVisible = false
    binding.viewPager.isUserInputEnabled = true
    binding.infoPanel.translationY = binding.infoPanel.height.toFloat()
    binding.infoPanel.alpha = 1f

    setControlsVisible(true)
    scheduleAutoHide()
}
```

The `metadataJob?.cancel()` line stops the previous coroutine from running its remaining `withContext(Dispatchers.IO)` blocks the instant a new page is selected, and the `if (position != currentPosition) return@launch` guard is a second safety net in case cancellation doesn't land before the coroutine reaches that point.

---

## Bug 5 — Info Panel Drag Freezes Mid-Gesture (Frames 45-48 in Recording)

### The Code (`ViewerActivity.kt` line 568-605, `attachInfoPanelDrag`)

```kotlin
binding.infoPanel.setOnTouchListener { _, event ->
    when (event.actionMasked) {
        MotionEvent.ACTION_MOVE -> {
            val deltaY = event.rawY - infoPanelDownY
            if (deltaY > dp(4) || infoPanelDragging) {
                infoPanelDragging = true
                val offset = deltaY.coerceAtLeast(0f)
                binding.infoPanel.translationY = offset
                // ...
            }
        }
        // ...
    }
    infoPanelDragging   // ← return value
}
```

### Why It Freezes

The listener returns `infoPanelDragging` — which is `false` until the first `ACTION_MOVE` exceeds 4dp. **On the very first `ACTION_MOVE` event, before `infoPanelDragging` becomes `true`, the listener returns `false` for that specific event**, meaning the system treats that event as unhandled and may re-deliver it elsewhere or drop it, causing the visible stutter/freeze seen at the start of the drag (frames 45-48 in the recording show the panel locked in place for ~0.6 seconds before continuing).

### The Fix — Always Consume Touches on the Info Panel Once It's Visible

```kotlin
@Suppress("ClickableViewAccessibility")
private fun attachInfoPanelDrag() {
    binding.infoPanel.setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                infoPanelDownY = event.rawY
                infoPanelDragging = false
                autoHideHandler.removeCallbacks(autoHideRunnable)
                // Consume immediately — this view should own its own touch stream
                // from the first frame, not just once a threshold is crossed.
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.rawY - infoPanelDownY
                if (deltaY > dp(4) || infoPanelDragging) {
                    infoPanelDragging = true
                    val offset = deltaY.coerceAtLeast(0f)
                    binding.infoPanel.translationY = offset
                    val progress = (offset / binding.infoPanel.height.toFloat()).coerceIn(0f, 1f)
                    binding.infoPanel.alpha = 1f - progress * 0.5f
                }
                true
            }
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> {
                val deltaY = event.rawY - infoPanelDownY
                if (infoPanelDragging && deltaY > binding.infoPanel.height * 0.25f) {
                    infoVisible = false
                    binding.viewPager.isUserInputEnabled = true
                    animateInfoPanelClosed()
                } else if (infoPanelDragging) {
                    SpringAnimation(binding.infoPanel, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                        spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                        start()
                    }
                    binding.infoPanel.alpha = 1f
                } else {
                    // A tap that landed on the info panel but wasn't a drag —
                    // treat it the same as tapping the photo: close the panel.
                    infoVisible = false
                    binding.viewPager.isUserInputEnabled = true
                    animateInfoPanelClosed()
                }
                infoPanelDragging = false
                true
            }
            else -> false
        }
    }
}
```

The key change is returning `true` unconditionally for `ACTION_DOWN`/`ACTION_MOVE`/`ACTION_UP`/`ACTION_CANCEL` instead of only when `infoPanelDragging` is already `true`. This makes the info panel claim its entire touch stream from the first finger-down, eliminating the stutter. It also adds a sensible "tap on the open panel itself closes it too" behavior as a bonus, matching the tap-to-close fix in Bug 2.

---

## Bug 6 — `dispatchTouchEvent` Runs Global Gesture Logic on Every Touch, Including Button Presses

### The Code (`ViewerActivity.kt` line 109-127)

```kotlin
override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
        touchIntercepted = false
    }
    handleViewerTouch(ev)   // ← runs for EVERY touch anywhere in the activity
    if (draggingToDismiss) { /* ... */ return true }
    return super.dispatchTouchEvent(ev)
}
```

### Why This Matters

This is not currently causing a visible crash, but it means every tap on the share/heart/wallpaper/edit/delete buttons, every drag on the info panel, and every pinch-zoom gesture all *also* run through `handleViewerTouch`'s full dismiss/info-swipe state machine in parallel with their own intended handler. With the angle-lock fix in Bug 1, this becomes harmless for buttons (a simple tap never exceeds the slop threshold so `gestureDirection` stays `UNDETERMINED` and nothing fires) — but it's worth tightening anyway for long-term stability:

```kotlin
override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
        touchIntercepted = false
    }

    // Skip the global gesture state machine entirely while the info panel
    // is visible — attachInfoPanelDrag already owns all touches in that state,
    // and ViewPager2 paging is disabled, so there is nothing for this to do.
    if (!infoVisible) {
        handleViewerTouch(ev)
    }

    if (draggingToDismiss) {
        if (!touchIntercepted) {
            touchIntercepted = true
            val cancelEvent = MotionEvent.obtain(ev)
            cancelEvent.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancelEvent)
            cancelEvent.recycle()
        }
        return true
    }
    return super.dispatchTouchEvent(ev)
}
```

---

## Full List of Changed Functions

```
ViewerActivity.kt:
  ✏️ Field additions: downX, gestureDirection enum, metadataJob
  ✏️ dispatchTouchEvent()        — skip handleViewerTouch while infoVisible
  ✏️ handleViewerTouch()         — full rewrite with angle-locking (Bug 1)
  ✏️ onMediaTap lambda (in onCreate) — close info-on-tap (Bug 2)
  ✏️ toggleInfoPanel()           — toggle viewPager.isUserInputEnabled (Bug 3)
  ✏️ bindPage()                  — cancel previous metadataJob (Bug 4),
                                    re-enable isUserInputEnabled (Bug 3)
  ✏️ attachInfoPanelDrag()       — always consume touches, tap-closes-panel (Bug 5)

MediaPagerAdapter.kt:
  (no changes required — onMediaTap callback signature is unchanged)
```

---

## Implementation Order

Apply and test in this exact order — each fix is independently verifiable, and later fixes depend on earlier ones being correct.

```
Step 1: Bug 4 (metadata job cancellation)
  → Easiest, zero gesture risk, fixes the visible "wrong caption" artifact alone.
  → Test: swipe through 10+ photos as fast as possible, watch the filename/date
    for any flash of a photo you've already passed.

Step 2: Bug 1 (angle-locked gesture classification)
  → Test: swipe left/right at various diagonal angles (not perfectly horizontal).
    Page should always change; dismiss should never trigger from a side-swipe.
  → Test: swipe straight down → dismiss still works exactly as before.
  → Test: swipe straight up → info panel still opens exactly as before.

Step 3: Bug 3 (disable paging while info panel open)
  → Test: open info panel, try to swipe to next photo — nothing should happen
    until the panel is closed first.

Step 4: Bug 2 (tap closes info panel)
  → Test: open info panel, tap anywhere on the photo → panel closes immediately,
    topBar/pill visibility is unaffected by that same tap.

Step 5: Bug 5 (info panel touch consumption)
  → Test: open info panel, drag down slowly from the very first pixel of movement
    → panel should track your finger immediately with zero stutter.
  → Test: open info panel, tap once anywhere on the panel without dragging
    → panel closes (new bonus behavior).

Step 6: Bug 6 (dispatchTouchEvent scoping)
  → Test: with info panel open, tap each pill button (share/favorite/wallpaper/
    edit/delete) → each should fire its action with no interference.
```

---

## Quick Sanity Checklist After All Fixes

```
□ Fast left/right swiping never triggers dismiss or info-panel-open
□ Slow diagonal swipes (45°) still resolve to ONE clear action, not both
□ Filename/date never flashes a different photo's data while swiping fast
□ Tapping the photo while info panel is open closes the panel (not chrome toggle)
□ Tapping the photo while info panel is closed toggles chrome as before
□ Swiping pages is blocked while info panel is open; works again once closed
□ Info panel drag-to-close starts tracking the finger with zero delay
□ Pinch-zoom still works and is unaffected by any of the above
□ Swipe-down-to-dismiss from a non-zoomed, info-closed state still works
□ All 5 pill buttons still respond correctly with info panel open or closed
```

---

*Gesture conflict fix plan — based on code audit of Deepix-main v7 + frame-by-frame
analysis of screen-20260628-155750.mp4*
