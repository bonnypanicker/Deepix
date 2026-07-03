# NSFW blur — MobileCLIP margin calibration

Calibrates `NsfwClassifier.SENSITIVE_MARGIN` and the prompt sets against the **real** MobileCLIP-S2
ONNX model shipped in the app (`app/src/main/assets`), so on-device behaviour matches.

## Content-safety scope
These scripts download **only clearly-safe public photos** (Unsplash via `picsum.photos`) to measure
the **false-positive boundary** — how often normal photos would be wrongly blurred. They do **not**
download explicit imagery. Recall (catching real NSFW) must be validated by the maintainer on a
trusted, locally-supplied labeled set (`--pos`/`--neg`), which is never committed or transmitted.

## Setup
```
pip install onnxruntime numpy pillow requests tokenizers regex onnx
python fp16_to_fp32.py ../../app/src/main/assets/vision_model_fp16.onnx vision_model_fp32.onnx
```
`fp16_to_fp32.py` is required because the shipped vision model is fp16 and onnxruntime's **CPU**
provider produces NaN for fp16 matmuls (the app uses fp16-capable providers on-device). The app
keeps the fp16 model; the fp32 copy is local-only.

## Run
- False-positive calibration:    `python calibrate.py --n 150`
- Prompt/scoring experiments:    `python experiment.py`
- Final tuned config + margin:   `python tuned.py`
- Recall (your labeled images):  `python calibrate.py --pos <blur_dir> --neg <keep_dir>`

## Findings (MobileCLIP-S2, 200 safe photos)
- MobileCLIP's cosine space is compressed (unrelated texts ~0.79 cos), so absolute similarities are
  meaningless — only the sensitive-vs-safe **margin** matters, and it's small (±0.07).
- **A broad, gallery-representative safe-prompt set is essential.** With only a few generic safe
  prompts, ~50% of normal photos scored as "sensitive" (margin ≥ 0.02). The current app default of
  0.02 would have blurred roughly half the gallery.
- With the expanded safe set (39 prompts) + focused sensitive set (5 prompts), safe photos have
  median margin **−0.012**; ~1% exceed +0.04, ~0.5% exceed +0.05, ~0% exceed +0.07.

## Chosen values (applied to `NsfwClassifier.kt`)
- `SENSITIVE_MARGIN = 0.05` → ~0.5% false-positive rate on safe photos. Precision-favoring, which is
  the right trade-off for a blur feature (a wrongly-blurred normal photo is the worse failure).
- More aggressive alternative: `0.04` (~1.5% FP, higher recall). Most conservative: `0.07` (~0% FP).

**Recall is unverified** — keep the feature marked Beta until validated with `--pos/--neg`.
