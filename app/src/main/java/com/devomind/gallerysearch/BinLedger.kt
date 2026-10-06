package com.devomind.gallerysearch

import java.io.File

/** Where a binned photo is in its life. Written into the sidecar, so it survives a process kill. */
enum class BinState {
    /** The bytes are being copied in. Nothing has been deleted yet. */
    Pending,

    /** The copy was verified complete. The original is, or is about to be, gone. */
    Committed,

    /** Found as bytes with no ledger beside them; its original path is unknown. */
    Recovered
}

/**
 * One photo in the Recycle Bin. `id` names both the sidecar and the data file, so the pair can be
 * found from either half.
 */
data class BinEntry(
    val id: String,
    val fileName: String,
    val originalPath: String?,
    val mimeType: String?,
    val deletedAt: Long,
    val sizeBytes: Long,
    val state: BinState
)

/**
 * The Recycle Bin's bookkeeping, kept free of Android so its crash behaviour is testable.
 *
 * Every binned photo is a pair of files: `bin/data/<id>` holding the bytes and `bin/entries/<id>.binmeta`
 * holding the record. There is no aggregate index — the directory listing *is* the bin — so a damaged
 * record can only cost one photo, never the whole bin. That replaces `bin_index.json`, whose single
 * file was the one place a truncated write could strand every deleted photo.
 *
 * Sidecars are positional text (state, name, path, mime, deletedAt, size — one line each) rather than
 * JSON: `org.json` is a throwing stub in this module's unit tests, and the raw-line form needs no
 * escaping rules for paths that contain spaces or quotes.
 *
 * [reconcile] resolves what a kill left behind. It answers two questions per record — is the copy
 * whole, and is the original still there — and every combination of those two is safe:
 * - original present → nothing was lost, so the bin copy goes away and the photo stays where it was;
 * - original gone, copy whole → the delete did happen, so the record is promoted and the bin shows it;
 * - original gone, copy short → the bytes are all that exists, so the record is quarantined aside with
 *   the data intact rather than being reported as an empty bin.
 */
object BinLedger {

    const val DataDirName = "data"
    const val EntriesDirName = "entries"
    const val SidecarSuffix = ".binmeta"
    const val CorruptSuffix = SidecarSuffix + ".corrupt"

    /** The pre-sidecar aggregate index, kept until [adoptLegacyIndex] can read it. */
    const val LegacyIndexName = "bin_index.json"

    private val States: Array<BinState> = BinState.values()

    fun dataDir(root: File): File = File(root, DataDirName)
    fun entriesDir(root: File): File = File(root, EntriesDirName)
    fun dataFile(root: File, id: String): File = File(dataDir(root), id)
    fun sidecarFile(root: File, id: String): File = File(entriesDir(root), id + SidecarSuffix)

    /** How much of the bin's storage may be used before a move-in is refused. */
    const val QuotaMarginBytes = 32L * 1024 * 1024

    /**
     * Whether a move-in of [neededBytes] fits in [usableBytes]. A bin copy that fails halfway is worse
     * than a refusal, so the margin keeps the filesystem from filling to the last block.
     */
    fun hasRoomFor(usableBytes: Long, neededBytes: Long, marginBytes: Long = QuotaMarginBytes): Boolean =
        usableBytes > marginBytes && usableBytes - marginBytes >= neededBytes

    /**
     * Whether a copy is the whole file. An unknown expected size (<= 0) only proves the copy is
     * non-empty; that is the weakest check in the ledger and the reason [reconcile] re-reads it later.
     */
    fun copyComplete(expectedBytes: Long, writtenBytes: Long): Boolean =
        if (expectedBytes <= 0L) writtenBytes > 0L else writtenBytes >= expectedBytes

    /** @return the record, or null when the file can't be read as one. */
    fun read(sidecar: File): BinEntry? {
        val lines = runCatching { sidecar.readText().split('\n') }.getOrNull() ?: return null
        if (lines.size < 6) return null
        val state = States.firstOrNull { it.name == lines[0] } ?: return null
        val deletedAt = lines[4].toLongOrNull() ?: return null
        val sizeBytes = lines[5].toLongOrNull() ?: return null
        val id = sidecar.name.removeSuffix(SidecarSuffix).removeSuffix(".corrupt")
        return BinEntry(
            id = id,
            fileName = lines[1],
            originalPath = lines[2].ifBlank { null },
            mimeType = lines[3].ifBlank { null },
            deletedAt = deletedAt,
            sizeBytes = sizeBytes,
            state = state
        )
    }

    /** Stage-then-rename, so a kill mid-write leaves the previous record or the new one — never a half. */
    fun write(root: File, entry: BinEntry): Boolean {
        val sidecar = sidecarFile(root, entry.id)
        sidecar.parentFile?.mkdirs()
        val payload = buildString {
            append(entry.state.name).append('\n')
            append(entry.fileName).append('\n')
            append(entry.originalPath ?: "").append('\n')
            append(entry.mimeType ?: "").append('\n')
            append(entry.deletedAt).append('\n')
            append(entry.sizeBytes).append('\n')
        }
        val staging = File(sidecar.parentFile, sidecar.name + ".tmp")
        return IndexCheckpoint.write(sidecar, staging) { output -> output.writeBytes(payload) }.replaced
    }

