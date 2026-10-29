package com.sportfit.sync.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 内容指纹的行为约定。
 *
 * 指纹是「同一份活动数据」的稳定标识：源平台给同一场骑行发了新的
 * activityId 之后，靠它依然能认出「这份数据已经同步过了」。
 */
class ActivityFingerprintTest {

    private fun meta(
        id: String = "a1",
        name: String = "晨骑",
        startTime: Long? = 1_700_000_000_000L,
        distanceMeters: Double? = 21_000.0,
        durationSec: Long? = 3600L,
        sportType: String? = "RIDE",
    ) = ActivityMeta(
        id = id,
        name = name,
        startTime = startTime,
        distanceMeters = distanceMeters,
        durationSec = durationSec,
        sportType = sportType,
    )

    @Test
    fun `同样的数据算出同样的指纹`() {
        // 同一个 id、同样的内容：必须完全一致。
        assertEquals(
            ActivityFingerprint.of(meta()),
            ActivityFingerprint.of(meta()),
        )

        // 关键场景：换了 activityId，但内容一模一样 —— 指纹必须不变，
        // 否则「重新导入 FIT」就会产生重复数据。
        assertEquals(
            ActivityFingerprint.of(meta(id = "a1")),
            ActivityFingerprint.of(meta(id = "a999")),
        )

        // ActivityMeta 重载和显式参数重载必须给出同一个结果。
        assertEquals(
            ActivityFingerprint.of(meta()),
            ActivityFingerprint.of(
                startTime = 1_700_000_000_000L,
                sportType = "RIDE",
                distanceMeters = 21_000.0,
                durationSec = 3600L,
                name = "晨骑",
            ),
        )
    }

    @Test
    fun `开始时间不同指纹不同`() {
        assertNotEquals(
            ActivityFingerprint.of(meta(startTime = 1_700_000_000_000L)),
            ActivityFingerprint.of(meta(startTime = 1_700_000_000_001L)),
        )
    }

    @Test
    fun `距离不同指纹不同`() {
        assertNotEquals(
            ActivityFingerprint.of(meta(distanceMeters = 21_000.0)),
            ActivityFingerprint.of(meta(distanceMeters = 21_001.0)),
        )
    }

    @Test
    fun `时长不同指纹不同`() {
        assertNotEquals(
            ActivityFingerprint.of(meta(durationSec = 3600L)),
            ActivityFingerprint.of(meta(durationSec = 3601L)),
        )
    }

    @Test
    fun `名称不同指纹不同`() {
        assertNotEquals(
            ActivityFingerprint.of(meta(name = "晨骑")),
            ActivityFingerprint.of(meta(name = "夜骑")),
        )
    }

    @Test
    fun `运动类型不同指纹不同`() {
        assertNotEquals(
            ActivityFingerprint.of(meta(sportType = "RIDE")),
            ActivityFingerprint.of(meta(sportType = "RUN")),
        )
    }

    @Test
    fun `没有开始时间就没有指纹`() {
        // 没有开始时间就没有可靠的内容标识，调用方必须回退到按 id 判断。
        assertEquals(ActivityFingerprint.NONE, ActivityFingerprint.of(meta(startTime = null)))
        assertEquals("", ActivityFingerprint.NONE)
        assertEquals(
            ActivityFingerprint.NONE,
            ActivityFingerprint.of(
                startTime = null,
                sportType = "RIDE",
                distanceMeters = 21_000.0,
                durationSec = 3600L,
                name = "晨骑",
            ),
        )
    }

    @Test
    fun `名称的大小写和首尾空格不影响指纹`() {
        // 不同平台给同一场活动的命名可能只差大小写或空格。
        assertEquals(
            ActivityFingerprint.of(meta(name = "晨骑")),
            ActivityFingerprint.of(meta(name = "  晨骑  ")),
        )
        assertEquals(
            ActivityFingerprint.of(meta(name = "Morning Ride")),
            ActivityFingerprint.of(meta(name = "morning ride")),
        )
        assertEquals(
            ActivityFingerprint.of(meta(sportType = "RIDE")),
            ActivityFingerprint.of(meta(sportType = " ride ")),
        )
    }

    @Test
    fun `距离取整后相同的距离得到相同指纹`() {
        // 不同平台的距离精度不一样（米 / 公里换算 / 浮点误差），
        // 取整到米之后应该视为同一场活动。
        assertEquals(
            ActivityFingerprint.of(meta(distanceMeters = 2199.46)),
            ActivityFingerprint.of(meta(distanceMeters = 2199.4612)),
        )
        assertNotEquals(
            ActivityFingerprint.of(meta(distanceMeters = 2199.46)),
            ActivityFingerprint.of(meta(distanceMeters = 2200.0)),
        )
    }

