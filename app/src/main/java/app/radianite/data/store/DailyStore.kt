package app.radianite.data.store

import app.radianite.data.content.Catalog
import app.radianite.data.riot.StoreOffer
import app.radianite.data.riot.StorefrontResponse
import app.radianite.data.riot.WalletResponse
import kotlinx.serialization.Serializable

const val VALORANT_POINTS_UUID = "85ad13f7-3d1b-5128-9eb2-7cd8ee0b5741"
const val RADIANITE_POINTS_UUID = "e59aa87c-4cbf-517a-5983-6e81511be9b7"
const val KINGDOM_CREDITS_UUID = "85ca954a-41f2-ce94-9b45-8ca3dd39a00d"

@Serializable
data class DailyStore(
    val puuid: String,
    val skins: List<StoreSkin>,
    val expiresAtMillis: Long,
    // Defaults let stores cached by older builds still load.
    val bundles: List<StoreBundle> = emptyList(),
    val nightMarket: List<NightMarketOffer> = emptyList(),
    val nightMarketExpiresAtMillis: Long? = null,
)

@Serializable
data class StoreBundle(
    val name: String,
    val artUrl: String?,
    val priceVp: Int?,
    /** Price before the bundle discount; equal to [priceVp] when there is none. */
    val basePriceVp: Int?,
    val expiresAtMillis: Long,
)

/** [skin] carries the discounted price. */
@Serializable
data class NightMarketOffer(
    val skin: StoreSkin,
    val basePriceVp: Int?,
    val discountPercent: Int,
)

data class Wallet(val valorantPoints: Int, val radianite: Int, val kingdomCredits: Int)

fun WalletResponse.toWallet() = Wallet(
    valorantPoints = balances[VALORANT_POINTS_UUID] ?: 0,
    radianite = balances[RADIANITE_POINTS_UUID] ?: 0,
    kingdomCredits = balances[KINGDOM_CREDITS_UUID] ?: 0,
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
    return DailyStore(
        puuid = puuid,
        skins = panel?.offers.orEmpty().map { it.toStoreSkin(catalog, it.cost[VALORANT_POINTS_UUID]) },
        expiresAtMillis = nowMillis + (panel?.remainingSeconds ?: 0) * 1000,
        bundles = featuredBundle?.bundles.orEmpty().map { offer ->
            val bundle = catalog.bundles[offer.dataAssetId.lowercase()]
            val price = offer.totalDiscountedCost?.get(VALORANT_POINTS_UUID)
            StoreBundle(
                name = bundle?.name ?: "Featured bundle",
                artUrl = bundle?.art,
                priceVp = price,
                basePriceVp = offer.totalBaseCost?.get(VALORANT_POINTS_UUID) ?: price,
                expiresAtMillis = nowMillis + offer.remainingSeconds * 1000,
            )
        },
        nightMarket = bonusStore?.offers.orEmpty().map { bonus ->
            NightMarketOffer(
                skin = bonus.offer.toStoreSkin(catalog, bonus.discountCosts[VALORANT_POINTS_UUID]),
                basePriceVp = bonus.offer.cost[VALORANT_POINTS_UUID],
                discountPercent = bonus.discountPercent,
            )
        },
        nightMarketExpiresAtMillis = bonusStore?.let { nowMillis + it.remainingSeconds * 1000 },
    )
}

private fun StoreOffer.toStoreSkin(catalog: Catalog, priceVp: Int?): StoreSkin {
    val levelUuid = (rewards.firstOrNull()?.itemId ?: offerId).lowercase()
    val skin = catalog.skinsByLevel[levelUuid]
    val tier = skin?.tierUuid?.let { catalog.tiers[it.lowercase()] }
    return StoreSkin(
        levelUuid = levelUuid,
        skinUuid = skin?.skinUuid,
        name = skin?.name ?: "Unknown skin",
        iconUrl = skin?.icon,
        priceVp = priceVp,
        tierName = tier?.name,
        tierColor = tier?.color,
        tierIconUrl = tier?.icon,
    )
}
