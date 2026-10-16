package app.radianite.data.riot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Response shapes for the pd/glz player endpoints, trimmed to the fields the app reads.
// Field names follow the community docs at valapidocs.techchrism.me.

@Serializable
data class WalletResponse(
    @SerialName("Balances") val balances: Map<String, Int> = emptyMap(),
)

@Serializable
data class MatchHistoryResponse(
    @SerialName("Total") val total: Int = 0,
    @SerialName("History") val history: List<MatchHistoryEntry> = emptyList(),
)

@Serializable
data class MatchHistoryEntry(
    @SerialName("MatchID") val matchId: String,
    @SerialName("GameStartTime") val gameStartTime: Long = 0,
    @SerialName("QueueID") val queueId: String = "",
)

@Serializable
data class MatchDetailsResponse(
    val matchInfo: MatchInfo,
    val players: List<MatchPlayer> = emptyList(),
    val teams: List<MatchTeam>? = null,
    val roundResults: List<RoundResult>? = null,
    val kills: List<MatchKill>? = null,
)

@Serializable
data class MatchInfo(
    val matchId: String,
    val mapId: String = "",
    val gameLengthMillis: Long? = null,
    val gameStartMillis: Long = 0,
    val queueID: String = "",
    val isRanked: Boolean = false,
    val seasonId: String = "",
    val completionState: String = "",
    val provisioningFlowID: String = "",
)

@Serializable
data class MatchPlayer(
    val subject: String,
    val gameName: String = "",
    val tagLine: String = "",
    val teamId: String = "",
    val partyId: String = "",
    val characterId: String = "",
    val stats: MatchPlayerStats? = null,
    val competitiveTier: Int = 0,
    val isObserver: Boolean = false,
    val playerCard: String = "",
    val accountLevel: Int = 0,
)

@Serializable
data class MatchPlayerStats(
    val score: Int = 0,
    val roundsPlayed: Int = 0,
    val kills: Int = 0,
    val deaths: Int = 0,
    val assists: Int = 0,
)

@Serializable
data class MatchTeam(
    val teamId: String,
    val won: Boolean = false,
    val roundsPlayed: Int = 0,
    val roundsWon: Int = 0,
    val numPoints: Int = 0,
)

@Serializable
data class RoundResult(
    val roundNum: Int,
    val roundResult: String = "",
    val roundResultCode: String = "",
    val winningTeam: String = "",
    val roundCeremony: String = "",
    val plantSite: String = "",
    val playerStats: List<RoundPlayerStats> = emptyList(),
)

@Serializable
data class RoundPlayerStats(
    val subject: String,
    val damage: List<RoundDamage> = emptyList(),
)

@Serializable
data class RoundDamage(
    val receiver: String = "",
    val damage: Int = 0,
    val legshots: Int = 0,
    val bodyshots: Int = 0,
    val headshots: Int = 0,
)

@Serializable
data class MatchKill(
    val round: Int = 0,
    val roundTime: Long = 0,
    val killer: String = "",
    val victim: String = "",
)

@Serializable
data class PlayerMmrResponse(
    @SerialName("QueueSkills") val queueSkills: Map<String, QueueSkill> = emptyMap(),
    @SerialName("LatestCompetitiveUpdate") val latestCompetitiveUpdate: CompetitiveUpdate? = null,
)

@Serializable
data class QueueSkill(
    @SerialName("CurrentSeasonGamesNeededForRating") val currentSeasonGamesNeededForRating: Int = 0,
    @SerialName("SeasonalInfoBySeasonID") val seasonalInfo: Map<String, SeasonalInfo>? = null,
)

@Serializable
data class SeasonalInfo(
    @SerialName("SeasonID") val seasonId: String,
    @SerialName("NumberOfWins") val wins: Int = 0,
    @SerialName("NumberOfWinsWithPlacements") val winsWithPlacements: Int = 0,
    @SerialName("NumberOfGames") val games: Int = 0,
    @SerialName("CompetitiveTier") val competitiveTier: Int = 0,
    @SerialName("RankedRating") val rankedRating: Int = 0,
    @SerialName("LeaderboardRank") val leaderboardRank: Int = 0,
    @SerialName("WinsByTier") val winsByTier: Map<String, Int>? = null,
)

@Serializable
data class CompetitiveUpdatesResponse(
    @SerialName("Matches") val matches: List<CompetitiveUpdate> = emptyList(),
)

@Serializable
data class CompetitiveUpdate(
    @SerialName("MatchID") val matchId: String,
    @SerialName("MapID") val mapId: String = "",
    @SerialName("SeasonID") val seasonId: String = "",
    @SerialName("MatchStartTime") val matchStartTime: Long = 0,
    @SerialName("TierAfterUpdate") val tierAfter: Int = 0,
    @SerialName("TierBeforeUpdate") val tierBefore: Int = 0,
    @SerialName("RankedRatingAfterUpdate") val rrAfter: Int = 0,
    @SerialName("RankedRatingBeforeUpdate") val rrBefore: Int = 0,
    @SerialName("RankedRatingEarned") val rrEarned: Int = 0,
    @SerialName("AFKPenalty") val afkPenalty: Int = 0,
)