    // ---- 跨平台宽松指纹（looseOf）-----------------------------------------

    @Test
    fun `宽松指纹在没有开始时间时为空`() {
        // 没有开始时间就没有可比对的锚点，调用方必须回退到按 id 判断。
        assertEquals(
            ActivityFingerprint.NONE,
            ActivityFingerprint.looseOf(meta(startTime = null)),
        )
        assertEquals(
            ActivityFingerprint.NONE,
            ActivityFingerprint.looseOf(
                startTime = null,
                distanceMeters = 21_000.0,
                durationSec = 3600L,
            ),
        )
    }

    @Test
    fun `宽松指纹在距离和时长都没有时为空`() {
        // 数据太少，误判风险高 —— 宁可不比，也不能错杀一次同步。
        assertEquals(
            ActivityFingerprint.NONE,
            ActivityFingerprint.looseOf(meta(distanceMeters = null, durationSec = null)),
        )
        assertEquals(
            ActivityFingerprint.NONE,
            ActivityFingerprint.looseOf(
                startTime = 1_700_000_000_000L,
                distanceMeters = null,
                durationSec = null,
            ),
        )
    }

    @Test
    fun `宽松指纹只要距离或时长有一个就有值`() {
        val onlyDistance = ActivityFingerprint.looseOf(
            startTime = 1_700_000_000_000L,
            distanceMeters = 21_000.0,
            durationSec = null,
        )
        val onlyDuration = ActivityFingerprint.looseOf(
            startTime = 1_700_000_000_000L,
            distanceMeters = null,
            durationSec = 3600L,
        )
        assertNotEquals(ActivityFingerprint.NONE, onlyDistance)
        assertNotEquals(ActivityFingerprint.NONE, onlyDuration)
        assertNotEquals(onlyDistance, onlyDuration)

        // ActivityMeta 重载必须和显式参数重载一致。
        assertEquals(
            onlyDistance,
            ActivityFingerprint.looseOf(meta(durationSec = null)),
        )
        assertEquals(
            onlyDuration,
            ActivityFingerprint.looseOf(meta(distanceMeters = null)),
        )
    }

    @Test
    fun `宽松指纹对同样的数据结果相同`() {
        assertEquals(
            ActivityFingerprint.looseOf(meta()),
            ActivityFingerprint.looseOf(meta()),
        )
        // 换 id 不影响宽松指纹（它只看内容）。
        assertEquals(
            ActivityFingerprint.looseOf(meta(id = "a1")),
            ActivityFingerprint.looseOf(meta(id = "a999")),
        )
    }

    @Test
    fun `宽松指纹对距离的微小差异不敏感`() {
        // 差 0.4 米，取整到米之后还是同一个值 → 视为同一场活动。
        assertEquals(
            ActivityFingerprint.looseOf(meta(distanceMeters = 21_000.0)),
            ActivityFingerprint.looseOf(meta(distanceMeters = 21_000.4)),
        )
        // 差 1.2 米，取整后不同 → 不同场活动。
        assertNotEquals(
            ActivityFingerprint.looseOf(meta(distanceMeters = 21_000.0)),
            ActivityFingerprint.looseOf(meta(distanceMeters = 21_001.2)),
        )
    }

    @Test
    fun `宽松指纹对时长和开始时间敏感`() {
        // 时长差 1 秒 → 不是同一场活动。
        assertNotEquals(
            ActivityFingerprint.looseOf(meta(durationSec = 3600L)),
            ActivityFingerprint.looseOf(meta(durationSec = 3601L)),
        )
        // 开始时间差 1 毫秒 → 不是同一场活动。
        assertNotEquals(
            ActivityFingerprint.looseOf(meta(startTime = 1_700_000_000_000L)),
            ActivityFingerprint.looseOf(meta(startTime = 1_700_000_000_001L)),
        )
    }

    @Test
    fun `宽松指纹和严格指纹结果不同`() {
        // 防止误用：严格指纹带运动类型和名字，宽松指纹不带，两者不可互换。
        assertNotEquals(
            ActivityFingerprint.of(meta()),
            ActivityFingerprint.looseOf(meta()),
        )
        assertNotEquals(
            ActivityFingerprint.of(meta()),
            ActivityFingerprint.looseOf(
                startTime = 1_700_000_000_000L,
                distanceMeters = 21_000.0,
                durationSec = 3600L,
            ),
        )
    }
}
