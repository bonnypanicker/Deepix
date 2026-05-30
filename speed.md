I have full context on every relevant file. I'll write a detailed, code-level implementation document (with copy-pasteable before/after Kotlin) to `/app/Deepix/INDEXING_SPEED_FIX.md`. No repo code is modified.
Action: file_editor create /app/Deepix/INDEXING_SPEED_FIX.md --file-text "# Deepix — Indexing Speed: Detailed Code-Level Fix

**Companion to:** `INDEXING_SPEED_PLAN.md` (strategy). This document is the **implementation spec** with concrete, copy-pasteable Kotlin for each fix.
**Status:** Reference only — *no files in this repo are modified by this document.* Each block shows the exact file, the current code, and the proposed replacement.
**Targets:** `onnxruntime-android:1.18.0`, minSdk 26 / targetSdk 35, Kotlin + coroutines.

> Apply in order. Re-baseline (Section 9) after each phase. Phases 1–2 need **no model change** and deliver most of the gain.

---

## 0. One-time: add stage timing (do this first)

Create `app/src/main/java/com/devomind/gallerysearch/StageTimer.kt`:

```kotlin
package com.devomind.gallerysearch

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Lightweight per-stage accumulator. Logs every [logEvery] images. */
class StageTimer(private val tag: String = \"IndexPerf\", private val logEvery: Int = 50) {
    private val decode = AtomicLong()
    private val preprocess = AtomicLong()
    private val inference = AtomicLong()
    private val persist = AtomicLong()
    private val count = AtomicLong()

    inline fun <T> measure(bucket: Bucket, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        val r = block()
        add(bucket, SystemClock.elapsedRealtimeNanos() - start)
        return r
    }

    fun add(bucket: Bucket, nanos: Long) {
        when (bucket) {
            Bucket.DECODE -> decode.addAndGet(nanos)
            Bucket.PREPROCESS -> preprocess.addAndGet(nanos)
            Bucket.INFERENCE -> inference.addAndGet(nanos)
            Bucket.PERSIST -> persist.addAndGet(nanos)
        }
    }

    fun onImagesDone(n: Int) {
        val total = count.addAndGet(n.toLong())
        if (total % logEvery < n) {
            Log.d(tag, \"after=$total imgs | decode=${ms(decode)}ms preprocess=${ms(preprocess)}ms \" +
                    \"inference=${ms(inference)}ms persist=${ms(persist)}ms\")
        }
    }

    private fun ms(a: AtomicLong) = a.get() / 1_000_000
    enum class Bucket { DECODE, PREPROCESS, INFERENCE, PERSIST }
}
```

Use it inside `buildIndex()` / `encodeBatch()` as shown in later sections. **You cannot tune what you don't measure.**

---

## PHASE 1 — Pipeline & decode

### 1.1 Parallelize the decode producer

**File:** `GalleryRepository.kt` → `buildIndex()`.
The current producer is a single `launch(Dispatchers.IO)` that decodes batches serially. Replace it with **N parallel decode workers** pulling from a URI queue and pushing finished batches to the inference channel.

**Current (lines ~191–212):**
```kotlin
coroutineScope {
    val channel = Channel<BatchData>(capacity = PipelineBuffer)

    val producer = launch(Dispatchers.IO) {
        for (batch in batches) {
            currentCoroutineContext().ensureActive()
            val bitmapEntries = mutableListOf<BitmapEntry>()
            for (uri in batch) {
                currentCoroutineContext().ensureActive()
                val bitmap = loadBitmap(uri)
                if (bitmap != null) {
                    bitmapEntries.add(BitmapEntry(uri, bitmap))
                }
            }
            if (bitmapEntries.isNotEmpty()) {
                channel.send(BatchData(bitmapEntries))
            }
        }
        channel.close()
    }
    // consumer ...
```

