package app.radianite.data.content

import kotlinx.serialization.Serializable

/** Compact lookup from skin-level UUID (what the store returns) to display data. */
@Serializable
data class Catalog(
    val version: String,
    val skinsByLevel: Map<String, CatalogSkin>,
    val tiers: Map<String, CatalogTier>,
    // No default: catalogs cached before bundles were indexed fail to decode and get rebuilt.
    val bundles: Map<String, CatalogBundle>,
)

@Serializable
data class CatalogSkin(
    // No default: catalogs cached before this field existed fail to decode and get rebuilt.
    val skinUuid: String,
    val name: String,
    val icon: String?,
    val tierUuid: String?,
    /** Lowercase UUID of the weapon the skin is for. No default, as above. */
    val weaponUuid: String,
)

/** [color] is RRGGBBAA hex, as published by valorant-api.com. [rank] orders rarities, Select lowest. */
@Serializable
data class CatalogTier(
    val name: String,
    val color: String,
    val icon: String?,
    val rank: Int,
)

@Serializable
data class CatalogBundle(
    val name: String,
    val art: String?,
)

@Serializable
data class ApiVersion(val riotClientVersion: String)

/** A weapon from valorant-api.com's weapons list, which carries its skins. */
@Serializable
data class ApiWeaponSkins(val uuid: String, val skins: List<ApiSkin> = emptyList())

@Serializable
data class ApiSkin(
    val uuid: String,
    val displayName: String,
    val displayIcon: String? = null,
    val contentTierUuid: String? = null,
    val levels: List<ApiSkinLevel> = emptyList(),
    val chromas: List<ApiChroma> = emptyList(),
)

@Serializable
data class ApiSkinLevel(val uuid: String, val displayIcon: String? = null)

@Serializable
data class ApiChroma(val fullRender: String? = null)

@Serializable
data class ApiContentTier(
    val uuid: String,
    val devName: String,
    val highlightColor: String,
    val displayIcon: String? = null,
    val rank: Int = 0,
)

@Serializable
data class ApiBundle(
    val uuid: String,
    val displayName: String,
    val displayIcon: String? = null,
    val verticalPromoImage: String? = null,
)

fun buildCatalog(
    version: String,
    weapons: List<ApiWeaponSkins>,
    tiers: List<ApiContentTier>,
    bundles: List<ApiBundle> = emptyList(),
): Catalog {
    val byLevel = HashMap<String, CatalogSkin>(weapons.sumOf { it.skins.size } * 2)
    for (weapon in weapons) for (skin in weapon.skins) {
        val icon = skin.levels.firstOrNull()?.displayIcon
            ?: skin.displayIcon
            ?: skin.chromas.firstOrNull()?.fullRender
        val entry = CatalogSkin(skin.uuid, skin.displayName, icon, skin.contentTierUuid, weapon.uuid.lowercase())
        for (level in skin.levels) byLevel[level.uuid.lowercase()] = entry
    }
    return Catalog(
        version = version,
        skinsByLevel = byLevel,
        tiers = tiers.associate { it.uuid.lowercase() to CatalogTier(it.devName, it.highlightColor, it.displayIcon, it.rank) },
        bundles = bundles.associate { it.uuid.lowercase() to CatalogBundle(it.displayName, it.verticalPromoImage ?: it.displayIcon) },
    )
}
