package com.devomind.gallerysearch

import androidx.room.migration.Migration
import com.devomind.gallerysearch.db.GalleryDatabase
import com.devomind.gallerysearch.db.SchemaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the database used to break: a `version` bump with no migration beside it was silently
 * answered by `fallbackToDestructiveMigration()`, which drops favorites, tags and every other table
 * on the floor. The blanket fallback is gone, so an unmigrated bump now crashes at open time — this
 * test moves that failure to build time, with a message that says what to add.
 *
 * What a schema bump still needs by hand is the instrumented run of each migration against a
 * populated database (`MigrationTestHelper`). That needs a device and the `androidx.test` artifacts,
 * neither of which this build has, so it stays a per-bump manual step rather than a claim here.
 */
class MigrationGraphTest {

    private val migrations: List<Migration> get() = GalleryDatabase.MIGRATIONS

    @Test
    fun theMigrationChainReachesTheDeclaredSchemaVersion() {
        assertTrue("the database declares no migrations at all", migrations.isNotEmpty())
        val byStart = migrations.associateBy { it.startVersion }
        val chainStart = migrations.minOf { it.startVersion }
        val walked = linkedSetOf(chainStart)

        var version = chainStart
        while (version < SchemaVersion) {
            val edge = byStart[version]
                ?: error(
                    "Schema is at version $SchemaVersion but no migration leads out of version $version. " +
                        "Add Migration($version, ${version + 1}) to GalleryDatabase.MIGRATIONS."
                )
            version = edge.endVersion
            // A backwards or repeating edge means the graph is not a chain, and the loop needs stopping.
            if (!walked.add(version)) {
                error("Migration graph loops: ${walked.joinToString(" → ")} → $version")
            }
        }

        assertEquals(
            "Migrations overshoot the declared schema version $SchemaVersion: ${walked.joinToString(" → ")}",
            SchemaVersion,
            version
        )
    }

    @Test
    fun noVersionIsMigratedFromOrToTwice() {
        val starts = migrations.map { it.startVersion }
        val ends = migrations.map { it.endVersion }
        assertEquals("two migrations start at the same version", starts.size, starts.toSet().size)
        assertEquals("two migrations land on the same version", ends.size, ends.toSet().size)
    }

    /**
     * Drop-and-recreate is permitted only for versions that never shipped. Listing a version that any
     * install could hold would put the fallback back in the building this test exists to keep out.
     */
    @Test
    fun destructiveFallbackIsLimitedToNeverReleasedVersions() {
        val chainStart = migrations.minOf { it.startVersion }
        val fallbackFrom = GalleryDatabase.DestructiveFallbackFrom.toList()

        assertTrue("the fallback list is empty, so nothing is dropped", fallbackFrom.isNotEmpty())
        assertTrue(
            "fallbackToDestructiveMigrationFrom covers $fallbackFrom, but version ${chainStart - 1} and above " +
                "have real migrations and hold user data",
            fallbackFrom.all { it < chainStart }
        )
        assertEquals(
            "the fallback versions must stop where the migration chain starts",
            chainStart - 1,
            fallbackFrom.maxOrNull() ?: 0
        )
    }
}
