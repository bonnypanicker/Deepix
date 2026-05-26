# GallerySearch — Complete Build Guide
### MobileCLIP S2 + Android XML Views
> Follow every step in order. Do not skip any step.

---

## What You Are Building

An Android gallery app where you can type "dog at beach" or "birthday cake" and it finds matching photos from your device — using AI semantic understanding, not keyword matching.

**Tech stack:**
- MobileCLIP S2 INT8 (Xenova/HuggingFace — already downloaded)
- ONNX Runtime for Android
- Kotlin + XML Views
- RecyclerView + MediaStore

---

## Files You Should Already Have

```
vision_model_int8.onnx       (36.7 MB)
text_model_int8.onnx         (64.1 MB)
tokenizer.json               (2.22 MB)
tokenizer_config.json        (763 Bytes)
preprocessor_config.json     (382 Bytes)
config.json                  (240 Bytes)
```

If any are missing, download from:
`https://huggingface.co/Xenova/mobileclip_s2`

---

## Phase 1 — Project Setup

### Step 1.1 — Create the Project

1. Open Android Studio
2. Click **New Project**
3. Select **Empty Views Activity**
4. Fill in:
   - Name: `GallerySearch`
   - Package: `com.yourname.gallerysearch`
   - Language: `Kotlin`
   - Minimum SDK: `API 26 (Android 8.0)`
5. Click **Finish**
6. Wait for Gradle sync to complete

---

### Step 1.2 — Place Model Files in Assets

1. In Android Studio, look at the left panel (Project view)
2. Navigate to: `app > src > main`
3. Right-click `main` → **New** → **Folder** → **Assets Folder** → click Finish
4. On your computer, copy all 6 downloaded files into this folder:

```
app/
  src/
    main/
      assets/
          vision_model_int8.onnx       ← copy here
          text_model_int8.onnx         ← copy here
          tokenizer.json               ← copy here
          tokenizer_config.json        ← copy here
          preprocessor_config.json     ← copy here
          config.json                  ← copy here
```

> ⚠️ **Error Prevention:** The assets folder path must be exactly `src/main/assets/`. If the files are inside any subfolder, model loading will fail.

---

### Step 1.3 — Configure build.gradle

Open `build.gradle (Module: app)` and make these changes:

**Inside `android {}` block — add aaptOptions:**

```groovy
android {
    compileSdk 34

    defaultConfig {
        applicationId "com.yourname.gallerysearch"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    // ✅ CRITICAL — prevents Android from compressing .onnx files
    // Without this, model loading will crash with a cryptic binary error
    aaptOptions {
        noCompress "onnx"
    }

    buildFeatures {
        viewBinding true
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }
}
```

**Inside `dependencies {}` block — add these:**

```groovy
dependencies {
    // ONNX Runtime for Android — runs the AI models
    implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.17.0'

    // Glide — loads gallery images efficiently
    implementation 'com.github.bumptech.glide:glide:4.16.0'

    // Coroutines — runs AI inference in background without freezing UI
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'

    // ViewModel and LiveData — manages UI state
    implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-livedata-ktx:2.7.0'

    // RecyclerView — displays photo grid
    implementation 'androidx.recyclerview:recyclerview:1.3.2'

    // Activity KTX — easier permission handling
    implementation 'androidx.activity:activity-ktx:1.8.2'
}
```

Click **Sync Now** and wait for it to complete.

---

### Step 1.4 — Update AndroidManifest.xml

Open `app/src/main/AndroidManifest.xml` and replace the entire content with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Android 13+ permission for images -->
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />

    <!-- Android 12 and below -->
    <uses-permission
        android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:largeHeap="true"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.GallerySearch">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
```

> ⚠️ **Error Prevention:** `android:largeHeap="true"` is required. Loading two AI models (36MB + 64MB) plus gallery bitmaps will crash without it.

---

## Phase 2 — Layout Files

### Step 2.1 — Create search_bg.xml (Drawable)

1. Navigate to `res/drawable/`
2. Right-click → **New** → **Drawable Resource File**
3. Name it `search_bg`
4. Replace content with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <corners android:radius="10dp"/>
    <solid android:color="#1E1E1E"/>
    <stroke android:width="1dp" android:color="#333333"/>
</shape>
```

---

### Step 2.2 — Update activity_main.xml

