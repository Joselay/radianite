package app.radianite.data.content

import kotlinx.serialization.Serializable

/** What a bundle can hold besides skins, with where valorant-api.com lists each. */
enum class CosmeticKind(val path: String) {
    BUDDY("buddies/levels"),
    SPRAY("sprays"),
    PLAYER_CARD("playercards"),
    TITLE("playertitles"),
}

/** A buddy, spray, player card or title as the app shows it. Titles have no art. */
data class Cosmetic(val name: String, val imageUrl: String?)

/** A bundle's [wideArtUrl] banner and [description], when valorant-api.com has more to say than its name. */
data class BundleInfo(val wideArtUrl: String?, val description: String?)

/** Any one of valorant-api.com's buddy level, spray, player card or title entries; each fills in its own fields. */
@Serializable
data class ApiCosmetic(
    val displayName: String? = null,
    val titleText: String? = null,
    val displayIcon: String? = null,
    val fullTransparentIcon: String? = null,
    val smallArt: String? = null,
)

fun ApiCosmetic.toCosmetic(kind: CosmeticKind) = Cosmetic(
    name = (if (kind == CosmeticKind.TITLE) titleText?.takeIf { it.isNotBlank() } else null) ?: displayName.orEmpty(),
    imageUrl = when (kind) {
        CosmeticKind.BUDDY -> displayIcon
        CosmeticKind.SPRAY -> fullTransparentIcon ?: displayIcon
        CosmeticKind.PLAYER_CARD -> smallArt ?: displayIcon
        CosmeticKind.TITLE -> null
    },
)

@Serializable
data class ApiBundleDetail(
    val displayName: String,
    val displayIcon: String? = null,
    val extraDescription: String? = null,
    val promoDescription: String? = null,
)

fun ApiBundleDetail.toBundleInfo() = BundleInfo(
    wideArtUrl = displayIcon,
    // valorant-api.com's plain description just repeats the name.
    description = listOfNotNull(promoDescription, extraDescription).map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.equals(displayName, ignoreCase = true) },
)
