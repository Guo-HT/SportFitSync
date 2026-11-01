package com.sportfit.sync.platform.outbase

/**
 * Outbase login helpers.
 *
 * Outbase offers no programmatic login: the web app posts the credentials to
 * its own gateway and the answer sets a `sessionId` cookie on the
 * `outbase.cn` domain. The app drives that page in a WebView and then reads
 * the cookie back out of the WebView's jar.
 */
object OutbaseAuth {

    /** The page that hosts Outbase's login form. */
    const val LOGIN_URL = "https://outbase.cn/zeusfit/official-website/login.html"

    /** The host the `sessionId` cookie belongs to. */
    const val COOKIE_HOST = "https://outbase.cn"

    /** Name of the cookie that carries the session. */
    const val SESSION_COOKIE = "sessionId"

    /**
     * Pulls the session id out of a `Cookie:` header.
     *
     * The header looks like `a=1; sessionId=abc; b=2`. The value is taken
     * verbatim — it is an opaque token, so there is nothing to normalise — but
     * surrounding whitespace and quotes are stripped, because some servers
     * quote cookie values.
     *
     * @return the session id, or null when the cookie is absent or empty.
     */
    fun extractSessionId(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        return cookieHeader.split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$SESSION_COOKIE=") }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() }
    }
}
