package gr.elfantasy.advisor.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Αποθήκευση σε αρχεία JSON στον εσωτερικό χώρο της εφαρμογής (filesDir).
 * Δεν είναι cache: δεν σβήνεται από το σύστημα, μόνο με "Εκκαθάριση δεδομένων" ή απεγκατάσταση.
 */
class Store(context: Context) {

    private val dir: File = context.filesDir
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun file(name: String) = File(dir, name)

    private inline fun <reified T> read(name: String): T? =
        try {
            val f = file(name)
            if (f.exists()) json.decodeFromString<T>(f.readText()) else null
        } catch (e: Exception) {
            null
        }

    private inline fun <reified T> write(name: String, value: T) {
        val tmp = file("$name.tmp")
        tmp.writeText(json.encodeToString(value))
        val target = file(name)
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun loadSnapshot(): DataSnapshot? = read("data.json")
    fun saveSnapshot(s: DataSnapshot) = write("data.json", s)

    fun loadRecs(): RecSet? = read("recs.json")
    fun saveRecs(r: RecSet) = write("recs.json", r)

    fun loadHistory(): Map<Int, HistoryEntry> = read<Map<Int, HistoryEntry>>("history.json") ?: emptyMap()
    fun saveHistory(h: Map<Int, HistoryEntry>) = write("history.json", h)
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var token: String?
        get() = sp.getString("token", null)
        set(value) {
            sp.edit().putString("token", value).apply()
        }
}
