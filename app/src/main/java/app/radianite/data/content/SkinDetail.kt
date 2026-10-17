package app.radianite.data.content

import kotlinx.serialization.Serializable

/** Upgrade levels (VFX, animation, finisher, ...) and color variants of one skin. */
data class SkinDetail(
    val uuid: String,
    val name: String,
    val levels: List<SkinVariant>,
    val chromas: List<SkinVariant>,
)

data class SkinVariant(
    val id: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val videoUrl: String?,
    val swatchUrl: String? = null,
)

@Serializable
data class ApiSkinDetail(
    val uuid: String,
    val displayName: String,
    val displayIcon: String? = null,
    val levels: List<ApiDetailLevel> = emptyList(),
    val chromas: List<ApiDetailChroma> = emptyList(),
)

@Serializable
data class ApiDetailLevel(
    val uuid: String,
    val levelItem: String? = null,
    val displayIcon: String? = null,
    val streamedVideo: String? = null,
)

@Serializable
data class ApiDetailChroma(
    val uuid: String,
    val displayName: String,
    val displayIcon: String? = null,
    val fullRender: String? = null,
    val swatch: String? = null,
    val streamedVideo: String? = null,
)

fun ApiSkinDetail.toSkinDetail(): SkinDetail {
    val baseImage = chromas.firstOrNull()?.fullRender ?: displayIcon
    return SkinDetail(
        uuid = uuid,
        name = displayName,
        levels = levels.mapIndexed { index, level ->
            SkinVariant(
                id = level.uuid,
                title = "Level ${index + 1}",
                subtitle = levelItemLabel(level.levelItem),
                imageUrl = level.displayIcon ?: baseImage,
                videoUrl = level.streamedVideo,
            )
        },
        chromas = chromas.mapIndexed { index, chroma ->
            SkinVariant(
                id = chroma.uuid,
                title = chromaLabel(chroma.displayName, index),
                subtitle = null,
                imageUrl = chroma.fullRender ?: chroma.displayIcon ?: baseImage,
                videoUrl = chroma.streamedVideo,
                swatchUrl = chroma.swatch,
            )
        },
    )
}

/** "EEquippableSkinLevelItem::KillBanner" -> "Kill Banner"; the base level has no item. */
fun levelItemLabel(levelItem: String?): String {
    val name = levelItem?.substringAfterLast("::")?.takeIf { it.isNotBlank() } ?: return "Base"
    return when (name) {
        "VFX" -> "VFX"
        else -> name.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
    }
}

/** "Reaver Vandal Level 4\n(Variant 1 Black)" -> "Black"; the first chroma is the default look. */
fun chromaLabel(displayName: String, index: Int): String {
    if (index == 0) return "Default"
    val inParens = Regex("""\(([^)]*)\)""").find(displayName)?.groupValues?.get(1) ?: return displayName.lines().last().trim()
    return inParens.replace(Regex("""^Variant\s*\d+\s*[-:]?\s*"""), "").trim().ifEmpty { inParens.trim() }
}
