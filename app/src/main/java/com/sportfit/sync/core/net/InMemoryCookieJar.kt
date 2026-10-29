package com.sportfit.sync.core.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide in-memory cookie store.
 *
 * Garmin's SSO handshake is cookie-driven (it walks several redirects across
 * `sso.garmin.com` before handing back a service ticket), so every request in
 * that flow must share one jar. Tokens themselves are persisted separately, so
 * nothing here needs to survive process death.
 */
class InMemoryCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val bucket = store.getOrPut(host) { mutableListOf() }
        cookies.forEach { incoming ->
            bucket.removeAll { it.name == incoming.name && it.path == incoming.path }
            if (incoming.expiresAt > System.currentTimeMillis()) bucket += incoming
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<Cookie>()
        val hosts = buildList {
            add(url.host)
            // Accept parent-domain cookies (".garmin.com" set on sso.garmin.com).
            val parts = url.host.split('.')
            for (i in 1 until parts.size) add(parts.subList(i, parts.size).joinToString("."))
        }
        hosts.forEach { host ->
            val bucket = store[host] ?: return@forEach
            bucket.removeAll { it.expiresAt <= now }
            result += bucket.filter { it.matches(url) }
        }
        return result
    }

    @Synchronized
    fun clear() = store.clear()
}
