package com.sportfit.sync.domain

import com.sportfit.sync.platform.PlatformRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the direction matrix matches the upstream projects exactly.
 *
 * "Migrated all 12 directions" is otherwise just a claim in a README. This
 * pins it to the actual script files the app was migrated from, so dropping a
 * platform or forgetting a direction fails the build instead of shipping
 * quietly.
 *
 * ## Two upstreams, one matrix
 *
 * The Android app was migrated from the Python project `garmin-sync-coros`,
 * which shipped 12 `<source>_sync_<target>.py` scripts over
 * 5 sources × 3 targets. iGPSPORT was one of the **sources** there, never a
 * target.
 *
 * A second project, [Dunky-Z/FitSync](https://github.com/Dunky-Z/FitSync),
 * implements the iGPSPORT *import* channel (`activity/uploadByOss`), and ships
 * `strava_to_igpsport` on top of it. That channel was ported into this app on
 * 2026-10-31, which makes iGPSPORT the fourth target platform and takes the
 * matrix from 12 to 16 directions.
 *
 * The matrix is therefore the union of the two upstreams, and this test pins
 * it explicitly: the 12 original directions plus the 4 that need iGPSPORT on
 * the right-hand side.
 */
class SyncDirectionMatrixTest {

    /**
     * The 12 `<source>_sync_<target>.py` scripts of `garmin-sync-coros`.
     *
     * These are the directions that existed before the iGPSPORT import
     * channel was ported in.
     */
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

    /**
     * The directions that only exist because iGPSPORT can now be a target.
     *
     * `Dunky-Z/FitSync` ships `strava_to_igpsport`; the remaining three are
     * the same channel opened to the other sources.
     */
    private val igpsportTargetDirections = setOf(
        "garmin->igpsport",
        "coros->igpsport",
        "strava->igpsport",
        "onelap->igpsport",
    )

    /** Every direction the app supports. */
    private val expectedDirections = upstreamDirections + igpsportTargetDirections

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
    fun `targets match the upstream projects`() {
        // OneLap is still export-only in both upstreams: it never appears as
        // the right-hand side of a sync script name.
        assertEquals(
            setOf("garmin", "coros", "strava", "igpsport"),
            realPlatforms.filter { it.canBeTarget }.map { it.key }.toSet(),
        )
    }

    @Test
    fun `the app offers exactly the upstream directions`() {
        val derived = realPlatforms
            .filter { it.canBeSource }
            .flatMap { source ->
                realPlatforms.filter { it.canBeTarget }
                    .filter { it != source }
                    .map { target -> SyncDirection(source, target).key }
            }
            .toSet()

        assertEquals(16, derived.size)
        assertEquals(expectedDirections, derived)
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
        expectedDirections.forEach { key ->
            val direction = SyncDirection.fromKey(key)
            assertNotNull("failed to parse $key", direction)
            assertEquals(key, direction!!.key)
            assertTrue("${direction.source.key} cannot be a source", direction.source.canBeSource)
            assertTrue("${direction.target.key} cannot be a target", direction.target.canBeTarget)
            assertTrue("self-sync slipped in: $key", direction.source != direction.target)
        }
    }

    @Test
    fun `self directions are the four the picker must exclude`() {
        // 5 sources x 4 targets = 20 combinations, minus the four self
        // directions (garmin, coros, strava and igpsport on both sides) =
        // the 16 supported directions.
        val allCombinations = realPlatforms.count { it.canBeSource } *
            realPlatforms.count { it.canBeTarget }
        val selfDirections = realPlatforms.count { it.canBeSource && it.canBeTarget }

        assertEquals(20, allCombinations)
        assertEquals(4, selfDirections)
        assertEquals(expectedDirections.size, allCombinations - selfDirections)
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
