package app.radianite.data.comps

import app.radianite.data.LocalStore
import app.radianite.data.Slots
import app.radianite.data.optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * The one comp that wins most on each map in Radiant ranked games, as kept in `team_comps.json` and written by
 * `scripts/best_comps.py`. [updated] is an ISO date, so newer sorts greater.
 */
@Serializable
data class CompGuide(val updated: String, val rankFilter: String, val sourceName: String, val sourceUrl: String, val maps: List<MapGuide>)

/**
 * One map; [inPool] when ranked can put players on it this act. [period] is the act its numbers cover, e.g.
 * "Act V, Aug–Oct 2026"; [thisAct] when that's the current one, which early in an act has too few games to say.
 */
@Serializable
data class MapGuide(val map: String, val inPool: Boolean, val period: String, val thisAct: Boolean = false, val best: BestComp)

/** Five agents by name, as the game spells them, and how they did in the [matches] teams played them. */
@Serializable
data class BestComp(val agents: List<String>, val matches: Int, val winPercent: Double)

/**
 * The team comps guide: ships with the app, and updates from the app's GitHub repository whenever the file there
 * changes, so new data needs no release. The newest of the shipped, saved and downloaded copies wins.
 */
class TeamComps(
    private val http: OkHttpClient,
    private val json: Json,
    private val device: LocalStore,
    /** The copy that ships with the app. */
    private val bundled: () -> String,
    private val url: String = GUIDE_URL,
) {
    /** The best guide on the phone, without going online. */
    suspend fun local(): CompGuide = newest(device.read(Slots.teamComps), shipped())

    /** Checks GitHub for a newer guide, keeping it when it is; the best on the phone when GitHub can't be reached. */
    suspend fun refreshed(): CompGuide {
        val saved = device.read(Slots.teamComps)
        val downloaded = optional("Team comps") { download() }
        if (downloaded != null && (saved == null || downloaded.updated > saved.updated)) device.write(Slots.teamComps, downloaded)
        return newest(downloaded, saved, shipped())
    }

    private suspend fun shipped(): CompGuide = withContext(Dispatchers.IO) { json.decodeFromString(CompGuide.serializer(), bundled()) }

    private suspend fun download(): CompGuide = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from GitHub")
            json.decodeFromString(CompGuide.serializer(), response.body.string())
        }
    }

    private fun newest(vararg guides: CompGuide?): CompGuide = guides.filterNotNull().maxBy { it.updated }

    companion object {
        const val GUIDE_URL = "https://raw.githubusercontent.com/Joselay/radianite/main/app/src/main/assets/team_comps.json"
    }
}
