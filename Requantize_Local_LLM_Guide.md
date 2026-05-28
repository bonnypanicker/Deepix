# MobileCLIP S2 — Local Requantization for Android
## Prompt this entire file to your LLM coding tool (Cursor, Copilot, Claude Code, etc.)

---

## CONTEXT FOR LLM

You are helping fix a MobileCLIP S2 ONNX model for Android compatibility.

**The Problem:**
`vision_model_int8.onnx` from Xenova/HuggingFace uses `ConvInteger` operators which are NOT supported by ONNX Runtime on Android CPU. It must be re-quantized from the FP32 model to produce `QLinearConv` operators instead.

**Files the user already has locally:**
```
vision_model.onnx           ← FP32 model (143 MB)
vision_model_int8.onnx      ← broken INT8 from Xenova (has ConvInteger)
text_model.onnx             ← text encoder FP32
text_model_int8.onnx        ← text encoder INT8
tokenizer.json              ← CLIP BPE tokenizer
tokenizer_config.json
preprocessor_config.json
config.json
```

**Goal:**
Produce `vision_model_android_int8.onnx` — an INT8 model that:
- Uses `QLinearConv` instead of `ConvInteger`
- Works on Android ONNX Runtime 1.17.0+
- Has cosine similarity > 0.975 vs FP32 baseline
- Is under 50 MB

---

## TASK 1 — Check Python Environment

Run this first. Fix any missing packages before proceeding.

```python
# check_env.py
import sys
print(f"Python version: {sys.version}")

required = ["onnx", "onnxruntime", "numpy"]
missing = []

for pkg in required:
    try:
        mod = __import__(pkg)
        print(f"✅ {pkg}: {mod.__version__}")
    except ImportError:
        print(f"❌ {pkg}: NOT INSTALLED")
        missing.append(pkg)

if missing:
    print(f"\nInstall missing packages:")
    print(f"pip install {' '.join(missing)}")
else:
    print("\n✅ All packages ready")
```

**Expected output:**
```
✅ onnx: 1.x.x
✅ onnxruntime: 1.17.x
✅ numpy: 1.x.x
All packages ready
```

---

## TASK 2 — Locate Model Files

```python
# locate_files.py
import os

# ── EDIT THESE PATHS TO MATCH WHERE YOUR FILES ARE ──────────────
# Examples:
#   Windows: r"C:\Users\YourName\Downloads\mobileclip_fix"
#   Mac/Linux: "/Users/YourName/Downloads/mobileclip_fix"

MODEL_DIR = "."   # ← change this to your folder path

# ────────────────────────────────────────────────────────────────

files_needed = {
    "vision_model.onnx":     "FP32 vision model (required for requantization)",
    "vision_model_int8.onnx":"Xenova INT8 (to verify the problem)",
    "tokenizer.json":        "CLIP tokenizer",
}

print(f"Looking in: {os.path.abspath(MODEL_DIR)}")
print()

all_found = True
for filename, description in files_needed.items():
    full_path = os.path.join(MODEL_DIR, filename)
    if os.path.exists(full_path):
        size_mb = os.path.getsize(full_path) / (1024 * 1024)
        print(f"✅ {filename} ({size_mb:.1f} MB) — {description}")
    else:
        print(f"❌ {filename} — NOT FOUND — {description}")
        all_found = False

print()
if all_found:
    print("✅ All required files found. Proceed to Task 3.")
else:
    print("❌ Some files missing. Update MODEL_DIR path and retry.")
```

**If files are not found:** Update the `MODEL_DIR` variable to the exact folder path where your files are stored.

---

## TASK 3 — Confirm the Problem

