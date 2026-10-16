package app.radianite.data.store

import app.radianite.data.content.Catalog
import app.radianite.data.riot.StorefrontResponse
import kotlinx.serialization.Serializable

const val VALORANT_POINTS_UUID = "85ad13f7-3d1b-5128-9eb2-7cd8ee0b5741"

@Serializable
data class DailyStore(
    val puuid: String,
    val skins: List<StoreSkin>,
    val expiresAtMillis: Long,
)

@Serializable
data class StoreSkin(
    val levelUuid: String,
    /** Null for stores cached by older builds; the next refresh fills it in. */
    val skinUuid: String? = null,
    val name: String,
    val iconUrl: String?,
    val priceVp: Int?,
    val tierName: String?,
    /** RRGGBBAA hex. */
    val tierColor: String?,
    val tierIconUrl: String?,
)

fun StorefrontResponse.toDailyStore(puuid: String, catalog: Catalog, nowMillis: Long): DailyStore {
    val panel = skinsPanel
    val skins = panel?.offers.orEmpty().map { offer ->
        val levelUuid = (offer.rewards.firstOrNull()?.itemId ?: offer.offerId).lowercase()
        val skin = catalog.skinsByLevel[levelUuid]
        val tier = skin?.tierUuid?.let { catalog.tiers[it.lowercase()] }
        StoreSkin(
            levelUuid = levelUuid,
            skinUuid = skin?.skinUuid,
            name = skin?.name ?: "Unknown skin",
            iconUrl = skin?.icon,
            priceVp = offer.cost[VALORANT_POINTS_UUID],
            tierName = tier?.name,
            tierColor = tier?.color,
            tierIconUrl = tier?.icon,
        )
    }
    return DailyStore(
        puuid = puuid,
        skins = skins,
        expiresAtMillis = nowMillis + (panel?.remainingSeconds ?: 0) * 1000,
    )
}
