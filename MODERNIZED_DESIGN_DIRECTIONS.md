# Modernized Windows Phone Photos — Design Directions

> Same soul. Sharper edges. Deeper black.
> This document defines the visual and interaction design direction for a modernized
> Android gallery app rooted in the Metro design philosophy — updated for 2025.

---

## The Philosophy, Restated

Metro's original promise was radical: **the interface disappears and the content remains.**
Every chrome element — toolbars, overlays, controls — exists only when it must, then vanishes.

The modernization doesn't break that contract. It deepens it.

Three additions to the original doctrine:

1. **AMOLED as the material.** True black (#000000) is not a dark mode. It is the substrate.
   Photos don't sit *on* the screen. They *are* the screen.

2. **Motion as meaning.** The original app had snap transitions. The modern version uses
   physics — spring, momentum, resistance — so the interface feels like it has mass.

3. **Typography with more contrast.** Headers get bigger. Body text gets smaller.
   The gap between them widens. Hierarchy sharpens.

Everything else stays. No rounded photo tiles. No gradients on text. No cards.
Flat geometry. Content over chrome.

---

## 1. Color System

### 1.1 The AMOLED Foundation

```
Pure Black         #000000    The canvas. Used everywhere backgrounds exist.
Surface            #0A0A0A    Drawers, sheets, overlays — just barely lifted.
Elevated           #111111    Pressed states, focused inputs, modals.
Border / Hairline  #1C1C1C    0.5px separators. Barely visible. Just felt.
```

These four steps span only 17 units of lightness. The darkness is intentional — any
brighter and photos stop owning the screen.

### 1.2 Text Hierarchy

```
Primary Text       #FFFFFF    Display headings, active labels
Secondary Text     #8A8A8A    Sub-labels, counts, metadata
Tertiary Text      #484848    Placeholder text, disabled states
```

Text never uses opacity. Colors are solid. Opacity-based text on AMOLED creates
gray halos — use hex values directly.

### 1.3 The Accent

The original Windows Blue (#0078D4) is too corporate for a photography app.
The modernized accent shifts toward a warmer, slightly less saturated blue that
photographs better against dark media content:

```
Accent Primary     #3B9EFF    Active elements, selection fills, focused states
Accent Subtle      #1A3D5C    Backgrounds behind accented UI (sheets, badges)
Accent On-Surface  #6DB8FF    Accent text on dark backgrounds
```

Use accent sparingly. Its job is to indicate *state* (selected, active), not decorate.
A page with no accent visible is a success.

### 1.4 Contextual Colors

```
Destructive        #FF4444    Delete confirmations only
Success            #34C759    Favorited confirmation flash
Video Badge Fill   #000000 at 65% opacity over photo
Selection Scrim    #000000 at 50% opacity over photo
Gradient Start     #000000 at 80%   (top bar top edge)
Gradient End       #00000000         (top bar bottom edge)
```

### 1.5 What Changes from the Original

| Element              | Original         | Modernized         |
|----------------------|------------------|--------------------|
| Background           | #000000          | #000000 (same)     |
| Surface              | #1A1A1A          | #0A0A0A (darker)   |
| Accent               | #0078D4          | #3B9EFF (warmer)   |
| Secondary text       | #ABABAB          | #8A8A8A (dimmer)   |
| Divider              | #2A2A2A 1px      | #1C1C1C 0.5px      |
| Overlay              | #99000000        | Gradient (not flat)|

---

## 2. Typography

### 2.1 The Typeface

**Segoe UI Variable** (or the closest available substitute) remains the face.
In the modernized system, weight contrast increases significantly.

The key principle: **one screen, two weights maximum.**
Headlines are light (300). Everything else is regular (400). Never mix three weights.

```
Display / Month Header     Light 300     40sp     letter-spacing: -0.5px
Section Sub-label          Regular 400   13sp     letter-spacing: +0.8px  ALL CAPS
Album / Folder Name        Regular 400   16sp     letter-spacing: 0
App Title in Drawer        Light 300     26sp
Metadata / Counts          Regular 400   12sp
Bottom Bar Labels          (none)        —        icon-only, no labels
Viewer Filename            Regular 400   13sp     letter-spacing: +0.2px
Info Sheet Label           Regular 400   14sp
Info Sheet Value           Regular 400   13sp     #8A8A8A
Selection Counter          Light 300     24sp
Fast Scroll Bubble         Light 300     11sp     ALL CAPS
```

### 2.2 The Big Change: Month Headers

The original app used 28sp for month headers. The modern version pushes to **40sp**.

This is not decoration — it is spatial orientation. When the user scrolls rapidly
through months, the enormous text acts as a landmark. They feel *where* they are
before they read it.

The sub-label below (e.g., "SAT, 14") drops in size to 13sp and uses ALL CAPS +
slightly expanded tracking to create maximum contrast against the 40sp header above.

```
January 2024          ← 40sp, Light 300, white
SAT, 14               ← 13sp, Regular 400, #8A8A8A, ALL CAPS, +0.8px tracking
```

This extreme size difference — 40sp vs 13sp — is deliberate Metro thinking:
hierarchy through contrast, not through color.

### 2.3 What Is Removed

- No bold weight anywhere in the UI (600+)
- No italic
- No underline
- No colored text except accent-state labels

---

## 3. Grid & Layout

### 3.1 The Photo Grid

The original 3-column grid is kept. The gutter stays tight (2dp).
No change here — the geometry was already correct.

What changes is **rhythm**. The original app used a mechanical span pattern (every Nth
item spans 2 columns). The modern version uses the **first photo in each date group**
as the featured tile — it spans 2 columns, full height, on the left. The remaining
photos of that day fill in to the right and below in 1-column cells.

```
┌──────────────────┬────────┐
│                  │  [02]  │   ← [01] is the first photo of the group → 2×2 featured
│      [01]        ├────────┤
│   2-col wide     │  [03]  │
├────────┬─────────┴────────┤
│  [04]  │  [05]  │  [06]  │   ← regular 1-col row
└────────┴─────────┴────────┘
```

This is more intentional than the original. The first photo of each day becomes the
*cover* of that day — a visual anchor before the grid continues.

### 3.2 Spacing Philosophy

The grid has 2dp gutters. Everything outside the grid breathes.

```
Header top padding        20dp
Header left padding       16dp
Header bottom padding     12dp
Screen left/right margin  0    (grid is edge-to-edge)
Bottom of grid padding    nav bar height + 16dp
```

No padding inside the grid. Photos touch. AMOLED black between tiles reads as
intentional separation — the black is the gutter material.

### 3.3 Albums Grid

2 columns. Same 2dp gutter. Cover image is 4:3 ratio (not square) — this shows
more of the photo and feels more like a film strip than a tile.

Album name sits below the image, left-aligned, 16sp Regular.
Count sits below the name, 12sp, #8A8A8A.

No card borders. No elevation shadows. The image is the card.

### 3.4 Folders List

Unchanged in concept — list layout. The thumbnail changes from 64dp square to
**56dp square**, and the row height tightens from 72dp to **64dp**.

Less vertical breathing room makes the list feel denser and more scannable —
Metro never wasted space on lists.

---

## 4. Navigation & Chrome

### 4.1 The Drawer Stays, But Changes Shape

The navigation drawer is retained. But the header changes:

**Original**: "Photos" as a text label at the top of the drawer.

**Modern**: No header. The drawer opens directly to navigation items.
The app title "Photos" is removed from the drawer entirely — the user knows
what app they're in. The space is returned to the navigation items.

Drawer items get more generous vertical spacing (60dp row height vs 52dp) and
the active indicator bar grows from 4dp × 32dp to **3dp × 40dp** — slimmer but taller.

### 4.2 Toolbar Treatment

The toolbar becomes even more invisible.

**Collection screen**: No toolbar title. The hamburger icon sits at top-left,
20% opacity by default, rising to 100% only when the user's thumb approaches it
(proximity detection via MotionEvent, not just scroll position). This makes the
icon feel recessed into the background rather than permanently present.

**All other screens**: Toolbar shows destination name as a 26sp Light header,
flush left, with 24dp left margin. No center-aligned titles. The text starts where
text should start.

### 4.3 System Bars

Status bar: transparent. Always. Content bleeds behind it.
Navigation bar: #000000. Matches the canvas.

In the photo viewer, both bars become transparent and content fills the entire screen.
The bottom edge of the screen is empty black — not a bar.

---

## 5. Photo Viewer Design

### 5.1 The Viewer is a Black Room

When a photo opens, the transition is a spatial zoom — the thumbnail expands
from its grid position to fill the screen. During the transition, the rest of the
collection fades to black in 200ms.

The viewer frame is pure black. Nothing competes with the photo.

### 5.2 Controls

The top and bottom bars follow the same logic as the original — auto-hide after 3 seconds.

**What changes:**

The bottom bar is no longer a full-width strip. It becomes a **floating pill** —
a semi-transparent capsule that hovers above the bottom edge, center-aligned,
containing only the most essential actions.

```
Pill width: 220dp
Pill height: 52dp
Pill background: #0A0A0A at 85%, blur radius 20dp (frosted dark glass)
Pill border: #1C1C1C at 100%, 0.5px
Pill corner radius: 26dp (fully round ends)
Pill bottom margin: 24dp + nav bar height
Contents: [Share]  [♡]  [Edit]  [Delete]
```

The ⋮ overflow button moves to the top bar (already there) and is removed from
the pill. Four actions, cleanly spaced inside a floating capsule.

This is the single biggest visual departure from the original. The pill feels modern.
It signals that the bar is *floating over* the photo, not *replacing* part of the screen.

### 5.3 The Info Panel

Swipe up on a photo to reveal the info panel from the bottom. The panel lifts
with the finger gesture — it follows touch position before snapping to its resting height.

Panel design:
- Background: #0A0A0A, top corners rounded to 20dp
- No drag handle visual (the gesture teaches itself)
- Date shown large: 22sp Light, white — this is the most important metadata
- Other fields (resolution, size, type) in 13sp Regular, #8A8A8A
- Location (if available): shown as city name only, 14sp, white, with a
  minimal location dot icon (#3B9EFF) to the left

The panel is **not a list of labeled rows**. It's structured prose:

```
Saturday, January 14, 2024

4128 × 3096  ·  4.2 MB  ·  JPEG

📍 Kollam, Kerala
```

All on three lines. No icon-per-row. No label/value pairs. The information
reads like a caption, not a table.

---

## 6. Selection Mode

### 6.1 Entry

Long press. Haptic. No animation delay — the selection circle appears instantly on
the long-pressed item. All other items fade to 60% opacity in 150ms to signal
they are *available to select*, not already selected.

This is subtler than the original. Rather than showing empty circles on every item,
dimming communicates "these can be selected" without visual noise.

### 6.2 Checkmark Design

The selection circle modernizes:

- **Unselected**: No visible circle. Just the dim state. Clean.
- **Selected**: A filled #3B9EFF circle (24dp) appears at the top-right corner
  with a white checkmark. The photo returns to full opacity to contrast
  with the surrounding dimmed items.

The visual message: selected photos *light up* against a dimmed field.
The original showed circles everywhere. The modern version shows only presence.

### 6.3 Toolbar in Selection

The hamburger transitions to a back arrow. The toolbar reads:

```
[←]   3 selected
```

That's it. No "Select all" text link in the toolbar. "Select all" moves to the
overflow menu (⋮) in the top-right. Cleaner.

### 6.4 Action Bar in Selection

The floating pill (from the viewer) concept carries here too.
Selection mode shows a **two-button pill** at the bottom:

```
[ Share ]  [ Delete ]
```

Same pill styling as the viewer bar. Width: 180dp. The consistency between
viewer mode and selection mode reinforces the design system.

---

## 7. Animations & Motion

### 7.1 Spring Physics Model

All UI transitions use spring physics, not duration curves. The feel target is:

```
Stiffness: 400
Damping:   0.8
```

This produces fast, slightly elastic motion — items overshoot by ~3–5% then
settle. It feels responsive without being bouncy.

Duration-based animations remain only for:
- Fade in / fade out (200ms, linear — no spring needed)
- Loading placeholders (shimmer, 1200ms loop)

### 7.2 Grid → Viewer Transition

The photo expands from its grid cell to fill the screen.
Simultaneously, all surrounding photos scale down very slightly (to 0.96)
and fade to 0 over 200ms. The expanding photo is the only thing moving.

On return: the reverse — photo contracts back to its grid position,
surrounding photos scale back to 1.0 and fade in. The grid reappears
as if the photo was always there.

### 7.3 Page Turns in Viewer

Horizontal swipe between photos. No page transformer applied —
the default ViewPager2 slide is correct.

Velocity matters: a fast fling snaps to the next photo immediately.
A slow swipe shows resistance past 30% of the page width and requires
a deliberate flick to commit.

### 7.4 Header Parallax

As the user scrolls the collection, month headers scroll at **0.6× the speed**
of the photo grid. The header lags slightly, creating a subtle depth layer.

The effect is gentle — barely perceptible during normal scrolling, but
noticed and appreciated when the user pauses and reverses direction.

### 7.5 Scroll Momentum in Viewer

When the user swipes to close the viewer (downward swipe), the photo
follows the finger. At 40% of screen height displacement, it snaps back
or dismisses depending on velocity. Low velocity → snap back with spring.
High velocity → dismiss and return to grid.

This gesture is not in the original app. It feels natural for 2025
full-gesture navigation.

### 7.6 Entrance Animation

The collection screen's first load: photos appear in a vertical wave,
staggered by row (not by individual item). All photos in row 1 appear
together, then row 2, then row 3. Stagger between rows: 30ms.
Each row: fade from 0 to 1 + translate from +16dp to 0. Duration 220ms.

This feels faster than item-by-item stagger and more intentional.

---

## 8. Iconography

Use **Fluent UI System Icons** exclusively, in the `Regular` (line) style.
Do not mix icon styles. Do not use filled icons except for the active
favorite state (heart filled = favorited).

Icon color: always #FFFFFF at 100% on dark backgrounds.
Never tint icons to match accent color except the filled-heart state
(which uses #FF6B8A — a warm red, not the accent blue).

Icon size across the app is consistent at **24dp** in all contexts.
The pill bar uses 22dp for a tighter visual fit inside the capsule.

---

## 9. Empty States

Empty states follow the same minimal philosophy:

A single Fluent icon at 40dp, #484848.
One line of text, 18sp Light, #8A8A8A.
No illustration. No call-to-action graphic. No animation.

```
[image icon]

No photos yet
```

The emptiness is itself communicative. The black canvas with faint text
is honest — there is nothing here. It doesn't try to entertain or compensate.

---

## 10. What Is Deliberately Kept from the Original

These elements must not be changed. They are the design DNA:

- **2dp gutters** between photo tiles. Not 4dp, not 8dp. 2dp.
- **No rounded corners on photo tiles.** Photos are rectangles. Period.
- **Segoe UI Light for all display text.** This typeface *is* the identity.
- **Dark-first.** There is no light theme. The app does not respect system theme.
  `AppCompatDelegate.MODE_NIGHT_YES` is forced on launch.
- **Icon-only bottom bar.** No text labels beneath icons in the viewer bar.
- **Oversized month headers as spatial anchors.** The typography is navigational.
- **Content bleeds behind status bar.** The photo grid starts at the very top of the screen.
- **No card borders, no elevation shadows, no backgrounds on list items.**
  The black background *is* the separator.

---

## Summary: The 10 Design Decisions

| # | Decision |
|---|----------|
| 1 | #000000 black everywhere. #0A0A0A for surfaces. Never lighter than #111111. |
| 2 | Month headers at 40sp Light — larger than original, not smaller. |
| 3 | Sub-labels in ALL CAPS 13sp — maximizes contrast against display text. |
| 4 | Accent shifts from corporate blue (#0078D4) to photographic blue (#3B9EFF). |
| 5 | Floating pill replaces the bottom action bar strip — in viewer and selection mode. |
| 6 | Featured tile = first photo of each date group, not every Nth photo. |
| 7 | Selection mode: dimming instead of empty circles. Light-up on select. |
| 8 | Info panel is prose, not a table — three lines, not a list of rows. |
| 9 | Spring physics for all interactive transitions. Duration curves only for fades. |
|10 | Swipe-down-to-dismiss in the viewer. Velocity-sensitive spring return. |

