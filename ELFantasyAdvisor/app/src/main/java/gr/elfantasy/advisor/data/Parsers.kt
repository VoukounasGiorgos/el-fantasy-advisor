package gr.elfantasy.advisor.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Μετατροπή των απαντήσεων του Dunkest API σε μοντέλα.
 * Καθαρός Kotlin κώδικας (χωρίς Android/δίκτυο), ώστε να τεστάρεται εύκολα.
 */
object Parsers {

    data class Config(
        val currentMatchday: Int,
        val currentRoundStartedAt: Long?,
        val teams: List<String>
    )

    data class TablePage(val players: List<PlayerRow>, val lastPage: Int)

    // ---------- βοηθητικά ----------

    fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    fun JsonElement?.str(): String? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.content
    }
    fun JsonElement?.num(): Double? = str()?.let { parseNum(it) }

    /** "18.9", "+2.2", "-0.1", "80%", "-" → αριθμός ή null */
    fun parseNum(raw: String?): Double? {
        if (raw == null) return null
        val s = raw.trim().replace("%", "").replace(",", ".")
        if (s.isEmpty() || s == "-") return null
        return s.toDoubleOrNull()
    }

    /** "20:56" → 20.93 λεπτά */
    fun parseMinutes(raw: String?): Double {
        if (raw == null) return 0.0
        val s = raw.trim()
        if (s.isEmpty() || s == "-") return 0.0
        val parts = s.split(":")
        return if (parts.size == 2) {
            (parts[0].toDoubleOrNull() ?: 0.0) + (parts[1].toDoubleOrNull() ?: 0.0) / 60.0
        } else {
            s.toDoubleOrNull() ?: 0.0
        }
    }

    fun parseIso(raw: String?): Long? =
        try {
            if (raw == null) null else Instant.parse(raw).toEpochMilli()
        } catch (e: Exception) {
            null
        }

    // ---------- endpoints ----------

    /** GET /leagues/10/config */
    fun config(root: JsonElement): Config {
        val d = root.obj()?.get("data").obj() ?: error("Άκυρη απάντηση config")
        val cm = d["current_matchday"].obj()?.get("number").num()?.toInt() ?: 1
        val started = parseIso(d["current_round"].obj()?.get("started_at").str())
        val teams = d["teams"].arr()?.mapNotNull { it.obj()?.get("abbreviation").str() } ?: emptyList()
        return Config(cm, started, teams)
    }

    /** GET /competitions/49/stats/players/table */
    fun table(root: JsonElement): TablePage {
        val o = root.obj()
        val data = o?.get("data").obj()
        val cols = data?.get("columns").arr()?.map { it.str() ?: "" } ?: emptyList()
        fun idx(name: String) = cols.indexOf(name)
        val iName = idx("name")
        val iPos = idx("position")
        val iTeam = idx("team")
        val iFpt = idx("fpt")
        val iQuot = idx("quotation")
        val iPlus = idx("plus")

        val players = data?.get("players").arr()?.mapNotNull { p ->
            val po = p.obj() ?: return@mapNotNull null
            val id = po["id"].num()?.toInt() ?: return@mapNotNull null
            val row = po["row"].arr() ?: return@mapNotNull null
            fun cell(i: Int): String? = if (i in 0 until row.size) row[i].str() else null
            val price = parseNum(cell(iQuot)) ?: return@mapNotNull null
            PlayerRow(
                id = id,
                name = cell(iName) ?: "#$id",
                position = cell(iPos) ?: "",
                team = cell(iTeam) ?: "",
                avgFpt = parseNum(cell(iFpt)) ?: 0.0,
                price = price,
                plus = parseNum(cell(iPlus)) ?: 0.0
            )
        } ?: emptyList()

        val lastPage = o?.get("meta").obj()?.get("last_page").num()?.toInt() ?: 1
        return TablePage(players, lastPage)
    }

    /** GET /basketball/leagues/10/players/{id}/match-log/rendered */
    fun matchLog(root: JsonElement): List<GameLog> {
        val data = root.obj()?.get("data").obj() ?: return emptyList()
        val cols = data["columns"].arr()?.map { (it.str() ?: "").uppercase() } ?: return emptyList()
        val rows = data["rows"].arr() ?: return emptyList()
        fun idx(name: String) = cols.indexOf(name)

        return rows.mapNotNull { r ->
            val a = r.arr() ?: return@mapNotNull null
            fun c(name: String): String? {
                val k = idx(name)
                return if (k in 0 until a.size) a[k].str() else null
            }
            fun n(name: String): Double = parseNum(c(name)) ?: 0.0

            val md = parseNum(c("MD"))?.toInt() ?: return@mapNotNull null
            val vs = (c("VS") ?: "").trim()
            val home = !vs.startsWith("@")
            val opp = vs.removePrefix("@").trim().split(" ").last().trim()

            val pir = n("PTS") + n("REB") + n("AST") + n("STL") + n("BLK") + n("FD") -
                (n("FGA") - n("FGM")) - (n("FTA") - n("FTM")) - n("TOV") - n("BA") - n("PF")

            GameLog(
                md = md,
                opp = opp,
                home = home,
                price = parseNum(c("CR")),
                plus = parseNum(c("PLUS")),
                fpt = n("FPT"),
                min = parseMinutes(c("MIN")),
                starter = c("ST")?.trim().equals("Y", ignoreCase = true),
                pts = n("PTS").toInt(),
                pir = pir
            )
        }
    }

    /** GET /players/{id}/profile */
    fun profile(id: Int, root: JsonElement): PlayerProfile? {
        val d = root.obj()?.get("data").obj() ?: return null
        val first = d["first_name"].str() ?: ""
        val last = d["last_name"].str() ?: ""
        return PlayerProfile(
            id = id,
            fullName = "$first $last".trim(),
            jersey = d["jersey"].str(),
            injured = d["is_injured"].str() == "true",
            popularity = d["popularity"].num() ?: 0.0,
            onFire = d["is_on_fire"].str() == "true"
        )
    }

    /** GET /players/{id}/upcoming-matches */
    fun upcoming(root: JsonElement): List<Fixture> {
        val list = root.obj()?.get("data").arr() ?: return emptyList()
        return list.mapNotNull { m ->
            val o = m.obj() ?: return@mapNotNull null
            if (o["status"].str() == "played") return@mapNotNull null
            val h = o["home_team"].obj()?.get("abbreviation").str() ?: return@mapNotNull null
            val a = o["away_team"].obj()?.get("abbreviation").str() ?: return@mapNotNull null
            val t = parseIso(o["started_at"].str()) ?: return@mapNotNull null
            Fixture(h, a, t)
        }.sortedBy { it.startsAtMillis }
    }
}