```python
# confirm_problem.py
import onnx
import os

MODEL_DIR      = "."   # ← same path as Task 2
INT8_MODEL     = os.path.join(MODEL_DIR, "vision_model_int8.onnx")

print("Scanning Xenova INT8 model for Android-incompatible operators...")
print()

model = onnx.load(INT8_MODEL)

ops = {}
for node in model.graph.node:
    ops[node.op_type] = ops.get(node.op_type, 0) + 1

# Android CPU incompatible operators
bad_ops  = ["ConvInteger", "MatMulInteger", "DynamicQuantizeLinear"]
# Android CPU compatible operators
good_ops = ["QLinearConv", "QLinearMatMul"]

print("Quantization operators found in model:")
for op in sorted(ops.keys()):
    if any(x in op for x in ["Quant", "Integer", "Linear", "Conv", "MatMul"]):
        status = "❌ BAD " if op in bad_ops else "✅ OK  "
        print(f"  {status} {op}: {ops[op]} nodes")

print()
if "ConvInteger" in ops:
    print("❌ CONFIRMED PROBLEM: ConvInteger found")
    print(f"   {ops['ConvInteger']} ConvInteger nodes must be replaced")
    print("   Proceed to Task 4.")
elif "QLinearConv" in ops:
    print("✅ Model already uses QLinearConv")
    print("   The crash is caused by something else — check other error guides")
else:
    print("⚠️  Unexpected operator set — proceed with Task 4 anyway")
```

---

## TASK 4 — Requantize for Android

```python
# requantize_android.py
import onnx
import onnxruntime
from onnxruntime.quantization import quantize_dynamic, QuantType
import os
import time

# ── PATHS — update if needed ─────────────────────────────────────
MODEL_DIR    = "."
INPUT_MODEL  = os.path.join(MODEL_DIR, "vision_model.onnx")
OUTPUT_MODEL = os.path.join(MODEL_DIR, "vision_model_android_int8.onnx")
# ─────────────────────────────────────────────────────────────────

print("=" * 55)
print("MobileCLIP S2 — Android INT8 Requantization")
print("=" * 55)
print(f"Input:  {INPUT_MODEL}")
print(f"Output: {OUTPUT_MODEL}")
print()

# Verify input exists
if not os.path.exists(INPUT_MODEL):
    print(f"❌ Input model not found: {INPUT_MODEL}")
    print("   Update MODEL_DIR to the correct path")
    exit(1)

# Step 1: Find sensitive nodes to protect
print("Step 1/3 — Scanning model structure...")
model = onnx.load(INPUT_MODEL)

sensitive_keywords_by_name = [
    "layernorm", "layer_norm", "/ln_", "/norm",
    "softmax", "gelu", "/final"
]
sensitive_keywords_by_op = [
    "layernormalization", "softmax", "gelu",
    "instancenormalization", "batchnormalization"
]

nodes_to_exclude = []
for node in model.graph.node:
    name_lower = node.name.lower()
    op_lower   = node.op_type.lower()
    if (any(k in name_lower for k in sensitive_keywords_by_name) or
        any(k in op_lower   for k in sensitive_keywords_by_op)):
        nodes_to_exclude.append(node.name)

nodes_to_exclude = list(set(nodes_to_exclude))
total_nodes      = len(model.graph.node)
excluded_count   = len(nodes_to_exclude)
quantized_count  = total_nodes - excluded_count

print(f"  Total nodes:      {total_nodes}")
print(f"  Will quantize:    {quantized_count}")
print(f"  Will protect:     {excluded_count} (LayerNorm, Softmax, etc.)")
print()

# Step 2: Quantize
print("Step 2/3 — Running quantization (1-3 minutes)...")
start = time.time()

try:
    quantize_dynamic(
        model_input=INPUT_MODEL,
        model_output=OUTPUT_MODEL,
        weight_type=QuantType.QInt8,
        nodes_to_exclude=nodes_to_exclude,
        extra_options={
            'DefaultTensorType': 2,
            'UseQDQContribOps': False,
        }
    )
    elapsed = time.time() - start
    print(f"  Completed in {elapsed:.1f} seconds")
    print()

except Exception as e:
    print(f"❌ Quantization failed: {e}")
    print()
    print("Trying fallback with QUInt8...")
    quantize_dynamic(
        model_input=INPUT_MODEL,
        model_output=OUTPUT_MODEL,
        weight_type=QuantType.QUInt8,
        nodes_to_exclude=nodes_to_exclude,
    )
    print("  Fallback complete")
    print()

# Step 3: Report
print("Step 3/3 — Results:")
fp32_mb = os.path.getsize(INPUT_MODEL)  / (1024 * 1024)
int8_mb = os.path.getsize(OUTPUT_MODEL) / (1024 * 1024)
reduction = (fp32_mb - int8_mb) / fp32_mb * 100

print(f"  FP32 size:     {fp32_mb:.1f} MB")
print(f"  INT8 size:     {int8_mb:.1f} MB")
print(f"  Reduction:     {reduction:.0f}%")
print()
print(f"✅ Output saved: {OUTPUT_MODEL}")
print("   Proceed to Task 5 to verify quality.")
```

