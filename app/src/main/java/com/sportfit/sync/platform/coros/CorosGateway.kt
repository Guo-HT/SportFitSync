package com.sportfit.sync.platform.coros

import com.sportfit.sync.core.format.FitPackaging
import com.sportfit.sync.core.net.Hashing
import com.sportfit.sync.core.oss.CorosStsProvider
import com.sportfit.sync.core.oss.OssUploaders
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.domain.UploadOutcome
import com.sportfit.sync.platform.PlatformContext
import com.sportfit.sync.platform.PlatformException
import com.sportfit.sync.platform.PlatformGateway
import com.sportfit.sync.platform.PlatformSource
import com.sportfit.sync.platform.PlatformTarget
import com.sportfit.sync.core.format.FitPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Builds logged-in Coros sources and targets. */
object CorosGateway : PlatformGateway {

    override suspend fun createSource(ctx: PlatformContext): PlatformSource =
        CorosSource(connect(ctx))

    override suspend fun createTarget(ctx: PlatformContext): PlatformTarget =
        CorosTarget(connect(ctx))

    private suspend fun connect(ctx: PlatformContext): CorosClient {
        val email = ctx.account.email ?: throw PlatformException.Auth("未填写 Coros 账号")
        val password = ctx.account.password ?: throw PlatformException.Auth("未填写 Coros 密码")
        val client = CorosClient(email, password, ctx.log)

        // Reuse the persisted session when present; fall back to a fresh login
        // if it was rejected. Coros tokens have no published lifetime, so the
        // 401 retry below is the only reliable invalidation signal.
        val cached = ctx.account.token?.let { decodeToken(it) }
        if (cached != null) {
            client.restore(cached)
            try {
                client.listActivities(limit = 1)
            } catch (unauthorized: CorosUnauthorized) {
                ctx.log("Coros 登录态已失效，重新登录…")
                val fresh = client.login()
                ctx.persistToken(Json.encodeToString(CorosToken.serializer(), fresh))
            }
        } else {
            val fresh = client.login()
            ctx.persistToken(Json.encodeToString(CorosToken.serializer(), fresh))
        }
        return client
    }

    private fun decodeToken(blob: String): CorosToken? =
        runCatching { Json.decodeFromString(CorosToken.serializer(), blob) }.getOrNull()
}

internal class CorosSource(private val client: CorosClient) : PlatformSource {

    override val platform: Platform = Platform.COROS

    override suspend fun listActivities(limit: Int): List<ActivityMeta> =
        client.listActivities(limit)

    override suspend fun download(activity: ActivityMeta): FitPayload =
        // sportType is mandatory for this endpoint and must match the activity.
        client.download(activity.id, sportType = activity.sportType)
}

/**
 * Uploads into Coros via its object-storage staging area.
 *
 * Coros has no direct file endpoint: the FIT must be wrapped in a ZIP, placed
 * at `fit_zip/{userId}/{md5}.zip` in the bucket matching the account's region,
 * and then registered through `activity/fit/import`.
 */
internal class CorosTarget(private val client: CorosClient) : PlatformTarget {

    override val platform: Platform = Platform.COROS

    /**
     * Coros 目标端已有的活动：同步前拿来做跨平台比对去重。
     *
     * 目标账号里可能早就有了这些活动（别的工具同步过、或本地账本被清过），
     * 再传一遍就是副本。
     */
    override suspend fun listActivities(limit: Int): List<ActivityMeta> =
        client.listActivities(limit)

    override suspend fun upload(payload: FitPayload, activityName: String?): UploadOutcome = withContext(Dispatchers.IO) {
        val userId = client.userId
            ?: return@withContext UploadOutcome.Failed("Coros 未登录，无法上传")
        // The OSS credentials come from the training-centre proxy, which
        // authorises with the account's login token.
        val accessToken = client.accessToken
            ?: return@withContext UploadOutcome.Failed("Coros 未登录，无法上传")

        // Coros only accepts ZIP archives, whatever the source handed us.
        val zip = FitPackaging.ensureZip(payload)
        val md5 = Hashing.md5Hex(zip.bytes)
        val objectKey = "fit_zip/$userId/$md5.zip"

        try {
            val credentials = CorosStsProvider.fetch(client.regionId, accessToken)
            val uploader = OssUploaders.forCredentials(credentials)
            uploader.upload(objectKey, zip.bytes)

            val imported = client.importFromOss(
                bucket = credentials.bucket,
                serviceName = credentials.service,
                ossObjectKey = objectKey,
                md5 = md5,
                fileName = zip.fileName,
                sizeBytes = zip.sizeBytes,
            )

            if (imported) {
                UploadOutcome.Success
            } else {
                // Coros reports a repeat import as a plain failure, so give the
                // engine a chance to treat it as an already-present activity.
                UploadOutcome.Failed("Coros 导入未确认（可能是重复活动）")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            UploadOutcome.Failed(error.message ?: "Coros 上传失败")
        }
    }
}
