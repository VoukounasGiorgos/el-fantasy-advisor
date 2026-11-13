package gr.elfantasy.advisor.engine

import gr.elfantasy.advisor.data.DataSnapshot
import gr.elfantasy.advisor.data.Fixture
import gr.elfantasy.advisor.data.GameLog
import gr.elfantasy.advisor.data.PLAYER_POSITIONS
import gr.elfantasy.advisor.data.PlayerRow
import gr.elfantasy.advisor.data.Rec
import gr.elfantasy.advisor.data.RecSet
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Το μοντέλο προτάσεων.
 *
 * Score 1 – xFP (αναμενόμενοι fantasy πόντοι):
 *   PIR/λεπτό (φόρμα + σεζόν) × αναμενόμενα λεπτά (+ λεπτά τραυματιών συμπαικτών)
 *   × συντελεστής άμυνας αντιπάλου στη θέση × συντελεστής έδρας × (1 + 10% × P(νίκη))
 * Score 2 – Αξία: xFP ανά credit και αναμενόμενη μεταβολή τιμής (γραμμική παλινδρόμηση στα match-logs)
 * Score 3 – Ρίσκο 0–100: διακύμανση PIR, σταθερότητα λεπτών, χαμένο τελευταίο ματς, μικρό δείγμα
 */
object Engine {

    const val PREMIUM_MIN = 14.0
    const val BUDGET_MAX = 9.0

    private const val ELO_K = 20.0
    private const val ELO_HOME = 60.0

    /** Πόσους παίκτες προτείνουμε ανά θέση: ακριβοί / μεσαίοι / φθηνοί */
    val TIER_COUNTS = mapOf(
        "Guard" to listOf(2, 2, 2),
        "Forward" to listOf(2, 2, 2),
        "Center" to listOf(1, 1, 2)
    )
    val TIERS = listOf("premium", "mid", "budget")

    data class PriceModel(val b0: Double, val bFpt: Double, val bPrice: Double) {
        fun expected(fpt: Double, price: Double) = b0 + bFpt * fpt + bPrice * price
        fun breakEven(price: Double): Double? =
            if (bFpt > 0.005) -(b0 + bPrice * price) / bFpt else null
    }

    data class TeamGame(val md: Int, val home: String, val away: String, val homeWon: Boolean, val margin: Int)

    // ------------------------------------------------------------------

