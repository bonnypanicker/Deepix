package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

/**
 * Pure crypto/format helpers for the Safe.
 *
 * Photos are stored as standard **AES-256 password ZIPs** (via zip4j) so any file manager /
 * 7-Zip / WinRAR can extract the original with the password — the vault is not tied to this app.
 * Grid thumbnails are cached app-privately as AES-GCM blobs keyed off the same password, so they
 * never sit in plaintext and are regenerable from the zips after a reinstall.
 */
object SafeCrypto {
    private const val GcmIvBytes = 12
    private const val GcmTagBits = 128
    const val ThumbMaxPx = 320
    const val VaultExtension = ".zip"

    private val secureRandom = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

    fun pbkdf2(password: String, salt: ByteArray, iterations: Int, bits: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, bits)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    /** Derives the 256-bit AES-GCM key used for the local thumbnail cache. */
    fun thumbKey(password: String, salt: ByteArray): SecretKey =
        SecretKeySpec(pbkdf2(password, salt, 20_000, 256), "AES")

    // ---- Thumbnail cache (AES-GCM, app-private) ----

    fun encryptBytes(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = randomBytes(GcmIvBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GcmTagBits, iv))
        val ct = cipher.doFinal(plain)
        return iv + ct
    }

    fun decryptBytes(key: SecretKey, blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, GcmIvBytes)
        val ct = blob.copyOfRange(GcmIvBytes, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GcmTagBits, iv))
        return cipher.doFinal(ct)
    }

    /** Downscaled JPEG bytes for the grid, or null if the source can't be decoded. */
    fun makeThumbnailJpeg(context: Context, source: Uri): ByteArray? =
        decodeScaledJpeg { context.contentResolver.openInputStream(source) }

    /**
     * Downscaled JPEG bytes decoded straight from one encrypted zip entry, skipping the
     * extract-to-temp step so there's no extracted-file path to mismatch — the decrypted stream
     * is fed straight to the decoder. Each pass opens its own [ZipFile] to keep stream lifetimes
     * self-contained (bounds, then sampled decode).
     */
    fun makeThumbnailJpeg(zip: File, innerName: String, password: CharArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeZipEntry(zip, innerName, password, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > ThumbMaxPx * 2) sample *= 2
        val decoded = decodeZipEntry(
            zip, innerName, password,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        return scaleToThumbnailJpeg(decoded)
    }

    private fun decodeZipEntry(
        zip: File, innerName: String, password: CharArray, opts: BitmapFactory.Options
    ): Bitmap? = runCatching {
        ZipFile(zip, password).use { zf ->
            val header = zf.fileHeaders.firstOrNull { it.fileName == innerName }
                ?: return@runCatching null
            zf.getInputStream(header).use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }.getOrNull()

    private inline fun decodeScaledJpeg(openStream: () -> java.io.InputStream?): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > ThumbMaxPx * 2) sample *= 2
        val decoded = openStream()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        return scaleToThumbnailJpeg(decoded)
    }

    private fun scaleToThumbnailJpeg(decoded: Bitmap): ByteArray {
        val scale = ThumbMaxPx.toFloat() / max(decoded.width, decoded.height).toFloat()
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                max(1, (decoded.width * scale).toInt()),
                max(1, (decoded.height * scale).toInt()),
                true
            ).also { if (it != decoded) decoded.recycle() }
        } else {
            decoded
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    // ---- Single AES-256 zip container (the whole vault "folder", encrypted) ----
    //
    // All safe photos live as entries inside ONE standard AES-256 zip. Filenames sit in the zip's
    // central directory (visible in a listing) but every file's *content* is AES-encrypted, so the
    // archive is useless without the password — and fully openable in any zip tool with it.

    private fun aesZipParameters(innerName: String) = ZipParameters().apply {
        isEncryptFiles = true
        encryptionMethod = EncryptionMethod.AES
        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
        // Photos are already compressed — STORE keeps import fast.
        compressionMethod = CompressionMethod.STORE
        fileNameInZip = innerName
    }

    /** Adds [source] to the vault archive as entry [innerName] (creates the zip if absent). */
    fun addFileToZip(zip: File, source: File, innerName: String, password: CharArray) {
        ZipFile(zip, password).use { it.addFile(source, aesZipParameters(innerName)) }
    }

    /** Entry names in the archive (readable without the password — contents stay encrypted). */
    fun listEntryNames(zip: File): List<String> {
        if (!zip.exists()) return emptyList()
        return runCatching {
            ZipFile(zip).use { z -> z.fileHeaders.map { it.fileName } }
        }.getOrDefault(emptyList())
    }

    /** Extracts one entry into [destDir], returning the extracted file. */
    fun extractEntry(zip: File, innerName: String, password: CharArray, destDir: File): File {
        destDir.mkdirs()
        ZipFile(zip, password).use { it.extractFile(innerName, destDir.absolutePath) }
        return File(destDir, innerName)
    }

    fun removeEntry(zip: File, innerName: String, password: CharArray) {
        ZipFile(zip, password).use { it.removeFile(innerName) }
    }

    /**
     * Validates [password] against an existing vault archive. Relies on the 2-byte AES password
     * verifier in the zip header, so it fails fast without reading whole files. An empty/absent
     * archive is treated as valid (nothing to protect yet).
     */
    fun verifyPassword(zip: File, password: CharArray): Boolean {
        if (!zip.exists()) return true
        return runCatching {
            ZipFile(zip, password).use { z ->
                val header = z.fileHeaders.firstOrNull() ?: return true
                z.getInputStream(header).use { it.read() }
            }
            true
        }.getOrDefault(false)
    }
}
