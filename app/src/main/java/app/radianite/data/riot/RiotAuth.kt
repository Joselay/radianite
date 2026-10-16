package app.radianite.data.riot

import java.net.URLDecoder

/** Tokens Riot hands back in the URL fragment after a successful sign-in. */
data class AuthTokens(
    val accessToken: String,
    val idToken: String,
    val expiresInSeconds: Long,
)

/**
 * The browser sign-in flow used by playvalorant.com. The user signs in on Riot's own page
 * (inside a WebView), and Riot redirects to [REDIRECT_URI] with the tokens in the fragment.
 * The app never sees the password.
 */
object RiotAuth {
    const val REDIRECT_URI = "https://playvalorant.com/opt_in"

    const val AUTHORIZE_URL = "https://auth.riotgames.com/authorize" +
        "?redirect_uri=https%3A%2F%2Fplayvalorant.com%2Fopt_in" +
        "&client_id=play-valorant-web-prod" +
        "&response_type=token%20id_token" +
        "&scope=account%20openid" +
        "&nonce=1"

    /** Cookies for this host hold the Riot session used for silent re-authorization. */
    const val COOKIE_URL = "https://auth.riotgames.com"

    fun isRedirect(url: String): Boolean = url.startsWith(REDIRECT_URI)

    /** Extracts tokens from a redirect URL, or null if the URL carries none. */
    fun parseRedirect(url: String): AuthTokens? {
        if (!isRedirect(url)) return null
        val fragment = url.substringAfter('#', missingDelimiterValue = "")
        if (fragment.isEmpty()) return null

        val params = fragment.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null else pair.substring(0, eq) to URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
        }.toMap()

        return AuthTokens(
            accessToken = params["access_token"]?.takeIf { it.isNotEmpty() } ?: return null,
            idToken = params["id_token"]?.takeIf { it.isNotEmpty() } ?: return null,
            expiresInSeconds = params["expires_in"]?.toLongOrNull() ?: 3600,
        )
    }

    /** Maps the PAS "live" affinity to the pd.{shard}.a.pvp.net shard. */
    fun shardForAffinity(affinity: String): String = when (val region = affinity.lowercase()) {
        "latam", "br" -> "na"
        else -> region
    }
}
