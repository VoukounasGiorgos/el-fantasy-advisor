package gr.elfantasy.advisor

import gr.elfantasy.advisor.data.Parsers
import gr.elfantasy.advisor.engine.Engine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersAndEngineTest {

    private fun load(name: String): JsonElement {
        val text = javaClass.classLoader!!.getResource(name)!!.readText()
        return Json.parseToJsonElement(text)
    }

    @Test
    fun config_isParsed() {
        val c = Parsers.config(load("config.json"))
        assertEquals(11, c.currentMatchday)
        assertEquals(20, c.teams.size)
    }

    @Test
    fun table_isParsed() {
        val t = Parsers.table(load("table.json"))
        assertEquals(25, t.players.size)
        assertEquals(15, t.lastPage)
        val v = t.players.first()
        assertEquals(3766, v.id)
        assertEquals("Forward", v.position)
        assertEquals(18.9, v.price, 0.001)
        assertEquals(2.2, v.plus, 0.001)
    }

    @Test
    fun matchLog_pirAndWinBonus() {
        val log = Parsers.matchLog(load("matchlog.json"))
        assertEquals(11, log.size)
        val md11 = log.first { it.md == 11 }
        assertEquals(26.0, md11.pir, 0.001)          // PIR από το boxscore
        assertEquals(28.6, md11.fpt, 0.001)          // = PIR × 1.1 (νίκη)
        assertTrue(md11.home)
        assertEquals("DUB", md11.opp)
        val md10 = log.first { it.md == 10 }
        assertEquals(md10.pir, md10.fpt, 0.001)      // ήττα: χωρίς bonus
        assertEquals(20.93, md11.min, 0.01)
    }

    @Test
    fun upcoming_and_profile() {
        val up = Parsers.upcoming(load("upcoming.json"))
        assertEquals("MTA", up.first().home)
        val p = Parsers.profile(3766, load("profile.json"))!!
        assertEquals("14", p.jersey)
        assertTrue(p.onFire)
    }

    @Test
    fun solve3_works() {
        // x + y + z = 6, 2y + 5z = -4, 2x + 5y - z = 27  → (5, 3, -2)
        val a = arrayOf(doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(0.0, 2.0, 5.0), doubleArrayOf(2.0, 5.0, -1.0))
        val s = Engine.solve3(a, doubleArrayOf(6.0, -4.0, 27.0))!!
        assertEquals(5.0, s[0], 1e-9)
        assertEquals(3.0, s[1], 1e-9)
        assertEquals(-2.0, s[2], 1e-9)
    }
}
