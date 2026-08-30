package com.devomind.gallerysearch

import android.content.Context
import android.location.Geocoder
import android.net.Uri
import android.util.Log
import com.devomind.gallerysearch.db.GpsPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Place-aware photo lookup for the search "Locations" section — deliberately independent of the
 * CLIP index: a photo matches because its EXIF GPS sits within [RadiusMeters] of the place the
 * query names, not because of what the image looks like.
 *
 * Two Android-Geocoder lookups, each cached to disk so repeats work offline:
 *  - forward: query text -> a coordinate ("goa" -> 15.3, 74.1). Known misses are cached as "".
 *  - the photos' own coordinates come from the exif_metadata table, filled progressively (bounded
 *    per search) by reading EXIF for library photos that were never opened in the viewer.
 *
 * Everything here fails soft: any error yields an empty match, never a failed search.
 */
object LocationSearch {

    private const val Tag = "LocationSearch"
    private const val CacheFileName = "location_geocode_cache.json"
    private const val RadiusMeters = 50_000.0
    private const val MaxExifExtractionsPerSearch = 150

    @Volatile private var cache: MutableMap<String, String>? = null

    /** URIs of library photos located within [RadiusMeters] of the place [query] names. */
    suspend fun matchingUris(
        context: Context,
        query: String,
        db: DbRepository,
        libraryUris: List<Uri>
    ): Set<String> {
        val cleaned = query.trim().lowercase(Locale.getDefault())
        if (cleaned.isEmpty() || !Geocoder.isPresent()) return emptySet()
        return try {
            fillMissingExif(context, db, libraryUris)
            val gps = db.gpsPoints()
            if (gps.isEmpty()) return emptySet()
            val target = resolveTarget(context, cleaned) ?: return emptySet()
            gps.filter { point ->
                haversineMeters(point.lat, point.lng, target.first, target.second) <= RadiusMeters
            }.mapTo(HashSet()) { it.uri }
        } catch (error: Throwable) {
            Log.w(Tag, "Location search failed (non-fatal)", error)
            emptySet()
        }
    }

    /** Reads EXIF (incl. GPS) for up to [MaxExifExtractionsPerSearch] library photos not yet stored. */
    private suspend fun fillMissingExif(context: Context, db: DbRepository, libraryUris: List<Uri>) {
        if (libraryUris.isEmpty()) return
        val existing = db.existingExifUris(libraryUris.map { it.toString() })
        val missing = libraryUris.asSequence()
            .filter { it.toString() !in existing }
            .take(MaxExifExtractionsPerSearch)
            .toList()
        for (uri in missing) {
            runCatching { ExifExtractor.extract(context, uri) }
                .getOrNull()
                ?.let { db.upsertExif(uri.toString(), it) }
        }
    }

    /** Query text -> (lat, lng): a disk-cached hit, else one forward-geocode call (misses cached too). */
    private suspend fun resolveTarget(context: Context, query: String): Pair<Double, Double>? {
        cache(context)[query]?.let { cached -> return parsePoint(cached) }
        val hit = withContext(Dispatchers.IO) {
            runCatching {
                Geocoder(context, Locale.getDefault())
                    .getFromLocationName(query, 1)
                    ?.firstOrNull()
            }.getOrNull()
        }
        val point = hit?.let { it.latitude to it.longitude }
        cache(context)[query] = point?.let { "${it.first},${it.second}" }.orEmpty()
        saveCache(context)
        return point
    }

    private fun cache(context: Context): MutableMap<String, String> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val map = runCatching {
                val obj = JSONObject(File(context.filesDir, CacheFileName).readText())
                val m = linkedMapOf<String, String>()
                for (key in obj.keys()) m[key] = obj.optString(key)
                m
            }.getOrDefault(linkedMapOf())
            cache = map
            return map
        }
    }

    private fun saveCache(context: Context) {
        val map = cache ?: return
        runCatching {
            val obj = JSONObject()
            for ((key, value) in map) obj.put(key, value)
            File(context.filesDir, CacheFileName).writeText(obj.toString())
        }
    }

    private fun parsePoint(raw: String): Pair<Double, Double>? {
        if (raw.isBlank()) return null
        val lat = raw.split(",").getOrNull(0)?.toDoubleOrNull() ?: return null
        val lng = raw.split(",").getOrNull(1)?.toDoubleOrNull() ?: return null
        return lat to lng
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
