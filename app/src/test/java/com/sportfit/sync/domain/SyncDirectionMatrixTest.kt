package com.sportfit.sync.domain

import com.sportfit.sync.platform.PlatformRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the direction matrix matches the upstream Python project exactly.
 *
 * "Migrated all 12 directions" is otherwise just a claim in a README. This
 * pins it to the actual script files in `garmin-sync-coros`, so dropping a
 * platform or forgetting a direction fails the build instead of shipping
 * quietly.
 */
class SyncDirectionMatrixTest {

    /** The 12 `<source>_sync_<target>.py` scripts that exist upstream. */
    private val upstreamDirections = setOf(
        "garmin->coros",
        "coros->garmin",
        "garmin->strava",
        "strava->garmin",
        "coros->strava",
        "strava->coros",
        "igpsport->garmin",
        "igpsport->coros",
        "igpsport->strava",
        "onelap->garmin",
        "onelap->coros",
        "onelap->strava",
    )

    /** Product platforms only: rehearsal and export are not destinations. */
    private val realPlatforms = Platform.entries.filter {
        it != Platform.DEMO && it != Platform.LOCAL_FILES
    }

    @Test
    fun `sources match the upstream project`() {
        assertEquals(
            setOf("garmin", "coros", "strava", "igpsport", "onelap"),
            realPlatforms.filter { it.canBeSource }.map { it.key }.toSet(),
        )
    }

    @Test
    fun `targets match the upstream project`() {
        // iGPSPORT and OneLap are export-only upstream: they never appear as
        // the right-hand side of a sync script name.
        assertEquals(
            setOf("garmin", "coros", "strava"),
            realPlatforms.filter { it.canBeTarget }.map { it.key }.toSet(),
        )
    }

    @Test
    fun `the app offers exactly the twelve upstream directions`() {
        val derived = realPlatforms
            .filter { it.canBeSource }
            .flatMap { source ->
                realPlatforms.filter { it.canBeTarget }
                    .filter { it != source }
                    .map { target -> SyncDirection(source, target).key }
            }
            .toSet()

        assertEquals(12, derived.size)
        assertEquals(upstreamDirections, derived)
    }

    @Test
    fun `every direction has a registered gateway for both ends`() {
        val sources = realPlatforms.filter { it.canBeSource }
        val targets = realPlatforms.filter { it.canBeTarget }

        (sources + targets).forEach { platform ->
            assertNotNull(
                "no gateway registered for ${platform.key}",
                PlatformRegistry.gateway(platform),
            )
        }
    }

    @Test
    fun `every direction key parses back to a usable pair`() {
        upstreamDirections.forEach { key ->
            val direction = SyncDirection.fromKey(key)
            assertNotNull("failed to parse $key", direction)
            assertEquals(key, direction!!.key)
            assertTrue("${direction.source.key} cannot be a source", direction.source.canBeSource)
            assertTrue("${direction.target.key} cannot be a target", direction.target.canBeTarget)
            assertTrue("self-sync slipped in: $key", direction.source != direction.target)
        }
    }

    @Test
    fun `self directions are the three the picker must exclude`() {
        // 5 sources x 3 targets = 15 combinations, minus garmin->garmin,
        // coros->coros and strava->strava = the 12 upstream directions.
        val allCombinations = realPlatforms.count { it.canBeSource } *
            realPlatforms.count { it.canBeTarget }
        val selfDirections = realPlatforms.count { it.canBeSource && it.canBeTarget }

        assertEquals(15, allCombinations)
        assertEquals(3, selfDirections)
        assertEquals(upstreamDirections.size, allCombinations - selfDirections)
    }

    @Test
    fun `the rehearsal platform never leaks into a release build`() {
        val releasePickable = Platform.pickable(includeDemo = false).map { it.key }.toSet()
        assertEquals(
            setOf("garmin", "coros", "strava", "igpsport", "onelap"),
            releasePickable,
        )
        assertTrue("demo" in Platform.pickable(includeDemo = true).map { it.key })
    }

    @Test
    fun `accounts can only be bound for real platforms`() {
        assertEquals(
            setOf("garmin", "coros", "strava", "igpsport", "onelap"),
            Platform.accountPlatforms.map { it.key }.toSet(),
        )
    }
}
