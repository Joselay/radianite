package app.radianite.data.riot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Everything needed to call the player-data (pd) endpoints for one account. */
@Serializable
data class RiotSession(
    val accessToken: String,
    val idToken: String,
    val expiresAtMillis: Long,
    val entitlementsToken: String,
    val puuid: String,
    val gameName: String?,
    val tagLine: String?,
    val shard: String,
) {
    val riotId: String
        get() = if (gameName != null && tagLine != null) "$gameName#$tagLine" else "Signed in"
}

@Serializable
data class UserInfoResponse(
    val sub: String,
    val acct: Account? = null,
) {
    @Serializable
    data class Account(
        @SerialName("game_name") val gameName: String? = null,
        @SerialName("tag_line") val tagLine: String? = null,
    )
}

@Serializable
data class EntitlementsResponse(
    @SerialName("entitlements_token") val entitlementsToken: String,
)

@Serializable
data class PasResponse(
    val affinities: Map<String, String> = emptyMap(),
)

@Serializable
data class RiotErrorBody(
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    val message: String? = null,
)

@Serializable
data class StorefrontResponse(
    @SerialName("SkinsPanelLayout") val skinsPanel: SkinsPanelLayout? = null,
    @SerialName("FeaturedBundle") val featuredBundle: FeaturedBundleLayout? = null,
)

@Serializable
data class FeaturedBundleLayout(
    @SerialName("Bundles") val bundles: List<FeaturedBundleOffer> = emptyList(),
)

/** [dataAssetId] is the bundle's UUID on valorant-api.com. */
@Serializable
data class FeaturedBundleOffer(
    @SerialName("DataAssetID") val dataAssetId: String,
)

@Serializable
data class SkinsPanelLayout(
    @SerialName("SingleItemStoreOffers") val offers: List<StoreOffer> = emptyList(),
    @SerialName("SingleItemOffersRemainingDurationInSeconds") val remainingSeconds: Long = 0,
)

@Serializable
data class StoreOffer(
    @SerialName("OfferID") val offerId: String,
    @SerialName("Cost") val cost: Map<String, Int> = emptyMap(),
    @SerialName("Rewards") val rewards: List<StoreReward> = emptyList(),
)

@Serializable
data class StoreReward(
    @SerialName("ItemTypeID") val itemTypeId: String,
    @SerialName("ItemID") val itemId: String,
)

class RiotHttpException(
    val status: Int,
    val errorCode: String?,
    message: String,
) : Exception(message) {
    val isAuthError: Boolean
        get() = status == 401 || status == 403 || errorCode == "BAD_CLAIMS" || errorCode == "TOKEN_EXPIRED"

    val isMaintenance: Boolean
        get() = status == 503 || errorCode == "SCHEDULED_DOWNTIME"
}
