package app.radianite.data.store

import app.radianite.data.content.ApiBundle
import app.radianite.data.content.buildCatalog
import app.radianite.data.riot.StorefrontResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FeaturedBundlesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val catalog = buildCatalog(
        version = "release-13.08",
        skins = emptyList(),
        tiers = emptyList(),
        bundles = listOf(
            ApiBundle("f67ab55a-4480-f305-9891-9bac1b17459f", "Popmonz", verticalPromoImage = "popmonz.png"),
            ApiBundle("82a19267-4739-d2d0-2a6a-5db2e69e3d89", "Champions 2026", verticalPromoImage = "champions.png"),
            ApiBundle("no-art", "No Art"),
        ),
    )

    @Test
    fun `maps featured bundles to names and art, skipping unknown or artless ones`() {
        val body = """
            {
              "FeaturedBundle": {
                "Bundle": {"ID": "x", "DataAssetID": "F67AB55A-4480-F305-9891-9BAC1B17459F"},
                "Bundles": [
                  {"ID": "offer-1", "DataAssetID": "F67AB55A-4480-F305-9891-9BAC1B17459F", "Items": []},
                  {"ID": "offer-2", "DataAssetID": "82a19267-4739-d2d0-2a6a-5db2e69e3d89", "Items": []},
                  {"ID": "offer-3", "DataAssetID": "not-in-catalog", "Items": []},
                  {"ID": "offer-4", "DataAssetID": "no-art", "Items": []}
                ],
                "BundleRemainingDurationInSeconds": 86400
              }
            }
        """.trimIndent()

        val featured = json.decodeFromString<StorefrontResponse>(body).featuredBundles(catalog)

        assertEquals(
            listOf(FeaturedBundle("Popmonz", "popmonz.png"), FeaturedBundle("Champions 2026", "champions.png")),
            featured,
        )
    }

    @Test
    fun `missing featured section yields no bundles`() {
        assertEquals(emptyList<FeaturedBundle>(), StorefrontResponse().featuredBundles(catalog))
    }
}