---

## TASK 5 — Verify Quality

```python
# verify_quality.py
import onnxruntime as ort
import numpy as np
from numpy.linalg import norm
import os

# ── PATHS ────────────────────────────────────────────────────────
MODEL_DIR  = "."
FP32_MODEL = os.path.join(MODEL_DIR, "vision_model.onnx")
INT8_MODEL = os.path.join(MODEL_DIR, "vision_model_android_int8.onnx")
# ─────────────────────────────────────────────────────────────────

print("Loading models...")
fp32 = ort.InferenceSession(FP32_MODEL)
int8 = ort.InferenceSession(INT8_MODEL)

input_name = fp32.get_inputs()[0].name
print(f"Input name: '{input_name}'")
print()

# Run quality tests
NUM_TESTS = 10
print(f"Running {NUM_TESTS} quality tests...")
scores = []

for i in range(NUM_TESTS):
    # Simulate normalized image input (256x256 RGB)
    test = np.random.randn(1, 3, 256, 256).astype(np.float32)

    out_fp32 = fp32.run(None, {input_name: test})[0][0]
    out_int8 = int8.run(None, {input_name: test})[0][0]

    # L2 normalize
    out_fp32 = out_fp32 / (norm(out_fp32) + 1e-8)
    out_int8 = out_int8 / (norm(out_int8) + 1e-8)

    score = float(np.dot(out_fp32, out_int8))
    scores.append(score)

    bar = "█" * int(score * 50) + "░" * (50 - int(score * 50))
    print(f"  Test {i+1:2d}: {score:.4f}  {bar}")

avg = sum(scores) / len(scores)
mn  = min(scores)
mx  = max(scores)

print()
print(f"Results:")
print(f"  Average:  {avg:.4f}")
print(f"  Minimum:  {mn:.4f}")
print(f"  Maximum:  {mx:.4f}")
print()

if avg > 0.990:
    print("✅ EXCELLENT quality — negligible accuracy loss")
    print("   Safe to use. Proceed to Task 6.")
elif avg > 0.975:
    print("✅ GOOD quality — acceptable for gallery search")
    print("   Safe to use. Proceed to Task 6.")
elif avg > 0.950:
    print("⚠️  ACCEPTABLE quality — some accuracy loss")
    print("   Usable, but lower your similarity threshold to 0.14 in GalleryRepository")
    print("   Proceed to Task 6.")
else:
    print("❌ POOR quality — too much accuracy loss")
    print("   Re-run requantize_android.py — it will try QUInt8 fallback")
```

---

## TASK 6 — Confirm Android Compatibility

