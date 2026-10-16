package app.radianite.data.content

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogTest {
    @Test
    fun `indexes every level and falls back to skin or chroma icons`() {
        val catalog = buildCatalog(
            "v1",
            skins = listOf(
                ApiSkin("skin-a", "A", displayIcon = "a.png", levels = listOf(ApiSkinLevel("L1"), ApiSkinLevel("L2"))),
                ApiSkin("skin-b", "B", levels = listOf(ApiSkinLevel("M1")), chromas = listOf(ApiChroma("b-render.png"))),
            ),
            tiers = emptyList(),
        )
        assertEquals(setOf("l1", "l2", "m1"), catalog.skinsByLevel.keys)
        assertEquals("a.png", catalog.skinsByLevel["l2"]?.icon)
        assertEquals("b-render.png", catalog.skinsByLevel["m1"]?.icon)
    }
}
