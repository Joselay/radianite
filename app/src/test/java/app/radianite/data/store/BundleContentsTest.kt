package app.radianite.data.store

import app.radianite.data.Fixture
import app.radianite.data.Reply
import app.radianite.data.content.Cosmetic
import app.radianite.data.content.CosmeticKind
import app.radianite.data.riot.ItemTypes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundleContentsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bundle = StoreBundle(
        name = "Reaver 2.0",
        artUrl = "vertical.png",
        priceVp = 7100,
        basePriceVp = 9000,
        expiresAtMillis = 0,
        uuid = "bundle-1",
        items = listOf(
            BundleItem(ItemTypes.SKIN_LEVELS, "level-1", amount = 1, priceVp = 1420, basePriceVp = 1775),
            BundleItem(ItemTypes.BUDDY_LEVELS, "buddy-1", amount = 1, priceVp = 0, basePriceVp = 475),
            BundleItem(ItemTypes.TITLES, "title-1", amount = 1, priceVp = 0, basePriceVp = null),
            BundleItem("some-new-type", "thing-1", amount = 1, priceVp = 0, basePriceVp = null),
        ),
    )

    private fun Fixture.serveBundle() {
        servers.json(
            "valorant-api.com/v1/weapons",
            """{"data":[{"uuid":"vandal","skins":[{"uuid":"reaver","displayName":"Reaver Vandal","contentTierUuid":"premium",
               "levels":[{"uuid":"level-1","displayIcon":"reaver.png"},{"uuid":"level-2"}]}]}]}""",
        )
        servers.json("valorant-api.com/v1/contenttiers", """{"data":[{"uuid":"premium","devName":"Premium","highlightColor":"d1548d33"}]}""")
        servers.json(
            "valorant-api.com/v1/weapons/skins/reaver",
            """{"data":{"uuid":"reaver","displayName":"Reaver Vandal","levels":[{"uuid":"level-1"},{"uuid":"level-2"}],
               "chromas":[{"uuid":"c1","displayName":"Reaver Vandal","swatch":"red.png"},{"uuid":"c2","displayName":"Reaver Vandal Level 4\n(Variant 1 Black)","swatch":"black.png"}]}}""",
        )
        servers.json("valorant-api.com/v1/buddies/levels/buddy-1", """{"data":{"displayName":"Reaver Buddy","displayIcon":"buddy.png"}}""")
        servers.json("valorant-api.com/v1/playertitles/title-1", """{"data":{"displayName":"Reaver Title","titleText":"Reaver"}}""")
        servers.json("valorant-api.com/v1/bundles/bundle-1", """{"data":{"displayName":"Reaver 2.0","displayIcon":"wide.png","promoDescription":"Reaver 2.0"}}""")
    }

    @Test
    fun `names a bundle's skins with their variants, and what comes with them`() = runTest {
        val app = Fixture(tmp.root).apply { serveBundle() }

        val contents = app.content.bundleContents(bundle)

        assertEquals("wide.png", contents.heroUrl)
        assertNull("a description that only repeats the name isn't shown", contents.description)
        val skin = contents.skins.single()
        assertEquals("Reaver Vandal", skin.skin.name)
        assertEquals("Premium", skin.skin.tierName)
        assertEquals(1420, skin.skin.priceVp)
        assertEquals(1775, skin.basePriceVp)
        assertEquals(listOf("red.png", "black.png"), skin.detail?.chromas?.map { it.swatchUrl })
        assertEquals(2, skin.detail?.levels?.size)
        assertEquals(
            listOf(
                BundleExtra(CosmeticKind.BUDDY, Cosmetic("Reaver Buddy", "buddy.png"), amount = 1, priceVp = 0, basePriceVp = 475),
                BundleExtra(CosmeticKind.TITLE, Cosmetic("Reaver", null), amount = 1, priceVp = 0, basePriceVp = null),
            ),
            contents.extras,
        )
    }

    @Test
    fun `items valorant-api can't describe still show`() = runTest {
        val app = Fixture(tmp.root).apply { serveBundle() }
        app.servers.on("valorant-api.com/v1/weapons/skins/reaver") { Reply(500) }
        app.servers.on("valorant-api.com/v1/buddies/levels/buddy-1") { Reply(500) }
        app.servers.on("valorant-api.com/v1/bundles/bundle-1") { Reply(500) }

        val contents = app.content.bundleContents(bundle)

        assertEquals("the store's own art stands in", "vertical.png", contents.heroUrl)
        assertNull(contents.skins.single().detail)
        assertEquals(Cosmetic("", null), contents.extras.first().cosmetic)
    }
}
