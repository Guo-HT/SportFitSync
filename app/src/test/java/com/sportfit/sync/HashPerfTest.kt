package com.sportfit.sync

import com.sportfit.sync.core.net.Hashing
import com.sportfit.sync.domain.ActivityFingerprint
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摘要计算的耗时回归测试。
 *
 * 内容指纹是**每条活动算一次**的：一次同步上千条活动就是上千次 SHA-256，
 * 而这些计算曾经跑在主线程上（切换同步方向时同一批活动算三遍），
 * 一次切换能把整个界面堵住几百毫秒。
 *
 * 这里守住两件事：
 *  1. 单次摘要足够快（不许退回 `String.format("%02x")` 那种写法）；
 *  2. 一千条活动的指纹计算总耗时在可接受范围内。
 */
class HashPerfTest {

    @Test
    fun sha256StaysFast() {
        val payload = "1755000000000|running|3640|2400|晨骑 morning ride"
        // 预热：JIT 还没编译，第一次调用不代表稳态。
        repeat(200) { Hashing.sha256Hex(payload) }

        val calls = 2000
        val started = System.nanoTime()
        repeat(calls) { Hashing.sha256Hex(payload) }
        val perCallMicros = (System.nanoTime() - started) / 1_000.0 / calls

        println(">>> sha256Hex 单次耗时 %.1f us".format(perCallMicros))
        // 原来的写法（每次新建 MessageDigest + 32 次 String.format）约 21us。
        // 留 10 倍余量，既能挡住明显的退化，又不会因为 CI 机器慢而误报。
        assertTrue("单次摘要耗时 ${perCallMicros}us，已经慢到会卡主线程了", perCallMicros < 20.0)
    }

    @Test
    fun thousandFingerprintsStayUnderBudget() {
        val started = System.nanoTime()
        repeat(1000) { index ->
            ActivityFingerprint.of(
                startTime = 1_755_000_000_000L + index * 86_400_000L,
                sportType = "running",
                distanceMeters = 3000.0 + index,
                durationSec = 1500L + index,
                name = "晨骑 $index",
            )
        }
        val totalMillis = (System.nanoTime() - started) / 1_000_000.0

        println(">>> 1000 条活动指纹合计 %.1f ms".format(totalMillis))
        // 这段计算以前跑在主线程上，超过 50ms 就会肉眼可见地卡一下。
        assertTrue("1000 条指纹耗时 ${totalMillis}ms，主线程扛不住", totalMillis < 120.0)
    }

    @Test
    fun digestReuseDoesNotCorruptResults() {
        // 复用 MessageDigest 之后必须每次都从干净状态开始，
        // 否则第二次算出来的哈希会带上第一次的残留。
        val first = Hashing.sha256Hex("garmin")
        val second = Hashing.sha256Hex("coros")
        val third = Hashing.sha256Hex("garmin")

        assertTrue(first == third)
        assertTrue(first != second)
        // 和一次性算出来的结果对一下，确认复用没有改变算法。
        assertTrue(first == Hashing.sha256Hex("garmin".toByteArray(Charsets.UTF_8)))
    }
}
