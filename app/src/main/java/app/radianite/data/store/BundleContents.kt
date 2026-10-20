package app.radianite.data.store

import app.radianite.data.content.Content
import app.radianite.data.content.Cosmetic
import app.radianite.data.content.CosmeticKind
import app.radianite.data.content.SkinDetail
import app.radianite.data.optional
import app.radianite.data.riot.ItemTypes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** What a featured bundle holds, named, in Riot's order. [heroUrl] and [description] are null when valorant-api.com lacks them. */
data class BundleContents(
    val heroUrl: String?,
    val description: String?,
    val skins: List<BundleSkin>,
    val extras: List<BundleExtra>,
)

/** A skin in a bundle: [skin] carries its bundle price, [basePriceVp] its price on its own. [detail] has its upgrades and variants, when it loaded. */
data class BundleSkin(val skin: StoreSkin, val basePriceVp: Int?, val detail: SkinDetail?)

/** A buddy, spray, player card or title in a bundle. */
data class BundleExtra(
    val kind: CosmeticKind,
    val cosmetic: Cosmetic,
    val amount: Int,
    val priceVp: Int?,
    val basePriceVp: Int?,
)

/**
 * Names [bundle]'s items from the catalog and valorant-api.com. Only the catalog is needed: an item, a skin's
 * variants or the bundle's art that can't be loaded shows without them.
 */
suspend fun Content.bundleContents(bundle: StoreBundle): BundleContents = coroutineScope {
    val info = async { bundle.uuid?.let { optional("Bundle info") { bundleInfo(it) } } }
    val catalog = catalog()
    val skins = bundle.items.filter { it.itemTypeId == ItemTypes.SKIN_LEVELS }.map { item ->
        async {
            val skin = catalog.storeSkinOrUnknown(item.itemId, item.priceVp)
            BundleSkin(skin, item.basePriceVp, skin.skinUuid?.let { optional("Skin variants") { skinDetail(it) } })
        }
    }
    val extras = bundle.items.mapNotNull { item ->
        val kind = cosmeticKind(item.itemTypeId) ?: return@mapNotNull null
        async {
            val cosmetic = optional("Bundle item") { cosmetic(kind, item.itemId) } ?: Cosmetic(name = "", imageUrl = null)
            BundleExtra(kind, cosmetic, item.amount, item.priceVp, item.basePriceVp)
        }
    }
    val bundleInfo = info.await()
    BundleContents(
        heroUrl = bundleInfo?.wideArtUrl ?: bundle.artUrl,
        description = bundleInfo?.description,
        skins = skins.awaitAll(),
        extras = extras.awaitAll(),
    )
}

private fun cosmeticKind(itemTypeId: String) = when (itemTypeId) {
    ItemTypes.BUDDY_LEVELS -> CosmeticKind.BUDDY
    ItemTypes.SPRAYS -> CosmeticKind.SPRAY
    ItemTypes.PLAYER_CARDS -> CosmeticKind.PLAYER_CARD
    ItemTypes.TITLES -> CosmeticKind.TITLE
    else -> null
}
