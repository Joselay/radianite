package app.radianite.data.content

import org.junit.Assert.assertEquals
import org.junit.Test

class GameContentTest {
    private val content = buildGameContent(
        version = "release-13.08",
        agents = listOf(ApiAgent("JETT-UUID", "Jett", "jett.png", role = ApiAgentRole("Duelist"))),
        maps = listOf(
            ApiMap("ascent-uuid", "Ascent", "/Game/Maps/Ascent/Ascent", "splash.png"),
            ApiMap("menu-uuid", "Menu", mapUrl = null),
        ),
    )

    @Test
    fun `indexes agents and maps, and finds them by name`() {
        assertEquals("Jett", content.agent("jett-uuid")?.name)
        assertEquals("Duelist", content.agent("JETT-UUID")?.role)
        assertEquals("Ascent", content.map("/game/maps/ascent/ascent")?.name)
        assertEquals(1, content.maps.size)
        assertEquals("jett-uuid", content.agentIdNamed("jett"))
        assertEquals("/game/maps/ascent/ascent", content.mapPathNamed("Ascent"))
    }
}
