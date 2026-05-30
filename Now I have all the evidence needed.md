Now I have all the evidence needed. Writing the final markdown report.
Action: file_editor create /app/Deepix/CLIP_Verification_Report.md --file-text "# MobileCLIP-S2 Wiring Verification Report

**Repo:** `bonnypanicker/Deepix` (Android gallery semantic-search app)
**Target model:** `Xenova/mobileclip_s2` (INT8 ONNX, shipped in `app/src/main/assets/`)
**Date:** Jan 2026
**Verification method:** Python + `onnxruntime` + `transformers` running on the **exact** ONNX files committed to the repo (downloaded via `git lfs pull`).

> Scope per request: **analysis only — no code edits in the Kotlin app.**
> All scripts used to obtain the numbers below live next to this file:
> `verify_clip.py`, `verify_text_quality.py`, `verify_vision_fp32.py`, `verify_fp32_e2e.py`.

---

## TL;DR — Will the wired model produce correct (non-random) output?

**No.** With the current wiring the vision encoder produces effectively useless embeddings, so the search results the user sees are the fallback \"top-10 most-recent photos\" path in `GalleryRepository.search()` — i.e. they look completely random with respect to the query. The cause is **not** the tokenizer, not the text encoder, not the preprocessing logic, and not the L2 normalisation. The cause is the **INT8 vision model itself plus a calibration/preprocessing mismatch**.

| Component | Status | Evidence |
|---|---|---|
| BPE tokenizer (`ClipTokenizer.kt`) | ✅ Perfect — token IDs match HuggingFace `CLIPTokenizer` byte-for-byte on every test query | §3 |
| Text encoder ONNX (INT8) | ✅ Healthy — cosine vs FP32 reference = **0.97 – 0.98** | §2 |
| Image preprocessing (resize 256/center-crop/`/255`, `do_normalize=false` read from JSON) | ✅ Matches HuggingFace `CLIPImageProcessor` **bit-perfectly** (cosine = 1.0000) | §1 |
| Vision ONNX `vision_model_android_int8.onnx` (the one the app actually loads) | ❌ **Broken** — outputs all zeros for the input distribution the app feeds it | §4 |
| Vision ONNX `vision_model_int8.onnx` (the alternate shipped file) | ❌ Severely degraded — cosine vs FP32 ≈ **0.05 – 0.17** (need ≥ 0.95 for usable INT8) | §4 |
| End-to-end semantic search (FP32 reference vs both INT8s) | ❌ Diagonal hit-rate: **5/5 with FP32, 1/5 with alt INT8, 0/5 with android INT8** | §5 |
| Search threshold `0.17f` + fallback \"top-10 anyway\" | ⚠️ Behaviourally correct, but the all-zero vision embeddings mean *every* cosine = 0, so the fallback path always fires → user sees \"random\" results | §6 |

---

## 1. ONNX topology and pre-processing match-up

```
vision_model_android_int8.onnx (loaded by ImageEncoder.kt)
  opset 12   input  pixel_values  [N,3,256,256] float32   output  image_embeds [N,512]
vision_model_int8.onnx          (shipped but NOT loaded)
  opset 12   input  pixel_values  [N,3,256,256] float32   output  image_embeds [N,512]
text_model_int8.onnx
  opset 14   input  input_ids     [N, seq]      int64     output  text_embeds  [N,512]
              ← NOTE: text model has NO attention_mask input (just input_ids)
```

`preprocessor_config.json` (the file the Kotlin code reads at runtime) declares:

```json
{ \"do_normalize\": false, \"do_rescale\": true,
  \"size\": { \"shortest_edge\": 256 }, \"crop_size\": { \"height\": 256, \"width\": 256 } }
```

This is the canonical Xenova/Apple MobileCLIP pipeline: **rescale to [0,1] only, do NOT apply CLIP mean/std.** Verified by loading the same JSON with HuggingFace `CLIPImageProcessor`:

```
HF processor:   do_normalize=False   do_rescale=True
                shortest_edge=256    crop=256x256
                (image_mean/std are present but unused because do_normalize=False)
```

