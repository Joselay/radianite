package app.radianite.data.riot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Response shapes for the pd player endpoints, trimmed to the fields the app reads.
// Field names follow the community docs at valapidocs.techchrism.me.

@Serializable
data class WalletResponse(
    @SerialName("Balances") val balances: Map<String, Int> = emptyMap(),
)

@Serializable
data class PlayerLoadoutResponse(
    @SerialName("Identity") val identity: LoadoutIdentity? = null,
)

@Serializable
data class LoadoutIdentity(
    @SerialName("PlayerCardID") val playerCardId: String = "",
)