    fun build(s: DataSnapshot, now: Long, zone: ZoneId = ZoneId.of("Europe/Athens")): RecSet {
        val players = s.players.filter { it.position in PLAYER_POSITIONS }
        val byId = players.associateBy { it.id }
        val played: Map<Int, List<GameLog>> = players.associate { p ->
            p.id to (s.logs[p.id] ?: emptyList()).filter { it.min > 0.0 }.sortedBy { it.md }
        }

        // ---- αποτελέσματα & Elo ----
        val games = teamGames(players, s.logs)
        val elo = elo(games)

        // ---- πότε έπαιξε τελευταία κάθε ομάδα ----
        val lastTeamMd = HashMap<String, Int>()
        for (p in players) for (g in played[p.id].orEmpty()) {
            lastTeamMd[p.team] = max(lastTeamMd[p.team] ?: 0, g.md)
        }

        // ---- στατιστικά παίκτη ----
        val stats = players.associate { p -> p.id to playerStats(played[p.id].orEmpty()) }

        // ---- άμυνα αντιπάλου ανά θέση ----
        val dvp = defenseVsPosition(players, played, stats)

        // ---- πρόγραμμα ----
        val fixtureByTeam = HashMap<String, Fixture>()
        for (f in s.fixtures) {
            fixtureByTeam[f.home] = f
            fixtureByTeam[f.away] = f
        }
        val dates = s.fixtures.map { Instant.ofEpochMilli(it.startsAtMillis).atZone(zone).toLocalDate() }
            .distinct().sorted()
        fun turnOf(f: Fixture): Int =
            dates.indexOf(Instant.ofEpochMilli(f.startsAtMillis).atZone(zone).toLocalDate()) + 1
        val deadline = s.fixtures.minOfOrNull { it.startsAtMillis }

        // ---- τραυματισμοί → επιπλέον λεπτά συμπαικτών ----
        val injured = players.filter { s.profiles[it.id]?.injured == true }.map { it.id }.toSet()
        val boosts = injuryBoosts(players, played, stats, injured, lastTeamMd)

        // ---- μοντέλο τιμής ----
        val priceModel = fitPriceModel(s.logs.values.flatten())

        // ---- υπολογισμός για κάθε παίκτη ----
        val candidates = mutableListOf<Rec>()
        for (p in players) {
            val g = played[p.id].orEmpty()
            val st = stats[p.id] ?: continue
            if (g.size < 3) continue
            if (p.id in injured) continue
            val fx = fixtureByTeam[p.team] ?: continue
            val home = fx.home == p.team
            val opp = fx.opponentOf(p.team)

            val boost = boosts[p.id]
            val expMin = min(34.0, st.expMin + (boost?.minutes ?: 0.0))
            val oppF = dvp[opp]?.get(p.position) ?: 1.0
            val homeF = if (home) st.homeFactor else st.awayFactor
            val pWin = winProbability(elo, p.team, opp, home)

            val xPir = max(0.0, st.pirPerMin * expMin * oppF * homeF)
            val xfp = xPir * (1.0 + 0.10 * pWin)

            val missedLast = (g.lastOrNull()?.md ?: 0) < (lastTeamMd[p.team] ?: 0)
            val risk = riskScore(g, missedLast)
            val label = when {
                risk < 30 -> "Σίγουρος"
                risk < 50 -> "Ισορροπημένος"
                else -> "Ρίσκο / Upside"
            }
            val expPlus = priceModel.expected(xfp, p.price)
            val breakEven = priceModel.breakEven(p.price)

            val tier = when {
                p.price >= PREMIUM_MIN -> "premium"
                p.price < BUDGET_MAX -> "budget"
                else -> "mid"
            }
            val minMinutes = if (tier == "budget") 15.0 else 12.0
            if (expMin < minMinutes) continue

            val value = xfp / max(p.price, 1.0)
            val score = when (tier) {
                "premium" -> xfp
                "mid" -> xfp * (1.0 - risk / 200.0)
                else -> value * 10.0 * (1.0 - risk / 200.0) + expPlus * 3.0
            }

            val reasons = mutableListOf<String>()
            val last5 = g.takeLast(5).map { it.fpt }
            reasons += "Φόρμα: τελ. 5 = ${fmt(last5.average())} FPT (σεζόν ${fmt(g.map { it.fpt }.average())})"
            if (abs(oppF - 1.0) >= 0.04) {
                reasons += "$opp: ${pct(oppF)} σε ${posGr(p.position)} σε σχέση με τον μέσο όρο τους"
            }
            if (abs(homeF - 1.0) >= 0.04) {
                reasons += (if (home) "Εντός" else "Εκτός") + " έδρας: ${pct(homeF)} για αυτόν"
            } else {
                reasons += if (home) "Εντός έδρας" else "Εκτός έδρας"
            }
            reasons += "Πιθανότητα νίκης ${(pWin * 100).roundToInt()}% (bonus +10% αν κερδίσει)"
            boost?.let { reasons += it.reason }
            if (missedLast) reasons += "Δεν έπαιξε στο τελευταίο ματς της ομάδας του"
            reasons += "Αναμενόμενα λεπτά ≈ ${expMin.roundToInt()}'"
            breakEven?.let { reasons += "Θέλει ≈ ${fmt(it)} FPT για να μη χάσει τιμή" }
            if (s.profiles[p.id]?.onFire == true) reasons += "On fire στο fantasy"

            candidates += Rec(
                playerId = p.id,
                name = s.profiles[p.id]?.fullName?.takeIf { it.isNotBlank() } ?: p.name,
                team = p.team,
                position = p.position,
                opp = opp,
                home = home,
                turn = turnOf(fx),
                price = p.price,
                xfp = round1(xfp),
                valuePerCredit = round2(value),
                risk = risk,
                label = label,
                expPlus = round1(expPlus),
                breakEven = breakEven?.let { round1(it) },
                tier = tier,
                score = score,
                reasons = reasons,
                last5 = last5,
                popularity = s.profiles[p.id]?.popularity ?: 0.0
            )
        }

        // ---- επιλογή ανά θέση/κατηγορία ----
        val recs = mutableListOf<Rec>()
        for (pos in PLAYER_POSITIONS) {
            val counts = TIER_COUNTS[pos] ?: listOf(2, 2, 2)
            TIERS.forEachIndexed { i, tier ->
                recs += candidates.filter { it.position == pos && it.tier == tier }
                    .sortedByDescending { it.score }
                    .take(counts[i])
            }
        }

        // ---- αρχηγός: υψηλότερο xFP, προτίμηση σε όσους παίζουν στο Turn 1 ----
        val byXfp = candidates.sortedByDescending { it.xfp }
        val captains = (byXfp.filter { it.turn == 1 }.take(3) + byXfp).distinctBy { it.playerId }.take(3)

        return RecSet(
            matchday = s.state.targetMatchday,
            createdAt = now,
            deadlineMillis = deadline,
            captains = captains,
            recs = recs
        )
    }

