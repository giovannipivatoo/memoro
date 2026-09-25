// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.giovannipivatoo.memoro.data.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HistoryScreen(repo: MemoroRepository) {
    var snapshot by remember { mutableStateOf<ArchiveSnapshot?>(null) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedCard by remember { mutableStateOf<Long?>(null) }
    var erase by remember { mutableStateOf<Attempt?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refresh) { try { snapshot = repo.snapshot(); error = "" } catch (e: Exception) { error = e.message ?: "Cronologia non disponibile" } }
    val data = snapshot
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row { Text("Statistiche", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); TextButton(onClick = { refresh++ }) { Text("Aggiorna") } } }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
        if (data != null) {
            val now = System.currentTimeMillis()
            val reviews = data.reviews.sortedByDescending { it.reviewedAtMillis }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Carte in scadenza: ${data.cards.count { !it.archived && (it.scheduling.importedQueue ?: 0) >= 0 && it.scheduling.dueAtMillis <= now }}")
                        Text("Ripassi: ${reviews.size}")
                        Text("Risposte corrette: ${data.attempts.count { it.finalOutcome == Outcome.CORRECT }}")
                        Text("Parziali: ${data.attempts.count { it.finalOutcome == Outcome.PARTIAL }} · Errate: ${data.attempts.count { it.finalOutcome == Outcome.WRONG }}")
                        Text("Non valutabili: ${data.attempts.count { it.finalOutcome == Outcome.UNGRADABLE }}")
                    }
                }
            }
            item {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(selectedCard?.let { id -> data.cards.firstOrNull { it.id == id }?.front ?: "Carta" } ?: "Tutte le carte") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("Tutte le carte") }, onClick = { selectedCard = null; expanded = false })
                        data.cards.forEach { card -> DropdownMenuItem(text = { Text(card.front, maxLines = 1) }, onClick = { selectedCard = card.id; expanded = false }) }
                    }
                }
            }
            item { Text("Ripassi recenti", style = MaterialTheme.typography.titleMedium) }
            val visibleReviews = reviews.filter { selectedCard == null || it.cardId == selectedCard }
            if (visibleReviews.isEmpty()) item { Text("Nessun ripasso registrato.") }
            items(visibleReviews, key = { it.id }) { review ->
                val card = data.cards.firstOrNull { it.id == review.cardId }
                val attempt = data.attempts.firstOrNull { it.id == review.attemptId }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(card?.front.orEmpty(), maxLines = 2, fontWeight = FontWeight.SemiBold)
                        Text("${review.rating.historyLabel()} · ${DateFormat.getDateTimeInstance().format(Date(review.reviewedAtMillis))}")
                        if (attempt != null) {
                            Text("Risposta: ${attempt.answer.ifBlank { "(classica)" }}")
                            Text("Automatico: ${attempt.automaticOutcome?.historyLabel() ?: "—"} · Finale: ${attempt.finalOutcome?.historyLabel() ?: "—"}")
                            attempt.feedback.parseStoredFeedback()?.let { feedback ->
                                Text(feedback.explanation)
                                if (feedback.errors.isNotEmpty()) Text("Errori: ${feedback.errors.joinToString("; ")}")
                                if (feedback.omissions.isNotEmpty()) Text("Omissioni: ${feedback.omissions.joinToString("; ")}")
                            }
                            TextButton(onClick = { erase = attempt }) { Text("Cancella risposta e feedback") }
                        }
                    }
                }
            }
            item { Text("Tentativi non conclusi", style = MaterialTheme.typography.titleMedium) }
            items(data.attempts.filter { it.state != AttemptState.REVIEWED && (selectedCard == null || it.cardId == selectedCard) }, key = { "attempt-${it.id}" }) { attempt ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(data.cards.firstOrNull { it.id == attempt.cardId }?.front.orEmpty(), fontWeight = FontWeight.SemiBold)
                        Text("${attempt.mode} · ${attempt.state} · ${attempt.answer}")
                        TextButton(onClick = { erase = attempt }) { Text("Cancella tentativo") }
                    }
                }
            }
        }
    }
    erase?.let { attempt -> AlertDialog(onDismissRequest = { erase = null }, title = { Text("Cancellare i dati del tentativo?") }, text = { Text("La risposta e il feedback saranno rimossi. Un ripasso già registrato conserverà il voto e la scadenza.") },
        confirmButton = { TextButton(onClick = { scope.launch { try { repo.deleteAttemptPersonalData(attempt.id); refresh++ } catch (e: Exception) { error = e.message ?: "Eliminazione non riuscita" }; erase = null } }) { Text("Cancella") } },
        dismissButton = { TextButton(onClick = { erase = null }) { Text("Annulla") } }) }
}

