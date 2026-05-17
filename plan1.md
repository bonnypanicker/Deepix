
Action: file_editor view /app/detailed.md
Observation: [Showing lines 1-838 of 838 total] /app/detailed.md:
1|# Android Gallery App — MobileCLIP-S2 Semantic Search
2|## Detailed Implementation Plan (Consolidated & Conflict-Resolved)
3|
4|> **Source documents synthesized:**
5|> 1. `GALLERY_APP_PLAN-1.md` — base architecture
6|> 2. `imp.md` — first improvement pass (L2-normalized export, foreground bypass, SearchCache, ghost-vector cleanup)
7|> 3. `imp2.md` — critical-review pass (off-heap buffer, race conditions, tokenizer warnings, Coil cache strategy)
8|>
9|> **Focus:** Solidify the **search function** end-to-end. Gallery UI, edit features, favorites, trash and timeline are deferred. Permissions, album scoping and indexing remain in scope because search quality is meaningless without a correct and resilient index.
10|
11|---
12|
13|## 0. Decision Log (Conflicts Resolved Up-Front)
14|
15|| # | Topic | imp.md proposed | imp2.md objected | **Final decision** |
16||---|---|---|---|---|
17|| 1 | In-memory vector store | `ConcurrentHashMap<Long, FloatArray>` | Triggers GC thrashing + OOM on budget devices | **Off-heap `DirectByteBuffer`** + parallel `LongArray` id map |
18|| 2 | Foreground indexing bypass | Direct coroutine bypass | Race condition — orphans rows stuck in `PROCESSING` | Keep bypass, **add `lastHeartbeatAt`** + orphan reclaim + `NonCancellable` DB write |
19|| 3 | Tokenizer | Custom BPE | Custom BPE is a trap; fallback methods break accuracy | **ONNX Runtime Extensions** (tokenizer fused into graph) — fall back to a verified HF Kotlin port only if ORT-Ext unavailable |
20|| 4 | Linear scan vs FAISS | Linear over RAM | Acceptable on native buffer; add telemetry | **Linear over `DirectByteBuffer`** in Phase 1; FAISS gated on telemetry trigger (>30K photos OR >150ms p95) |
21|| 5 | Coil cache | Default ImageLoader | Search shatters locality assumptions | **Dedicated `ImageLoader` for SearchScreen**: large disk cache, small volatile memory cache |
22|| 6 | Model precision | FP32 + INT8 dynamic | Switching mid-life corrupts vector space | **Lock precision in `SettingsEntity` at first run**; switching requires full re-index |
23|| 7 | L2 normalization | Done in Kotlin after inference | Math bugs / drift risk | **Bake `F.normalize` into ONNX graph** (`NormalizedVisualEncoder` wrapper) |
24|
25|---
26|
27|## 1. Goals & Non-Goals (Phase 1)
28|
29|### In scope
30|- On-device, fully offline semantic image search using MobileCLIP-S2.
31|- Automatic, OOM-safe background indexing of user-selected albums.
32|- Real-time indexing of newly captured photos when app is foregrounded.
33|- Crash/reboot/OOM-survivable index queue with retry + orphan reclaim.
34|- Deletion reconciliation (ghost-vector cleanup).
35|- Minimal but correct search UI (text field → result grid).
36|
37|### Out of scope (later phases)
38|- Gallery grid, timeline, favorites, trash, album browser.
39|- Photo edit features (crop, rotate, filter).
40|- OCR-based hybrid search (schema present, retrieval logic deferred).
41|- Image-to-image / duplicate detection.
42|- Shared elements, widgets, polish.
43|
44|---
45|
46|## 2. Tech Stack (Final)
47|
48|| Layer | Choice | Notes |
49||---|---|---|
50|| Language | Kotlin (JVM target 17) | — |
51|| UI | Jetpack Compose + Material 3 | Skeleton only |
52|| ML runtime | **ONNX Runtime for Android 1.18+** with NNAPI | Hybrid CNN+transformer needs ORT, not TFLite |
53|| Tokenizer | **ONNX Runtime Extensions** (preferred) → HF Kotlin port (fallback) | Never roll-your-own BPE |
54|| Vector store (RAM) | **`ByteBuffer.allocateDirect`** (off-heap) | One contiguous buffer, native order |
55|| DB | Room + SQLite | Embeddings as BLOB |
56|| Background | WorkManager 2.9+ | `UniqueWork` for full + periodic safety net |
57|| Media | MediaStore + Photo Picker (API 33+) | Album-scoped |
58|| DI | Hilt | — |
59|| Image loading | Coil 2.6+ | Two `ImageLoader` instances (gallery + search) |
60|| Permissions | `READ_MEDIA_VISUAL_USER_SELECTED` (API 34+) tiered fallback | — |
61|| OCR (Phase 2) | ML Kit Text Recognition | Schema ready Day 1, processing deferred |
62|
63|---
64|
65|## 3. MobileCLIP-S2 Model Pipeline
66|
67|### 3.1 Offline export (one-time, Python)
68|
69|The output of `imageSession.run()` and `textSession.run()` MUST be L2-normalized vectors. Bake it into the graph — no Kotlin-side normalization.
70|
71|```python
72|import open_clip, torch
73|from mobileclip.modules.common.mobileone import reparameterize_model
74|
75|class NormalizedVisualEncoder(torch.nn.Module):
76|    def __init__(self, visual): super().__init__(); self.visual = visual
77|    def forward(self, x):
78|        f = self.visual(x)
79|        return torch.nn.functional.normalize(f, p=2, dim=-1)
80|
81|class NormalizedTextEncoder(torch.nn.Module):
82|    def __init__(self, m): super().__init__(); self.m = m
83|    def forward(self, ids):
84|        f = self.m.encode_text(ids)
85|        return torch.nn.functional.normalize(f, p=2, dim=-1)
86|
87|model, _, preprocess = open_clip.create_model_and_transforms(
88|    "MobileCLIP-S2", pretrained="datacompdr")
89|model.eval()
90|model = reparameterize_model(model)   # CRITICAL
91|
92|# Image encoder
93|torch.onnx.export(NormalizedVisualEncoder(model.visual),
94|    torch.randn(1, 3, 256, 256), "mobileclip_s2_image.onnx",
95|    input_names=["image"], output_names=["embedding"],
96|    dynamic_axes={"image": {0: "batch"}}, opset_version=17)
97|
98|# Text encoder (token ids shape depends on MobileCLIP tokenizer; SimpleTokenizer uses 77)
99|torch.onnx.export(NormalizedTextEncoder(model),
100|    torch.randint(0, 49408, (1, 77)), "mobileclip_s2_text.onnx",
101|    input_names=["token_ids"], output_names=["embedding"],
102|    dynamic_axes={"token_ids": {0: "batch"}}, opset_version=17)
103|```
104|
105|> **Verify before shipping (Section 17.D):** MobileCLIP-S2 reuses the standard CLIP
106|> tokenizer with context length 77, but confirm against `open_clip.get_tokenizer("MobileCLIP-S2").context_length`
107|> before locking the Kotlin tensor shape. A mismatched length yields an ORT session
108|> creation error at runtime, not at build time.
109|
110|### 3.2 INT8 quantization (second artifact, ship both)
111|
112|```python
113|from onnxruntime.quantization import quantize_dynamic, QuantType
114|quantize_dynamic("mobileclip_s2_image.onnx",
115|                 "mobileclip_s2_image_int8.onnx",
116|                 weight_type=QuantType.QInt8)
117|```
118|
119|| Variant | Size | Recall drop | RAM bucket |
120||---|---|---|---|
121|| FP32 image encoder | ~45 MB | baseline | device totalMem ≥ 4 GB |
122|| INT8 image encoder | ~22 MB | <0.5% | device totalMem < 4 GB |
123|
124|### 3.3 Precision-lock invariant
125|On first launch, write `DEPLOYED_MODEL_PRECISION = FP32|INT8` to `SettingsEntity`.
126|**Mixing precisions in one DB pollutes cosine-similarity space.** Switching at runtime requires:
127|1. Drop `embeddings` table.
128|2. Re-enqueue every `PhotoEntity` with `isIndexed = false`.
129|3. Restart full-index worker.
130|
131|### 3.4 Hard-coded preprocessing constants (CLIP, not ImageNet)
132|- Input size: **256×256** (NOT 224 — MobileCLIP-S2 native resolution).
133|- Channel order: RGB.
134|- Mean: `[0.48145466, 0.4578275, 0.40821073]`
135|- Std:  `[0.26862954, 0.26130258, 0.27577711]`
136|- Layout: `NCHW`, float32.
137|
138|### 3.5 Asset placement
139|```
140|app/src/main/assets/
141|├── mobileclip_s2_image.onnx           # FP32
142|├── mobileclip_s2_image_int8.onnx      # INT8
143|├── mobileclip_s2_text.onnx            # FP32 (text encoder is small; INT8 not needed)
144|├── mobileclip_bpe_vocab.txt
145|└── mobileclip_bpe_merges.txt
146|```
147|
148|---
149|
150|## 4. Project Structure
151|
152|```
153|app/
154|├── data/
155|│   ├── db/
156|│   │   ├── AppDatabase.kt
157|│   │   ├── dao/  (PhotoDao, AlbumDao, EmbeddingDao, IndexQueueDao, SettingsDao)
158|│   │   └── entities/  (PhotoEntity, AlbumEntity, EmbeddingEntity, IndexQueueEntity, SettingsEntity)
159|│   ├── repository/  (PhotoRepository, AlbumRepository, SearchRepository, SettingsRepository)
160|│   └── mediastore/MediaStoreHelper.kt
161|│
162|├── ml/
163|│   ├── MobileCLIPSession.kt         # ORT session lifecycle
164|│   ├── ImageEncoder.kt              # Bitmap → float[512]
165|│   ├── TextEncoder.kt               # String → float[512]
166|│   ├── Preprocessor.kt              # 256×256 NCHW float tensor
167|│   ├── Tokenizer.kt                 # ORT-Extensions wrapper (interface-only, swappable)
168|│   └── BitmapLoader.kt              # OOM-safe two-step inSampleSize decode
169|│
170|├── indexing/
171|│   ├── IndexingWorker.kt
172|│   ├── IndexingScheduler.kt
173|│   ├── IndexQueue.kt                # priority + retry + heartbeat
174|│   ├── OrphanReclaimer.kt           # PROCESSING-stuck recovery
175|│   ├── MediaObserver.kt             # ContentObserver real-time
176|│   ├── DeletionReconciler.kt        # Ghost-vector cleanup
177|│   ├── ForegroundIndexer.kt         # Bypass path
178|│   └── IndexingState.kt             # StateFlow<IndexingProgress>
179|│
180|├── search/
181|│   ├── EmbeddingStore.kt            # OFF-HEAP DirectByteBuffer manager
182|│   ├── SearchEngine.kt              # Dot-product scan over native buffer
183|│   └── SearchTelemetry.kt           # p95 latency + cohort size logger
184|│
185|├── permissions/
186|│   ├── PermissionManager.kt
187|│   └── AlbumSelector.kt
188|│
189|├── ui/
190|│   ├── MainActivity.kt
191|│   ├── search/  (SearchScreen, SearchViewModel)
192|│   ├── albums/  (AlbumSelectionScreen, AlbumViewModel)
193|│   └── onboarding/OnboardingScreen.kt
194|│
195|└── di/  (DatabaseModule, MLModule, RepositoryModule, WorkerModule)
196|```
197|
198|---
199|
200|## 5. Database Schema (Day-1 final shape)
201|
202|```kotlin
203|@Entity(tableName = "photos")
204|data class PhotoEntity(
205|    @PrimaryKey val id: Long,           // MediaStore ID
206|    val uri: String,
207|    val albumId: Long,
208|    val dateTaken: Long,
209|    val modifiedAt: Long,               // re-edit detection
210|    val width: Int, val height: Int,
211|    val mimeType: String,
212|    val isIndexed: Boolean = false,
213|    val indexedAt: Long? = null,
214|    val ocrText: String? = null         // Phase 2 — schema ready now
215|)
216|
217|@Entity(tableName = "embeddings")
218|data class EmbeddingEntity(
219|    @PrimaryKey val photoId: Long,
220|    val vector: ByteArray               // 512 × 4 = 2048 B per photo
221|)
222|
223|@Entity(tableName = "albums")
224|data class AlbumEntity(
225|    @PrimaryKey val id: Long,
226|    val name: String,
227|    val coverUri: String?,
228|    val isIndexingAllowed: Boolean = false,
229|    val photoCount: Int = 0
230|)
231|
232|@Entity(tableName = "index_queue",
233|        indices = [Index("priority"), Index("status"), Index("lastHeartbeatAt")])
234|data class IndexQueueEntity(
235|    @PrimaryKey val mediaId: Long,
236|    val priority: Int,                  // 0 = highest (camera/recent)
237|    val createdAt: Long,
238|    val retryCount: Int = 0,
239|    val status: String = "PENDING",     // PENDING | PROCESSING | FAILED
240|    val lastHeartbeatAt: Long = 0L      // orphan-reclaim watermark
241|)
242|
243|@Entity(tableName = "settings")
244|data class SettingsEntity(
245|    @PrimaryKey val key: String,        // DEPLOYED_MODEL_PRECISION, INDEX_VERSION...
246|    val value: String
247|)
248|```
249|
250|**Storage budget:** 2 KB per embedding × 50 K photos ≈ 100 MB SQLite BLOB. Acceptable.
251|
252|---
253|
254|## 6. Permissions & Album Scoping
255|
256|Tiered runtime permission strategy:
257|
258|| API level | Permission requested |
259||---|---|
260|| 34+ | `READ_MEDIA_VISUAL_USER_SELECTED` (partial-photo access) |
261|| 33 | `READ_MEDIA_IMAGES` |
262|| ≤32 | `READ_EXTERNAL_STORAGE` |
263|
264|Flow:
265|1. Onboarding requests appropriate permission.
266|2. `MediaStoreHelper.listAlbums()` enumerates accessible buckets.
267|3. `AlbumSelectionScreen` lets the user toggle per-album indexing → `AlbumEntity.isIndexingAllowed`.
268|4. Only allowed albums feed `IndexQueue`.
269|5. Disabling an album: delete its `PhotoEntity` rows + corresponding `EmbeddingEntity` rows + `SearchCache.remove()`.
270|
271|---
272|
273|## 7. Indexing Pipeline
274|
275|### 7.1 OOM-safe bitmap decode (two-pass `inSampleSize`)
276|
277|Mandatory for 50–108 MP source images.
278|
279|```kotlin
280|fun loadDownsampledBitmap(ctx: Context, uri: Uri, target: Int = 256): Bitmap? {
281|    val cr = ctx.contentResolver
282|    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
283|    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
284|    opts.inSampleSize = calculateInSampleSize(opts, target, target)
285|    opts.inJustDecodeBounds = false
286|    opts.inPreferredConfig = Bitmap.Config.ARGB_8888
287|    return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
288|}
289|```
290|
291|After decode: center-crop or letterbox to 256×256, normalize with CLIP constants, write into reused `FloatBuffer`.
292|
293|### 7.2 Priority queue
294|
295|| Priority | Source |
296||---|---|
297|| 0 | Camera roll, last 30 days |
298|| 1 | Screenshots |
299|| 2 | Favorites / starred |
300|| 3 | Messaging apps (WhatsApp, Telegram, etc.) |
301|| 4 | All other allowed albums |
302|
303|### 7.3 Adaptive batch size
304|
305|```kotlin
306|fun resolveBatchSize(ctx: Context): Int {
307|    val bm = ctx.getSystemService(BatteryManager::class.java)
308|    val pm = ctx.getSystemService(PowerManager::class.java)
309|    val charging = bm.isCharging
310|    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
311|    val thermal = pm.currentThermalStatus
312|    return when {
313|        thermal >= PowerManager.THERMAL_STATUS_SEVERE -> 2
314|        level < 20 && !charging -> 4
315|        charging -> 32
316|        else -> 16
317|    }
318|}
319|```
320|
321|### 7.4 `IndexingWorker` — lifecycle contract
322|
323|> **Note (see Section 17.B):** Do **not** rely on `Result.retry()` for batched continuation.
324|> Loop internally inside `doWork()` until queue is empty or the worker's 10-min window is near
325|> exhaustion, then return `Result.success()` and let `IndexingScheduler` enqueue the next
326|> one-time work. `Result.retry()` triggers exponential backoff (10s/20s/40s/…) that destroys
327|> throughput on large queues.
328|
329|1. Pull batch from queue: `PENDING` **OR** (`PROCESSING` AND `lastHeartbeatAt < now − 10 min`).
330|2. Mark batch `PROCESSING`, set `lastHeartbeatAt = now`.
331|3. For each item:
332|   - Load bitmap (two-pass).
333|   - Preprocess → tensor.
334|   - `imageSession.run()` → `FloatArray(512)`.
335|   - **Transactional** write inside `db.withTransaction { … }`:
336|     - Insert `EmbeddingEntity`.
337|     - Mark `PhotoEntity.isIndexed = true`.
338|     - Delete row from `index_queue`.
339|   - Push vector into `EmbeddingStore` (off-heap).
340|4. Update heartbeat every N items.
341|5. On exception → `incrementRetry()`. After `retryCount > 3` mark `FAILED` and remove from queue.
342|6. Return `Result.retry()` while queue non-empty, else `Result.success()`.
343|
344|### 7.5 Scheduling
345|
346|```kotlin
347|WorkManager.getInstance(ctx).enqueueUniqueWork(
348|    "full_index", ExistingWorkPolicy.KEEP,
349|    OneTimeWorkRequestBuilder<IndexingWorker>()
350|        .setConstraints(Constraints.Builder()
351|            .setRequiresBatteryNotLow(true)
352|            .setRequiresStorageNotLow(true)
353|            .build())
354|        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
355|        .build())
356|
357|WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
358|    "incremental_index", ExistingPeriodicWorkPolicy.KEEP,
359|    PeriodicWorkRequestBuilder<IndexingWorker>(6, TimeUnit.HOURS)
360|        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
361|        .build())
362|```
363|
364|The 6-hour periodic worker also runs `DeletionReconciler` and `OrphanReclaimer`.
365|
366|### 7.6 Real-time `MediaObserver` + foreground bypass (race-safe)
367|
368|> **Initialization order (see Section 17.E):** `ProcessLifecycleOwner` is provided by
369|> `androidx.lifecycle:lifecycle-process` and auto-initializes via its `ContentProvider`
370|> *before* `Application.onCreate()`. Verify this is true on the minSdk targets used and
371|> only register the `MediaObserver` from `Application.onCreate()` (or later) so the
372|> lifecycle state is always queryable when a callback fires.
373|
374|```kotlin
375|fun onNewPhotoDetected(ctx: Context, photo: PhotoEntity, scope: CoroutineScope) {
376|    indexQueue.enqueue(photo, priority = 0)             // always persist first
377|    if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
378|        scope.launch(Dispatchers.Default) {
379|            indexQueue.markProcessing(photo.id)         // sets status + heartbeat
380|            try {
381|                val bmp = BitmapLoader.load(ctx, Uri.parse(photo.uri)) ?: return@launch
382|                val vec = imageEncoder.encode(bmp)
383|                withContext(NonCancellable) {           // crucial — DB write must complete
384|                    db.withTransaction {
385|                        db.embeddingDao().insert(EmbeddingEntity(photo.id, vec.toByteArray()))
386|                        db.photoDao().markIndexed(photo.id)
387|                        indexQueue.markDone(photo.id)
388|                    }
389|                    embeddingStore.put(photo.id, vec)
390|                }
391|            } catch (_: Throwable) {
392|                indexQueue.markPending(photo.id)        // hand back to WorkManager
393|            }
394|        }
395|    } else {
396|        IndexingScheduler.scheduleIncrementalIndex(ctx)
397|    }
398|}
399|```
400|
401|`OrphanReclaimer` runs at every `IndexingWorker` start AND on app launch:
402|```sql
403|UPDATE index_queue
404|SET status = 'PENDING', lastHeartbeatAt = 0
405|WHERE status = 'PROCESSING'
406|  AND lastHeartbeatAt < (strftime('%s','now')*1000 - 600000);
407|```
408|
409|### 7.7 Re-index on edit
410|`MediaObserver` callback compares `MediaStore.DATE_MODIFIED` against `PhotoEntity.modifiedAt`. If different → delete old `EmbeddingEntity`, set `isIndexed = false`, enqueue with priority `0`.
411|
412|### 7.8 Ghost-vector cleanup (`DeletionReconciler`)
413|Run every 6 h with the periodic worker.
414|
415|```kotlin
416|suspend fun reconcile(ctx: Context) {
417|    photoDao.getAllIds().chunked(500).forEach { batch ->
418|        val alive = MediaStoreHelper.verifyExisting(ctx, batch).toSet()
419|        val dead  = batch.filterNot(alive::contains)
420|        if (dead.isNotEmpty()) db.withTransaction {
421|            embeddingDao.deleteByIds(dead)
422|            photoDao.deleteByIds(dead)
423|            indexQueueDao.deleteByIds(dead)
424|            dead.forEach { embeddingStore.remove(it) }
425|        }
426|    }
427|}
428|```
429|
430|---
431|
432|## 8. Search Pipeline (the centerpiece)
433|
434|### 8.1 Tokenization
435|- Primary: **ONNX Runtime Extensions** — tokenizer compiled as part of the `mobileclip_s2_text.onnx` graph, input becomes raw `String`.
436|- Fallback: a verified JVM port of HuggingFace `tokenizers` (BPE) shipped as `.aar`.
437|- **Never** write a hand-rolled BPE or substitute char/word-piece tokenization — it silently destroys retrieval recall.
438|
439|### 8.2 Off-heap embedding store (`EmbeddingStore`)
440|
441|Replaces `ConcurrentHashMap<Long, FloatArray>` from `imp.md`.
442|
443|```kotlin
444|class EmbeddingStore(initialCapacity: Int = 16_384) {
445|    private val dim = 512
446|    private val stride = dim * 4                       // bytes per vector
447|    private var capacity = initialCapacity
448|    private var size = 0
449|    private var ids = LongArray(capacity)              // photoId at slot i
450|    private val idIndex = HashMap<Long, Int>()         // photoId → slot
451|    @Volatile private var buf: ByteBuffer =
452|        ByteBuffer.allocateDirect(capacity * stride).order(ByteOrder.nativeOrder())
453|
454|    @Synchronized fun put(photoId: Long, vector: FloatArray) {
455|        require(vector.size == dim)
456|        val slot = idIndex[photoId] ?: run {
457|            if (size == capacity) growLocked()
458|            val s = size++
459|            ids[s] = photoId
460|            idIndex[photoId] = s
461|            s
462|        }
463|        val off = slot * stride
464|        for (i in 0 until dim) buf.putFloat(off + i * 4, vector[i])
465|    }
466|
467|    @Synchronized fun remove(photoId: Long) {
468|        val slot = idIndex.remove(photoId) ?: return
469|        val last = --size
470|        if (slot != last) {                            // swap-with-last
471|            val movedId = ids[last]
472|            ids[slot] = movedId
473|            idIndex[movedId] = slot
474|            for (i in 0 until dim) {
475|                buf.putFloat(slot * stride + i * 4,
476|                             buf.getFloat(last * stride + i * 4))
477|            }
478|        }
479|    }
480|
481|    // NOTE: read-only view shares the underlying memory. Concurrent remove()/put()
482|    // can mutate a slot mid-scan. See Section 17.A — search is eventually consistent
483|    // during indexing; strict consistency would require a full buffer copy (~100 MB).
484|    fun snapshotForScan(): Snapshot = Snapshot(buf.asReadOnlyBuffer(), ids.copyOf(size), size)
485|
486|    private fun growLocked() {
487|        val newCap = capacity * 2
488|        val newBuf = ByteBuffer.allocateDirect(newCap * stride).order(ByteOrder.nativeOrder())
489|        buf.rewind(); newBuf.put(buf); newBuf.position(0)
490|        ids = ids.copyOf(newCap); buf = newBuf; capacity = newCap
491|    }
492|
493|    data class Snapshot(val buf: ByteBuffer, val ids: LongArray, val size: Int)
494|}
495|```
496|
497|Memory: 50 K × 2048 B = **100 MB off-heap**, **0 B JVM heap pressure**. No boxing, no per-vector object header.
498|
499|Initialization at app start: stream `embeddings` table in pages of 1000 rows, `put()` each.
500|
501|### 8.3 Search engine
502|
503|```kotlin
504|class SearchEngine(private val store: EmbeddingStore, private val text: TextEncoder) {
505|    private val dim = 512
506|    private val stride = dim * 4
507|
508|    suspend fun search(query: String, topK: Int = 20): List<Pair<Long, Float>> =
509|        withContext(Dispatchers.Default) {
510|            val q = text.encode(query)                 // already L2-normalized by ONNX graph
511|            val snap = store.snapshotForScan()
512|            val heap = MinFloatHeap(topK)              // bounded-K min-heap on score
513|            for (slot in 0 until snap.size) {
514|                var s = 0f; val off = slot * stride
515|                // unrolled hot loop
516|                var i = 0
517|                while (i < dim) {
518|                    s += q[i]   * snap.buf.getFloat(off + i*4)
519|                    s += q[i+1] * snap.buf.getFloat(off + (i+1)*4)
520|                    s += q[i+2] * snap.buf.getFloat(off + (i+2)*4)
521|                    s += q[i+3] * snap.buf.getFloat(off + (i+3)*4)
522|                    i += 4
523|                }
524|                heap.offer(snap.ids[slot], s)
525|            }
526|            heap.drainDescending()
527|        }
528|}
529|```
530|
531|Both vectors are unit-length (baked normalization), so the dot product **is** cosine similarity. Range `[-1, 1]`; treat anything < ~0.18 as noise (tunable cutoff per Phase 2 telemetry).
532|
533|### 8.4 Telemetry hook (Phase 1 cost: ~free)
534|Log to local Room `metrics` (or `SettingsEntity` rolling buffer):
535|- `cohort_size` at search time.
536|- Wall time per query (median + p95 across last 50 queries).
537|- Cache hit/miss for thumbnails.
538|
539|**Trigger for Phase-2 FAISS migration:** cohort_size > 30 K AND p95 > 150 ms over 7-day window.
540|
541|### 8.5 Coil thumbnail strategy (search-specific)
542|Search results pull random, non-contiguous URIs → blows out default memory cache.
543|
544|```kotlin
545|val searchImageLoader = ImageLoader.Builder(context)
546|    .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.05).build() }   // small, volatile
547|    .diskCache  { DiskCache.Builder().directory(context.cacheDir.resolve("search_thumbs"))
548|                                     .maxSizeBytes(100L * 1024 * 1024).build() }  // 100 MB
549|    .respectCacheHeaders(false)
550|    .build()
551|```
552|
553|Default `ImageLoader` continues to serve the (future) gallery grid untouched.
554|
555|---
556|
557|## 9. ONNX Runtime Session
558|
559|```kotlin
560|class MobileCLIPSession(ctx: Context, precision: ModelPrecision) {
561|    private val env = OrtEnvironment.getEnvironment()
562|    val image: OrtSession
563|    val text:  OrtSession
564|    init {
565|        val opts = OrtSession.SessionOptions().apply {
566|            try { addNnapi() } catch (_: Throwable) { /* fallback to CPU */ }
567|            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
568|            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
569|        }
570|        val imgAsset = if (precision == ModelPrecision.INT8)
571|            "mobileclip_s2_image_int8.onnx" else "mobileclip_s2_image.onnx"
572|        image = env.createSession(ctx.assets.open(imgAsset).readBytes(), opts)
573|        text  = env.createSession(ctx.assets.open("mobileclip_s2_text.onnx").readBytes(), opts)
574|    }
575|}
576|```
577|
578|Hilt provides a single `@Singleton` instance; tear down on `Application.onTerminate` (rarely fires) and `ProcessLifecycleOwner` `ON_STOP` if memory pressure observed.
579|
580|---
581|
582|## 10. Skeleton UI (just enough to validate search)
583|
584|### 10.1 Onboarding screen
585|- Explain the app in 2–3 lines.
586|- Tiered permission request.
587|- Album list with toggle switches → save to `AlbumEntity.isIndexingAllowed`.
588|- “Start Indexing” → triggers `IndexingScheduler.scheduleFullIndex`.
589|
590|### 10.2 Search screen
591|- `TextField` (auto-focus on entry, IME action “Search”).
592|- Debounced flow: 250 ms after last keystroke.
593|- `LazyVerticalGrid` (4 cols) of thumbnails via `searchImageLoader`.
594|- Top bar: `"X / Y photos indexed"` from `IndexingState` StateFlow.
595|- Empty-state copy when index is < 1 % done.
596|
597|### 10.3 Album selection screen (revisitable from settings)
598|- Re-enables/disables albums.
599|- Re-trigger incremental index on save.
600|- Deletion of an album’s data is atomic (Room transaction).
601|
602|No favorites, trash, detail view, gallery grid, edit. Stubs only.
603|
604|---
605|
606|## 11. Gradle Dependencies (final)
607|
608|```kotlin
609|dependencies {
610|    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
611|    implementation("com.microsoft.onnxruntime:onnxruntime-extensions-android:0.10.0")   // tokenizer
612|
613|    implementation("androidx.room:room-runtime:2.6.1")
614|    implementation("androidx.room:room-ktx:2.6.1")
615|    ksp("androidx.room:room-compiler:2.6.1")
616|
617|    implementation("androidx.work:work-runtime-ktx:2.9.0")
618|
619|    implementation("com.google.dagger:hilt-android:2.51")
620|    ksp("com.google.dagger:hilt-android-compiler:2.51")
621|    implementation("androidx.hilt:hilt-work:1.2.0")
622|    ksp("androidx.hilt:hilt-compiler:1.2.0")
623|
624|    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
625|    implementation("androidx.compose.ui:ui")
626|    implementation("androidx.compose.material3:material3")
627|    implementation("androidx.activity:activity-compose:1.9.0")
628|    implementation("androidx.lifecycle:lifecycle-process:2.8.0")
629|    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
630|
631|    implementation("io.coil-kt:coil-compose:2.6.0")
632|
633|    implementation("com.google.mlkit:text-recognition:16.0.0")           // Phase 2
634|
635|    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
636|}
637|```
638|
639|---
640|
641|## 12. Build Phases & Milestones
642|
643|### Phase 1 — Foundation (current focus)
644|1. **Project scaffold** — Hilt, Room, WorkManager, ORT, ORT-Extensions.
645|2. **Offline ONNX export** — both encoders with L2 normalization baked in; ship FP32 + INT8 image, FP32 text.
646|3. **Tokenizer** — ORT-Extensions integration validated with 20 sample CLIP prompts vs Python reference (cosine ≥ 0.999).
647|4. **Bitmap pipeline** — OOM-safe two-pass `inSampleSize` + CLIP preprocessing (validate with golden tensor from Python).
648|5. **DB schema** — all five entities exactly as Section 5.
649|6. **`EmbeddingStore`** — off-heap `DirectByteBuffer`, swap-with-last remove, page-load from Room on app start.
650|7. **`SearchEngine`** — bounded min-heap top-K, unrolled dot-product, telemetry.
651|8. **`IndexQueue`** — priority + retry + heartbeat + orphan reclaim.
652|9. **`IndexingWorker`** + adaptive batching + thermal/battery awareness.
653|10. **`IndexingScheduler`** — one-time full + 6-hour periodic + expedited path.
654|11. **`MediaObserver`** + race-safe `ForegroundIndexer` (NonCancellable DB write).
655|12. **`DeletionReconciler`** ghost-vector cleanup (in periodic worker).
656|13. **Permission + album-selector flow** — tiered API-level branches.
657|14. **Skeleton UI** — `OnboardingScreen`, `SearchScreen`, `AlbumSelectionScreen`.
658|15. **End-to-end validation** — see Section 13.
659|
660|### Phase 2 — Search quality
661|- ML Kit OCR on `mimeType == image/*` whose album bucket suggests screenshots/docs; populate `ocrText`.
662|- Hybrid ranking: `score = α · cosine + β · ocrBM25` with α/β tunable.
663|- Image-to-image (use stored embedding as query).
664|- FAISS-android or ScaNN gated on telemetry trigger.
665|- Duplicate detection (cosine ≥ 0.97 cluster).
666|
667|### Phase 3 — Gallery UI
668|Full grid, timeline, album browser, photo detail, favorites, trash.
669|
670|### Phase 4 — Polish
671|Shared-element transitions, widgets, metadata filters, accessibility audit.
672|
673|---
674|
675|## 13. Validation Checklist (Phase 1 exit criteria)
676|
677|| # | Test | Pass criterion |
678||---|---|---|
679|| 1 | Golden-tensor parity | Kotlin preprocess output matches Python `preprocess(img)` to within 1e-5 |
680|| 2 | Image embedding parity | Cosine(Kotlin emb, Python emb) ≥ 0.999 on 20 sample photos |
681|| 3 | Text embedding parity | Same as above for 20 prompts |
682|| 4 | OOM resilience | Index 1,000 photos at 108 MP without `OutOfMemoryError` |
683|| 5 | Crash recovery | Kill app mid-indexing → on restart, no row stuck `PROCESSING` > 10 min |
684|| 6 | Reboot recovery | Reboot device during indexing → queue resumes without duplicates |
685|| 7 | Deletion sync | Delete 50 photos in Files app → reconcile clears all 50 in ≤ 6 h |
686|| 8 | Battery awareness | Battery < 20%, not charging → background batch size ≤ 4 |
687|| 9 | Thermal awareness | Force `THERMAL_STATUS_SEVERE` → batch size ≤ 2 |
688|| 10 | Foreground bypass | Take photo with app open, battery low → photo searchable within 5 s |
689|| 11 | Album scoping | Disable an album → its embeddings deleted, no longer in search results |
690|| 12 | Search latency | 10 K photos, p95 search time < 80 ms on Pixel 6a (FP32) / Redmi Note 11 (INT8) |
691|| 13 | Heap pressure | `EmbeddingStore` of 50 K vectors uses ≤ 30 MB JVM heap (off-heap ≈ 100 MB excluded) |
692|| 14 | Search semantic sanity | Hand-curated queries (“sunset beach”, “my dog”, “whiteboard”) return visually correct top-5 |
693|
694|---
695|
696|## 14. Risk Register
697|
698|| Risk | Likelihood | Impact | Mitigation |
699||---|---|---|---|
700|| Tokenizer mismatch silently degrades recall | Medium | High | Golden parity test #3, ORT-Ext only, never custom BPE |
701|| Mixing FP32/INT8 embeddings | Low | High | Lock precision in `SettingsEntity`; switch = full re-index |
702|| ContentObserver fires for irrelevant changes | High | Low | Debounce 2 s; compare `modifiedAt` to skip no-op events |
703|| NNAPI flaky on older OEMs | Medium | Medium | `try addNnapi` then CPU fallback; ship telemetry on session init failure |
704|| ORT-Extensions tokenizer unavailable for some op | Low | High | Fallback path: shipped HF Kotlin port `.aar` |
705|| Off-heap leak (`DirectByteBuffer`) | Low | High | Single owned instance, no per-query allocation, growth amortized 2× |
706|| User revokes partial photo access mid-session | Medium | Medium | Re-prompt; mark inaccessible photos as soft-deleted |
707|| Re-index storm after edit batch | Medium | Low | Coalesce `MediaObserver` events in a 2 s window |
708|
709|---
710|
711|## 15. Key Design Decisions (Why)
712|
713|- **ONNX Runtime over TFLite** — MobileCLIP-S2’s hybrid CNN-transformer + structural reparameterization is poorly handled by TFLite’s converter.
714|- **Baked L2 normalization** — eliminates a class of Kotlin-side math bugs; guarantees `dot == cosine`.
715|- **256×256 input** — native training resolution; 224 silently degrades retrieval.
716|- **Off-heap `DirectByteBuffer`** — avoids GC thrash on devices with 256 MB heap limits at 50 K-photo scale.
717|- **Race-safe foreground bypass** — instant search on freshly captured photos without orphaning rows.
718|- **Priority queue** — useful results within minutes of first launch, not hours.
719|- **Adaptive batch size** — invisible to user on hot/low-battery devices; aggressive on charger.
720|- **Schema columns reserved now** (`ocrText`, `modifiedAt`, `status`, `lastHeartbeatAt`) — zero cost today, no Room migration tomorrow.
721|- **Per-album scoping** — aligns with platform direction (API 34+), shrinks DB, increases user trust.
722|- **Linear scan first, FAISS later** — keep the skeleton dead-simple; promote only when telemetry says so.
723|- **Dedicated search `ImageLoader`** — prevents search-driven cache eviction from killing the future gallery grid.
724|
725|---
726|
727|## 16. Open Questions to Revisit Before Coding
728|
729|1. Should video files be in scope (MobileCLIP can embed video frames)? — Phase 2.
730|2. Cloud backup of embeddings (so user reinstall doesn’t restart indexing)? — Phase 3.
731|3. Are saved searches / search history desired? — Phase 2 (cheap; reuse `SearchTelemetry`).
732|4. Multi-language query support — MobileCLIP-S2 is English-only; multilingual model swap would need full re-index.
733|5. Accessibility: TalkBack support for search results (announce result count + alt text) — Phase 4 polish, but must not be forgotten.
734|
735|---
736|
737|---
738|
739|## 17. Edge Cases & Concurrency Notes
740|
741|Reviewer-flagged minor concerns, all verified valid and incorporated into the plan.
742|
743|### 17.A — `EmbeddingStore` concurrent-scan consistency
744|**Issue.** `snapshotForScan()` returns `buf.asReadOnlyBuffer()`, which is read-only *from the
745|caller's reference* but shares the underlying memory. A concurrent `remove()` (swap-with-last)
746|or `put()` from `ForegroundIndexer` / `IndexingWorker` will mutate slots that the scanner is
747|in the middle of reading, so one of the top-K scores may be computed against a stale or wrong
748|vector.
749|
750|**Decision.** Accept this as **eventually-consistent search during active indexing**.
751|- Symptom in the wild: ≤1 incorrect result that self-heals on the next keystroke (search is
752|  debounced and re-runs in 250 ms).
753|- Acceptable for a photo-search UX; not acceptable for a financial or ranked system.
754|
755|**Documentation contract.** `EmbeddingStore.snapshotForScan()` KDoc must state:
756|> Snapshot shares the underlying buffer with concurrent writers. Reads during writes may
757|> return torn floats. Results are eventually consistent; callers must tolerate single-slot
758|> noise during indexing.
759|
760|**Strict-consistency escape hatch (not Phase 1):** copy the active prefix of `buf` into a
761|freshly allocated direct buffer on each scan. Cost: ~100 MB allocation + memcpy per query —
762|unacceptable for an interactive search field.
763|
764|### 17.B — `IndexingWorker` retry-backoff trap
765|**Issue.** Returning `Result.retry()` triggers WorkManager exponential backoff
766|(10s → 20s → 40s → 80s …). On a 10 K-photo queue with batches of 16, that's ~625 worker
767|invocations; backoff delays alone will dominate wall-clock indexing time.
768|
769|**Decision (already incorporated into Section 7.4).**
770|1. **Loop internally** inside `doWork()`: dequeue → process → repeat until the queue is
771|   empty *or* `getRunAttemptCount()`-tracked wall time approaches the 10-minute worker
772|   ceiling (use a soft budget of ~8 min to leave headroom for the final transaction).
773|2. Return `Result.success()` and let `IndexingScheduler` enqueue the next unique one-time
774|   work immediately if the queue is non-empty. This yields cleanly to the OS without
775|   inheriting backoff penalties.
776|3. Reserve `Result.retry()` strictly for **transient failures** (e.g., `IOException` on
777|   bitmap decode of a single batch), and rely on per-item `retryCount` in
778|   `IndexQueueEntity` for granular retry — *not* on WorkManager's worker-level retry.
779|4. Continue to scale `batchSize` aggressively when charging (Section 7.3) to minimize
780|   worker invocations overall.
781|
782|### 17.C — `EmbeddingStore` initialization must not block UI
783|**Issue.** Streaming 50 K rows from the `embeddings` table and writing each into the
784|off-heap buffer is several seconds of SQLite I/O + native memcpy. Doing this synchronously
785|on the main thread, or even synchronously before allowing the search UI to render, gives
786|users an empty result list for 5–30 seconds.
787|
788|**Decision.**
789|1. Initialize from `Application.onCreate()` inside
790|   `CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { ... }`.
791|2. Page through `embeddings` in chunks of 1 000 rows; call `EmbeddingStore.put()` per row.
792|3. Expose `val isReady: StateFlow<Boolean>` from `EmbeddingStore`; flip to `true` only on
793|   completion of the final page.
794|4. `SearchScreen` collects `isReady`:
795|   - `false` → render a "Preparing search index…" skeleton with the same progress copy as
796|     the indexing status bar; disable the input field.
797|   - `true` → enable input.
798|5. **Crash safety:** if `put()` throws mid-load (corrupt BLOB, unexpected length), log the
799|   `photoId`, mark `PhotoEntity.isIndexed = false`, and re-enqueue at priority 0. Never
800|   crash app startup over a single bad row.
801|
802|### 17.D — Text-encoder context-length verification
803|**Issue.** The export snippet uses a dummy input of shape `(1, 77)` assuming MobileCLIP-S2
804|reuses standard CLIP's 77-token context. If the actual context length differs (some CLIP
805|variants ship at 64 or 128), the Kotlin tensor allocation in `TextEncoder` won't match the
806|ONNX graph's expected dim and ORT will throw at first inference — *runtime error, not build
807|error*.
808|
809|**Decision.**
810|1. Before exporting, run `print(open_clip.get_tokenizer("MobileCLIP-S2").context_length)`
811|   from the Python export environment; lock the printed value into a `const val
812|   CLIP_CONTEXT_LENGTH` in `TextEncoder.kt`.
813|2. Add the value to `SettingsEntity` as `CLIP_CONTEXT_LENGTH` for runtime cross-check.
814|3. Validation gate: extend Section 13 checklist test #3 to assert the Kotlin pad-and-mask
815|   produces a `LongArray` of length exactly `CLIP_CONTEXT_LENGTH`; fail the build if not.
816|
817|### 17.E — `ProcessLifecycleOwner` initialization order
818|**Issue.** The foreground bypass branch reads
819|`ProcessLifecycleOwner.get().lifecycle.currentState`. This requires
820|`androidx.lifecycle:lifecycle-process` (already listed in Gradle deps). If a `MediaObserver`
821|callback fires *before* the process-lifecycle initializer runs, the state read can be
822|`INITIALIZED` (not `RESUMED`) and the bypass is incorrectly skipped.
823|
824|**Decision.**
825|1. `lifecycle-process` registers an `androidx.startup` `Initializer` that runs before
826|   `Application.onCreate()`. Confirm `androidx.startup:startup-runtime` resolves at the
827|   right version (it is a transitive dep of `lifecycle-process` ≥ 2.6.0).
828|2. Register `MediaObserver` only from inside `Application.onCreate()` — never from a
829|   `ContentProvider#onCreate` or `BootReceiver` that may race the lifecycle initializer.
830|3. As a belt-and-braces defence in the bypass code: treat `INITIALIZED` as equivalent to
831|   "not yet foreground" — fall through to the WorkManager scheduling branch rather than
832|   crash or skip. This makes the early-fire case a quality issue (slightly delayed index),
833|   never a correctness one.
834|
835|---
836|
837|*End of plan. No code has been written yet — this document is the single source of truth for the Phase-1 implementation kickoff.*
838|
[End of file]