package app.radianite.data.riot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RiotAuthTest {
    @Test
    fun `parses tokens from the opt-in redirect fragment`() {
        val url = "https://playvalorant.com/opt_in#access_token=eyJ.acc-ess_1&scope=openid+account" +
            "&iss=https%3A%2F%2Fauth.riotgames.com&id_token=eyJ.id.tok&token_type=Bearer" +
            "&session_state=abc&expires_in=3600"

        assertEquals(AuthTokens("eyJ.acc-ess_1", "eyJ.id.tok", 3600), RiotAuth.parseRedirect(url))
    }

    @Test
    fun `defaults expiry when Riot omits it`() {
        val tokens = RiotAuth.parseRedirect("https://playvalorant.com/opt_in#access_token=a&id_token=b")
        assertEquals(3600L, tokens?.expiresInSeconds)
    }

    @Test
    fun `returns null for non-redirect urls or missing tokens`() {
        assertNull(RiotAuth.parseRedirect("https://auth.riotgames.com/login#access_token=a&id_token=b"))
        assertNull(RiotAuth.parseRedirect("https://playvalorant.com/opt_in"))
        assertNull(RiotAuth.parseRedirect("https://playvalorant.com/opt_in#access_token=a"))
        assertNull(RiotAuth.parseRedirect("https://playvalorant.com/opt_in#error=access_denied"))
    }

    @Test
    fun `maps live affinity to pd shard`() {
        assertEquals("ap", RiotAuth.shardForAffinity("ap"))
        assertEquals("na", RiotAuth.shardForAffinity("latam"))
        assertEquals("na", RiotAuth.shardForAffinity("br"))
        assertEquals("eu", RiotAuth.shardForAffinity("EU"))
        assertEquals("kr", RiotAuth.shardForAffinity("kr"))
    }

    @Test
    fun `flags auth and maintenance errors`() {
        assert(RiotHttpException(400, "BAD_CLAIMS", "").isAuthError)
        assert(RiotHttpException(401, null, "").isAuthError)
        assert(RiotHttpException(503, null, "").isMaintenance)
        assert(!RiotHttpException(500, null, "").isAuthError)
    }
}