> ⚠️ The Build Guide (`GallerySearch_Build_Guide.md`, Step 3.3) instructs the AI tool to apply `(pixel/255 - mean) / std`. That instruction **contradicts the model's own preprocessor_config and is wrong for MobileCLIP-S2.** The Kotlin code (`ImageEncoder.kt`) currently respects the JSON instead of the guide, which is the correct behaviour for the *intended* model. Numerical proof:

```
Pipeline (Kotlin reproduction) vs HuggingFace CLIPImageProcessor on the alternate vision model
   alt(no mean/std)  ↔ HF processor     cos = +1.0000   ← bit-perfect match (good)
   alt(with mean/std)↔ HF processor     cos = +0.3536   ← would be the wrong pipeline
```

---

## 2. Text encoder health

INT8 vs FP32 text-embedding cosine on identical token IDs (FP32 model = `Xenova/mobileclip_s2/onnx/text_model.onnx`, 254 MB):

```
  a photo of a cat       cos(INT8,FP32) = +0.9767
  a photo of a kitten    cos(INT8,FP32) = +0.9719
  a photo of a dog       cos(INT8,FP32) = +0.9785
  a photo of a puppy     cos(INT8,FP32) = +0.9830
  a photo of a beach     cos(INT8,FP32) = +0.9784
```

Text-vs-text semantic discrimination is intact:

```
  cat ↔ kitten        cos = 0.91   ← similar concept = high
  dog ↔ puppy         cos = 0.91   ← similar concept = high
  beach ↔ ocean       cos = 0.85
  pizza ↔ food        cos = 0.80
  cat ↔ car           cos = 0.79   ← unrelated concept = lower
  dog ↔ pizza         cos = 0.75
  beach ↔ pizza       cos = 0.74
```

**Conclusion:** The text side is fine. Whatever is wrong in production, it is **not** the text encoder, not the tokenizer, and not the missing `attention_mask` input.

---

## 3. Tokenizer correctness

Re-implemented `ClipTokenizer.kt` byte-for-byte in Python (same byte-encoder table, same `\p{L}+|\p{N}+|[^...]` token regex, same BPE merges, same `</w>` end-of-word suffix, same auto `\"a photo of \"` prefix) and compared to `transformers.CLIPTokenizer` token IDs:

```
query                      kotlin (real ids)                                  HF ids                                              match
cat                        [49406, 320, 1125, 539, 2368, 49407]               [49406, 320, 1125, 539, 2368, 49407]               OK
dog at beach               [49406, 320, 1125, 539, 1929, 536, 2117, 49407]    [49406, 320, 1125, 539, 1929, 536, 2117, 49407]    OK
a photo of food            [49406, 320, 1125, 539, 1559, 49407]               [49406, 320, 1125, 539, 1559, 49407]               OK
birthday cake              [49406, 320, 1125, 539, 1166, 2972, 49407]         [49406, 320, 1125, 539, 1166, 2972, 49407]         OK
screenshot of text         [49406, 320, 1125, 539, 12646, 539, 4160, 49407]   [49406, 320, 1125, 539, 12646, 539, 4160, 49407]   OK
```

**Conclusion:** Tokenizer is perfect, including the auto-prefix and BOS/EOS handling. No changes needed.

Minor stylistic note: the Kotlin regex uses Java's `\p{L}` / `\p{N}` Unicode categories which are correctly Unicode-aware in Kotlin (`Regex` on Android), so non-ASCII queries will also tokenize correctly.

---

## 4. Vision encoder — root cause of the \"random output\" symptom

### 4.1 Single-image embedding statistics (cat photo, 320 × 220)

