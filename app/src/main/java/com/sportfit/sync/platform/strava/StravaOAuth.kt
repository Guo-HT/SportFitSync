package com.sportfit.sync.platform.strava

import com.sportfit.sync.core.net.AppJson
import com.sportfit.sync.core.net.HttpClients
import com.sportfit.sync.core.net.bodyTextOrThrow
import com.sportfit.sync.core.net.int
import com.sportfit.sync.core.net.obj
import com.sportfit.sync.core.net.str
import com.sportfit.sync.platform.PlatformException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request

/**
 * Strava's OAuth2 authorization-code flow.
 *
 * Strava has no public "username + password" grant: the user must register
 * their own API application and authorise it. We therefore drive a WebView to
 * the authorize page and intercept the redirect back to [REDIRECT_URI].
 *
 * The callback domain is deliberately `localhost`, which is what Strava's
 * "Authorization Callback Domain" field accepts for native apps. The page
 * itself will fail to load after the redirect — we read the `code` out of the
 * URL before that happens and never let the navigation complete.
 */
object StravaOAuth {

    const val REDIRECT_URI = "http://localhost/exchange_token"

    /** Scopes needed to both read history and upload new activities. */
    private const val SCOPE = "activity:read_all,activity:write"

    fun authorizeUrl(clientId: String): String = buildString {
        append("https://www.strava.com/oauth/authorize")
        append("?client_id=").append(clientId)
        append("&redirect_uri=").append(REDIRECT_URI)
        append("&response_type=code")
        append("&approval_prompt=auto")
        append("&scope=").append(SCOPE)
    }

    /** True when a WebView navigation is our OAuth callback. */
    fun isRedirect(url: String?): Boolean =
        url != null && url.startsWith(REDIRECT_URI)

    /** Extracts `code`, or throws with Strava's own `error` text. */
    fun extractCode(url: String): String {
        val params = url.substringAfter('?', "")
            .split('&')
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) null else pair.substring(0, index) to pair.substring(index + 1)
            }
            .toMap()

        params["error"]?.let { error ->
            throw PlatformException.Auth("Strava 授权被拒绝：$error")
        }
        return params["code"]?.takeIf { it.isNotBlank() }
            ?: throw PlatformException.Auth("Strava 未返回授权码")
    }

    suspend fun exchangeCode(
        clientId: String,
        clientSecret: String,
        code: String,
    ): StravaTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .build()

        val body = HttpClients.api.newCall(
            Request.Builder().url("https://www.strava.com/oauth/token").post(form).build(),
        ).execute().use { response ->
            val text = response.bodyTextOrThrow()
            if (!response.isSuccessful) {
                throw PlatformException.Auth("Strava 授权失败：HTTP ${response.code} $text")
            }
            text
        }

        val json = AppJson.parseToJsonElement(body)
        val accessToken = json.str("access_token")
            ?: throw PlatformException.Auth("Strava 未返回 access_token")

        val nowSeconds = System.currentTimeMillis() / 1000
        val expiresAt = json.str("expires_at")?.toLongOrNull()
            ?: (nowSeconds + (json.int("expires_in") ?: 21_600))

        StravaTokens(
            accessToken = accessToken,
            refreshToken = json.str("refresh_token").orEmpty(),
            expiresAt = expiresAt,
            // Only the athlete id is useful for display; the token response
            // nests it under "athlete". Absence is not an error.
            athleteId = json.obj("athlete")?.str("id"),
        )
    }
}