Open `res/layout/activity_main.xml` and replace with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#0A0A0A">

    <!-- Header -->
    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Gallery Search"
        android:textColor="#FFFFFF"
        android:textSize="22sp"
        android:textStyle="bold"
        android:paddingStart="16dp"
        android:paddingTop="16dp"
        android:paddingBottom="8dp"/>

    <!-- Search Row -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:paddingStart="12dp"
        android:paddingEnd="12dp"
        android:paddingBottom="8dp">

        <EditText
            android:id="@+id/searchInput"
            android:layout_width="0dp"
            android:layout_height="48dp"
            android:layout_weight="1"
            android:hint="Search your photos..."
            android:textColor="#FFFFFF"
            android:textColorHint="#666666"
            android:background="@drawable/search_bg"
            android:paddingStart="16dp"
            android:paddingEnd="16dp"
            android:inputType="text"
            android:imeOptions="actionSearch"
            android:singleLine="true"/>

        <Button
            android:id="@+id/searchBtn"
            android:layout_width="wrap_content"
            android:layout_height="48dp"
            android:layout_marginStart="8dp"
            android:text="Search"
            android:backgroundTint="#5B4FE8"/>

    </LinearLayout>

    <!-- Status / Progress -->
    <TextView
        android:id="@+id/statusText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Loading models..."
        android:textColor="#888888"
        android:textSize="12sp"
        android:paddingStart="16dp"
        android:paddingBottom="4dp"/>

    <ProgressBar
        android:id="@+id/progressBar"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent"
        android:layout_height="3dp"
        android:visibility="visible"/>

    <!-- Results count -->
    <TextView
        android:id="@+id/resultCount"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text=""
        android:textColor="#5B4FE8"
        android:textSize="12sp"
        android:paddingStart="16dp"
        android:paddingBottom="4dp"/>

    <!-- Photo Grid -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/imageGrid"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:padding="2dp"
        android:clipToPadding="false"/>

</LinearLayout>
```

---

### Step 2.3 — Create item_image.xml

1. Navigate to `res/layout/`
2. Right-click → **New** → **Layout Resource File**
3. Name it `item_image`
4. Replace content with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:padding="2dp">

    <ImageView
        android:id="@+id/thumbnail"
        android:layout_width="match_parent"
        android:layout_height="120dp"
        android:scaleType="centerCrop"
        android:background="#1A1A1A"/>

</FrameLayout>
```

---

## Phase 3 — Kotlin Source Files

Create each file in `app/src/main/java/com/yourname/gallerysearch/`

> 💡 **How to create each file:** Right-click your package folder → New → Kotlin Class/File → name it.

---

### Step 3.1 — EmbeddingUtils.kt

Give this prompt to your AI tool (Cursor/Copilot/etc.):

> **Prompt:**
> "Create `EmbeddingUtils.kt` in package `com.yourname.gallerysearch`.
> It must contain:
> 1. A function `l2Normalize(vector: FloatArray): FloatArray` — divides each element by the L2 norm of the vector. If norm < 1e-8f, return the vector unchanged.
> 2. A function `cosineSimilarity(a: FloatArray, b: FloatArray): Float` — returns dot product of two vectors (assumes both are already L2 normalized).
> No imports needed beyond kotlin.math.sqrt."

**Verify the output looks like this:**
```kotlin
package com.yourname.gallerysearch

import kotlin.math.sqrt

object EmbeddingUtils {

    fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0f
        for (v in vector) sum += v * v
        val norm = sqrt(sum)
        if (norm < 1e-8f) return vector
        return FloatArray(vector.size) { i -> vector[i] / norm }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}
```

---

### Step 3.2 — ClipTokenizer.kt

This is the most critical file. Use this exact prompt:

> **Prompt:**
> "Create `ClipTokenizer.kt` in package `com.yourname.gallerysearch`.
> It must:
> - Accept a Context in the constructor
> - Load `tokenizer.json` from assets on initialization
> - Parse the BPE merges and vocab from the JSON (the file is HuggingFace fast tokenizer format — it has a `model` object containing `vocab` map and `merges` list)
> - Implement standard BPE encoding matching OpenAI CLIP tokenizer behavior
> - The special tokens are: SoT (start of text) = 49406, EoT (end of text) = 49407
> - Wrap input with SoT and EoT tokens
> - Pad or truncate to exactly 77 tokens (pad with 0)
> - Return a LongArray of size 77
> - Also return an attention_mask LongArray of size 77 (1 for real tokens, 0 for padding)
> - Also prepend 'a photo of ' to any query automatically before tokenizing
> Use org.json.JSONObject to parse the tokenizer.json file (it is available in Android without extra dependencies)."