**Proposed:**
```kotlin
coroutineScope {
    val channel = Channel<BatchData>(capacity = PipelineBuffer)

    // Fan-out: N decoder coroutines share the batch list via an index channel.
    val batchQueue = Channel<List<Uri>>(capacity = Channel.UNLIMITED).apply {
        batches.forEach { trySend(it) }
        close()
    }

    val decoderCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    val ioDispatcher = Dispatchers.IO.limitedParallelism(decoderCount)

    val producers = (0 until decoderCount).map {
        launch(ioDispatcher) {
            for (batch in batchQueue) {
                currentCoroutineContext().ensureActive()
                val entries = ArrayList<BitmapEntry>(batch.size)
                for (uri in batch) {
                    currentCoroutineContext().ensureActive()
                    val t0 = android.os.SystemClock.elapsedRealtimeNanos()
                    val bitmap = loadBitmap(uri)
                    stageTimer.add(StageTimer.Bucket.DECODE,
                        android.os.SystemClock.elapsedRealtimeNanos() - t0)
                    if (bitmap != null) entries.add(BitmapEntry(uri, bitmap))
                }
                if (entries.isNotEmpty()) channel.send(BatchData(entries))
            }
        }
    }

    // Close the inference channel only after ALL producers finish.
    launch {
        producers.joinAll()
        channel.close()
    }
    // consumer loop unchanged ...
}
```
Add the field at class level: `private val stageTimer = StageTimer()`.
Add imports: `kotlinx.coroutines.joinAll`.

> **Why:** decode is usually the bottleneck. N workers saturate cores and keep the encoder fed. Bitmaps are still recycled in the consumer's `finally` block (unchanged).

---

### 1.2 Decode straight to ~256 px (kill the double resize + fixed sample size)

**File:** `GalleryRepository.kt` → `loadBitmap()`.
Currently uses a fixed `inSampleSize = 4`, then `scaleToMaxEdge(512)`, and later `ImageEncoder.preprocess()` rescales again to 256. Compute the sample size from real bounds and decode close to the model's 256 input.

**Current:**
```kotlin
fun loadBitmap(uri: Uri): Bitmap? {
    return runCatching {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 4
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) return null
            BitmapFactory.decodeStream(input, null, options)
        } ?: return null
        scaleToMaxEdge(decoded, MaxBitmapEdge)
    }.onFailure { Log.w(Tag, \"Failed to decode $uri\", it) }.getOrNull()
}
```

**Proposed:**
```kotlin
fun loadBitmap(uri: Uri): Bitmap? {
    return runCatching {
        // Pass 1: read dimensions only (no pixel allocation).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) return null
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Largest power-of-two sample that keeps shortest edge >= target.
        val target = ImageEncoder.ImageSize           // 256
        val shortEdge = minOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (shortEdge / (sample * 2) >= target) sample *= 2

        // Pass 2: decode downsampled. RGB_565 halves decode bandwidth (no alpha needed).
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) return null
            BitmapFactory.decodeStream(input, null, opts)
        } ?: return null

        // Single final scale so shortest edge == target (preprocess only center-crops after this).
        scaleToMaxEdge(decoded, MaxBitmapEdge)
    }.onFailure { Log.w(Tag, \"Failed to decode $uri\", it) }.getOrNull()
}
```

> **Why:** one decode close to target + one scale, instead of fixed `/4` → 512 → 256. RGB_565 halves decode memory. **Caveat:** RGB_565 loses minor color precision; validate search recall (Section 9). If quality dips, revert to `ARGB_8888` here and keep only the sample-size fix.

Lower `MaxBitmapEdge` to reduce the second scale's work (256 is enough since the model crops to 256):
```kotlin
private const val MaxBitmapEdge = 320   // was 512
```

---

### 1.3 Parallelize the CHW pixel-pack loop

**File:** `ImageEncoder.kt` → `preprocess()`.
The per-pixel loop is single-threaded. Parallelize across rows.

**Current:**
```kotlin
val floats = FloatArray(3 * ImageSize * ImageSize)
val planeSize = ImageSize * ImageSize
for (index in pixels.indices) {
    val color = pixels[index]
    floats[index] = normalize(Color.red(color) / 255f, 0)
    floats[planeSize + index] = normalize(Color.green(color) / 255f, 1)
    floats[2 * planeSize + index] = normalize(Color.blue(color) / 255f, 2)
}
return floats
```

