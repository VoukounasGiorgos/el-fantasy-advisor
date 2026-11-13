package gr.elfantasy.advisor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.elfantasy.advisor.data.DataSnapshot
import gr.elfantasy.advisor.data.HistoryEntry
import gr.elfantasy.advisor.data.Rec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val Green = Color(0xFF1B7F3B)
private val Amber = Color(0xFFB26A00)
private val Red = Color(0xFFB3261E)
private val Compact = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

@Composable
fun App(vm: MainViewModel) {
    val s by vm.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf("home") }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            when (screen) {
                "settings" -> {
                    BackHandler { screen = "home" }
                    SettingsScreen(vm) { screen = "home" }
                }
                "history" -> {
                    BackHandler { screen = "home" }
                    HistoryScreen(s) { screen = "home" }
                }
                else -> HomeScreen(s, onRefresh = { vm.refresh() }, onNav = { screen = it })
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
fun HomeScreen(s: UiState, onRefresh: () -> Unit, onNav: (String) -> Unit) {
    var pos by rememberSaveable { mutableStateOf("Guard") }
    val recs = s.recs

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (recs != null) "Αγωνιστική ${recs.matchday}" else "EL Fantasy Advisor",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                recs?.deadlineMillis?.let {
                    Text("Deadline: ${fmtDate(it)}", style = MaterialTheme.typography.bodyMedium)
                }
                s.snapshot?.let {
                    Text("Τελευταία ενημέρωση: ${fmtDate(it.syncedAt)}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRefresh, enabled = !s.syncing, contentPadding = Compact) { Text("Ανανέωση") }
                    OutlinedButton(onClick = { onNav("history") }, contentPadding = Compact) { Text("Ιστορικό") }
                    OutlinedButton(onClick = { onNav("settings") }, contentPadding = Compact) { Text("Ρυθμίσεις") }
                }
                if (s.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                s.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                s.error?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
            }
        }

        if (recs == null) {
            item(key = "empty") {
                Text(
                    if (s.loading) "Φόρτωση…"
                    else "Δεν υπάρχουν δεδομένα ακόμα. Πάτα «Ανανέωση» (η πρώτη φορά θέλει 1–3 λεπτά).",
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            return@LazyColumn
        }

        item(key = "cap-title") { SectionTitle("Αρχηγός (×2) – προτίμηση σε Turn 1") }
        items(recs.captains, key = { "cap-${it.playerId}" }) { RecCard(it) }

        item(key = "pos-select") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                listOf("Guard" to "Γκαρντ", "Forward" to "Φόργουορντ", "Center" to "Σέντερ").forEach { (k, label) ->
                    if (pos == k) Button(onClick = { pos = k }, contentPadding = Compact) { Text(label) }
                    else OutlinedButton(onClick = { pos = k }, contentPadding = Compact) { Text(label) }
                }
            }
        }

        listOf(
            "premium" to "Ακριβοί (≥ 14 cr) – καθαροί πόντοι",
            "mid" to "Μεσαίοι (9–14 cr) – ισορροπία πόντων/ρίσκου",
            "budget" to "Φθηνοί (< 9 cr) – αξία & άνοδος τιμής"
        ).forEach { (tier, title) ->
            val list = recs.recs.filter { it.position == pos && it.tier == tier }
            item(key = "t-$pos-$tier") { SectionTitle(title) }
            if (list.isEmpty()) {
                item(key = "t-$pos-$tier-empty") {
                    Text("Κανένας κατάλληλος παίκτης", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(list, key = { "r-$pos-$tier-${it.playerId}" }) { RecCard(it) }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(
        t,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
fun RecCard(r: Rec) {
    var open by remember(r.playerId) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { open = !open },
        colors = CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${r.name} · ${r.team}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text("${f1(r.price)} cr", fontWeight = FontWeight.SemiBold)
            }
            Text(
                "${if (r.home) "vs" else "@"} ${r.opp} · Turn ${r.turn} · ${posGr(r.position)}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Stat("xFP", f1(r.xfp))
                Stat("Αξία", "${f2(r.valuePerCredit)}/cr")
                Stat("Ρίσκο", r.risk.toString())
                Stat("Τιμή", signed(r.expPlus))
            }
            Text(
                r.label,
                color = when {
                    r.risk < 30 -> Green
                    r.risk < 50 -> Amber
                    else -> Red
                },
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall
            )
            if (open) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                r.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Τελ. 5 FPT: " + r.last5.joinToString(" · ") { f1(it) },
                    style = MaterialTheme.typography.bodySmall
                )
                if (r.popularity > 0) {
                    Text(
                        "Τον έχει το ${(r.popularity * 100).toInt()}% των ομάδων",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Text("Πάτα για αιτιολόγηση", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    var token by remember { mutableStateOf(vm.prefs.token ?: "") }
    var saved by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("← Πίσω") }
        Text("Ρυθμίσεις", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Text("Token του fantasy (προαιρετικό)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Χρειάζεται μόνο αν η ανανέωση βγάζει σφάλμα σύνδεσης (401). " +
                "Από τον υπολογιστή: σύνδεση στο fantasy → F12 → Network → ένα request προς " +
                "fantaking-api.dunkest.com → Headers → Authorization. Αντέγραψε ό,τι είναι μετά το «Bearer».",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; saved = false },
            label = { Text("Token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { vm.saveToken(token); saved = true }) { Text("Αποθήκευση") }
        if (saved) Text("Αποθηκεύτηκε", color = Green)

        HorizontalDivider()
        Text("Αυτόματη ενημέρωση", style = MaterialTheme.typography.titleMedium)
        Text(
            "Η εφαρμογή ελέγχει μόνη της κάθε ~2 ώρες. Όταν βγουν νέες τιμές μετά από αγωνιστική " +
                "κατεβάζει τα πάντα, βγάζει νέες προτάσεις και στέλνει ειδοποίηση. " +
                "Τις τελευταίες 12 ώρες πριν το deadline ξαναελέγχει τους τραυματισμούς.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
fun HistoryScreen(s: UiState, onBack: () -> Unit) {
    val entries = s.history.values.sortedByDescending { it.matchday }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "h-head") {
            Column {
                TextButton(onClick = onBack) { Text("← Πίσω") }
                Text("Πρόβλεψη vs πραγματικό", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Για κάθε αγωνιστική κρατάμε τις προτάσεις όπως ήταν στο deadline και τις συγκρίνουμε με τους πόντους που έγιναν.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (entries.isEmpty()) {
            item(key = "h-empty") { Text("Δεν υπάρχει ιστορικό ακόμα.") }
        }
        items(entries, key = { "h-${it.matchday}" }) { HistoryCard(it, s.snapshot) }
    }
}

@Composable
private fun HistoryCard(e: HistoryEntry, snap: DataSnapshot?) {
    val rows = e.predictions.map { p ->
        val actual = snap?.logs?.get(p.playerId)?.firstOrNull { it.md == e.matchday }?.fpt
        p to actual
    }
    val withActual = rows.filter { it.second != null }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Αγωνιστική ${e.matchday}", fontWeight = FontWeight.Bold)
            if (withActual.isEmpty()) {
                Text("Δεν έχει παιχτεί ακόμα (ή δεν έχει γίνει ανανέωση).", style = MaterialTheme.typography.bodySmall)
            } else {
                val pred = withActual.map { it.first.xfp }.average()
                val act = withActual.map { it.second!! }.average()
                val mae = withActual.map { abs(it.first.xfp - it.second!!) }.average()
                val hits = withActual.count { it.second!! >= it.first.xfp }
                Text(
                    "Μ.Ο. πρόβλεψης ${f1(pred)} · πραγματικό ${f1(act)} · μέσο σφάλμα ${f1(mae)} · " +
                        "πάνω από την πρόβλεψη $hits/${withActual.size}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            rows.forEach { (p, a) ->
                Text(
                    "${p.name}: πρόβλεψη ${f1(p.xfp)} → ${a?.let { f1(it) } ?: "–"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------

private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)
private fun f2(d: Double) = String.format(Locale.US, "%.2f", d)
private fun signed(d: Double) = (if (d >= 0) "+" else "") + f1(d)
private fun fmtDate(ms: Long) = SimpleDateFormat("EEE d/M HH:mm", Locale("el")).format(Date(ms))
private fun posGr(p: String) = when (p) {
    "Guard" -> "Γκαρντ"
    "Forward" -> "Φόργουορντ"
    "Center" -> "Σέντερ"
    else -> p
}