private fun Rating.historyLabel() = when (this) { Rating.AGAIN -> "Da rifare"; Rating.HARD -> "Difficile"; Rating.GOOD -> "Buona"; Rating.EASY -> "Facile" }
private fun Outcome.historyLabel() = when (this) { Outcome.CORRECT -> "Corretta"; Outcome.PARTIAL -> "Parziale"; Outcome.WRONG -> "Errata"; Outcome.UNGRADABLE -> "Non valutabile" }

@Composable
internal fun SettingsScreen(actions: AppActions, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var key by remember { mutableStateOf(actions.apiKey()) }
    var model by remember { mutableStateOf(actions.model()) }
    var pet by remember { mutableStateOf(actions.petEnabled()) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var licenses by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun perform(uri: Uri?, operation: suspend (Uri) -> String) { if (uri != null && !busy) scope.launch { busy = true; try { report = operation(uri) } catch (e: Exception) { report = e.message ?: "Operazione non riuscita" } finally { busy = false } } }
    val importApkg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { perform(it, actions.importApkg) }
    val exportApkg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { perform(it, actions.exportApkg) }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { perform(it, actions.backup) }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pendingRestore = it }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("DeepSeek", style = MaterialTheme.typography.titleLarge) }
        item { Text("Solo la carta e la tua risposta vengono inviate quando tocchi Invia risposta in modalità AI. I link fonte sono riferimenti e non vengono letti automaticamente.") }
        item { OutlinedTextField(key, { key = it }, label = { Text("Chiave API personale") }, modifier = Modifier.fillMaxWidth().testTag("apiKey"), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()) }
        item { OutlinedTextField(model, { model = it }, label = { Text("Modello DeepSeek") }, modifier = Modifier.fillMaxWidth().testTag("modelName"), singleLine = true) }
        item { Button(onClick = { try { actions.saveApiKey(key.trim()); actions.saveModel(model.trim().ifBlank { "deepseek-flash" }); onMessage("Impostazioni salvate") } catch (e: Exception) { onMessage(e.message ?: "Impostazioni non salvate") } }, modifier = Modifier.testTag("saveSettings")) { Text("Salva API e modello") } }
        item { HorizontalDivider() }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Gattino", style = MaterialTheme.typography.titleMedium); Text("Silenzioso e senza animazioni") }; Switch(checked = pet, onCheckedChange = { pet = it; actions.savePetEnabled(it) }, modifier = Modifier.testTag("petSwitch")) } }
        item { HorizontalDivider() }
        item { Text("Archivio", style = MaterialTheme.typography.titleLarge) }
        item { Text("Il ripristino sostituisce l'archivio locale dopo la verifica del file e crea una copia preventiva.") }
        item { OutlinedButton(enabled = !busy, onClick = { importApkg.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("Importa .apkg") } }
        item { OutlinedButton(enabled = !busy, onClick = { exportApkg.launch("memoro.apkg") }) { Text("Esporta .apkg") } }
        item { OutlinedButton(enabled = !busy, onClick = { createBackup.launch("memoro-backup.zip") }) { Text("Crea backup") } }
        item { OutlinedButton(enabled = !busy, onClick = { restoreBackup.launch(arrayOf("application/zip", "*/*")) }) { Text("Ripristina backup") } }
        item { TextButton(onClick = { licenses = true }) { Text("Licenze open source") } }
    }
    pendingRestore?.let { uri ->
        AlertDialog(onDismissRequest = { pendingRestore = null }, title = { Text("Ripristinare il backup?") }, text = { Text("Le note, le carte e la cronologia correnti saranno sostituite. Una copia preventiva verrà conservata sul dispositivo.") },
            confirmButton = { TextButton(onClick = { pendingRestore = null; perform(uri, actions.restore) }) { Text("Ripristina") } },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Annulla") } })
    }
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Licenze") }, text = {
        val contents = remember { context.assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name ->
            "$name\n" + context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
        } }
        Box(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { Text(contents) }
    }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Chiudi") } })
    report?.let { message -> AlertDialog(onDismissRequest = { report = null }, title = { Text("Risultato archivio") }, text = {
        Box(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) { Text(message) }
    }, confirmButton = { TextButton(onClick = { report = null }) { Text("Chiudi") } }) }
}
