package com.devomind.gallerysearch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.devomind.gallerysearch.databinding.ActivitySafeDiagnosticsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only probe for the durability assumptions behind [VaultStorage]: what a *fresh install* can
 * still reach of a vault another install left in the public Documents folder. It answers four
 * questions for `Documents/PixaSafe/PixaSafe.zip` (and the two names earlier builds wrote there):
 * the plain `File` view, a `MediaStore.Files` row by `DISPLAY_NAME` (with its owner package), a
 * `ContentResolver` open, and the persisted document grants.
 *
 * Nothing here ships: the class lives in the debug source set and its manifest entry is merged into
 * debug variants only. It is deliberately reachable from the launcher so it can be opened right
 * after an uninstall/reinstall, when the app's own Safe flow is still unusable.
 */
class SafeDiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySafeDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySafeDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.rerunBtn.setOnClickListener { runReport() }
        binding.copyBtn.setOnClickListener { copyReport() }
        runReport()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.diagRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + (TOP_PADDING_DP * resources.displayMetrics.density).toInt())
            binding.reportScroll.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun runReport() {
        binding.reportText.text = "probing…"
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { buildReport() }
            binding.reportText.text = report
        }
    }

    private fun copyReport() {
        val manager = getSystemService(ClipboardManager::class.java)
        manager?.setPrimaryClip(ClipData.newPlainText("Safe diagnostics", binding.reportText.text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun buildReport(): String {
        val out = StringBuilder()
        appendEnvironment(out)
        appendFileApi(out)
        appendMediaStoreRows(out)
        appendPersistedGrants(out)
        return out.toString()
    }

    /** Which install is asking, and what it currently believes about the Safe. */
    private fun appendEnvironment(out: StringBuilder) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        out.appendLine("Pixa Safe diagnostics — $timestamp")
        out.appendLine("package=${packageName}  debug=${BuildConfig.DEBUG}")
        out.appendLine(
            "android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})  " +
                "${Build.MANUFACTURER} ${Build.MODEL}"
        )
        val allFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        out.appendLine("all-files access=$allFiles")
        out.appendLine("safe configured=${SafeManager.isConfigured(this)}")
        out.appendLine("saved location=${SafeStore.getVaultLocation(this)}")
        out.appendLine("saved tree uri=${SafeStore.getTreeUri(this)}")
        out.appendLine("archiveExists()=${runCatching { SafeManager.archiveExists(this) }.getOrElse { "error: $it" }}")
        out.appendLine("filesDir entries=${runCatching { filesDir.list()?.joinToString() }.getOrElse { "error: $it" }}")
        out.appendLine()
    }

    /** Question 1: can a plain File see the vault, and can it be listed around? */
    private fun appendFileApi(out: StringBuilder) {
        out.appendLine("---- File API ----")
        for (target in targets) {
            val file = target.file
            val length = runCatching { file.length() }.getOrDefault(0L)
            out.appendLine("${target.label}: ${file.absolutePath}")
            out.appendLine(
                "  exists=${file.exists()} canRead=${file.canRead()} " +
                    "canWrite=${file.canWrite()} length=$length"
            )
        }
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        out.appendLine("Documents/ listing=${listing(documents)}")
        out.appendLine("Documents/PixaSafe/ listing=${listing(File(documents, PIXA_SAFE_DIR))}")
        out.appendLine()
    }

    /** Questions 2 and 3: is there a MediaStore row, and can this install read through it? */
    private fun appendMediaStoreRows(out: StringBuilder) {
        out.appendLine("---- MediaStore.Files by DISPLAY_NAME ----")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            out.appendLine("not applicable below API 29: no MediaStore rows back public Documents")
            out.appendLine()
            return
        }
        val names = listOf(VAULT_ARCHIVE_NAME, LEGACY_VAULT_ARCHIVE_NAME)
        val rows = runCatching { queryRows(names) }
        val found = rows.getOrElse {
            out.appendLine("query failed: $it")
            out.appendLine()
            return
        }
        out.appendLine("rows=${found.size}")
        for (row in found) out.appendLine("  ${row.describe(packageName)}")
        out.appendLine("---- ContentResolver open ----")
        if (found.isEmpty()) out.appendLine("nothing to open")
        for (row in found) out.appendLine("  ${row.displayName} -> ${row.openResult}")
        out.appendLine()
    }

    /** Question 4: which document grants survived, if any. Uninstall clears these. */
    private fun appendPersistedGrants(out: StringBuilder) {
        out.appendLine("---- persistedUriPermissions ----")
        val grants = contentResolver.persistedUriPermissions
        if (grants.isEmpty()) out.appendLine("none")
        for (grant in grants) {
            out.appendLine("  ${grant.uri} read=${grant.isReadPermission} write=${grant.isWritePermission}")
        }
    }

    private fun queryRows(names: List<String>): List<Row> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = MediaStore.MediaColumns.DISPLAY_NAME + " IN (?, ?)"
        val args = names.toTypedArray()
        val base = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE
        )
        // OWNER_PACKAGE_NAME is the column that says whether this install or the one before it owns
        // the row; it is not accepted on every build, so the query is retried without it.
        val withOwner = base + MediaStore.MediaColumns.OWNER_PACKAGE_NAME
        return runCatching { readRows(collection, withOwner, selection, args) }
            .getOrElse { readRows(collection, base, selection, args) }
    }

    private fun readRows(
        collection: Uri,
        projection: Array<String>,
        selection: String,
        args: Array<String>
    ): List<Row> {
        val rows = mutableListOf<Row>()
        val cursor = contentResolver.query(collection, projection, selection, args, null)
        cursor?.use {
            val idColumn = it.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameColumn = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathColumn = it.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val sizeColumn = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val ownerColumn = it.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            while (it.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, it.getLong(idColumn))
                rows += Row(
                    uri = uri,
                    displayName = if (nameColumn >= 0) it.getString(nameColumn) else null,
                    relativePath = if (pathColumn >= 0) it.getString(pathColumn) else null,
                    size = if (sizeColumn >= 0) it.getLong(sizeColumn) else 0L,
                    owner = if (ownerColumn >= 0) it.getString(ownerColumn) else null,
                    openResult = openTest(uri)
                )
            }
        }
        return rows
    }

    /** Mimics the only read the vault ever does: open the document and pull bytes out of it. */
    private fun openTest(uri: Uri): String = runCatching {
        val stream = contentResolver.openInputStream(uri)
        if (stream == null) {
            "openInputStream() returned null"
        } else {
            stream.use { input ->
                val head = ByteArray(PROBE_BYTES)
                val read = input.read(head)
                val zip = read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                "opened, firstRead=$read, zipHeader=$zip"
            }
        }
    }.getOrElse { "failed: $it" }

    private fun listing(dir: File): String =
        runCatching { dir.listFiles()?.joinToString { it.name } ?: "null (absent or no access)" }
            .getOrElse { "error: $it" }

    private data class Target(val label: String, val file: File)

    private data class Row(
        val uri: Uri,
        val displayName: String?,
        val relativePath: String?,
        val size: Long,
        val owner: String?,
        val openResult: String
    ) {
        fun describe(installedPackage: String): String {
            val ownership = when (owner) {
                null -> "owner=unknown"
                installedPackage -> "owner=this install"
                else -> "owner=$owner"
            }
            return "$displayName  path=$relativePath  size=$size  $ownership  uri=$uri"
        }
    }

    private val targets: List<Target>
        get() {
            val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            return listOf(
                Target("PixaSafe.zip (intended path)", File(File(documents, PIXA_SAFE_DIR), VAULT_ARCHIVE_NAME)),
                Target("PixaSafe.zip (current path)", File(documents, VAULT_ARCHIVE_NAME)),
                Target("DeepixSafe.zip (legacy)", File(documents, LEGACY_VAULT_ARCHIVE_NAME))
            )
        }

    private companion object {
        /** The folder the vault is meant to live in once [VaultStorage] is reworked. */
        const val PIXA_SAFE_DIR = "PixaSafe"

        /** How much of the document is pulled just to prove it can be read. */
        const val PROBE_BYTES = 4096

        const val TOP_PADDING_DP = 8
    }
}