> ⚠️ **Error Prevention:** After your AI tool generates this, test it with:
> `tokenizer.encode("cat")` — the output should be a LongArray of size 77, starting with 49406 and containing 49407 somewhere before the zeros.

---

### Step 3.3 — ImageEncoder.kt

> **Prompt:**
> "Create `ImageEncoder.kt` in package `com.yourname.gallerysearch`.
> It loads `vision_model_int8.onnx` from Android assets using ONNX Runtime (OrtEnvironment, OrtSession).
> Session options: add NNAPI execution provider inside a try-catch (fallback to CPU silently), set intraOpNumThreads to 4, set optimization level to ALL_OPT.
> It has a function `encode(bitmap: Bitmap): FloatArray` that:
> 1. Resizes the bitmap so shortest edge = 256 (maintaining aspect ratio)
> 2. Center-crops to exactly 256×256
> 3. Converts pixels to FloatArray in CHW format (channels first: all R values, then all G values, then all B values)
> 4. Normalizes: pixel = (pixel/255 - mean) / std where mean=[0.48145466f, 0.4578275f, 0.40821073f] and std=[0.26862954f, 0.26130258f, 0.27577711f]
> 5. Creates OnnxTensor with shape [1, 3, 256, 256]
> 6. Runs session with input name 'pixel_values' (do NOT hardcode — query session.inputNames.first() at init and store it)
> 7. Gets output from session.outputNames.first()
> 8. Calls EmbeddingUtils.l2Normalize() on the result before returning
> Also add a close() function that closes the session.
> Use FloatBuffer.wrap() to create the tensor."

> ⚠️ **Error Prevention — the resize + crop logic:**
> Make sure your AI tool implements this correctly:
> ```
> Step 1: If width < height → newWidth=256, newHeight=(256*height/width)
>         If height < width → newHeight=256, newWidth=(256*width/height)
>         If square → resize to 256x256 directly
> Step 2: Crop center 256x256 from the resized bitmap
> ```
> NOT just `Bitmap.createScaledBitmap(bitmap, 256, 256, true)` — that squashes non-square images.

---

### Step 3.4 — TextEncoder.kt

> **Prompt:**
> "Create `TextEncoder.kt` in package `com.yourname.gallerysearch`.
> Constructor takes a Context.
> It loads `text_model_int8.onnx` from assets using ONNX Runtime.
> Same session options as ImageEncoder (NNAPI try-catch, 4 threads, ALL_OPT).
> It initializes a ClipTokenizer in its constructor.
> It has a function `encode(query: String): FloatArray` that:
> 1. Calls tokenizer.encode(query) to get tokenIds: LongArray and attentionMask: LongArray
> 2. Creates two OnnxTensors: one for input_ids (shape [1,77]) and one for attention_mask (shape [1,77])
> 3. Query session.inputNames at init — do NOT hardcode input names
> 4. Runs the session with both tensors as inputs
> 5. Gets output from session.outputNames.first()
> 6. Calls EmbeddingUtils.l2Normalize() on the result before returning
> Also has a close() function.
> Use java.nio.LongBuffer.wrap() to create the LongArray tensors."

---

### Step 3.5 — GalleryRepository.kt

> **Prompt:**
> "Create `GalleryRepository.kt` in package `com.yourname.gallerysearch`.
> Constructor takes a Context, an ImageEncoder, and a TextEncoder.
> It must:
>
> 1. Have a function `getAllImageUris(): List<Uri>` that queries MediaStore
>    (MediaStore.Images.Media.EXTERNAL_CONTENT_URI) for all image URIs on the device.
>    Sort by DATE_ADDED descending.
>
> 2. Have a function `loadBitmap(uri: Uri): Bitmap?` that loads a bitmap from a URI
>    using BitmapFactory with inSampleSize=4 (to reduce memory), then scales to 512x512 max.
>    Return null on any exception.
>
> 3. Have a function `buildIndex(uris: List<Uri>, onProgress: (Int, Int) -> Unit): Unit`
>    (suspend function) that:
>    - Checks if a saved index file exists in context.filesDir named 'embedding_index.bin'
>    - If it exists, loads it (deserialize Map<String, FloatArray> from the file)
>    - For each URI not already in the index, load bitmap, encode with imageEncoder, store in map
>    - Report progress via onProgress(current, total)
>    - After processing every 20 images, save the index to disk
>    - Serialize as: for each entry, write URI string length (Int), URI string bytes, embedding size (Int), then FloatArray bytes using DataOutputStream
>
> 4. Have a function `search(query: String, topK: Int = 30): List<Uri>` that:
>    - Encodes the query with textEncoder
>    - Computes cosine similarity with every embedding in the index
>    - Returns top K URIs sorted by score descending
>    - Only includes results with score > 0.17f
>    - If no results above threshold, returns top 10 regardless (so search never shows empty)
>
> 5. Have a val `indexedCount: Int` that returns current index size."

