# Third-party notices

Pixa (`com.devomind.gallerysearch`) bundles machine-learning weights and third-party libraries.
This file records the licence each one arrives under, and — where that licence restricts what we may
do with it — says so plainly instead of leaving it to be discovered at store review.

Licences were read from upstream primary sources (the model's own repository directory, or the
dependency's published POM) on 2026-10-06. Library versions match `app/build.gradle`.

---

## 1. Models in `app/src/main/assets`

| File | Origin | Licence | Ship in a commercial build? |
|---|---|---|---|
| `vision_model_fp16.ort`, `text_model_int8.ort`, `tokenizer.json`, `tokenizer_config.json`, `preprocessor_config.json`, `config.json` | Apple **MobileCLIP S2** (`apple/ml-mobileclip`), re-exported to ONNX | Apple Machine Learning Research Model License Agreement — **non-commercial research only** | **No.** Blocked pending written terms from Apple. |
| `face_detection_yunet_2026may.onnx` | YuNet detector, distributed by OpenCV Zoo (`opencv_zoo/models/face_detection_yunet`) | **MIT**, © 2020 Shiqi Yu | Yes — MIT text below must travel with the app. |
| `mobilefacenet_w600k_mbf.onnx` | InsightFace **w600k_mbf** face-recognition model (trained on WebFace600K) | InsightFace states its models are for **non-commercial research purposes only** | **No.** Blocked pending a commercial licence from InsightFace. |

### MobileCLIP S2 — the semantic search encoder

Code in `apple/ml-mobileclip` is MIT, but the checkpoints are under a separate agreement
(`LICENSE_MODELS`, the "Apple Machine Learning Research Model License Agreement"), and that agreement
covers the artifact we actually ship: it names "algorithms, formulas, trained model weights,
parameters, configurations, checkpoints, and any related materials", grants use "exclusively for
Research Purposes", defines Research Purposes to *exclude* "any commercial exploitation, product
development or use in any commercial product or service", and extends the same limit to Model
Derivatives. An fp16/int8 ONNX re-export is a derivative, so re-exporting does not launder the terms —
and no third party who publishes an ONNX mirror can grant rights Apple never gave out.

Required notice if Apple ever licenses it: "Apple Machine Learning Research Model is licensed under the
Apple Machine Learning Research Model License Agreement", plus a copy of the agreement and a statement
identifying our files as derivatives with the modifications (fp16 vision, int8 text quantisation, graph
rewrites for ONNX Runtime) disclosed. Training-data provenance is DataCompDR (CC-BY-NC-ND per the
repo's `LICENSE_DATA`) — another non-commercial signal worth repeating to anyone who asks.

### YuNet — face detection

The only bundled model that is clear for commercial use. OpenCV Zoo's root is Apache-2.0 for *code*,
and the YuNet model directory carries its own MIT licence covering every file in it, including the
`.onnx` weights. Detection was trained on WIDER FACE; we did not verify WIDER FACE's own terms, which
is a provenance caveat rather than a blocker on the MIT grant covering the file we redistribute.

```
MIT License

Copyright (c) 2020 Shiqi Yu

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### MobileFaceNet w600k_mbf — face recognition

InsightFace's README: the code is MIT with "no limitation for both academic and commercial usage",
immediately followed by "The training data containing the annotation (and the models trained with these
data) are available for non-commercial research purposes only", covering both manual and library-driven
model downloads; its model zoo repeats "ALL models are available for non-commercial research purposes
only". A commercial path exists by agreement (the repo directs recognition-model requests to
`recognition-oss-pack@insightface.ai`), not by attribution — so this entry records the restriction, it
does not lift it.

**Decision taken (2026-10-06):** keep this model as shipped. It is not being replaced or re-embedded,
so People data stays valid and no re-clustering pass is needed. Consequence: builds containing it are
for personal / non-commercial distribution only, until Apple and InsightFace terms are in hand. The
licence-safe substitution on the table is SFace (Apache-2.0 weights), which would require a new
`FaceEmbedder.ModelVersion`, full re-embedding and re-clustering — deliberately not done.

Face embeddings are also biometric data; GDPR/BIPA-style exposure is independent of who owns the
weights, and should be answered by the Play data-safety declaration rather than by this file.

---

## 2. Libraries

Licences below are the ones declared in each artifact's published Maven POM; AndroidX and JetBrains
artifacts are Apache-2.0 throughout.

| Dependency | Version | Licence |
|---|---|---|
| `com.microsoft.onnxruntime:onnxruntime-android` | 1.28.0 | MIT (bundles its own third-party notices — protobuf, abseil, etc.) |
| `com.github.bumptech.glide:glide`, `:avif-integration`, `:compiler` | 4.16.0 | Simplified BSD (3-clause); AVIF decoding pulls in native decoders (libyuv / AOM / dav1d) under BSD-style terms |
| `com.caverock:androidsvg-aar` | 1.4 | Apache-2.0 |
| `com.github.chrisbanes:PhotoView` | 2.3.0 | Apache-2.0 (per its repository; the POM declares none) |
| `net.lingala.zip4j:zip4j` | 2.11.5 | Apache-2.0 |
| `androidx.media3:media3-exoplayer / -transformer / -effect / -ui` | 1.5.1 | Apache-2.0 |
| `androidx.room:room-runtime / -ktx / -compiler` | 2.6.1 | Apache-2.0 |
| `androidx.work:work-runtime-ktx` | 2.9.1 | Apache-2.0 |
| `androidx.activity / appcompat / core / core-splashscreen / drawerlayout / dynamicanimation / exifinterface / heifwriter / lifecycle-runtime-ktx / recyclerview / viewpager2 / biometric` | as pinned | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib`, `org.jetbrains.kotlinx:kotlinx-coroutines-android / -test` | 2.0.20 / 1.9.0 | Apache-2.0 |
| `junit:junit` (test only) | 4.13.2 | Eclipse Public License 1.0 |

The full text of Apache-2.0 licences must accompany the distribution if we ship source; for a binary
Play release, this list plus the app's "About" attribution is the customary form. MIT (YuNet, ONNX
Runtime) is reproduced above because MIT requires its notice in "all copies or substantial portions".

---

## 3. What is *not* covered here

- Fonts, icons and drawables drawn in-app (Fluent-style icon set) — tracked separately if they are
  vendored files rather than our own paths.
- Play-services-free: the app declares no network permission for user data, so nothing here comes from
  a cloud SDK.
