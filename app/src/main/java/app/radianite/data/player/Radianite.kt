package app.radianite.data.player

import app.radianite.data.content.Catalog
import app.radianite.data.riot.ItemUpgradesResponse
import app.radianite.data.store.RADIANITE_POINTS_UUID

/** A skin level or chroma bought with Radianite Points; [variantUuid] is the level's or the chroma's. */
data class RadianiteUpgrade(val variantUuid: String, val rp: Int)

/**
 * Every skin's Radianite upgrades by skin UUID, levels first, then chromas. A skin's first level and first
 * chroma come with it, so they're never here; nor are skins with nothing to unlock.
 */
fun ItemUpgradesResponse.radianiteUpgrades(catalog: Catalog): Map<String, List<RadianiteUpgrade>> =
    definitions.mapNotNull { definition ->
        val skin = catalog.skinsByLevel[definition.requiredEntitlement.itemId.lowercase()]?.skinUuid?.lowercase() ?: return@mapNotNull null
        val levels = if (definition.progression.currencyId.equals(RADIANITE_POINTS_UUID, ignoreCase = true)) {
            definition.progression.costPerLevel.orEmpty()
                .zip(definition.rewardSchedule.rewardsPerLevel.orEmpty())
                .mapNotNull { (rp, reward) -> reward.entitlements?.firstOrNull()?.let { RadianiteUpgrade(it.itemId.lowercase(), rp) } }
        } else {
            emptyList()
        }
        val chromas = definition.sidegrades.orEmpty().flatMap { it.options }.mapNotNull { option ->
            val chroma = option.rewards.firstOrNull() ?: return@mapNotNull null
            val rp = option.cost.walletCosts.filter { it.currencyId.equals(RADIANITE_POINTS_UUID, ignoreCase = true) }.sumOf { it.amount }
            RadianiteUpgrade(chroma.itemId.lowercase(), rp).takeIf { rp > 0 }
        }
        (levels + chromas).takeIf { it.isNotEmpty() }?.let { skin to it }
    }.toMap()

/** The Radianite Points still needed to unlock every upgrade whose level or chroma isn't in [unlocked]. */
fun List<RadianiteUpgrade>.remainingRp(unlocked: Set<String>): Int = filter { it.variantUuid !in unlocked }.sumOf { it.rp }

/**
 * One skin's Radianite prices by level or chroma UUID, and which of them the player has [unlocked]. Upgrades
 * can only be bought for a skin the player owns ([ownsSkin]).
 */
data class SkinUpgrades(val rpByVariant: Map<String, Int>, val unlocked: Set<String>, val ownsSkin: Boolean) {
    /** What's left to unlock; everything when the skin isn't owned. */
    val remainingRp: Int get() = rpByVariant.filterKeys { it !in unlocked }.values.sum()

    fun isUnlocked(variantUuid: String) = variantUuid.lowercase() in unlocked

    fun rp(variantUuid: String): Int? = rpByVariant[variantUuid.lowercase()]
}
