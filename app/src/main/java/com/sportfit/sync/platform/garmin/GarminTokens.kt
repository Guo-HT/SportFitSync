package com.sportfit.sync.platform.garmin

import kotlinx.serialization.Serializable

/**
 * Everything needed to talk to Garmin Connect without logging in again.
 *
 * Garmin has **no `refresh_token` grant**: when the OAuth2 access token
 * expires, the client re-runs the OAuth1 exchange with the long-lived OAuth1
 * token. Both stages are therefore persisted together.
 */
@Serializable
data class GarminTokens(
    /** `garmin.com` (international) or `garmin.cn` (China). */
    val domain: String,
    val oauth1Token: String,
    val oauth1Secret: String,
    val accessToken: String,
    val refreshToken: String = "",
    val tokenType: String = "Bearer",
    val scope: String? = null,
    /** Unix seconds, computed client-side from the exchange response. */
    val expiresAt: Long = 0,
    val refreshTokenExpiresAt: Long = 0,
    /** Present only when the login was completed through an MFA challenge. */
    val mfaToken: String? = null,
    val mfaExpiresAt: String? = null,
) {
    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        expiresAt <= nowSeconds

    /** `Bearer <token>`; the server sends `Bearer` but garth normalises case. */
    val authorizationValue: String
        get() = "${tokenType.replaceFirstChar { it.uppercaseChar() }} $accessToken"
}

/** Garmin's SSO domain layout, identical in shape for both regions. */
data class GarminDomains(val domain: String) {
    val sso: String get() = "https://sso.$domain/sso"
    val ssoEmbed: String get() = "$sso/embed"
    val connectApi: String get() = "https://connectapi.$domain"

    companion object {
        const val INTERNATIONAL = "garmin.com"
        const val CHINA = "garmin.cn"

        /** Maps the UI's `COM` / `CN` selector onto a domain. */
        fun fromRegion(region: String?): GarminDomains =
            GarminDomains(if (region.equals("CN", ignoreCase = true)) CHINA else INTERNATIONAL)
    }
}
