package com.sportfit.sync.platform.demo

import com.sportfit.sync.core.format.FitPackaging
import com.sportfit.sync.core.format.FitPayload
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.domain.UploadOutcome
import com.sportfit.sync.platform.PlatformAccount
import com.sportfit.sync.platform.PlatformContext
import com.sportfit.sync.platform.PlatformGateway
import com.sportfit.sync.platform.PlatformSource
import com.sportfit.sync.platform.PlatformTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * A self-contained rehearsal platform, available only in debug builds.
 *
 * It exists so the whole pipeline — listing, ledger seeding, download,
 * FIT/ZIP normalisation, temp-file staging, upload, per-activity failure,
 * retry, cancellation and cache cleanup — can be exercised on a device with
 * no real credentials and no network.
 *
 * **It deliberately mirrors the real clients' call shape.** Every real
 * platform wraps its work in `withContext(Dispatchers.IO)` and calls
 * `ctx.log(...)` from inside that block. An earlier version of this class did
 * neither, and as a result the rehearsal runs never touched the code path that
 * broke every real login (`Flow invariant is violated`, see
 * docs/development-handbook.md §11). Keep the `withContext` + `log` calls:
 * they are the regression surface, not decoration.
 *
 * Behaviour is deterministic on purpose: activity `demo-5` always fails so
 * "重试失败项" has something to retry, and every third activity reports as a
 * duplicate so that branch is covered too.
 */
object DemoGateway : PlatformGateway {

    const val ACTIVITY_COUNT = 12

    /** Never sends anything anywhere; satisfies the gateway contract. */
    private val account = PlatformAccount(Platform.DEMO)

    override suspend fun createSource(ctx: PlatformContext): PlatformSource =
        DemoSource(ctx.log)

    override suspend fun createTarget(ctx: PlatformContext): PlatformTarget =
        DemoTarget(ctx.log)

    fun accountFor(): PlatformAccount = account
}

private class DemoSource(private val log: suspend (String) -> Unit) : PlatformSource {

    override val platform: Platform = Platform.DEMO

    override suspend fun listActivities(limit: Int): List<ActivityMeta> =
        withContext(Dispatchers.IO) {
            delay(150) // make the "listing" step visible in the UI
            val now = System.currentTimeMillis()
            val activities = (1..DemoGateway.ACTIVITY_COUNT).take(limit).map { index ->
                ActivityMeta(
                    id = "demo-$index",
                    name = "演练活动 $index",
                    startTime = now - TimeUnit.DAYS.toMillis(index.toLong()),
                    distanceMeters = 5_000.0 + index * 1_234.0,
                    durationSec = 1_500L + index * 61,
                    sportType = if (index % 3 == 0) "cycling" else "running",
                )
            }
            log("演练：已生成 ${activities.size} 条活动")
            activities
        }

    override suspend fun download(activity: ActivityMeta): FitPayload =
        withContext(Dispatchers.IO) {
            delay(120)
            // A Garmin-style download is a ZIP wrapping one `{id}_ACTIVITY.fit`
            // entry; producing the same shape here means the normaliser is
            // exercised exactly as it will be in production.
            val id = activity.id
            val fit = syntheticFit(id)
            val zip = FitPackaging.zip(fit, "${id}_ACTIVITY.fit")
            log("演练：已下载 $id")
            FitPayload(bytes = zip, fileName = "$id.zip", zipped = true)
        }

    /** Minimal well-formed FIT header plus padding; content is irrelevant. */
    private fun syntheticFit(activityId: String): ByteArray {
        val payload = ByteArray(2_048 + activityId.length * 7) { index ->
            (index * 31 + activityId.hashCode()).toByte()
        }
        ".FIT".toByteArray(Charsets.US_ASCII).copyInto(payload, destinationOffset = 8)
        return payload
    }
}

private class DemoTarget(private val log: suspend (String) -> Unit) : PlatformTarget {

    override val platform: Platform = Platform.DEMO

    override suspend fun upload(payload: FitPayload): UploadOutcome =
        withContext(Dispatchers.IO) {
            delay(180)

            // A target must never receive a ZIP; if this fires the normaliser is
            // broken, which is precisely what the rehearsal run is meant to catch.
            val raw = FitPackaging.ensureRawFit(payload)
            if (raw.zipped) {
                return@withContext UploadOutcome.Failed("演练目标收到了压缩包：FIT 归一化失败")
            }

            val id = payload.fileName.substringBeforeLast('.')
            log("演练：正在处理 $id")
            when {
                id.endsWith("5") -> UploadOutcome.Failed("演练：模拟的上传失败（可在失败后重试）")
                id.removePrefix("demo-").toIntOrNull()?.rem(3) == 0 -> UploadOutcome.Duplicate
                else -> UploadOutcome.Success
            }
        }
}
