package gr.elfantasy.advisor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

/**
 * Το (ανεπίσημο) API του EuroLeague Fantasy Challenge (Dunkest/Fantaking).
 * Το token είναι προαιρετικό: στέλνεται μόνο αν έχει οριστεί στις Ρυθμίσεις.
 */
class DunkestApi(private val tokenProvider: () -> String?) {

    private val base = "https://fantaking-api.dunkest.com/api/v1"
    private val league = 10
    private val competition = 49

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun request(url: String): Request {
        val b = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "ELFantasyAdvisor/1.0 (personal use)")
        val token = tokenProvider()?.trim()
        if (!token.isNullOrEmpty()) b.header("Authorization", "Bearer $token")
        return b.build()
    }

    private suspend fun get(url: String): JsonElement {
        var attempt = 0
        while (true) {
            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(request(url)).execute().use { r -> r.code to (r.body?.string() ?: "") }
            }
            if ((code == 429 || code >= 500) && attempt < 3) {
                attempt++
                delay(1500L * attempt)
                continue
            }
            if (code == 401 || code == 403) {
                throw ApiException(code, "Το fantasy ζητάει σύνδεση (HTTP $code). Βάλε token στις Ρυθμίσεις.")
            }
            if (code !in 200..299) throw ApiException(code, "HTTP $code από το fantasy API")
            return json.parseToJsonElement(body)
        }
    }

    suspend fun config(): Parsers.Config =
        Parsers.config(get("$base/leagues/$league/config"))

    /** Όλοι οι ενεργοί παίκτες με τιμές και μέσους όρους (με σελιδοποίηση). */
    suspend fun playersTable(): List<PlayerRow> {
        val out = mutableListOf<PlayerRow>()
        var page = 1
        var last: Int
        do {
            val url = "$base/competitions/$competition/stats/players/table" +
                "?stats_type=avg&per_page=100&page=$page&sort_by=fpt&sort_order=desc&active_players=true"
            val p = Parsers.table(get(url))
            out += p.players
            last = p.lastPage
            page++
        } while (page <= last && page <= 30)
        return out.distinctBy { it.id }
    }

    suspend fun matchLog(playerId: Int): List<GameLog> =
        Parsers.matchLog(get("$base/basketball/leagues/$league/players/$playerId/match-log/rendered"))

    suspend fun profile(playerId: Int): PlayerProfile? =
        Parsers.profile(playerId, get("$base/players/$playerId/profile?league=$league&lang=el"))

    suspend fun upcoming(playerId: Int): List<Fixture> =
        Parsers.upcoming(get("$base/players/$playerId/upcoming-matches?league=$league"))
}