    /** Everything the bin should show: verified entries and adopted ones, but not mid-flight writes. */
    fun list(root: File): List<BinEntry> = sidecars(root).mapNotNull { sidecar ->
        runCatching { read(sidecar) }.getOrNull()
            ?.takeIf { it.state != BinState.Pending }
            ?.let { it.copy(fileName = it.fileName.ifBlank { it.id }) }
    }.sortedByDescending { it.deletedAt }

    fun sidecars(root: File): List<File> =
        entriesDir(root).listFiles { file -> file.isFile && file.name.endsWith(SidecarSuffix) }
            ?.sortedBy { it.name }
            .orEmpty()

    /** Removes both halves of an entry. Returns whether everything that was there could be deleted. */
    fun discard(root: File, id: String): Boolean {
        val sidecar = sidecarFile(root, id)
        val data = dataFile(root, id)
        val sidecarGone = !sidecar.exists() || sidecar.delete()
        val dataGone = !data.exists() || data.delete()
        return sidecarGone && dataGone
    }

    /** The data file for `id`, pulling it in from the pre-sidecar layout where it sits loose in [root]. */
    fun claimData(root: File, id: String): File? {
        val canonical = dataFile(root, id)
        if (canonical.exists()) return canonical
        val loose = File(root, id)
        if (!loose.isFile) return null
        dataDir(root).mkdirs()
        return if (loose.renameTo(canonical)) canonical else null
    }

    /** Copies the binned bytes back out; the record is only removed once the copy has landed. */
    fun copyDataOut(root: File, entry: BinEntry, destination: File): Boolean {
        val data = dataFile(root, entry.id)
        if (!data.exists()) return false
        destination.parentFile?.mkdirs()
        return runCatching { data.copyTo(destination, overwrite = false); true }.getOrDefault(false)
    }

    data class Reconciled(
        val promoted: Int = 0,
        val rolledBack: Int = 0,
        val quarantined: Int = 0,
        val discarded: Int = 0,
        val adopted: Int = 0
    ) {
        fun describe(): String =
            "promoted=$promoted rolledBack=$rolledBack quarantined=$quarantined discarded=$discarded adopted=$adopted"
    }

    /**
     * Resolves whatever an interruption left behind. Safe to run more than once.
     *
     * [originalPresent] answers whether the photo is still where it was deleted from. The caller owns
     * that question because "still there" can mean a path, a MediaStore row, or both.
     */
    fun reconcile(root: File, originalPresent: (BinEntry) -> Boolean): Reconciled {
        var promoted = 0
        var rolledBack = 0
        var quarantined = 0
        var discarded = 0
        var adopted = 0
        val claimed = mutableSetOf<String>()

        for (sidecar in sidecars(root)) {
            val id = sidecar.name.removeSuffix(SidecarSuffix)
            val entry = read(sidecar)
            if (entry == null) {
                // Unclaimed, so its bytes fall through to the orphan pass below and are adopted.
                if (quarantine(sidecar)) quarantined++
                continue
            }
            claimed += id
            val data = claimData(root, id)
            val originalStillThere = originalPresent(entry)
            when {
                // The photo never left its folder: the bin copy is redundant, and the original is authoritative.
                originalStillThere -> {
                    data?.delete()
                    sidecar.delete()
                    rolledBack++
                }
                data == null -> {
                    // Both halves are gone; the record describes a photo that no longer exists anywhere.
                    sidecar.delete()
                    discarded++
                }
                !copyComplete(entry.sizeBytes, data.length()) -> {
                    // The only surviving bytes are a partial copy. Keep them, but stop reading them as an entry.
                    quarantine(sidecar)
                    quarantined++
                }
                entry.state == BinState.Pending -> {
                    write(root, entry.copy(state = BinState.Committed))
                    promoted++
                }
            }
        }

        for (data in dataDir(root).listFiles()?.filter { it.isFile }.orEmpty()) {
            val id = data.name
            if (id in claimed || id.endsWith(".tmp")) continue
            write(
                root,
                BinEntry(
                    id = id,
                    fileName = id,
                    originalPath = null,
                    mimeType = null,
                    deletedAt = data.lastModified(),
                    sizeBytes = data.length(),
                    state = BinState.Recovered
                )
            )
            adopted++
        }

        return Reconciled(promoted, rolledBack, quarantined, discarded, adopted)
    }

    /**
     * Whether the photo is still where it was deleted from, at the size it was binned at. The size has
     * to match too: a folder can gain a new file under a recycled name, and that is not the original
     * coming back — deleting the bin copy for it would be the one thing this ledger exists to prevent.
     */
    fun originalStillThere(entry: BinEntry): Boolean {
        val path = entry.originalPath ?: return false
        val original = File(path)
        if (!original.isFile) return false
        return entry.sizeBytes <= 0L || original.length() == entry.sizeBytes
    }

    /** Rename, never delete: an unreadable or unfinished record stays on disk for inspection. */
    private fun quarantine(sidecar: File): Boolean =
        sidecar.renameTo(File(sidecar.parentFile, sidecar.name + ".corrupt"))
}