@Serializable
data class AccountXpResponse(
    @SerialName("Progress") val progress: XpProgress = XpProgress(),
    @SerialName("NextTimeFirstWinAvailable") val nextTimeFirstWinAvailable: String? = null,
)

@Serializable
data class XpProgress(
    @SerialName("Level") val level: Int = 0,
    @SerialName("XP") val xp: Int = 0,
)

@Serializable
data class PlayerLoadoutResponse(
    @SerialName("Guns") val guns: List<LoadoutGun> = emptyList(),
    @SerialName("Identity") val identity: LoadoutIdentity? = null,
)

@Serializable
data class LoadoutGun(
    @SerialName("ID") val weaponId: String,
    @SerialName("SkinID") val skinId: String = "",
    @SerialName("SkinLevelID") val skinLevelId: String = "",
    @SerialName("ChromaID") val chromaId: String = "",
    @SerialName("CharmID") val charmId: String? = null,
)

@Serializable
data class LoadoutIdentity(
    @SerialName("PlayerCardID") val playerCardId: String = "",
    @SerialName("PlayerTitleID") val playerTitleId: String = "",
    @SerialName("AccountLevel") val accountLevel: Int = 0,
)

/** The typed endpoint returns a flat list; the untyped one groups by type. Accept either. */
@Serializable
data class OwnedItemsResponse(
    @SerialName("Entitlements") val entitlements: List<Entitlement> = emptyList(),
    @SerialName("EntitlementsByTypes") val byTypes: List<EntitlementGroup> = emptyList(),
) {
    val itemIds: Set<String>
        get() = (entitlements + byTypes.flatMap { it.entitlements }).mapTo(HashSet()) { it.itemId.lowercase() }
}

@Serializable
data class EntitlementGroup(
    @SerialName("Entitlements") val entitlements: List<Entitlement> = emptyList(),
)

@Serializable
data class Entitlement(
    @SerialName("ItemID") val itemId: String,
)

@Serializable
data class ContractsResponse(
    @SerialName("Contracts") val contracts: List<ContractProgress> = emptyList(),
)

@Serializable
data class ContractProgress(
    @SerialName("ContractDefinitionID") val contractDefinitionId: String,
    @SerialName("ProgressionLevelReached") val levelReached: Int = 0,
    @SerialName("ProgressionTowardsNextLevel") val progressTowardsNextLevel: Int = 0,
)

@Serializable
data class LeaderboardResponse(
    @SerialName("Players") val players: List<LeaderboardPlayer> = emptyList(),
    val totalPlayers: Int = 0,
)

@Serializable
data class LeaderboardPlayer(
    @SerialName("PlayerCardID") val playerCardId: String = "",
    @SerialName("IsAnonymized") val isAnonymized: Boolean = false,
    val puuid: String = "",
    val gameName: String = "",
    val tagLine: String = "",
    val leaderboardRank: Int = 0,
    val rankedRating: Int = 0,
    val numberOfWins: Int = 0,
    val competitiveTier: Int = 0,
)

/** pregame/v1/players and core-game/v1/players: which live match the player is in. */
@Serializable
data class LiveMatchPointer(
    @SerialName("MatchID") val matchId: String,
)

@Serializable
data class PregameMatchResponse(
    @SerialName("ID") val id: String,
    @SerialName("MapID") val mapId: String = "",
    @SerialName("QueueID") val queueId: String = "",
    @SerialName("PregameState") val pregameState: String = "",
    @SerialName("PhaseTimeRemainingNS") val phaseTimeRemainingNs: Long = 0,
    @SerialName("AllyTeam") val allyTeam: PregameTeam? = null,
)

@Serializable
data class PregameTeam(
    @SerialName("TeamID") val teamId: String = "",
    @SerialName("Players") val players: List<PregamePlayer> = emptyList(),
)

@Serializable
data class PregamePlayer(
    @SerialName("Subject") val subject: String,
    @SerialName("CharacterID") val characterId: String = "",
    @SerialName("CharacterSelectionState") val selectionState: String = "",
    @SerialName("PlayerIdentity") val identity: LivePlayerIdentity? = null,
)

@Serializable
data class CoreGameMatchResponse(
    @SerialName("MatchID") val matchId: String,
    @SerialName("MapID") val mapId: String = "",
    @SerialName("ModeID") val modeId: String = "",
    @SerialName("ProvisioningFlow") val provisioningFlow: String = "",
    @SerialName("Players") val players: List<CoreGamePlayer> = emptyList(),
)

@Serializable
data class CoreGamePlayer(
    @SerialName("Subject") val subject: String,
    @SerialName("TeamID") val teamId: String = "",
    @SerialName("CharacterID") val characterId: String = "",
    @SerialName("PlayerIdentity") val identity: LivePlayerIdentity? = null,
    @SerialName("IsCoach") val isCoach: Boolean = false,
)

@Serializable
data class LivePlayerIdentity(
    @SerialName("AccountLevel") val accountLevel: Int = 0,
    @SerialName("Incognito") val incognito: Boolean = false,
    @SerialName("HideAccountLevel") val hideAccountLevel: Boolean = false,
)

@Serializable
data class NameServiceEntry(
    @SerialName("Subject") val subject: String,
    @SerialName("GameName") val gameName: String = "",
    @SerialName("TagLine") val tagLine: String = "",
)
