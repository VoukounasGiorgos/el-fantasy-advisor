package gr.elfantasy.advisor.data

import android.content.Context
import gr.elfantasy.advisor.engine.Engine
import gr.elfantasy.advisor.engine.FixtureResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

data class SyncResult(
    val message: String,
    /** Κείμενο ειδοποίησης (μόνο για το background sync), ή null */
    val notify: String? = null
)

/**
 * Η ροή συγχρονισμού.
 *
 * Ελαφρύ sync (κάθε ~2 ώρες): config + πίνακας τιμών (≈5 requests).
 *   - Νέες τιμές ή νέα αγωνιστική-στόχος → πλήρες sync + νέες προτάσεις + ειδοποίηση.
 *   - Λιγότερο από 12 ώρες πριν το deadline → ξαναδιαβάζει τραυματισμούς, ειδοποίηση αν άλλαξαν οι προτάσεις.
 * Πλήρες sync (κουμπί ή αλλαγή αγωνιστικής): match-logs + προφίλ όλων + πρόγραμμα.
 */
object Sync {

    private val mutex = Mutex()
    private const val HOUR = 3_600_000L

    suspend fun run(context: Context, force: Boolean, progress: (String) -> Unit = {}): SyncResult =
        mutex.withLock { doRun(context, force, progress) }

    private suspend fun doRun(context: Context, force: Boolean, progress: (String) -> Unit): SyncResult {
        val store = Store(context)
        val prefs = Prefs(context)
        val api = DunkestApi { prefs.token }
        val old = store.loadSnapshot()
        val now = System.currentTimeMillis()

        progress("Κατάσταση αγωνιστικής…")
        val cfg = api.config()
        val inProgress = cfg.currentRoundStartedAt?.let { it <= now } ?: false
        val target = if (inProgress) cfg.currentMatchday + 1 else cfg.currentMatchday
        val state = GameState(cfg.currentMatchday, inProgress, target)

        progress("Τιμές παικτών…")
        val players = api.playersTable().filter { it.position in PLAYER_POSITIONS }
        if (players.isEmpty()) throw IOException("Δεν ήρθαν παίκτες από το fantasy")
        val priceHash = players.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.price}" }.hashCode()

        val pricesChanged = old != null && old.priceHash != priceHash
        val targetChanged = old != null && old.state.targetMatchday != target
        val needFull = force || old == null || pricesChanged || targetChanged

        if (needFull) {
            val snap = fullSync(api, players, state, priceHash, cfg.teams, old, progress)
            store.saveSnapshot(snap)
            val recs = recompute(store, snap, now)
            val notify = when {
                force -> null
                pricesChanged -> "Νέες τιμές – προτάσεις για την αγωνιστική ${recs.matchday}"
                targetChanged -> "Προτάσεις για την αγωνιστική ${recs.matchday}"
                else -> null
            }
            return SyncResult("Ενημερώθηκε: αγωνιστική ${recs.matchday}", notify)
        }

