# Deepix

Deepix is an Android gallery app for local, offline semantic image search. The current implementation focuses on making MobileCLIP-based text-to-image search work robustly before adding broader gallery UI features.

## Current Build

- Kotlin + XML Views Android project.
- MobileCLIP S2 ONNX Runtime inference.
- Dynamic ONNX input/output binding for model compatibility.
- CLIP-style tokenizer implementation using the bundled Hugging Face `tokenizer.json`.
- MediaStore image discovery.
- Persisted image embedding index with corrupt-cache recovery.
- Search over L2-normalized embeddings with thresholded fallback results.
- Sideways and resized windows handled in place — activities declare `configChanges`, and the sizes a
  window decides (grid columns, tile and card widths, hero title, dialog and sheet heights) come from
  `Responsive.kt` and are re-applied when the measured width changes. See `SYMBOLS.md` → *Landscape pass
  additions*. `VideoEditorActivity` is the deliberate exception: still portrait-locked.

## Model Assets

The Android app expects these files in `app/src/main/assets/`:

- `vision_model_fp16.onnx`
- `text_model_int8.onnx`
- `tokenizer.json`
- `tokenizer_config.json`
- `preprocessor_config.json`
- `config.json`

The `.onnx` assets are tracked with Git LFS. The root-level `.onnx` files are ignored and kept only as local source copies.

Every bundled model and library is listed with its licence in `THIRD_PARTY_NOTICES.md`. Two of the
weights are research-only, which gates a commercial release; the decision record is in
`DECISION_GATES.md`, along with the Android 15 `dataSync` foreground-service limit that bounds a
first-time full-library indexing pass, and the decision to keep the Room database out of Android Auto
Backup (`faces.embeddingJson` holds face feature vectors beside the person names typed next to them,
and the rows are keyed on per-device MediaStore uris — favorites, tags and labels therefore do not
arrive on a new phone).

## Data boundaries

Nothing in the app talks to a network. Two derived-data rules are worth knowing before changing code:

- Smart Cleanup only pre-selects a duplicate that `DuplicateVerifier` has measured (dHash against the
  kept file). A photo it cannot measure is listed but never offered, because accepting a tile deletes
  what was offered.
- Every Room write and `IN (:list)` read is chunked through `RoomBatching` against the entity's own
  `BindVariables`, because Android 8–10 ship SQLite at 999 variables and a bulk insert binds one per
  column per row.

## Build Notes

Open this folder in Android Studio and sync Gradle. The shell used during setup did not have a local Android SDK or Gradle command available, so APK compilation should be verified from Android Studio.

See `plan1.md` for the original detailed project plan and `GallerySearch_Build_Guide.md` for the downloaded model build guide.
