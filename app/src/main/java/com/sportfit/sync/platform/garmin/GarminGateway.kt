package com.sportfit.sync.platform.garmin

import com.sportfit.sync.core.format.FitPayload
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.domain.UploadOutcome
import com.sportfit.sync.platform.PlatformContext
import com.sportfit.sync.platform.PlatformException
import com.sportfit.sync.platform.PlatformGateway
import com.sportfit.sync.platform.PlatformSource
import com.sportfit.sync.platform.PlatformTarget
import kotlinx.serialization.json.Json

/** Builds logged-in Garmin sources and targets. */
object GarminGateway : PlatformGateway {

    override suspend fun createSource(ctx: PlatformContext): PlatformSource =
        GarminSource(ctx.account.platform, connect(ctx))

    override suspend fun createTarget(ctx: PlatformContext): PlatformTarget =
        GarminTarget(ctx.account.platform, connect(ctx))

    private suspend fun connect(ctx: PlatformContext): GarminApi {
        // The site follows from the platform: Garmin was split into an
        // international and a China account, and the two do not share
        // credentials, tokens or endpoints.
        val domains = GarminDomains.fromPlatform(ctx.account.platform)
        val auth = GarminAuth(ctx.account.platform, ctx.mfa, ctx.log)
        val persist: suspend (GarminTokens) -> Unit = { tokens ->
            ctx.persistToken(Json.encodeToString(GarminTokens.serializer(), tokens))
        }

        val cached = ctx.account.token?.let { decode(it) }
        // A cached token is only reusable for the same site; switching
        // between the two regions must force a full re-login.
        if (cached != null && cached.domain == domains.domain && cached.accessToken.isNotEmpty()) {
            return GarminApi(cached, auth, persist, ctx.log)
        }

        val email = ctx.account.email ?: throw PlatformException.Auth("未填写 Garmin 账号")
        val password = ctx.account.password ?: throw PlatformException.Auth("未填写 Garmin 密码")
        val fresh = auth.login(email, password, domains)
        persist(fresh)
        return GarminApi(fresh, auth, persist, ctx.log)
    }

    private fun decode(blob: String): GarminTokens? =
        runCatching { Json.decodeFromString(GarminTokens.serializer(), blob) }.getOrNull()
}

internal class GarminSource(
    override val platform: Platform,
    private val api: GarminApi,
) : PlatformSource {

    override suspend fun listActivities(limit: Int): List<ActivityMeta> = api.listActivities(limit)

    override suspend fun download(activity: ActivityMeta): FitPayload = api.download(activity.id)
}

internal class GarminTarget(
    override val platform: Platform,
    private val api: GarminApi,
) : PlatformTarget {

    override suspend fun upload(payload: FitPayload, activityName: String?): UploadOutcome =
        api.upload(payload)
}
