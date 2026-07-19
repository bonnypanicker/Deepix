# Deepix — Production Readiness Plan (UI & Usability)

**Goal:** Take Deepix from a feature-complete build to a shippable production app by polishing UI and usability, staying strictly inside the existing design language: **AMOLED Metro/WP10 — pure black `#000000` surfaces, accent `#3B9EFF` (user-customizable via `AccentPalette`), flat surfaces, squared 2–6dp corners, Fluent icons, large Metro typography (`SCREEN_TITLE_SIZE = 40f`).**

**Ground rules for every phase:**
- No Material redesign. Every new surface reuses existing patterns: flat command bars, Metro key-value sheets, `dialog_metro_bg` dialogs, accent pill buttons, 6dp rounded-square chips.
- Never re-introduce anything from CONTEXT.md "Solved Issues" (items 1–36).
- All constants go through `DesignTokens.kt`; all persisted prefs through `IndexPreferences` (or the relevant store).
- Each phase ends with a build that runs on a real device with a 10k+ photo library.

---

## Phase 1 — Design-system hardening (consistency audit)

*Make the existing style airtight before adding anything. Mostly XML + small Kotlin touch-ups.*

1. **Spacing & inset audit.** The 20dp inset unification done for the Sort & filter sheet (Solved Issue 23) and the 16dp start-aligned screen titles (Issue 26) are the standard. Sweep every layout (`activity_*.xml`, `sheet_*.xml`, `dialog_*.xml`, `item_*.xml`) and fix stragglers to: 16dp screen inset, 20dp sheet/dialog inset, 6dp chip radius, 2dp tile radius (per the "unify 2dp corner radii" commit).
2. **Dialog audit.** `dialog_smart_album.xml` is the reference Metro dialog (Issue 22). Verify `dialog_safe_setup.xml`, `dialog_safe_password.xml` (both currently modified — finish them), and `dialog_tag_picker.xml` match: `Theme.GallerySearch.Dialog`, `dialog_metro_bg`, flat accent primary + plain secondary buttons, no system `AlertDialog` chrome anywhere. Grep for remaining `AlertDialog.Builder` uses and convert or restyle each.
3. **Typography scale.** Define the full type ramp as named text-appearance styles in `themes.xml` (title 40sp, section header, timeline header 22sp, pinned-header 13sp, body, caption) and point every layout at the styles instead of raw `textSize` values.
4. **Accent propagation audit.** `AccentPalette` global accent (recent commit) must reach every accent-colored element: selection frame/badge, chips, sheet APPLY pill, progress bars (Settings indexing, Smart Cleanup), fast-scroll thumb, search sparkle span, editor controls, Safe buttons. Fix any element still hardcoding `#3B9EFF` in a drawable that isn't tinted at runtime.
5. **Icon audit.** All icons Fluent (`ic_fluent_*`), consistent 24dp optical size, consistent stroke weight; kill any leftover emoji/text glyphs used as icons.
6. **State drawables.** Every tappable surface gets a pressed state (flat Metro press: slight alpha or accent tint, no ripple-on-round-rect Material look) — buttons, drawer rows, bottom-nav tabs, command-bar items, settings rows.

**Exit criteria:** side-by-side screenshots of all 9 activities + all sheets/dialogs show one coherent system; no raw hex accents outside `colors.xml`/`AccentPalette`.

---

## Phase 2 — Usability: states, feedback, and recovery

*The app must never show a blank screen, dead end, or silent failure.*

1. **Empty states (Metro style: large thin glyph + one-line explanation + one accent action).**
   - Search with zero results → "No matches" + suggestions to relax filters / switch Match mode; if index incomplete, say "Still indexing — N% done, results may be partial."
   - Favorites, Videos, Albums, Folders, smart album with zero members, Safe with no photos, Bin empty, Smart Cleanup with nothing found — each gets a distinct empty view (extend `item_empty.xml` into a reusable pattern).
   - Search attempted **before consent/indexing** → inline card explaining AI search needs the one-time index, with a Start button (routes through `IndexController`).