---

### Step 3.6 — ImageAdapter.kt

> **Prompt:**
> "Create `ImageAdapter.kt` in package `com.yourname.gallerysearch`.
> It is a RecyclerView.Adapter that takes a MutableList<Uri>.
> ViewHolder uses item_image.xml with an ImageView id `thumbnail`.
> In onBindViewHolder, load the URI into the ImageView using Glide:
> `Glide.with(context).load(uri).centerCrop().placeholder(ColorDrawable(Color.DKGRAY)).into(holder.thumbnail)`
> Add a function `updateList(newList: List<Uri>)` that clears and re-adds all items, then calls notifyDataSetChanged().
> Use GridLayoutManager with 3 columns (set this up in MainActivity, not the adapter)."

---

### Step 3.7 — MainActivity.kt

> **Prompt:**
> "Create `MainActivity.kt` in package `com.yourname.gallerysearch`.
> Use ViewBinding (ActivityMainBinding).
> It must:
>
> 1. On create, immediately request READ_MEDIA_IMAGES permission (or READ_EXTERNAL_STORAGE for API < 33).
>    Use registerForActivityResult(ActivityResultContracts.RequestPermission()).
>    If denied, show a Toast 'Storage permission required' and finish().
>
> 2. After permission granted, call `initializeAndIndex()` as a coroutine on Dispatchers.IO.
>
> 3. `initializeAndIndex()` must:
>    - Show progressBar (set visibility VISIBLE on main thread)
>    - Update statusText to 'Loading AI models...'
>    - Initialize ImageEncoder(context) and TextEncoder(context) on IO thread
>    - Update statusText to 'Indexing your photos...'
>    - Call repository.buildIndex(uris) with a progress lambda that updates statusText
>      to 'Indexing: X / Y photos' on main thread
>    - When done: hide progressBar, update statusText to 'Ready — X photos indexed'
>    - Load all URIs into the adapter to show full gallery
>
> 4. Set up the search button click and keyboard search action:
>    - Get text from searchInput
>    - If empty: show full gallery, clear resultCount text
>    - If not empty: run search on Dispatchers.IO, show results on main thread,
>      update resultCount to 'Found X results for \"query\"'
>
> 5. Set up RecyclerView with GridLayoutManager(context, 3) and ImageAdapter.
>
> 6. In onDestroy, close imageEncoder and textEncoder.
>
> Handle all exceptions with try-catch — if model loading fails, show an AlertDialog
> with the error message so it's visible during development."

---

## Phase 4 — Verify Before Running

### Checklist — Go Through This Before Clicking Run

```
□ All 6 model files are in app/src/main/assets/
□ noCompress "onnx" is in build.gradle aaptOptions
□ android:largeHeap="true" is in AndroidManifest.xml
□ READ_MEDIA_IMAGES permission is in AndroidManifest.xml
□ Gradle sync completed with no errors
□ viewBinding true is in build.gradle buildFeatures
```

### Debug Code — Add This to ImageEncoder.kt init block

```kotlin
// Add this after creating the session — run once, then you can remove it
Log.d("CLIP", "Vision model inputs: ${imageSession.inputNames}")
Log.d("CLIP", "Vision model outputs: ${imageSession.outputNames}")
```

### Debug Code — Add This to TextEncoder.kt init block

```kotlin
Log.d("CLIP", "Text model inputs: ${textSession.inputNames}")
Log.d("CLIP", "Text model outputs: ${textSession.outputNames}")
```

Check Logcat for these lines after first launch. They should show:
- Vision inputs: `[pixel_values]`
- Vision outputs: `[image_embeds]`
- Text inputs: `[input_ids, attention_mask]`
- Text outputs: `[text_embeds]`

If the names are different from above, update your encoder code to match.

