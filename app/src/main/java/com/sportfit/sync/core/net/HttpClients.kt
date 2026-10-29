package com.sportfit.sync.core.net

import com.sportfit.sync.BuildConfig
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP clients.
 *
 * Two profiles are provided because the platforms behave very differently:
 * control-plane calls (login, paging) must fail fast, while FIT uploads and
 * downloads can legitimately take minutes on a slow mobile connection.
 */
object HttpClients {

    val cookieJar = InMemoryCookieJar()

    private fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
            redactHeader("accesstoken")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
        }

    private fun baseBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(loggingInterceptor())
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)

    /** Control plane: login, listings, metadata. */
    val api: OkHttpClient by lazy {
        baseBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** Data plane: FIT/ZIP transfers and OSS object uploads. */
    val transfer: OkHttpClient by lazy {
        baseBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    /** A client that must observe redirects itself (OAuth callbacks). */
    fun noRedirect(): OkHttpClient = baseBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * Coros' training-centre proxy, which mints the OSS upload credentials.
     *
     * It authenticates with a `CPL-coros-token` cookie that we set by hand,
     * and that requires a **cookie-less** client: OkHttp's `BridgeInterceptor`
     * replaces any hand-set `Cookie` header with the cookies the jar holds
     * for that host (`requestBuilder.header("Cookie", cookieHeader)`), so a
     * shared jar could silently drop the token and answer 401.
     */
    val corosSts: OkHttpClient by lazy {
        baseBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    val defaultUserAgent: String =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
}
