package gr.elfantasy.advisor.engine

import gr.elfantasy.advisor.data.Fixture

/**
 * Βρίσκει τον αγώνα κάθε ομάδας στην αγωνιστική-στόχο.
 *
 * - Αν η τρέχουσα αγωνιστική δεν έχει ξεκινήσει → στόχος είναι αυτή, άρα ο 1ος επόμενος αγώνας.
 * - Αν είναι σε εξέλιξη → στόχος η επόμενη: για όσες ομάδες έχουν ήδη παίξει παίρνουμε τον 1ο
 *   επόμενο αγώνα, για όσες δεν έχουν παίξει ακόμα τον 2ο.
 * Στο τέλος ελέγχουμε ότι και οι δύο ομάδες ενός αγώνα "συμφωνούν"· αν όχι, πέφτουμε στον 1ο.
 */
object FixtureResolver {

    fun resolve(
        teams: List<String>,
        upcomingByTeam: Map<String, List<Fixture>>,
        playedCurrent: Set<String>,
        currentInProgress: Boolean
    ): List<Fixture> {
        fun pick(team: String, idx: Int): Fixture? = upcomingByTeam[team]?.getOrNull(idx)

        val choice = HashMap<String, Fixture?>()
        for (t in teams) {
            val idx = if (currentInProgress && t !in playedCurrent) 1 else 0
            choice[t] = pick(t, idx)
        }

        val final = HashMap<String, Fixture>()
        for (t in teams) {
            val f = choice[t] ?: continue
            val o = f.opponentOf(t)
            if (choice[o]?.key == f.key) final[t] = f
        }
        // ασυμφωνίες → δοκίμασε τον 1ο επόμενο αγώνα αν συμφωνεί και ο αντίπαλος
        for (t in teams) {
            if (t in final) continue
            val f0 = pick(t, 0) ?: continue
            val o = f0.opponentOf(t)
            if (o in final) continue
            val oFirst = pick(o, 0)
            if (oFirst?.key == f0.key || choice[o]?.key == f0.key) {
                final[t] = f0
                final[o] = f0
            }
        }
        return final.values.distinctBy { it.key }.sortedBy { it.startsAtMillis }
    }
}
