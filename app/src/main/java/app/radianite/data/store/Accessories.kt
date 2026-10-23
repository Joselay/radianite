package app.radianite.data.store

import app.radianite.data.content.Content
import app.radianite.data.content.Cosmetic
import app.radianite.data.content.CosmeticKind
import app.radianite.data.optional
import app.radianite.data.riot.AccessoryStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

/** One buddy, spray, player card or title in the Accessory Store. [name] is blank and [imageUrl] null when valorant-api.com couldn't name it. */
@Serializable
data class AccessoryOffer(
    val kind: CosmeticKind,
    val itemId: String,
    val name: String,
    val imageUrl: String?,
    val amount: Int,
    val priceKc: Int?,
)

/** The accessories on offer, named by valorant-api.com; anything that isn't a buddy, spray, card or title is left out. */
suspend fun Content.accessoryOffers(accessories: AccessoryStore?): List<AccessoryOffer> = coroutineScope {
    accessories?.offers.orEmpty().mapNotNull { entry ->
        val reward = entry.offer.rewards.firstOrNull() ?: return@mapNotNull null
        val kind = cosmeticKind(reward.itemTypeId.lowercase()) ?: return@mapNotNull null
        val itemId = reward.itemId.lowercase()
        async {
            val cosmetic = optional("Accessory") { cosmetic(kind, itemId) } ?: Cosmetic(name = "", imageUrl = null)
            AccessoryOffer(kind, itemId, cosmetic.name, cosmetic.imageUrl, reward.quantity, entry.offer.cost[KINGDOM_CREDITS_UUID])
        }
    }.awaitAll()
}
