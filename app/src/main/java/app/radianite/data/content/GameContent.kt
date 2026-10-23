package app.radianite.data.content

import kotlinx.serialization.Serializable

/** Names and art for the agents and maps the team comps guide refers to, indexed from valorant-api.com. */
@Serializable
data class GameContent(
    val version: String,
    val agents: Map<String, AgentInfo>,
    /** Keyed by lowercase map path, e.g. "/game/maps/ascent/ascent". */
    val maps: Map<String, MapInfo>,
) {
    fun agent(uuid: String?): AgentInfo? = uuid?.let { agents[it.lowercase()] }
    fun map(path: String?): MapInfo? = path?.let { maps[it.lowercase()] }

    /** The UUID of the agent the game calls [name], as the team comps guide names agents. */
    fun agentIdNamed(name: String): String? = agents.entries.find { it.value.name.equals(name, ignoreCase = true) }?.key

    /** The path of the map the game calls [name]. */
    fun mapPathNamed(name: String): String? = maps.entries.find { it.value.name.equals(name, ignoreCase = true) }?.key
}

@Serializable
data class AgentInfo(val name: String, val icon: String?, val role: String?)

@Serializable
data class MapInfo(val name: String, val splash: String?)

@Serializable
data class ApiAgent(
    val uuid: String,
    val displayName: String,
    val displayIcon: String? = null,
    val role: ApiAgentRole? = null,
)

@Serializable
data class ApiAgentRole(val displayName: String)

@Serializable
data class ApiMap(
    val uuid: String,
    val displayName: String,
    val mapUrl: String? = null,
    val splash: String? = null,
)

fun buildGameContent(version: String, agents: List<ApiAgent>, maps: List<ApiMap>) = GameContent(
    version = version,
    agents = agents.associate { it.uuid.lowercase() to AgentInfo(it.displayName, it.displayIcon, it.role?.displayName) },
    // Menus and test ranges have no map path; nothing refers to them.
    maps = maps.mapNotNull { map -> map.mapUrl?.let { it.lowercase() to MapInfo(map.displayName, map.splash) } }.toMap(),
)

/** Player card art follows a fixed CDN path, so it needs no lookup. */
object CardArt {
    fun wide(cardId: String?) = cardId?.takeIf { it.isNotBlank() }?.let { "https://media.valorant-api.com/playercards/$it/wideart.png" }
    fun small(cardId: String?) = cardId?.takeIf { it.isNotBlank() }?.let { "https://media.valorant-api.com/playercards/$it/smallart.png" }
}
