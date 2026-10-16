package app.radianite.data.store

import app.radianite.data.content.Catalog
import app.radianite.data.riot.StorefrontResponse
import kotlinx.serialization.Serializable

@Serializable
data class FeaturedBundle(val name: String, val artUrl: String)

/** The store's featured bundles, skipping any valorant-api.com doesn't have art for yet. */
fun StorefrontResponse.featuredBundles(catalog: Catalog): List<FeaturedBundle> =
    featuredBundle?.bundles.orEmpty()
        .mapNotNull { offer ->
            val bundle = catalog.bundles[offer.dataAssetId.lowercase()] ?: return@mapNotNull null
            FeaturedBundle(bundle.name, bundle.art ?: return@mapNotNull null)
        }
        .distinct()
