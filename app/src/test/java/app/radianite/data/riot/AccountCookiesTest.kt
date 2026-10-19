package app.radianite.data.riot

import app.radianite.data.LocalStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountCookiesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun cookies() = AccountCookies(LocalStore(tmp.root, Json))

    @Test
    fun `keeps the sign-in page's cookies across restarts`() = runTest {
        cookies().replace("ssid=abc; clid=uw1; tdid=t")

        assertEquals("ssid=abc; clid=uw1; tdid=t", cookies().header())
    }

    @Test
    fun `renewals replace rotated cookies and drop deleted ones`() = runTest {
        val cookies = cookies().apply { replace("ssid=old; clid=uw1; asid=a") }

        cookies.store(
            listOf(
                "ssid=new; Path=/; HttpOnly; Secure",
                "asid=; Max-Age=0",
                "sub=puuid; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
                "csid=c; Max-Age=3600",
            ),
        )

        assertEquals("ssid=new; clid=uw1; csid=c", cookies.header())
    }

    @Test
    fun `an account with no cookies has no header`() = runTest {
        val cookies = cookies().apply { replace("ssid=abc") }

        cookies.clear()

        assertNull(cookies.header())
        cookies.replace(null)
        assertNull(cookies.header())
    }
}