2. **Error states.** MediaStore permission denied/partial (`READ_MEDIA_VISUAL_USER_SELECTED` partial-access on API 34+) → banner explaining limited library with "Manage access" action. Encoder load failure, corrupt index recovery (Issue 4), Safe wrong-password, All-files-access revoked mid-session → each surfaces a Metro dialog/banner, never a silent no-op or stock toast.
3. **Feedback for every action.** Replace raw `Toast` calls with a single flat Metro snackbar-style banner component (bottom, above nav/command bar, pure black + hairline + accent action). Actions that need it: delete (with count), move-to-Safe complete, restore from Bin, save/copy in editor, smart album created/refreshed, cleanup delete complete.
4. **Undo where destructive.** Delete already routes through `DeleteCoordinator`/Bin — surface "Moved to Bin · UNDO" in the new banner. Tag removal and un-favoriting get lightweight undo too.
5. **Long-operation transparency.** Reuse the Settings indexing progress pattern (Issue 30): any screen that depends on a background pass (search during indexing, Smart Cleanup scanning, Safe import encrypting N photos) shows determinate progress with pause/stop where the worker supports it. Safe import of many photos needs a progress dialog (currently the weakest spot — zip4j writes are slow on big batches).
6. **Gesture discoverability.** One-time, dismissible hint chips (persisted in `IndexPreferences`, mirroring `isSmartAlbumOnboardingDismissed`): pinch to resize grid/collage, long-press to select, swipe down to dismiss viewer, drag fast-scroll thumb. Never a full-screen tutorial.
7. **Confirmation hygiene.** Exactly one Metro confirm for destructive ops (Bin purge, Safe remove-item, Remove Safe, cleanup batch delete, editor overwrite-original); no double-confirms; system delete-consent (MediaStore) counts as the confirm where it appears.

**Exit criteria:** scripted walkthrough of every feature with (a) empty library, (b) permissions denied, (c) indexing paused — zero dead ends, zero stock toasts.

---

## Phase 3 — Strings, accessibility, and input breadth

*Currently `strings.xml` is ~empty and ~119 layout strings are hardcoded; a11y coverage is partial. This is the largest mechanical phase.*

1. **String extraction.** Move every user-visible string from layouts and Kotlin (`MainActivity` alone is 3,350 lines) into `res/values/strings.xml` with plurals (`%d selected`, "N photos") and formatted args. This unblocks localization later and is a Play-quality requirement. Do it screen-by-screen to keep diffs reviewable.
2. **Content descriptions.** Audit every `ImageView`/`ImageButton`: meaningful `contentDescription` (photo tiles: date + "photo"/"video"; decorative images: `@null`). Cover viewer actions, command bar, editor tools, Safe grid.
3. **TalkBack pass.** Selection state announced (`isSelected`/state description on tiles), viewer swipe alternatives, custom views (`FastScrollIndicator`, `CropOverlayView`, editor overlays) get basic a11y actions or documented exemptions; live regions for progress text.
4. **Touch targets.** Everything ≥48dp (fast-scroll already has a 64dp target; check the viewer top-bar icons, chip × buttons, onboarding ✕, editor handles).
5. **Font scaling & display sizes.** All text in `sp`; verify 200% font scale + smallest-width 320dp doesn't clip the command bar, sheet footers, or the 40sp titles (allow the title to shrink via `autoSizeTextType` on small widths rather than wrap ugly).
6. **Keyboard & IME.** Search field: `imeOptions=actionSearch`, proper `windowSoftInputMode` so the sheet/filter chips aren't covered; Safe password fields: `textPassword`, no autofill leaks (`importantForAutofill=no` on Safe fields), paste allowed.
7. **RTL sanity.** `supportsRtl` audit: use start/end everywhere (grep for `layout_marginLeft|Right|paddingLeft|Right`), mirror-exempt the media content itself.

**Exit criteria:** Accessibility Scanner run on every screen with no critical findings; a pseudo-locale (`en-XA`) build shows no truncated/hardcoded text.

---

## Phase 4 — Performance, stability, and edge-to-edge polish

*Perceived quality: the app must feel instant and survive hostile conditions.*