**Proposed (parallel rows via Java streams; no extra deps):**
```kotlin
val floats = FloatArray(3 * ImageSize * ImageSize)
val planeSize = ImageSize * ImageSize
java.util.stream.IntStream.range(0, ImageSize).parallel().forEach { y ->
    val rowStart = y * ImageSize
    for (x in 0 until ImageSize) {
        val index = rowStart + x
        val color = pixels[index]
        floats[index] = normalize(Color.red(color) / 255f, 0)
        floats[planeSize + index] = normalize(Color.green(color) / 255f, 1)
        floats[2 * planeSize + index] = normalize(Color.blue(color) / 255f, 2)
    }
}
return floats
```

> **Why:** spreads ~65 k pixel ops across cores. Note: `do_normalize=false` in `preprocessor_config.json`, so `normalize()` is a passthrough — the loop is pure rescale + repack. The **bigger** long-term win is baking rescale+transpose into the ONNX graph (offline), letting you feed raw uint8 — see Phase 4.4.

---

### 1.4 Tune pipeline buffer + adaptive batch size

**File:** `GalleryRepository.kt` companion constants.

**Current:**
```kotlin
const val BatchSize = 4
private const val PipelineBuffer = 2
```

**Proposed:**
```kotlin
private const val PipelineBuffer = 4          // keep decoders ahead of inference

// Batch size is now chosen at runtime by ExecutionProvider (see Phase 3/4).
// CPU/XNNPACK: 4–8, NNAPI: 8, QNN HTP: 8–16.
@Volatile var BatchSize = 4                    // set by IndexWorker before buildIndex()
```
Set it in `IndexWorker.doWork()` after EP selection:
```kotlin
GalleryRepository.BatchSize = OnnxSessionOptions.recommendedBatchSize(applicationContext)
```

The existing single-image fallback in the consumer already covers OOM on too-large batches — keep it.

---

### 1.5 Expedited WorkManager + sensible constraints

**File:** `MainActivity.kt` → `enqueueBackgroundIndexing()`.

**Current:**
```kotlin
val request = OneTimeWorkRequestBuilder<IndexWorker>()
    .setInputData(payload)
    .build()
```

**Proposed:**
```kotlin
val request = OneTimeWorkRequestBuilder<IndexWorker>()
    .setInputData(payload)
    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    .setConstraints(
        Constraints.Builder()
            .setRequiresBatteryNotLow(true)   // avoid heavy indexing on low battery
            .build()
    )
    .build()
```
Add imports: `androidx.work.OutOfQuotaPolicy`, `androidx.work.Constraints`.

> **Why:** expedited asks the OS for a foreground-grade compute slice (falls back gracefully if quota is exhausted). The existing foreground notification in `IndexWorker` already satisfies expedited-work requirements on API 31+.

---

## PHASE 2 — ONNX Runtime session tuning (no model change)

### 2.1 Actually use `ThreadBenchmark` (currently dead code)

`ThreadBenchmark.getOrBenchmark()` is never called; `OnnxSessionOptions.create()` always uses `DefaultThreadCount = 4`.

**File:** `IndexWorker.kt` → `doWork()` (before creating encoders):
```kotlin
val threads = ThreadBenchmark.getOrBenchmark(applicationContext)
imageEncoder = ImageEncoder(applicationContext, threads)
textEncoder = TextEncoder(applicationContext, threads)
```

**File:** `ImageEncoder.kt` / `TextEncoder.kt` — accept and forward the thread count:
```kotlin
class ImageEncoder(
    private val context: Context,
    threadCount: Int = 4
) : AutoCloseable {
    ...
    private val options = OnnxSessionOptions.create(Tag, threadCount)
    init { session = environment.createSession(modelBytes, options) }
}
```
(`OnnxSessionOptions.create(tag, threadCount)` already accepts `threadCount` — just pass it through instead of relying on the default.)

---

### 2.2 Add XNNPACK CPU execution provider (broadest win)

