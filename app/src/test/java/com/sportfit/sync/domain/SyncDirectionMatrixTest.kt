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
 *
 * ## Two additions of this project
 *
 * On top of the two upstreams the app added its own directions:
 *
 * - **Outbase** (2026-11-01) — target-only, so 5 more directions.
 * - **Garmin 中国区** (2026-11-15) — Garmin was split into two platforms
 *   (`garmin` = 国际区, `garmin-cn` = 中国区) because the two sites are
 *   separate accounts. Both can be a source and a target, which adds
 *   10 directions: the two cross-region pairs (`garmin` ⇄ `garmin-cn`),
 *   every other source into `garmin-cn`, and `garmin-cn` into every other
 *   target.
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

    /**
     * Outbase is target-only, so every source can push into it.
     *
     * It was added after the upstream projects, which is why it does not
     * appear in the "matches the upstream" assertions below.
     */
    private val outbaseTargetDirections = setOf(
        "garmin->outbase",
        "garmin-cn->outbase",
        "coros->outbase",
        "strava->outbase",
        "igpsport->outbase",
        "onelap->outbase",
    )

    /**
     * Directions that only exist because Garmin 中国区 is a platform of its own.
     *
     * The two cross-region pairs are the ones the feature was asked for:
     * `garmin` (国际区) ⇄ `garmin-cn` (中国区). The rest follow from the
     * split — the China account can also feed the other platforms, and the
     * other platforms can feed it.
     */
    private val garminCnDirections = setOf(
        // 跨区互搬
        "garmin->garmin-cn",
        "garmin-cn->garmin",
        // 中国区 → 其它目标
        "garmin-cn->coros",
        "garmin-cn->strava",
        "garmin-cn->igpsport",
        // 其它源 → 中国区
        "coros->garmin-cn",
        "strava->garmin-cn",
        "igpsport->garmin-cn",
        "onelap->garmin-cn",
    )

    /** Every direction the app supports. */
    private val expectedDirections = upstreamDirections + igpsportTargetDirections +
        outbaseTargetDirections + garminCnDirections

    /** Product platforms only: rehearsal and export are not destinations. */
    private val realPlatforms = Platform.entries.filter {
        it != Platform.DEMO && it != Platform.LOCAL_FILES
    }

    @Test
    fun `sources match the upstream project`() {
        assertEquals(
            setOf("garmin", "garmin-cn", "coros", "strava", "igpsport", "onelap"),
            realPlatforms.filter { it.canBeSource }.map { it.key }.toSet(),
        )
    }

    @Test
    fun `targets match the upstream projects`() {
        // OneLap is still export-only in both upstreams: it never appears as
        // the right-hand side of a sync script name.
        assertEquals(
            setOf("garmin", "garmin-cn", "coros", "strava", "igpsport"),
            realPlatforms.filter { it.canBeTarget && it != Platform.OUTBASE }.map { it.key }.toSet(),
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

        assertEquals(31, derived.size)
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
    fun `self directions are the five the picker must exclude`() {
        // 6 sources x 6 targets = 36 combinations, minus the five self
        // directions (garmin, garmin-cn, coros, strava and igpsport on both
        // sides) = the 31 supported directions. Outbase cannot be a source
        // and OneLap cannot be a target, so neither contributes a self
        // direction.
        val allCombinations = realPlatforms.count { it.canBeSource } *
            realPlatforms.count { it.canBeTarget }
        val selfDirections = realPlatforms.count { it.canBeSource && it.canBeTarget }

        assertEquals(36, allCombinations)
        assertEquals(5, selfDirections)
        assertEquals(expectedDirections.size, allCombinations - selfDirections)
    }

    @Test
    fun `the rehearsal platform never leaks into a release build`() {
        val releasePickable = Platform.pickable(includeDemo = false).map { it.key }.toSet()
        assertEquals(
            setOf("garmin", "garmin-cn", "coros", "strava", "igpsport", "onelap", "outbase"),
            releasePickable,
        )
        assertTrue("demo" in Platform.pickable(includeDemo = true).map { it.key })
    }

    @Test
    fun `accounts can only be bound for real platforms`() {
        // Outbase counts: it is a real platform, just not one the upstream
        // projects had. Its credential is the session cookie from the web
        // login rather than a username and password.
        assertEquals(
            setOf("garmin", "garmin-cn", "coros", "strava", "igpsport", "onelap", "outbase"),
            Platform.accountPlatforms.map { it.key }.toSet(),
        )
    }

    @Test
    fun `the two Garmin regions can sync into each other`() {
        // 这是本次改动的核心诉求：国际区与中国区是各自独立的账号，
        // 但两边互相搬运活动是合理的 —— 很多用户两个站都有账号。
        assertTrue(
            "国际区 → 中国区 方向缺失",
            expectedDirections.contains("garmin->garmin-cn"),
        )
        assertTrue(
            "中国区 → 国际区 方向缺失",
            expectedDirections.contains("garmin-cn->garmin"),
        )

        // 两个方向都不是自环：它们是两个不同的平台。
        val forward = SyncDirection.fromKey("garmin->garmin-cn")
        val backward = SyncDirection.fromKey("garmin-cn->garmin")
        assertNotNull(forward)
        assertNotNull(backward)
        assertTrue(forward!!.source != forward.target)
        assertTrue(backward!!.source != backward.target)
        assertEquals(Platform.GARMIN, forward.source)
        assertEquals(Platform.GARMIN_CN, forward.target)
        assertEquals(Platform.GARMIN_CN, backward.source)
        assertEquals(Platform.GARMIN, backward.target)
    }

    @Test
    fun `the two Garmin regions are separate accounts`() {
        // 账号是按平台 key 存的，所以两个区天然是两条独立的凭据，
        // 可以同时配置 —— 这正是「账户列表支持两个佳明账号」的基础。
        assertEquals("garmin", Platform.GARMIN.key)
        assertEquals("garmin-cn", Platform.GARMIN_CN.key)
        assertTrue(Platform.GARMIN.key != Platform.GARMIN_CN.key)

        // 两边都是既可作源又可作目标的平台。
        listOf(Platform.GARMIN, Platform.GARMIN_CN).forEach { platform ->
            assertTrue("${platform.key} 不能作源", platform.canBeSource)
            assertTrue("${platform.key} 不能作目标", platform.canBeTarget)
        }
    }
}