1. **Startup.** Verify the lazy encoder warm-up path (already done) holds on cold start with indexing paused; keep the splash → `loadingOverlay` handoff seamless (no double-flash). Target: first content < 800ms warm, < 2s cold on a mid-range device.
2. **Scroll performance.** Profile the justified-collage at scale level 1 (biggest tiles) and 6-column grid on a 50k library: Glide sizing per `collageHeightPx`, RecyclerView pool sizes, no main-thread `IndexPreferences` reads in bind paths (the collage-scale cache pattern is the model).
3. **Edge-to-edge & SDK 35.** targetSdk 35 enforces edge-to-edge: audit every activity for correct inset handling (main already handles nav insets for command bar; verify viewer, editor, Safe, Cleanup, Settings, Bin, Indexing screens + IME insets). Pure-black system bars with proper contrast enforcement disabled where the content is already black.
4. **Predictive back.** `OnBackPressedCallback` is already in place — enable `android:enableOnBackInvokedCallback` and verify drawer/sheet/selection/viewer back order; add the viewer's swipe-dismiss as the predictive-back preview where cheap.
5. **Config changes & process death.** Rotation (rotation gesture state fix already landed), split-screen, and process-death restore: mode/section/search query/selection survive via `MainState`/saved state; viewer restores position; editor warns before losing edits.
6. **Hostile-condition matrix.** Test and fix: airplane mode (should be irrelevant — fully offline), storage near-full (index writes, editor saves, Safe zip writes → clear error path), 0-photo device, permission revoked while app is open, battery saver + charging-only indexing interaction.
7. **Crash & ANR hygiene.** StrictMode in debug; wrap remaining disk I/O off main thread; verify `hs_err_pid2724.log` (JVM crash artifact in repo root) root cause isn't app-related, then delete it from the tree.

**Exit criteria:** zero jank flags in a Perfetto trace of browse+search+viewer; full hostile-condition matrix passes; monkey test (10k events) without crash.

---

## Phase 5 — Release engineering & store readiness

*Everything between "works on my phone" and "on the Play Store."*

1. **Build config.** Add `buildTypes` to `app/build.gradle`: release with `minifyEnabled true`, `shrinkResources true`, ProGuard/R8 keep rules for ONNX Runtime (JNI), Room, Glide (`AppGlideModule` if added), zip4j, kotlinx-coroutines; verify the `noCompress "onnx"` asset path survives shrinking. Add signing config via `keystore.properties` (gitignored).
2. **App size.** The ~135MB CLIP models dominate. Ship as **Play Asset Delivery (install-time pack)** or at minimum verify AAB + uncompressed assets stay under Play limits; measure download size and document it in the listing.
3. **Branding.** Finalize the launcher icon (decide between the untracked `ai_icon.jpg`/`icon_ai.png` candidates → proper adaptive icon: foreground/background layers, monochrome layer for themed icons on API 33+), themed splash icon, and consistent in-app "Deepix" naming (drawer header, Settings About).
4. **Versioning & hygiene.** Bump `versionCode`/`versionName` scheme (semantic + CI-incremented code); remove debug artifacts from the repo (`hs_err_pid2724.log`, stray root images); confirm `detekt` passes clean; add a `CHANGELOG.md`.
5. **Privacy & policy.** Play Data-safety form content: fully offline, no network permission for user data, biometric usage rationale, All-files-access justification for the Safe (this is a **sensitive permission** — prepare the Play declaration + in-app prominent disclosure, or have a fallback plan if rejected). Write the privacy policy page.
6. **Store assets.** Screenshots per screen (pure-black frames look striking — lean into it), feature graphic, short/full descriptions highlighting: offline AI search, Smart Cleanup, encrypted Safe, editor.
7. **Final QA sweep.** Full regression against CONTEXT.md Solved Issues 1–36 (each has a one-line repro), install-update path from the previous build (Room v2 migration, prefs, index file compatibility), fresh-install onboarding flow (consent → index → first search), Android 8.0 (minSdk 26) device pass, and the Safe reinstall-recovery flow end-to-end.

**Exit criteria:** signed release AAB installs clean and upgrades from the current build; internal-testing track live.

---

## Sequencing & risk notes

- **Phases 1–2 first** (visual + behavioral polish) since they touch the same layouts — do them together per screen to avoid re-visiting files.
- **Phase 3 string extraction is the biggest diff churn** — land it *after* Phase 1/2 stabilize the layouts, or every string move conflicts with layout edits.
- **Phase 4 and 5.1–5.2 can run in parallel** with Phase 3 (different files).
- **Biggest ship risk:** Play review of All-files access (Safe) and the 135MB asset size — start the Play declaration + asset-delivery spike early (during Phase 2), not at the end.
- `MainActivity` (3,350 lines) is a known god activity; **do not** refactor it as part of this plan — production polish only. Schedule extraction (search controller, selection controller, render pipeline) as a post-1.0 track.