    // ------------------------------------------------------------------
    // Στατιστικά παίκτη

    data class PlayerStats(
        val avgPir: Double,
        val pirPerMin: Double,
        val expMin: Double,
        val homeFactor: Double,
        val awayFactor: Double
    )

    fun playerStats(g: List<GameLog>): PlayerStats {
        if (g.isEmpty()) return PlayerStats(0.0, 0.0, 0.0, 1.0, 1.0)
        val avgPir = g.map { it.pir }.average()
        val recent = g.takeLast(6)
        val fpmRecent = recent.sumOf { it.pir } / max(recent.sumOf { it.min }, 1.0)
        val fpmSeason = g.sumOf { it.pir } / max(g.sumOf { it.min }, 1.0)
        val fpm = 0.6 * fpmRecent + 0.4 * fpmSeason
        val expMin = ewma(g.takeLast(5).map { it.min }, 0.4)

        fun split(list: List<GameLog>, k: Double): Double {
            if (list.isEmpty() || avgPir <= 2.0) return 1.0
            val ratio = list.map { it.pir }.average() / avgPir
            val n = list.size.toDouble()
            return (1.0 + (ratio - 1.0) * n / (n + k)).coerceIn(0.85, 1.15)
        }
        return PlayerStats(
            avgPir = avgPir,
            pirPerMin = fpm,
            expMin = expMin,
            homeFactor = split(g.filter { it.home }, 5.0),
            awayFactor = split(g.filter { !it.home }, 5.0)
        )
    }

    fun ewma(values: List<Double>, alpha: Double): Double {
        if (values.isEmpty()) return 0.0
        var e = values.first()
        for (v in values.drop(1)) e = alpha * v + (1 - alpha) * e
        return e
    }

    fun riskScore(g: List<GameLog>, missedLast: Boolean): Int {
        val last8 = g.takeLast(8).map { it.pir }
        val m8 = last8.average()
        val cv = sd(last8) / max(m8, 5.0)
        val r1 = min(cv, 1.0) * 40.0
        val mins = g.takeLast(5).map { it.min }
        val r2 = min(sd(mins) / max(mins.average(), 10.0), 1.0) * 30.0
        val r3 = if (missedLast) 20.0 else 0.0
        val r4 = if (g.size < 5) 10.0 else 0.0
        return (r1 + r2 + r3 + r4).roundToInt().coerceIn(0, 100)
    }

    fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }

    // ------------------------------------------------------------------
    // Άμυνα αντιπάλου ανά θέση (PIR που "δίνει" κάθε ομάδα σε σχέση με τον μέσο όρο των παικτών)

    fun defenseVsPosition(
        players: List<PlayerRow>,
        played: Map<Int, List<GameLog>>,
        stats: Map<Int, PlayerStats>
    ): Map<String, Map<String, Double>> {
        val act = HashMap<String, HashMap<String, Double>>()
        val exp = HashMap<String, HashMap<String, Double>>()
        val cnt = HashMap<String, HashMap<String, Int>>()
        for (p in players) {
            val g = played[p.id].orEmpty()
            val st = stats[p.id] ?: continue
            if (g.size < 3 || st.avgPir < 3.0) continue
            for (x in g) {
                act.getOrPut(x.opp) { HashMap() }.merge(p.position, x.pir, Double::plus)
                exp.getOrPut(x.opp) { HashMap() }.merge(p.position, st.avgPir, Double::plus)
                cnt.getOrPut(x.opp) { HashMap() }.merge(p.position, 1, Int::plus)
            }
        }
        val out = HashMap<String, Map<String, Double>>()
        for ((team, m) in act) {
            val f = HashMap<String, Double>()
            for ((pos, a) in m) {
                val e = exp[team]?.get(pos) ?: continue
                val n = (cnt[team]?.get(pos) ?: 0).toDouble()
                if (e <= 0.0) continue
                val raw = a / e
                f[pos] = (1.0 + (raw - 1.0) * n / (n + 20.0)).coerceIn(0.8, 1.25)
            }
            out[team] = f
        }
        return out
    }

    // ------------------------------------------------------------------
    // Τραυματισμοί: τα λεπτά όσων λείπουν μοιράζονται στους υπόλοιπους της ίδιας θέσης

    data class Boost(val minutes: Double, val reason: String)

    fun injuryBoosts(
        players: List<PlayerRow>,
        played: Map<Int, List<GameLog>>,
        stats: Map<Int, PlayerStats>,
        injured: Set<Int>,
        lastTeamMd: Map<String, Int>
    ): Map<Int, Boost> {
        val out = HashMap<Int, Boost>()
        val groups = players.groupBy { it.team to it.position }
        for ((key, group) in groups) {
            val teamLast = lastTeamMd[key.first] ?: continue
            // μετράνε μόνο τραυματίες που έπαιζαν πρόσφατα (αλλιώς τα λεπτά τους έχουν ήδη μοιραστεί)
            val out0 = group.filter { it.id in injured && (played[it.id]?.lastOrNull()?.md ?: -99) >= teamLast - 2 }
            if (out0.isEmpty()) continue
            val lostMin = out0.sumOf { stats[it.id]?.expMin ?: 0.0 } * 0.7
            val healthy = group.filter { it.id !in injured && (played[it.id]?.isNotEmpty() == true) }
            val totalHealthyMin = healthy.sumOf { stats[it.id]?.expMin ?: 0.0 }
            if (totalHealthyMin <= 0.0) continue
            val names = out0.joinToString(", ") { it.name }
            for (h in healthy) {
                val share = (stats[h.id]?.expMin ?: 0.0) / totalHealthyMin
                val add = min(8.0, lostMin * share)
                if (add >= 1.0) {
                    out[h.id] = Boost(add, "Λείπει: $names → +${add.roundToInt()}' λεπτά (εκτίμηση)")
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Αποτελέσματα αγώνων από τα match-logs & Elo

    fun teamGames(players: List<PlayerRow>, logs: Map<Int, List<GameLog>>): List<TeamGame> {
        val pts = HashMap<Pair<Int, String>, Int>()
        val votes = HashMap<Pair<Int, String>, Int>()
        val meta = HashMap<Pair<Int, String>, Pair<String, Boolean>>()
        for (p in players) for (g in logs[p.id].orEmpty()) {
            val k = g.md to p.team
            pts.merge(k, g.pts, Int::plus)
            meta[k] = g.opp to g.home
            // fantasy = PIR × 1.1 όταν η ομάδα κερδίζει → ψήφος για νίκη/ήττα
            if (g.min > 0 && g.pir >= 4.0) {
                votes.merge(k, if (g.fpt > g.pir * 1.05) 1 else -1, Int::plus)
            }
        }
        val out = mutableListOf<TeamGame>()
        for ((k, m) in meta) {
            val (md, team) = k
            val (opp, home) = m
            if (!home) continue
            val ok = md to opp
            val hp = pts[k] ?: 0
            val ap = pts[ok] ?: 0
            val v = (votes[k] ?: 0) - (votes[ok] ?: 0)
            val homeWon = when {
                v >= 2 -> true
                v <= -2 -> false
                hp > 40 && ap > 40 && hp != ap -> hp > ap
                else -> v >= 0
            }
            val margin = if (hp > 40 && ap > 40) abs(hp - ap) else 8
            out += TeamGame(md, team, opp, homeWon, max(margin, 1))
        }
        return out.sortedBy { it.md }
    }

    fun elo(games: List<TeamGame>): Map<String, Double> {
        val r = HashMap<String, Double>()
        for (g in games) {
            val rh = r[g.home] ?: 1500.0
            val ra = r[g.away] ?: 1500.0
            val expHome = 1.0 / (1.0 + 10.0.pow((ra - (rh + ELO_HOME)) / 400.0))
            val s = if (g.homeWon) 1.0 else 0.0
            val mult = ln(g.margin + 1.0).coerceIn(1.0, 3.0)
            val d = ELO_K * mult * (s - expHome)
            r[g.home] = rh + d
            r[g.away] = ra - d
        }
        return r
    }

    fun winProbability(elo: Map<String, Double>, team: String, opp: String, home: Boolean): Double {
        val rt = (elo[team] ?: 1500.0) + if (home) ELO_HOME else -ELO_HOME
        val ro = elo[opp] ?: 1500.0
        return 1.0 / (1.0 + 10.0.pow((ro - rt) / 400.0))
    }

    // ------------------------------------------------------------------
    // Μεταβολή τιμής: plus ≈ b0 + b1·FPT + b2·τιμή (ελάχιστα τετράγωνα στα match-logs)

    fun fitPriceModel(all: List<GameLog>): PriceModel {
        val fallback = PriceModel(0.0, 0.045, -0.05)
        // αγωνιστικές που έχουν ήδη "βγάλει" τιμές
        val pricedMds = all.filter { it.plus != null && it.plus != 0.0 }
            .groupingBy { it.md }.eachCount().filter { it.value >= 20 }.keys
        if (pricedMds.isEmpty()) return fallback
        val maxMd = pricedMds.max()
        val rows = all.filter { it.md <= maxMd && it.plus != null && it.price != null && it.min > 0.0 }
        if (rows.size < 30) return fallback

        // κανονικές εξισώσεις X'X b = X'y για X = [1, fpt, price]
        val a = Array(3) { DoubleArray(3) }
        val b = DoubleArray(3)
        for (r in rows) {
            val x = doubleArrayOf(1.0, r.fpt, r.price!!)
            val y = r.plus!!
            for (i in 0 until 3) {
                b[i] += x[i] * y
                for (j in 0 until 3) a[i][j] += x[i] * x[j]
            }
        }
        val sol = solve3(a, b) ?: return fallback
        if (sol[1] <= 0.0) return fallback
        return PriceModel(sol[0], sol[1], sol[2])
    }

    fun solve3(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
        val a = Array(3) { a0[it].copyOf() }
        val b = b0.copyOf()
        for (c in 0 until 3) {
            var piv = c
            for (r in c + 1 until 3) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
            if (abs(a[piv][c]) < 1e-9) return null
            val tr = a[c]; a[c] = a[piv]; a[piv] = tr
            val tb = b[c]; b[c] = b[piv]; b[piv] = tb
            for (r in 0 until 3) {
                if (r == c) continue
                val f = a[r][c] / a[c][c]
                for (k in c until 3) a[r][k] -= f * a[c][k]
                b[r] -= f * b[c]
            }
        }
        return DoubleArray(3) { b[it] / a[it][it] }
    }

    // ------------------------------------------------------------------

    fun posGr(p: String) = when (p) {
        "Guard" -> "γκαρντ"
        "Forward" -> "φόργουορντ"
        "Center" -> "σέντερ"
        else -> p
    }

    private fun pct(f: Double): String {
        val v = ((f - 1.0) * 100).roundToInt()
        return if (v >= 0) "+$v%" else "$v%"
    }

    private fun fmt(d: Double) = String.format(java.util.Locale.US, "%.1f", d)
    private fun round1(d: Double) = (d * 10).roundToInt() / 10.0
    private fun round2(d: Double) = (d * 100).roundToInt() / 100.0
}