```python
# confirm_android_compat.py
import onnx
import os

MODEL_DIR  = "."
NEW_MODEL  = os.path.join(MODEL_DIR, "vision_model_android_int8.onnx")
OLD_MODEL  = os.path.join(MODEL_DIR, "vision_model_int8.onnx")

print("Comparing old vs new model...")
print()

def scan_ops(path, label):
    model = onnx.load(path)
    ops = {}
    for node in model.graph.node:
        ops[node.op_type] = ops.get(node.op_type, 0) + 1
    
    bad  = {k: v for k, v in ops.items() if k in ["ConvInteger", "MatMulInteger"]}
    good = {k: v for k, v in ops.items() if k in ["QLinearConv", "QLinearMatMul"]}
    size = os.path.getsize(path) / (1024 * 1024)
    
    print(f"── {label} ({size:.1f} MB) ──")
    if bad:
        for op, count in bad.items():
            print(f"  ❌ {op}: {count}  ← Android incompatible")
    if good:
        for op, count in good.items():
            print(f"  ✅ {op}: {count}  ← Android compatible")
    if not bad and not good:
        print("  — No quantized conv operators found")
    print()
    return len(bad) == 0

print("Before vs After comparison:")
print()
old_ok = scan_ops(OLD_MODEL, "OLD — Xenova INT8 (broken)")
new_ok = scan_ops(NEW_MODEL, "NEW — Android INT8 (fixed)")

if new_ok:
    print("=" * 45)
    print("✅ NEW MODEL IS ANDROID COMPATIBLE")
    print("=" * 45)
    print()
    print("Next step — copy to your Android project:")
    new_path = os.path.abspath(NEW_MODEL)
    print(f"  Source: {new_path}")
    print(f"  Destination: app/src/main/assets/vision_model_android_int8.onnx")
    print()
    print("Then update ImageEncoder.kt:")
    print('  Change: context.assets.open("vision_model_int8.onnx")')
    print('  To:     context.assets.open("vision_model_android_int8.onnx")')
else:
    print("=" * 45)
    print("❌ STILL HAS INCOMPATIBLE OPERATORS")
    print("=" * 45)
    print("Re-run requantize_android.py")
```

---

## TASK 7 — Run All Tasks Automatically

If you want to run everything in one go:

```python
# run_all.py
import subprocess
import sys

scripts = [
    ("check_env.py",            "Checking Python environment"),
    ("locate_files.py",         "Locating model files"),
    ("confirm_problem.py",      "Confirming ConvInteger problem"),
    ("requantize_android.py",   "Requantizing for Android"),
    ("verify_quality.py",       "Verifying quality"),
    ("confirm_android_compat.py","Confirming Android compatibility"),
]

print("Running all tasks...\n")
for script, description in scripts:
    print("=" * 55)
    print(f"▶  {description}")
    print("=" * 55)
    result = subprocess.run([sys.executable, script], capture_output=False)
    if result.returncode != 0:
        print(f"\n❌ {script} failed — fix the error above before continuing")
        break
    print()

print("All tasks complete.")
```

Run with:
```
python run_all.py
```

---

## IMPORTANT NOTES FOR LLM

1. **All scripts use `MODEL_DIR = "."` by default** — this means they look in the same folder where the scripts are saved. If the model files are in a different folder, update `MODEL_DIR` in each script to the correct absolute path.

2. **Run scripts in order** — each task depends on the previous one succeeding.

3. **The only output file you need** is `vision_model_android_int8.onnx` — this goes into the Android project's `assets/` folder.

4. **Do not re-quantize the text model** — `text_model_int8.onnx` from Xenova works fine on Android. Only the vision model has the ConvInteger issue.

5. **After copying to Android:** Update the filename in `ImageEncoder.kt` from `vision_model_int8.onnx` to `vision_model_android_int8.onnx`. No other code changes needed.

6. **If quality score is below 0.950:** The `requantize_android.py` script automatically falls back to `QUInt8`. Re-run `verify_quality.py` after the fallback completes.

---

## File Structure When Done

```
your_folder/
    vision_model.onnx                  ← keep (source)
    vision_model_int8.onnx             ← keep (for comparison)
    vision_model_android_int8.onnx     ← NEW ✅ copy this to Android assets/
    text_model.onnx                    ← keep
    text_model_int8.onnx               ← keep (already works on Android)
    tokenizer.json                     ← copy to Android assets/
    tokenizer_config.json              ← copy to Android assets/
    preprocessor_config.json           ← copy to Android assets/
    config.json                        ← copy to Android assets/
    check_env.py
    locate_files.py
    confirm_problem.py
    requantize_android.py
    verify_quality.py
    confirm_android_compat.py
    run_all.py
```