| Vision model | Pipeline | output L2-norm (pre-normalise) | min | max | mean |
|---|---|---:|---:|---:|---:|
| `vision_model_android_int8.onnx` (app loads this) | **/255 only** (current code) | **0.0000** | 0.000 | 0.000 | 0.0000 |
| `vision_model_android_int8.onnx` | with CLIP mean/std | 31 648 | -7 521 | +6 364 | -40.7 |
| `vision_model_int8.onnx` (alt) | /255 only | 129.5 | -20.8 | +18.0 | -0.20 |
| `vision_model_int8.onnx` (alt) | with CLIP mean/std | 82 777 | -12 170 | +15 141 | -45.0 |

The **android INT8 model produces an all-zero embedding for the input distribution the app actually feeds it.** Every cosine becomes 0, and the search code's fallback (`return ranked.take(FallbackCount)` when nothing exceeds `0.17f`) returns the first 10 images encountered, which look random with respect to any query.

### 4.2 Why is the android INT8 broken? — direct evidence from `requantize_android.py`

```python
# requantize_android.py — RandomCalibDataReader and ImageCalibDataReader BOTH apply CLIP mean/std
x = (x - self._mean) / self._std
```

The static-quantisation calibration for `vision_model_android_int8.onnx` was performed with **mean/std-normalised inputs**, so its activation-quantisation scales/zero-points expect data roughly in `[-2, +2]`.

The Kotlin code, following `preprocessor_config.json`, feeds it data in `[0, 1]`. The first activation node's `(x-zp)*scale` requantises every value to the same quantised bucket → downstream `QLinearConv` produces a saturated/zero pattern → final pooled output is exactly zero. This is a textbook **calibration-distribution mismatch**.

### 4.3 INT8 vs FP32 (the real quality budget)

Downloaded `Xenova/mobileclip_s2/onnx/vision_model.onnx` (143 MB, FP32) as ground truth. Per image, cosine similarity of each INT8 model's embedding vs the FP32 embedding, using each model's **intended** input pipeline:

| image | FP32 baseline vs `alt INT8 / no_norm` (intended) | FP32 baseline vs `android INT8 / with_norm` (its calibration pipeline) |
|---|---:|---:|
| beach | +0.168 | -0.061 |
| car   | +0.057 | +0.410 |
| pizza | +0.047 | +0.178 |
| dog   | +0.128 | +0.026 |
| cat   | +0.011 | -0.023 |

A correctly-quantised CLIP INT8 should retain **≥ 0.95** cosine vs FP32. Both INT8 vision files here sit between **-0.06 and +0.41**, i.e. effectively destroyed by quantisation. So even if the wrong-file/wrong-pipeline issue is fixed, the *best* possible vision quality on the device is still very poor.

### 4.4 End-to-end 5×5 image-vs-query matrix

Five test photos (`beach`, `car`, `pizza`, `dog`, `cat`) × five text queries (`a photo of a cat / dog / beach / car / pizza`). Cell value = cosine similarity. `→` marks the predicted label (argmax). `OK` = correct, `X` = wrong.

**FP32 vision ⊗ FP32 text — reference, what the model is supposed to do**
```
image\query     a cat    a dog  a beach     a car    pizza
   beach       0.116    0.129    0.284    0.119    0.104   → beach  OK
     car       0.105    0.073    0.071    0.226    0.073   → car    OK
     cat       0.304    0.180    0.099    0.124    0.137   → cat    OK
     dog       0.132    0.264    0.085    0.102    0.109   → dog    OK
   pizza       0.078    0.063    0.071    0.068    0.257   → pizza  OK
                                                                  5/5 correct
```

**`vision_model_int8.onnx` (alt) ⊗ INT8 text — the file the build guide intended**
```
image\query     a cat    a dog  a beach     a car    pizza
   beach       0.067    0.048    0.057    0.022    0.005   → cat    X
     car       0.064    0.049    0.075    0.071    0.015   → beach  X
     cat       0.068    0.054    0.045    0.043    0.055   → cat    OK
     dog       0.079    0.054    0.030    0.035    0.091   → pizza  X
   pizza      -0.004   -0.025    0.017    0.010   -0.009   → beach  X
                                                                  1/5 correct
```

