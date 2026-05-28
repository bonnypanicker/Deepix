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

## Model Assets

The Android app expects these files in `app/src/main/assets/`:

- `vision_model_fp16.onnx`
- `text_model_int8.onnx`
- `tokenizer.json`
- `tokenizer_config.json`
- `preprocessor_config.json`
- `config.json`

The `.onnx` assets are tracked with Git LFS. The root-level `.onnx` files are ignored and kept only as local source copies.

## Build Notes

Open this folder in Android Studio and sync Gradle. The shell used during setup did not have a local Android SDK or Gradle command available, so APK compilation should be verified from Android Studio.

See `plan1.md` for the original detailed project plan and `GallerySearch_Build_Guide.md` for the downloaded model build guide.