        // ---- ελαφρύ: κοντά στο deadline ξαναδιαβάζουμε τραυματισμούς ----
        val oldRecs = store.loadRecs()
        val deadline = oldRecs?.deadlineMillis
        val nearDeadline = deadline != null && deadline > now && deadline - now < 12 * HOUR
        if (old != null && nearDeadline && now - old.profilesSyncedAt > 3 * HOUR) {
            progress("Τραυματισμοί…")
            val ids = old.players.filter { (old.logs[it.id]?.count { g -> g.min > 0 } ?: 0) >= 3 }.map { it.id }
            val fresh = fetchProfiles(api, ids, old.profiles, progress)
            val snap = old.copy(profiles = old.profiles + fresh, profilesSyncedAt = now, syncedAt = now)
            store.saveSnapshot(snap)
            val recs = recompute(store, snap, now)
            val before = oldRecs?.recs.orEmpty().map { it.playerId }.toSet() +
                oldRecs?.captains.orEmpty().map { it.playerId }
            val after = recs.recs.map { it.playerId }.toSet() + recs.captains.map { it.playerId }
            val notify = if (before != after) "Αλλαγές στις προτάσεις της αγωνιστικής ${recs.matchday} (τραυματισμοί)" else null
            return SyncResult("Ενημερώθηκαν οι τραυματισμοί", notify)
        }
        return SyncResult("Καμία αλλαγή")
    }

    private fun recompute(store: Store, snap: DataSnapshot, now: Long): RecSet {
        val recs = Engine.build(snap, now)
        store.saveRecs(recs)
        // κρατάμε τις προβλέψεις μέχρι το deadline για σύγκριση με τα πραγματικά
        val deadline = recs.deadlineMillis
        if (deadline == null || now < deadline) {
            val hist = store.loadHistory().toMutableMap()
            hist[recs.matchday] = HistoryEntry(
                recs.matchday,
                (recs.captains + recs.recs).distinctBy { it.playerId }
                    .map { PredictedItem(it.playerId, it.name, it.tier, it.xfp) }
            )
            store.saveHistory(hist)
        }
        return recs
    }

    private suspend fun fullSync(
        api: DunkestApi,
        players: List<PlayerRow>,
        state: GameState,
        priceHash: Int,
        teams: List<String>,
        old: DataSnapshot?,
        progress: (String) -> Unit
    ): DataSnapshot {
        val now = System.currentTimeMillis()

        // 1) match-logs
        val ids = players.map { it.id }
        val logs = HashMap<Int, List<GameLog>>()
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        var firstError: Exception? = null
        val sem = Semaphore(3)
        coroutineScope {
            ids.map { id ->
                async {
                    sem.withPermit {
                        val l = try {
                            api.matchLog(id)
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                            if (firstError == null) firstError = e
                            old?.logs?.get(id) ?: emptyList()
                        }
                        synchronized(logs) { logs[id] = l }
                        val d = done.incrementAndGet()
                        if (d % 20 == 0 || d == ids.size) progress("Ιστορικό παικτών $d/${ids.size}")
                    }
                }
            }.awaitAll()
        }
        if (failed.get() > ids.size / 2) {
            throw IOException("Απέτυχε η λήψη ιστορικού: ${firstError?.message ?: "άγνωστο σφάλμα"}")
        }

        // 2) προφίλ (τραυματισμοί, νούμερο φανέλας, δημοφιλία)
        val withGames = players.filter { (logs[it.id]?.count { g -> g.min > 0 } ?: 0) >= 1 }.map { it.id }
        val profiles = fetchProfiles(api, withGames, old?.profiles ?: emptyMap(), progress)

        // 3) πρόγραμμα: ένας παίκτης ανά ομάδα (αυτός με τα περισσότερα λεπτά)
        progress("Πρόγραμμα αγωνιστικής ${state.targetMatchday}…")
        val allTeams = (teams + players.map { it.team }).filter { it.isNotBlank() }.distinct()
        val upcoming = HashMap<String, List<Fixture>>()
        for (team in allTeams) {
            val rep = players.filter { it.team == team }
                .maxByOrNull { p -> logs[p.id]?.sumOf { it.min } ?: 0.0 } ?: continue
            upcoming[team] = try { api.upcoming(rep.id) } catch (e: Exception) { emptyList() }
        }
        val playedCurrent = players.filter { p -> logs[p.id]?.any { it.md == state.currentMatchday && it.min > 0 } == true }
            .map { it.team }.toSet()
        val fixtures = FixtureResolver.resolve(allTeams, upcoming, playedCurrent, state.currentInProgress)

        return DataSnapshot(
            syncedAt = now,
            state = state,
            players = players,
            logs = logs,
            profiles = profiles,
            fixtures = fixtures,
            priceHash = priceHash,
            profilesSyncedAt = now
        )
    }

    private suspend fun fetchProfiles(
        api: DunkestApi,
        ids: List<Int>,
        old: Map<Int, PlayerProfile>,
        progress: (String) -> Unit
    ): Map<Int, PlayerProfile> {
        val out = HashMap<Int, PlayerProfile>()
        val done = AtomicInteger(0)
        val sem = Semaphore(3)
        coroutineScope {
            ids.map { id ->
                async {
                    sem.withPermit {
                        val p = try { api.profile(id) } catch (e: Exception) { null } ?: old[id]
                        if (p != null) synchronized(out) { out[id] = p }
                        val d = done.incrementAndGet()
                        if (d % 20 == 0 || d == ids.size) progress("Προφίλ/τραυματισμοί $d/${ids.size}")
                    }
                }
            }.awaitAll()
        }
        return out
    }
}
