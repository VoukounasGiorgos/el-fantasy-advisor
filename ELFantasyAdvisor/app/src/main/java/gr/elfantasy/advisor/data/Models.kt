package gr.elfantasy.advisor.data

import kotlinx.serialization.Serializable

/** Θέσεις παικτών όπως τις γράφει το fantasy. */
val PLAYER_POSITIONS = listOf("Guard", "Forward", "Center")

/** Μία γραμμή από τον πίνακα παικτών του fantasy (μέσοι όροι σεζόν + τρέχουσα τιμή). */
@Serializable
data class PlayerRow(
    val id: Int,
    val name: String,
    val position: String,
    val team: String,
    val avgFpt: Double,
    val price: Double,
    val plus: Double
)

/** Ένας αγώνας από το match-log ενός παίκτη. */
@Serializable
data class GameLog(
    val md: Int,
    val opp: String,
    val home: Boolean,
    val price: Double? = null,
    val plus: Double? = null,
    val fpt: Double,
    val min: Double,
    val starter: Boolean,
    val pts: Int,
    val pir: Double
)

@Serializable
data class PlayerProfile(
    val id: Int,
    val fullName: String = "",
    val jersey: String? = null,
    val injured: Boolean = false,
    val popularity: Double = 0.0,
    val onFire: Boolean = false
)

@Serializable
data class Fixture(
    val home: String,
    val away: String,
    val startsAtMillis: Long
) {
    val key: String get() = "$home-$away-$startsAtMillis"
    fun opponentOf(team: String): String = if (home == team) away else home
}

@Serializable
data class GameState(
    val currentMatchday: Int,
    val currentInProgress: Boolean,
    val targetMatchday: Int
)

/** Όλα τα δεδομένα που κατεβάσαμε, όπως αποθηκεύονται στο κινητό. */
@Serializable
data class DataSnapshot(
    val syncedAt: Long,
    val state: GameState,
    val players: List<PlayerRow>,
    val logs: Map<Int, List<GameLog>>,
    val profiles: Map<Int, PlayerProfile>,
    val fixtures: List<Fixture>,
    val priceHash: Int,
    val profilesSyncedAt: Long
)

/** Μία πρόταση παίκτη. */
@Serializable
data class Rec(
    val playerId: Int,
    val name: String,
    val team: String,
    val position: String,
    val opp: String,
    val home: Boolean,
    val turn: Int,
    val price: Double,
    val xfp: Double,
    val valuePerCredit: Double,
    val risk: Int,
    val label: String,
    val expPlus: Double,
    val breakEven: Double? = null,
    val tier: String,
    val score: Double,
    val reasons: List<String>,
    val last5: List<Double>,
    val popularity: Double = 0.0
)

@Serializable
data class RecSet(
    val matchday: Int,
    val createdAt: Long,
    val deadlineMillis: Long? = null,
    val captains: List<Rec>,
    val recs: List<Rec>
)

@Serializable
data class PredictedItem(
    val playerId: Int,
    val name: String,
    val tier: String,
    val xfp: Double
)

@Serializable
data class HistoryEntry(
    val matchday: Int,
    val predictions: List<PredictedItem>
)
