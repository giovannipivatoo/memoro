// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ui

import android.text.Html
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.giovannipivatoo.memoro.data.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
internal fun HistoryScreen(repo: MemoroRepository) {
    var snapshot by remember { mutableStateOf<ArchiveSnapshot?>(null) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var expandedReview by rememberSaveable { mutableStateOf<Long?>(null) }
    var expandedAttempt by rememberSaveable { mutableStateOf<Long?>(null) }
    var erase by remember { mutableStateOf<Attempt?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refresh) {
        try { snapshot = repo.snapshot(); error = "" }
        catch (e: Exception) { error = e.message ?: "Cronologia non disponibile" }
    }

    val data = snapshot
    val cards = data?.cards?.associateBy { it.id }.orEmpty()
    val attempts = data?.attempts?.associateBy { it.id }.orEmpty()
    val now = System.currentTimeMillis()
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val reviews = data?.reviews.orEmpty().sortedByDescending { it.reviewedAtMillis }
    val due = data?.cards.orEmpty().count {
        !it.archived && (it.scheduling.importedQueue ?: 0) >= 0 && it.scheduling.dueAtMillis <= now
    }
    val query = search.trim()
    val visibleReviews = reviews.filter { query.isBlank() || cards[it.cardId]?.front.plainTitle().contains(query, ignoreCase = true) }
    val practices = data?.attempts.orEmpty().filter { it.state == AttemptState.PRACTICED }
        .sortedByDescending { it.updatedAtMillis }
        .filter { query.isBlank() || cards[it.cardId]?.front.plainTitle().contains(query, ignoreCase = true) }
    val drafts = data?.attempts.orEmpty().filter { it.state == AttemptState.DRAFT || it.state == AttemptState.EVALUATED }
        .sortedByDescending { it.updatedAtMillis }
        .filter { query.isBlank() || cards[it.cardId]?.front.plainTitle().contains(query, ignoreCase = true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Il tuo percorso", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Ripassi, progressi e risposte", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, contentDescription = "Aggiorna cronologia") }
            }
        }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
        if (data == null) item { Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverviewStat("Oggi", reviews.count { it.reviewedAtMillis >= today }.toString(), Modifier.weight(1f))
                    OverviewStat("Totale", reviews.size.toString(), Modifier.weight(1f))
                    OverviewStat("Dovute", due.toString(), Modifier.weight(1f))
                }
            }
            if (reviews.isNotEmpty() || practices.isNotEmpty() || drafts.isNotEmpty()) item {
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Cerca una carta") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item { Text("Ripassi recenti", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            if (reviews.isEmpty()) item {
                HistoryEmpty("Qui compariranno i tuoi ripassi", "Studia una carta e conferma il voto per iniziare a seguire i progressi.")
            } else if (visibleReviews.isEmpty()) item { HistoryEmpty("Nessuna carta trovata", "Prova un'altra parola nella ricerca.") }
            items(visibleReviews, key = { "review-${it.id}" }) { review ->
                val attempt = attempts[review.attemptId]
                val expanded = expandedReview == review.id
                ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().clickable { expandedReview = if (expanded) null else review.id }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(cards[review.cardId]?.front.plainTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(review.reviewedAtMillis)),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (expanded) "Chiudi dettagli" else "Apri dettagli")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            LabelPill(review.rating.historyLabel(), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                            attempt?.finalOutcome?.let { LabelPill(it.historyLabel(), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) }
                        }
                        if (expanded) {
                            HorizontalDivider()
                            if (attempt == null) Text("Ripasso importato da Anki", style = MaterialTheme.typography.bodyMedium)
                            else {
                                Text("Risposta: ${attempt.answer.ifBlank { if (attempt.mode == AnswerMode.CLASSIC) "Valutazione classica" else "Risposta rimossa" }}")
                                attempt.automaticOutcome?.let { Text("Esito automatico: ${it.historyLabel()}") }
                                attempt.finalOutcome?.let { Text("Esito finale: ${it.historyLabel()}") }
                                val feedback = attempt.feedback.parseStoredFeedback()
                                if (feedback != null) {
                                    if (feedback.explanation.isNotBlank()) Text(feedback.explanation)
                                    if (feedback.errors.isNotEmpty()) Text("Errori: ${feedback.errors.joinToString("; ")}")
                                    if (feedback.omissions.isNotEmpty()) Text("Omissioni: ${feedback.omissions.joinToString("; ")}")
                                } else if (attempt.feedback.isNotBlank()) Text(attempt.feedback)
                                if (attempt.answer.isNotBlank() || attempt.feedback.isNotBlank() || attempt.finalOutcome != null) {
                                    TextButton(onClick = { erase = attempt }) {
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp)); Text("Cancella dati della risposta")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (practices.isNotEmpty()) {
                item { Text("Ripassi liberi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
                items(practices, key = { "practice-${it.id}" }) { attempt ->
                    val expanded = expandedAttempt == attempt.id
                    ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().clickable { expandedAttempt = if (expanded) null else attempt.id }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(cards[attempt.cardId]?.front.plainTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(attempt.updatedAtMillis)),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (expanded) "Chiudi dettagli" else "Apri dettagli")
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.Start) {
                                LabelPill("Ripasso libero completato", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                                attempt.finalOutcome?.let { LabelPill(it.historyLabel(), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) }
                            }
                            if (expanded) {
                                HorizontalDivider()
                                Text("Modalità: ${attempt.mode.historyLabel()}")
                                if (attempt.answer.isNotBlank()) Text("Risposta: ${attempt.answer}")
                                attempt.automaticOutcome?.let { Text("Esito automatico: ${it.historyLabel()}") }
                                attempt.finalOutcome?.let { Text("Esito finale: ${it.historyLabel()}") }
                                val feedback = attempt.feedback.parseStoredFeedback()
                                if (feedback != null) {
                                    if (feedback.explanation.isNotBlank()) Text(feedback.explanation)
                                    if (feedback.errors.isNotEmpty()) Text("Errori: ${feedback.errors.joinToString("; ")}")
                                    if (feedback.omissions.isNotEmpty()) Text("Omissioni: ${feedback.omissions.joinToString("; ")}")
                                } else if (attempt.feedback.isNotBlank()) Text(attempt.feedback)
                                TextButton(onClick = { erase = attempt }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp)); Text("Cancella ripasso libero")
                                }
                            }
                        }
                    }
                }
            }
            if (drafts.isNotEmpty()) {
                item { Text("Da completare", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
                items(drafts, key = { "attempt-${it.id}" }) { attempt ->
                    val expanded = expandedAttempt == attempt.id
                    ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().clickable { expandedAttempt = if (expanded) null else attempt.id }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(cards[attempt.cardId]?.front.plainTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text("${attempt.mode.historyLabel()} · ${attempt.state.historyLabel()}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (expanded) "Chiudi dettagli" else "Apri dettagli")
                            }
                            if (expanded) {
                                if (attempt.answer.isNotBlank()) Text("Risposta: ${attempt.answer}")
                                TextButton(onClick = { erase = attempt }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp)); Text("Cancella tentativo")
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
    erase?.let { attempt -> AlertDialog(
        onDismissRequest = { erase = null }, title = { Text("Cancellare i dati del tentativo?") },
        text = { Text("La risposta e il feedback saranno rimossi. Un ripasso già registrato conserverà il voto e la scadenza.") },
        confirmButton = { TextButton(onClick = { scope.launch {
            try { repo.deleteAttemptPersonalData(attempt.id); refresh++ }
            catch (e: Exception) { error = e.message ?: "Eliminazione non riuscita" }
            erase = null
        } }) { Text("Cancella") } },
        dismissButton = { TextButton(onClick = { erase = null }) { Text("Annulla") } },
    ) }
}

@Composable
private fun OverviewStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 15.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryEmpty(title: String, subtitle: String) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LabelPill(label: String, background: Color, foreground: Color) {
    Surface(shape = RoundedCornerShape(100.dp), color = background) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = foreground)
    }
}

private fun String?.plainTitle(): String {
    if (this.isNullOrBlank()) return "Carta senza titolo"
    val cleaned = replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("\\{\\{c\\d+::(.*?)(?:::[^}]*)?\\}\\}"), "$1")
        .replace(Regex("\\[(?:sound|audio):[^]]+]", RegexOption.IGNORE_CASE), " Audio ")
        .replace(Regex("\\[image:[^]]+]", RegexOption.IGNORE_CASE), " Immagine ")
        .replace(Regex("<[^>]*>"), " ")
    return Html.fromHtml(cleaned, Html.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\\s+"), " ").trim().ifBlank { "Carta senza titolo" }
}

private fun Rating.historyLabel() = when (this) { Rating.AGAIN -> "Da rifare"; Rating.HARD -> "Difficile"; Rating.GOOD -> "Buona"; Rating.EASY -> "Facile" }
private fun Outcome.historyLabel() = when (this) { Outcome.CORRECT -> "Corretta"; Outcome.PARTIAL -> "Parziale"; Outcome.WRONG -> "Errata"; Outcome.UNGRADABLE -> "Non valutabile" }
private fun AnswerMode.historyLabel() = when (this) { AnswerMode.CLASSIC -> "Classica"; AnswerMode.WRITTEN -> "Scritta"; AnswerMode.EXACT -> "Risposta esatta"; AnswerMode.AI -> "Correzione AI"; AnswerMode.MULTIPLE_CHOICE -> "Scelta multipla" }
private fun AttemptState.historyLabel() = when (this) { AttemptState.DRAFT -> "Bozza"; AttemptState.EVALUATED -> "Da confermare"; AttemptState.REVIEWED -> "Completato"; AttemptState.PRACTICED -> "Ripasso libero completato" }
