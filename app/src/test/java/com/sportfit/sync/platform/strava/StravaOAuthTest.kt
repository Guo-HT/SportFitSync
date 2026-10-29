package com.sportfit.sync.platform.strava

import com.sportfit.sync.platform.PlatformException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StravaOAuthTest {

    @Test
    fun `authorize url carries client id, callback and write scope`() {
        val url = StravaOAuth.authorizeUrl("12345")

        assertTrue(url.startsWith("https://www.strava.com/oauth/authorize?"))
        assertTrue(url.contains("client_id=12345"))
        assertTrue(url.contains("redirect_uri=${StravaOAuth.REDIRECT_URI}"))
        assertTrue(url.contains("response_type=code"))
        // Uploads need activity:write; history needs activity:read_all.
        assertTrue(url.contains("scope=activity:read_all,activity:write"))
    }

    @Test
    fun `only the localhost callback is treated as our redirect`() {
        assertTrue(StravaOAuth.isRedirect("${StravaOAuth.REDIRECT_URI}?code=abc&scope=read"))
        assertFalse(StravaOAuth.isRedirect("https://www.strava.com/oauth/authorize?x=1"))
        assertFalse(StravaOAuth.isRedirect(null))
    }

    @Test
    fun `extracts the authorization code from the callback`() {
        val code = StravaOAuth.extractCode(
            "${StravaOAuth.REDIRECT_URI}?state=&code=abc123def&scope=read,activity:write",
        )
        assertEquals("abc123def", code)
    }

    @Test
    fun `surfaces a denial instead of a missing code`() {
        val error = runCatching {
            StravaOAuth.extractCode("${StravaOAuth.REDIRECT_URI}?error=access_denied")
        }.exceptionOrNull()

        assertTrue(error is PlatformException.Auth)
        assertTrue(error!!.message!!.contains("access_denied"))
    }

    @Test
    fun `missing code is an authentication failure`() {
        val error = runCatching { StravaOAuth.extractCode(StravaOAuth.REDIRECT_URI) }
            .exceptionOrNull()
        assertTrue(error is PlatformException.Auth)
    }
}