**`vision_model_android_int8.onnx` (alt) ⊗ INT8 text — what the APP CURRENTLY RUNS**
```
image\query     a cat    a dog  a beach     a car    pizza
   beach       0.000    0.000    0.000    0.000    0.000   → cat (arbitrary)  X
     car       0.000    0.000    0.000    0.000    0.000   → cat              X
     cat       0.000    0.000    0.000    0.000    0.000   → cat (lucky)      OK
     dog       0.000    0.000    0.000    0.000    0.000   → cat              X
   pizza       0.000    0.000    0.000    0.000    0.000   → cat              X
                                                          0/5 — every score = 0
```

This is the smoking gun: **with the current asset/code combination every cosine is exactly zero**, so the threshold check `score > 0.17f` fails for everything and the code takes the fallback path that returns the first `FallbackCount = 10` photos in date order. The user sees the same 10 recent photos for every query — i.e. \"random outputs\".

---

## 5. Other (non-blocking) observations

1. **`compareDocuments_*` config drift.** `config.json` is the HuggingFace CLIP `transformers.js` config and is *not consumed by the Kotlin code*. Harmless, can stay.
2. **Text model has only one input (`input_ids`).** `TextEncoder.kt` correctly probes `session.inputNames` and only binds `attention_mask` if present. Future-proof and safe.
3. **NNAPI EP.** `OnnxSessionOptions.create()` reflectively tries `addNnapi()`. ORT 1.17 on Android does expose it; on emulators/older devices the catch falls back to CPU silently — fine.
4. **Index file format** in `GalleryRepository.kt` is sound (magic + version + length-prefixed UTF-8 URI + float32 array). Embedding dimension is hard-coded only via `MaxEmbeddingSize = 4096`, model emits 512 — safe.
5. **Fallback search behaviour** (`if (thresholded.isEmpty()) ranked.take(FallbackCount)`) is *intended* to \"never show empty\", but it doubles as a silent failure mode: with all-zero embeddings it is *guaranteed* to return semantically meaningless results without any error signal. Recommend adding a debug log of `embedding.first()` per query in production (no code change asked for here).
6. **Bilinear vs Bicubic resize.** Kotlin's `Bitmap.createScaledBitmap(..., true)` is bilinear; the HF reference and `verify_quality.py` use bicubic. We measured this affects cosine by < 0.005 once the model is correct — not the cause of any user-visible issue.

---

## 6. Changes required

### Must-fix (root cause of \"random outputs\")

| # | Change | Where | Type |
|---|---|---|---|
| 1 | **Stop loading `vision_model_android_int8.onnx`.** Either delete it from `assets/` or have the encoder load `vision_model_int8.onnx` instead (one-character asset-name change in `ImageEncoder.kt` line 22). With the current `preprocessor_config.json` (`do_normalize=false`) only `vision_model_int8.onnx` is internally consistent. | `app/src/main/assets/` and/or `ImageEncoder.kt` | Asset rename or 1-line code change |
| 2 | **Reproduce the INT8 vision model with a quantisation strategy that doesn't tank quality.** Both shipped INT8 vision files drop FP32 cosine to ≤ 0.17, which is unusable for semantic search. Recommended options, in order of preference: <br>**(a)** Ship the Xenova **`vision_model_fp16.onnx`** (~71 MB, basically lossless and supported by ORT-Android 1.17 on most devices). <br>**(b)** Use **dynamic INT8 (weights-only)** via `onnxruntime.quantization.quantize_dynamic` — no activation-calibration step, so the [0,1] vs CLIP-normalised mismatch becomes impossible. <br>**(c)** Use **`compress_weights_android.py`** (already in the repo) which does per-channel weight-only INT8 — keeps activations FP32 → safe. | `app/src/main/assets/vision_model_*.onnx` | Asset replacement |
| 3 | After (1)/(2), **re-verify** with `python verify_fp32_e2e.py`. The diagonal hit-rate on the 5×5 matrix above should jump from 0/5 (current) toward 5/5 (FP32 reference). | — | Verification |

### Should-fix (correctness / clarity, but app would already work)

