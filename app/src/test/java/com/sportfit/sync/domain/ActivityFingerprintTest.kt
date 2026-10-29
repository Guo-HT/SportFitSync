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
}
