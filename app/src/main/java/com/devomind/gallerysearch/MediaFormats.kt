package com.devomind.gallerysearch

/**
 * One extension→MIME map for every path that has to guess a type from a file name (Safe vault
 * entries, Bin restores). Discovery itself never filters by format — the MediaStore queries take
 * whatever the system indexed — so this only has to cover what those write paths can meet.
 */
object MediaFormats {

    private val imageMimes = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "jpe" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "bmp" to "image/bmp",
        "tiff" to "image/tiff",
        "tif" to "image/tiff",
        "jxl" to "image/jxl",
        // Camera raw — MediaStore indexes these as images on most OEM builds.
        "dng" to "image/x-adobe-dng",
        "cr2" to "image/x-canon-cr2",
        "cr3" to "image/x-canon-cr3",
        "nef" to "image/x-nikon-nef",
        "arw" to "image/x-sony-arw",
        "orf" to "image/x-olympus-orf",
        "rw2" to "image/x-panasonic-rw2",
        "raf" to "image/x-fuji-raf",
        "pef" to "image/x-pentax-pef"
    )

    private val videoMimes = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "3gp" to "video/3gpp",
        "3g2" to "video/3gpp2",
        "avi" to "video/avi",
        "wmv" to "video/x-ms-wmv",
        "flv" to "video/x-flv",
        "ts" to "video/mp2ts",
        "mts" to "video/mp2ts",
        "m2ts" to "video/mp2ts",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg"
    )

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    /** MIME for any known image/video extension, or null when unrecognized. */
    fun mimeFor(fileName: String): String? {
        val ext = extensionOf(fileName)
        return imageMimes[ext] ?: videoMimes[ext]
    }

    /** MIME for an image file name; unknown extensions fall back to JPEG (Safe import default). */
    fun imageMimeFor(fileName: String): String =
        imageMimes[extensionOf(fileName)] ?: "image/jpeg"

    fun isVideoMime(mime: String?): Boolean = mime?.startsWith("video/") == true

    fun isVideoFileName(fileName: String): Boolean =
        videoMimes.containsKey(extensionOf(fileName))
}
