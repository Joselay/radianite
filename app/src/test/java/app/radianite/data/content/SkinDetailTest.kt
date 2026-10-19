package app.radianite.data.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkinDetailTest {
    @Test
    fun `labels level items readably`() {
        assertEquals("Base", levelItemLabel(null))
        assertEquals("Finisher", levelItemLabel("EEquippableSkinLevelItem::Finisher"))
        assertEquals("Kill Banner", levelItemLabel("EEquippableSkinLevelItem::KillBanner"))
        assertEquals("Sound Effects", levelItemLabel("EEquippableSkinLevelItem::SoundEffects"))
        assertEquals("VFX", levelItemLabel("EEquippableSkinLevelItem::VFX"))
        assertEquals("Heartbeat And Map Sensor", levelItemLabel("EEquippableSkinLevelItem::HeartbeatAndMapSensor"))
    }

    @Test
    fun `labels chromas by color`() {
        assertEquals("Default", chromaLabel("Prelude to Chaos Sheriff", 0))
        assertEquals("Green", chromaLabel("Prelude to Chaos Sheriff Level 4\n(Variant 1 Green)", 1))
        assertEquals("White", chromaLabel("Reaver Vandal Level 4\n(Variant 2 - White)", 2))
        assertEquals("Gold", chromaLabel("Some Skin\n(Gold)", 1))
        assertEquals("Other Name", chromaLabel("Some Skin\nOther Name", 1))
    }

    @Test
    fun `says which level unlocks a variant, when its name tells`() {
        assertEquals("Unlocks at Level 4", chromaUnlock("Elderflame Vandal Level 4\n(Variant 1 Red)", 1))
        assertEquals("Unlocks at Level 5", chromaUnlock("RGX 11z Pro Vandal Level 5\r\n(Variant 2 Blue)", 2))
        assertNull(chromaUnlock("Comet Odin\r\n(Variant 1 Red)", 1))
        assertNull(chromaUnlock("Elderflame Vandal", 0))
    }

    @Test
    fun `maps levels and chromas with videos`() {
        val detail = ApiSkinDetail(
            uuid = "s1",
            displayName = "Prelude to Chaos Sheriff",
            displayIcon = "icon.png",
            levels = listOf(
                ApiDetailLevel("l1", null, "l1.png", "base.mp4"),
                ApiDetailLevel("l4", "EEquippableSkinLevelItem::Finisher", null, "finisher.mp4"),
            ),
            chromas = listOf(
                ApiDetailChroma("c0", "Prelude to Chaos Sheriff", fullRender = "c0.png", swatch = "sw0.png"),
                ApiDetailChroma("c1", "Prelude to Chaos Sheriff Level 4\n(Variant 1 Green)", fullRender = "c1.png", streamedVideo = "green.mp4"),
            ),
        ).toSkinDetail()

        assertEquals(listOf("Level 1", "Level 2"), detail.levels.map { it.title })
        assertEquals("Finisher", detail.levels[1].subtitle)
        assertEquals("finisher.mp4", detail.levels[1].videoUrl)
        assertEquals("c0.png", detail.levels[1].imageUrl)
        assertEquals(listOf("Default", "Green"), detail.chromas.map { it.title })
        assertNull(detail.chromas[0].videoUrl)
        assertEquals("green.mp4", detail.chromas[1].videoUrl)
        assertEquals(listOf(null, "Unlocks at Level 4"), detail.chromas.map { it.subtitle })
    }
}