**File:** `OnnxSessionOptions.kt`. Append XNNPACK after the accelerator attempt (it's bundled in ORT 1.18 mobile AAR; registered via reflection for version safety).

Add to `create()`:
```kotlin
fun create(tag: String, threadCount: Int = DefaultThreadCount): OrtSession.SessionOptions {
    return OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threadCount)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        setMemoryPatternOptimization(true)
        tryAddNnapiWithFlags(this, tag)        // existing accelerator path
        tryAddXnnpack(this, threadCount, tag)  // NEW: fast CPU fallback
    }
}

private fun tryAddXnnpack(options: OrtSession.SessionOptions, threads: Int, tag: String) {
    try {
        val method = options.javaClass.methods.firstOrNull {
            it.name == \"addXnnpack\" && it.parameterCount == 1
        } ?: run {
            Log.d(tag, \"XNNPACK overload not found in this ORT build\"); return
        }
        // addXnnpack(Map<String,String>)
        val cfg = mapOf(\"intra_op_num_threads\" to threads.toString())
        method.invoke(options, cfg)
        Log.d(tag, \"XNNPACK EP enabled (threads=$threads)\")
    } catch (e: Exception) {
        Log.d(tag, \"XNNPACK unavailable: ${e.message}\")
    }
}
```

> **Why:** XNNPACK is a highly optimized fp32 ARM CPU backend — typically faster than ORT's default CPU EP, and it benefits **every** device regardless of NPU support.

---

### 2.3 Reuse a direct buffer + cache the optimized graph

**File:** `ImageEncoder.kt`.

**(a) Reusable direct buffer** — avoid per-batch `FloatArray`/`FloatBuffer` allocation in `encodeBatch()`:
```kotlin
// Sized for the max batch once; reused across calls.
private val maxBatch = 16
private val reusableBuffer: FloatBuffer =
    java.nio.ByteBuffer
        .allocateDirect(maxBatch * 3 * ImageSize * ImageSize * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer()
```
In `encodeBatch()`, fill `reusableBuffer` (clear/position 0, put each preprocessed image) instead of `FloatArray(batchSize * imageFloatCount)` + `FloatBuffer.wrap(...)`.

**(b) Cache optimized graph** — pay graph optimization cost once. In `OnnxSessionOptions.create()`:
```kotlin
// Persist the optimized model so subsequent launches skip re-optimization.
val cacheDir = OnnxSessionOptions.optimizedCacheDir   // inject context.filesDir path
setOptimizedModelFilePath(\"$cacheDir/$tag-optimized.ort\")
```
(Provide the path via a setter or pass `context.filesDir` into `create()`.)

> **Why:** removes JNI copy + GC churn per batch, and shaves session warm-up on every app start.

---

## PHASE 3 — Hardware acceleration (device-tiered EP ladder)

> NNAPI is **deprecated in Android 15** (this app targets SDK 35). Keep it as fallback; prefer QNN on Snapdragon. **Always verify correctness** — accelerators can silently CPU-fall-back or degrade output.

### 3.1 EP ladder with correctness + speed probe

Extend `ThreadBenchmark.kt` (or add `ExecutionProviderSelector.kt`) to probe candidate EPs once and cache the winner.

```kotlin
package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

object ExecutionProviderSelector {
    private const val Tag = \"EPSelector\"

    enum class Ep { QNN_HTP, NNAPI, XNNPACK, CPU }

    /** Returns the cached or freshly-probed best EP for this device. */
    fun getOrSelect(context: Context): Ep {
        IndexPreferences.getChosenEp(context)?.let { return it }
        val chosen = probe(context)
        IndexPreferences.saveChosenEp(context, chosen)
        return chosen
    }

    private fun probe(context: Context): Ep {
        val env = OrtEnvironment.getEnvironment()
        val modelBytes = AssetUtils.readAssetBytes(context, \"vision_model_fp16.onnx\")
        val input = FloatArray(3 * ImageEncoder.ImageSize * ImageEncoder.ImageSize) { 0.5f }

        // Reference output on plain CPU for correctness comparison.
        val reference = runOnce(env, modelBytes, input, Ep.CPU) ?: return Ep.CPU

        var best = Ep.CPU
        var bestMs = Long.MAX_VALUE
        for (ep in listOf(Ep.QNN_HTP, Ep.NNAPI, Ep.XNNPACK)) {
            val (ms, out) = timeAndRun(env, modelBytes, input, ep) ?: continue
            val cos = out?.let { cosine(reference, it) } ?: 0f
            Log.d(Tag, \"$ep -> ${ms}ms, cosine=$cos\")
            if (cos >= 0.99f && ms < bestMs) { bestMs = ms; best = ep }   // correctness gate
        }
        Log.d(Tag, \"Selected EP: $best\")
        return best
    }

    // runOnce / timeAndRun build SessionOptions per EP (QNN via addQnn, NNAPI via existing
    // reflection path, XNNPACK via addXnnpack) and run the synthetic tensor.
    // Implementation mirrors ThreadBenchmark.benchmarkWithThreads(). Omitted for brevity.

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i] }
        return if (na == 0f || nb == 0f) 0f else dot / (Math.sqrt((na*nb).toDouble())).toFloat()
    }
}
```

`OnnxSessionOptions.create()` then branches on the chosen EP:
```kotlin
fun create(tag: String, threadCount: Int, ep: ExecutionProviderSelector.Ep): OrtSession.SessionOptions =
    OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threadCount)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        when (ep) {
            ExecutionProviderSelector.Ep.QNN_HTP -> tryAddQnnHtp(this, tag)
            ExecutionProviderSelector.Ep.NNAPI   -> tryAddNnapiWithFlags(this, tag)
            ExecutionProviderSelector.Ep.XNNPACK -> tryAddXnnpack(this, threadCount, tag)
            ExecutionProviderSelector.Ep.CPU     -> { /* default */ }
        }
    }
```

Add to `IndexPreferences.kt`:
```kotlin
private const val KeyChosenEp = \"chosen_ep\"
fun saveChosenEp(c: Context, ep: ExecutionProviderSelector.Ep) =
    c.getSharedPreferences(PrefName, Context.MODE_PRIVATE).edit().putString(KeyChosenEp, ep.name).apply()
fun getChosenEp(c: Context): ExecutionProviderSelector.Ep? =
    c.getSharedPreferences(PrefName, Context.MODE_PRIVATE).getString(KeyChosenEp, null)
        ?.let { runCatching { ExecutionProviderSelector.Ep.valueOf(it) }.getOrNull() }
```

> **Why:** lands on the fastest **correct** path per device (flagship/mid/budget) rather than assuming. The cosine gate catches silent NNAPI/HTP accuracy fallbacks.

### 3.2 QNN EP (Snapdragon HTP) — highest payoff on Qualcomm

**File:** `app/build.gradle` — add the QNN package (and bundle HTP `.so` libs), arm64 only:
```gradle
dependencies {
    // existing ...
    implementation \"com.microsoft.onnxruntime:onnxruntime-android-qnn:1.18.0\"
}
android {
    defaultConfig {
        ndk { abiFilters \"arm64-v8a\" }   // QNN HTP is arm64-only; controls APK size
    }
}
```
`tryAddQnnHtp()` registers QNN with `backend_path` → `libQnnHtp.so` and requires the **int8/QDQ** vision model (Phase 4.1) for full HTP offload.

> **Why:** Snapdragon HTP NPU offload is the single largest speedup on the most common flagship/mid SoCs.

---

## PHASE 4 — Model-level optimization

> The repo already ships `requantize_android.py`, `compress_weights_android.py`, `compare_models.py`, `verify_quality.py` and `Requantize_Local_LLM_Guide.md`. Reuse them — these are **offline** steps, the app only consumes the produced asset.

### 4.1 Produce an int8 (QDQ) vision model (offline)
```bash
# Static quantization with calibration images (representative gallery sample).
python requantize_android.py \
    --input vision_model_fp16.onnx \
    --output app/src/main/assets/vision_model_int8.onnx \
    --calib ./calib_images --mode qdq --per-channel

# Validate search quality vs fp32 reference before shipping.
python verify_quality.py --ref vision_model_fp16.onnx --cand vision_model_int8.onnx
python compare_models.py
```
Quality gate: cosine ≥ ~0.98 vs fp32 and top-k overlap parity on a labeled query set.

### 4.2 Runtime model + batch selection
**File:** `ImageEncoder.kt` companion + a small selector. Pick asset by chosen EP:
```kotlin
// int8 for CPU/XNNPACK/NPU; fp16 only when probe proved a true GPU/NPU fp16 win.
private fun visionAssetFor(ep: ExecutionProviderSelector.Ep): String = when (ep) {
    ExecutionProviderSelector.Ep.QNN_HTP,
    ExecutionProviderSelector.Ep.NNAPI,
    ExecutionProviderSelector.Ep.XNNPACK,
    ExecutionProviderSelector.Ep.CPU -> \"vision_model_int8.onnx\"
}
```
And `recommendedBatchSize()` in `OnnxSessionOptions`:
```kotlin
fun recommendedBatchSize(context: Context): Int = when (ExecutionProviderSelector.getOrSelect(context)) {
    ExecutionProviderSelector.Ep.QNN_HTP -> 16
    ExecutionProviderSelector.Ep.NNAPI   -> 8
    else                                 -> 6
}
```
Keep `vision_model_fp16.onnx` as fallback asset.

### 4.3 Optional MobileCLIP-S0/S1 \"fast mode\" (budget devices)
Ship as an **on-demand download** (not in base APK). Detect low-end via `ActivityManager.isLowRamDevice` / core count, expose a toggle. Note: S0/S1 use a different embedding space → require their **own text encoder** and a full **re-index** on switch (treat as a distinct mode, not hot-swap).

### 4.4 (Best long-term) bake preprocess into the graph (offline)
Prepend `Cast(uint8→float) → Mul(1/255) → Transpose(HWC→CHW)` to the ONNX model so the app feeds raw `uint8` `[N,256,256,3]`. Eliminates the Kotlin pixel loop (Phase 1.3) entirely and lets the accelerator do it.

---

## PHASE 5 — Persistence & incremental indexing

### 5.1 Stop full-file rewrites — append checkpoints
**File:** `GalleryRepository.kt`. Current `saveIndex()` rewrites the **entire** `.bin` every `SaveEvery = 20` → O(n²) IO across a big build.

Minimal fix — append only the new records during checkpoints, compact once at the end:
```kotlin
// Append a batch of new entries without rewriting existing data.
private fun appendIndex(newEntries: List<Pair<String, FloatArray>>) {
    DataOutputStream(BufferedOutputStream(java.io.FileOutputStream(indexFile, /*append=*/true))).use { out ->
        for ((uri, emb) in newEntries) {
            val b = uri.toByteArray(Charsets.UTF_8)
            out.writeInt(b.size); out.write(b)
            out.writeInt(emb.size); for (v in emb) out.writeFloat(v)
        }
    }
}
```
(Write the magic/version/count header once on first create; store count in a small sidecar or recompute on load. Or jump to 5.1b.)

**5.1b Preferred: move to SQLite/Room**
```kotlin
@Entity(tableName = \"embeddings\")
data class EmbeddingRow(
    @PrimaryKey val uri: String,
    val dims: Int,
    val vec: ByteArray   // fp16-packed (see 5.2)
)
```
Insert per batch on `Dispatchers.IO` off the inference thread. O(batch) checkpoints, free deletion handling, indexed lookups.

### 5.2 Store embeddings as fp16 on disk
**File:** `EmbeddingUtils.kt` — add pack/unpack; halves index file size and write bandwidth (search math stays fp32 after unpack):
```kotlin
fun packFp16(v: FloatArray): ByteArray {
    val bb = java.nio.ByteBuffer.allocate(v.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (f in v) bb.putShort(floatToHalf(f))
    return bb.array()
}
fun unpackFp16(bytes: ByteArray, dims: Int): FloatArray {
    val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    return FloatArray(dims) { halfToFloat(bb.short) }
}
// floatToHalf / halfToFloat: standard IEEE-754 half conversions.
```

### 5.3 Use the existing incremental query (currently unused)
**File:** `IndexWorker.kt` → `doWork()`. `GalleryRepository.getNewImageUris()` already exists but is never called. For top-up runs:
```kotlin
val lastIndexed = IndexPreferences.getLastIndexedTime(applicationContext)
val uris = if (lastIndexed > 0L && !albumSelectionChanged)
    repository.getNewImageUris(selected, lastIndexed)     // only new photos
else
    repository.getImageUrisForAlbumIds(selected)          // first run / album change
```
> **Why:** avoids re-listing thousands of already-indexed URIs every run.

### 5.4 Prune deleted images
Periodically drop index rows whose URIs no longer resolve (try `openInputStream` / MediaStore existence) so the index doesn't grow unbounded and search stays fast.

---

## 6. `app/build.gradle` change summary

```gradle
android {
    defaultConfig {
        ndk { abiFilters \"arm64-v8a\" }          // 3.2 — QNN HTP, smaller APK
    }
    // existing noCompress \"onnx\", viewBinding, java 17 ...
}
dependencies {
    // existing ...
    implementation \"com.microsoft.onnxruntime:onnxruntime-android-qnn:1.18.0\"  // 3.2 (replaces base ORT)
    implementation \"androidx.room:room-runtime:2.6.1\"   // 5.1b (optional)
    kapt           \"androidx.room:room-compiler:2.6.1\"
}
```
> Note: `onnxruntime-android-qnn` supersedes `onnxruntime-android`; do not include both. XNNPACK is bundled in both packages.

---

## 7. File-by-file change map

| File | Sections |
|---|---|
| `StageTimer.kt` *(new)* | 0 |
| `GalleryRepository.kt` | 1.1, 1.2, 1.4, 5.1, 5.2, 5.4 |
| `ImageEncoder.kt` | 1.3, 2.1, 2.3, 4.2, 4.4 |
| `OnnxSessionOptions.kt` | 2.2, 2.3, 3.1, 3.2, 4.2 |
| `ThreadBenchmark.kt` / `ExecutionProviderSelector.kt` *(new)* | 2.1, 3.1 |
| `IndexPreferences.kt` | 3.1 (chosen EP), batch size |
| `IndexWorker.kt` | 1.4, 2.1, 5.3 |
| `MainActivity.kt` | 1.5, optional fast-mode toggle (4.3) |
| `EmbeddingUtils.kt` | 5.2 |
| `app/build.gradle` | 6 (QNN, abiFilters, Room) |
| `app/src/main/assets/` | 4.1 int8 model, 4.3 optional S0/S1 |
| Python tools | 4.1 produce + validate int8 |

---

## 8. Suggested order & rough effort

| Order | Item | Effort | Model change? |
|---|---|---|---|
| 1 | Phase 0 timers | 0.5d | no |
| 2 | 1.1 parallel decode | 0.5d | no |
| 3 | 1.2 smart decode | 0.5d | no |
| 4 | 2.1 wire ThreadBenchmark | 0.25d | no |
| 5 | 2.2 XNNPACK | 0.5d | no |
| 6 | 1.5 expedited work | 0.25d | no |
| 7 | 5.3 incremental query | 0.25d | no |
| 8 | 4.1 + 4.2 int8 model + selection | 1–2d | yes (offline) |
| 9 | 3.1 EP probe | 1d | no |
| 10 | 3.2 QNN HTP | 1–2d | needs int8 |
| 11 | 5.1/5.2 persistence | 1d | no |
| 12 | 1.3 / 4.4 preprocess | 0.5–1d | 4.4 offline |

**Phases 1–2 + 5.3 (~2.5 dev-days) are pure-code, no-model-change wins** and typically deliver 2–4× before you touch quantization or NPUs.

---

## 9. Validation after each change

1. Delete `embedding_index.bin`; time a **cold** full index of a fixed 1 000-photo set; then a **warm** re-run (must be near-instant via incremental skip).
2. Read `StageTimer` logs — confirm which stage dropped; verify no stage regressed.
3. After any decode/model/EP change, run `verify_quality.py` + `compare_models.py` (cosine ≥ ~0.98 vs fp32, top-k overlap parity) **and** a manual spot-check of 10 search queries.
4. Device matrix: flagship Snapdragon, mid-range, budget (≤4 GB). Record img/s, peak RAM, thermal throttling, chosen EP/threads/batch from logs.

---

*End of code-level fix spec. Pair with `INDEXING_SPEED_PLAN.md` for rationale and tradeoffs.*
"
Observation: Create successful: /app/Deepix/INDEXING_SPEED_FIX.md