| # | Change | Where | Type |
|---|---|---|---|
| 4 | **Update the build guide.** Step 3.3 currently tells developers to apply `(pixel/255 - mean)/std`. This is wrong for MobileCLIP-S2 (its `preprocessor_config.json` ships `do_normalize=false`, and FP32 self-cosine between the two pipelines is only 0.02–0.07, confirming the model wants `/255` only). Replace that instruction with the Kotlin code's actual behaviour: read `do_normalize` from JSON. | `GallerySearch_Build_Guide.md` Step 3.3 | Documentation |
| 5 | **Add a startup self-test log** that encodes a known asset image (or a 256×256 constant `0.5` tensor) once and logs `||embedding||` and `embedding.first()`. An all-zero vector is the same fingerprint as the bug above and is currently silent. | `ImageEncoder.kt` (init block) | Debug log only |
| 6 | **Stop shipping unused assets.** `vision_model_int8.onnx` *or* `vision_model_android_int8.onnx` will go away once (1) lands — drop the other from `assets/` to save ~36 MB APK size. | `app/src/main/assets/` | Asset cleanup |

### Optional / nice-to-have

| # | Change | Where | Type |
|---|---|---|---|
| 7 | The auto `\"a photo of \"` prefix is currently always applied. MobileCLIP-S2 was trained with the OpenAI 80-prompt ensemble; for a small *single* prompt, `\"a photo of {x}\"` is fine, but you can squeeze 1-2 extra mAP points by averaging text embeddings over 3–5 prompts (\"a photo of {x}\", \"a picture of {x}\", \"an image of {x}\") — pure CPU work, no model change. | `ClipTokenizer.kt` + `TextEncoder.kt` | Quality boost |
| 8 | `GalleryRepository.search()` falls back to \"top-10 anyway\" if nothing exceeds `0.17f`. With healthy embeddings this is harmless; with broken embeddings it hides the problem. Consider making the fallback opt-in (or showing \"no good matches\" UI) so future regressions are visible. | `GalleryRepository.kt` | UX hardening |

---

## 7. How to re-run these checks

```bash
cd /app/Deepix
git lfs pull                                  # pull the 36 MB / 64 MB ONNX files
pip install onnx onnxruntime transformers pillow numpy regex

python verify_clip.py            # model IO + 5×5 image/query matrix + tokenizer match
python verify_text_quality.py    # INT8 text vs FP32 text + text-text semantics
python verify_vision_fp32.py     # downloads FP32 vision (~143 MB), compares to both INT8s
python verify_fp32_e2e.py        # 5×5 matrices for FP32, alt INT8, android INT8
```

The four scripts together produce all numbers cited in this report.

---

## 8. One-paragraph executive summary

The Kotlin wiring (tokenizer, preprocessing, attention-mask handling, L2 normalisation, threshold, fallback) is correct. The **`text_model_int8.onnx`** is healthy (cosine vs FP32 ≈ 0.98). The **`vision_model_android_int8.onnx`** that `ImageEncoder.kt` actually loads was statically quantised using **CLIP-mean/std-normalised calibration data** (see `requantize_android.py`), but the app feeds it raw `pixel/255` values (correctly, per the shipped `preprocessor_config.json`). The resulting calibration-distribution mismatch causes the model to emit **all-zero embeddings**, every cosine collapses to 0, the `0.17f` threshold filters everything out, and the \"never show empty\" fallback path returns the 10 most-recent photos for every query — exactly the \"random output\" symptom. Even the alternate file `vision_model_int8.onnx`, while not all-zero, has been so badly damaged by INT8 quantisation that its cosine vs FP32 is ≤ 0.17 (target: ≥ 0.95) and end-to-end accuracy on a 5-class sanity matrix is 1/5 vs FP32's 5/5. **Fix:** drop the static-INT8 vision files, ship the FP16 vision model (or a dynamic-INT8 weights-only re-export), keep everything else as-is, and re-run `verify_fp32_e2e.py` until the diagonal lights up.
"
Observation: Create successful: /app/Deepix/CLIP_Verification_Report.md