---

## Phase 5 — Testing

### Test 1 — Model Loading Test
Run the app. It should:
- Show "Loading AI models..." for a few seconds
- Then show "Indexing your photos..."
- Progress counter should increment

If it crashes immediately → check `noCompress "onnx"` in build.gradle.
If it shows an error dialog → paste the error into your AI tool with context.

### Test 2 — Tokenizer Test
Add this temporary code in MainActivity after models are ready:

```kotlin
// Temporary test — remove after confirming
val tokens = textEncoder.tokenizer.encode("cat")
Log.d("TOKENIZER", "First token: ${tokens[0]}")   // Should be 49406
Log.d("TOKENIZER", "Length: ${tokens.size}")       // Should be 77
```

### Test 3 — Search Quality Test
Search these queries one by one and observe results:

| Query | What you should see |
|-------|---------------------|
| `a photo of food` | Food/meal photos |
| `a photo of people` | Photos with people |
| `a photo of outdoor` | Outdoor/nature photos |
| `a photo of text` | Screenshots, documents |
| `a photo of night` | Dark/night photos |

If all results look random → tokenizer is wrong, revisit Step 3.2.
If results are slightly off → adjust threshold in GalleryRepository from 0.17f to 0.15f.
If results are good → your app is working correctly.

---

## Phase 6 — Common Errors and Fixes

### Error: `java.lang.IllegalArgumentException: Invalid name: image`
**Cause:** Hardcoded input name doesn't match model.
**Fix:** Make sure your encoder uses `session.inputNames.first()` dynamically.

### Error: App crashes on launch with `IOException`
**Cause:** ONNX files are being compressed by Android.
**Fix:** Verify `aaptOptions { noCompress "onnx" }` is in the correct place in build.gradle.

### Error: `OutOfMemoryError`
**Cause:** Loading too many bitmaps at full resolution.
**Fix:** Make sure loadBitmap() uses `inSampleSize = 4`.

### Error: `NullPointerException` in GalleryRepository
**Cause:** URI from MediaStore is null or image was deleted.
**Fix:** Wrap bitmap loading in try-catch and skip null bitmaps.

### Error: Indexing never progresses past 0
**Cause:** Permission not granted.
**Fix:** Check that you're requesting `READ_MEDIA_IMAGES` for Android 13+ and `READ_EXTERNAL_STORAGE` for older.

### Error: Search returns empty every time
**Cause 1:** Threshold too high — try lowering to 0.15f.
**Cause 2:** Tokenizer returning wrong token IDs.
**Cause 3:** Missing L2 normalization in one of the encoders.

### Error: `OrtException: No such operator`
**Cause:** ONNX Runtime version too old for INT8 ops.
**Fix:** Make sure you're using `onnxruntime-android:1.17.0` or newer.

---

## Final File Structure

When everything is done, your project should look like:

```
app/
  src/
    main/
      assets/
          vision_model_int8.onnx
          text_model_int8.onnx
          tokenizer.json
          tokenizer_config.json
          preprocessor_config.json
          config.json
      java/com/yourname/gallerysearch/
          MainActivity.kt
          EmbeddingUtils.kt
          ClipTokenizer.kt
          ImageEncoder.kt
          TextEncoder.kt
          GalleryRepository.kt
          ImageAdapter.kt
      res/
          layout/
              activity_main.xml
              item_image.xml
          drawable/
              search_bg.xml
      AndroidManifest.xml
  build.gradle
```

---

## Build Order Summary

```
Phase 1  →  Create project + place files + configure build.gradle + manifest
Phase 2  →  Create all 3 XML layout files
Phase 3  →  Create Kotlin files in this exact order:
              1. EmbeddingUtils.kt    (no dependencies)
              2. ClipTokenizer.kt     (no dependencies)
              3. ImageEncoder.kt      (depends on EmbeddingUtils)
              4. TextEncoder.kt       (depends on EmbeddingUtils, ClipTokenizer)
              5. GalleryRepository.kt (depends on ImageEncoder, TextEncoder)
              6. ImageAdapter.kt      (no AI dependencies)
              7. MainActivity.kt      (depends on everything)
Phase 4  →  Run the checklist + add debug logs
Phase 5  →  Test with the 5 queries above
Phase 6  →  Fix any errors using the error table
```

---

*Guide version 1.0 — MobileCLIP S2 INT8 (Xenova) + ONNX Runtime Android 1.17.0*
