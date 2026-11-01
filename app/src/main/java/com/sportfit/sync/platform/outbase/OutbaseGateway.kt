package com.sportfit.sync.platform.outbase

import com.sportfit.sync.core.format.FitPackaging
import com.sportfit.sync.core.format.FitPayload
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.domain.UploadOutcome
import com.sportfit.sync.platform.PlatformContext
import com.sportfit.sync.platform.PlatformException
import com.sportfit.sync.platform.PlatformGateway
import com.sportfit.sync.platform.PlatformSource
import com.sportfit.sync.platform.PlatformTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Outbase (outbase.cn) — target-only platform.
 *
 * Outbase is an aggregation service: activities are pushed *into* its
 * library, but the service does not expose a per-user activity list to
 * download from, so it can only ever be the target of a sync.
 *
 * The upload protocol lives in [OutbaseClient]; this class wires it into the
 * app's platform contract.
 */
object OutbaseGateway : PlatformGateway {

    /**
     * Outbase has no download side.
     *
     * The sync engine only calls this for a source platform, and Outbase is
     * never offered as one, so this is a guard rather than a real path.
     */
    override suspend fun createSource(ctx: PlatformContext): PlatformSource =
        throw PlatformException.Protocol("Outbase 仅支持作为同步目标，不能作为数据来源")

    override suspend fun createTarget(ctx: PlatformContext): PlatformTarget {
        val sessionId = ctx.account.token?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw PlatformException.Auth("尚未登录 Outbase，请先在「账号」里完成登录")

        val client = OutbaseClient(sessionId)

        // A dead session is reported as an auth failure upstream, which drops
        // the stored credential and sends the user back to the login screen.
        if (!client.probeSession()) {
            throw PlatformException.Auth("Outbase 登录态已失效，请重新登录")
        }
        ctx.log("已连接 Outbase")
        return OutbaseTarget(client)
    }
}

/**
 * Pushes activities into Outbase's library.
 *
 * Outbase decides duplication itself: registering an activity it already has
 * answers with a "already exists" code, which surfaces as
 * [UploadOutcome.Duplicate]. There is no need to list the target side.
 */
internal class OutbaseTarget(private val client: OutbaseClient) : PlatformTarget {

    override val platform: Platform = Platform.OUTBASE

    /**
     * Outbase cannot list the caller's library, so target-side de-duplication
     * is impossible; the sync engine falls back to the local ledger plus the
     * duplicate signal the server sends.
     */
    override suspend fun listActivities(limit: Int): List<ActivityMeta> = emptyList()

    override suspend fun upload(payload: FitPayload, activityName: String?): UploadOutcome =
        withContext(Dispatchers.IO) {
            // Outbase parses FIT files; a ZIP is rejected outright.
            val raw = FitPackaging.ensureRawFit(payload)
            if (raw.zipped) {
                return@withContext UploadOutcome.Failed("无法从压缩包中解析出 FIT 文件")
            }

            // Outbase stores the file under its own name; the activity title
            // is read out of the FIT itself.
            val fileName = if (raw.fileName.endsWith(".fit", ignoreCase = true)) {
                raw.fileName
            } else {
                "${raw.fileName.substringBeforeLast('.', raw.fileName)}.fit"
            }

            client.upload(raw.bytes, fileName)
        }
}
