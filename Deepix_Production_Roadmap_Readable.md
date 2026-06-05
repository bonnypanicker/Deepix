# Deepix Production Roadmap (Readable Summary)

## Project Goal

Build a production-ready Android gallery app inspired by Windows Phone
10 Photos, with: - Metro/WP10 design language - Aves-style gallery
organization - MobileCLIP semantic search

## Current Strengths

-   MobileCLIP S2 semantic search
-   ONNX Runtime indexing pipeline
-   MediaStore integration
-   AMOLED Metro-style UI foundation
-   Timeline-based gallery

## Major Missing Features

### Viewer

-   No swipe between photos
-   No pinch-to-zoom
-   No shared-element transitions
-   Videos open in external apps

### Gallery

-   No fast scroll with date bubble
-   No metadata explorer
-   No folders view
-   No map view
-   No tags or advanced filters

### Engineering

-   No tests
-   No CI/CD
-   No release configuration
-   No crash reporting
-   No settings screen

## Planned Development Phases

### Phase 0

Build health, CI, design tokens, linting.

### Phase 1

Timeline redesign: - Featured tiles - Fast scrolling - Parallax
headers - Better animations - Improved selection mode

### Phase 2

Metadata system: - Room database - EXIF extraction - Rich info panel -
Favorites migration

### Phase 3

New viewer: - ViewPager2 photo paging - Pinch zoom - Double-tap zoom -
Shared-element transitions - In-app video player

### Phase 4

Search & organization: - Tags - Faceted search - Folder tree - Album
improvements

### Phase 5

Advanced media: - GIF support - HEIC - RAW - Motion photos - Batch
move/copy/rename

### Phase 6

Map view: - Geotagged photo browsing - Clustering - Dark theme maps

### Phase 7

Polish: - Settings screen - Accessibility - Performance tuning -
Localization

### Phase 8

Release preparation: - Tests - R8 optimization - Play Store readiness -
Privacy policy - Crash monitoring

## Recommended Priority

### P0 (Must Have)

1.  Phase 0
2.  Phase 1
3.  Phase 2
4.  Phase 3
5.  Phase 8

### P1 (Strongly Recommended)

1.  Phase 4
2.  Phase 5
3.  Phase 7

### P2 (Nice to Have)

1.  Phase 6
2.  Duplicate photo detection
3.  Search by example
4.  Future Compose migration
