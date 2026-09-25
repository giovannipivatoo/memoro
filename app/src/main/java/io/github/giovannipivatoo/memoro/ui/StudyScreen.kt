// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.giovannipivatoo.memoro.ai.*
import io.github.giovannipivatoo.memoro.anki.AnkiRenderer
import io.github.giovannipivatoo.memoro.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
internal fun StudyScreen(
    repo: MemoroRepository,
    deckId: Long,
    ai: DeepSeekClient,
    apiKey: () -> String,
    model: () -> String,
    petEnabled: Boolean,
    onError: (String) -> Unit,
) {
    var due by remember(deckId) { mutableStateOf<List<Card>>(emptyList()) }
    var loading by remember(deckId) { mutableStateOf(true) }
    var index by remember(deckId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(deckId) { try { due = repo.dueCards(System.currentTimeMillis(), deckId) } catch (e: Exception) { onError(e.message ?: "Impossibile caricare le carte") }; loading = false }
    if (loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val card = due.getOrNull(index)
    if (card == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (petEnabled) Kitten(Outcome.CORRECT)
                Text(if (due.isEmpty()) "Nessuna carta in scadenza." else "Sessione completata.", style = MaterialTheme.typography.headlineSmall)
            }
        }
        return
    }
    val attempts by produceState<List<Attempt>?>(initialValue = null, card.id) {
        repo.observeAttempts(card.id).collect { value = it }
    }
    val attemptList = attempts
    if (attemptList == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val current = attemptList.firstOrNull { it.state != AttemptState.REVIEWED }
    val note by produceState<Note?>(initialValue = null, card.noteId) { value = repo.getNote(card.noteId) }
    val faces = remember(card, note) { note?.let { AnkiRenderer.render(card, it) } }
    val question = faces?.question ?: card.front
    val reference = faces?.answer ?: card.back
    val textReference = reference.replace(Regex("\\[(image|audio):[^]]+]"), "").trim()
    val exactExpected = note?.let { AnkiRenderer.exactExpected(card, it) }
    val availableModes = (card.modes + AnswerMode.CLASSIC).filterTo(mutableSetOf()) {
        when (it) { AnswerMode.CLASSIC -> true; AnswerMode.EXACT -> exactExpected != null; AnswerMode.AI -> textReference.isNotBlank() }
    }
    var mode by androidx.compose.runtime.saveable.rememberSaveable(card.id) { mutableStateOf(current?.mode ?: availableModes.firstOrNull() ?: AnswerMode.CLASSIC) }
    var answer by androidx.compose.runtime.saveable.rememberSaveable(card.id) { mutableStateOf(current?.answer.orEmpty()) }
    var attemptId by androidx.compose.runtime.saveable.rememberSaveable(card.id) { mutableLongStateOf(current?.id ?: 0L) }
    var submitted by androidx.compose.runtime.saveable.rememberSaveable(card.id) { mutableStateOf(current?.state == AttemptState.EVALUATED) }
    var automatic by remember(card.id) { mutableStateOf(current?.automaticOutcome) }
    var finalOutcome by remember(card.id) { mutableStateOf(current?.finalOutcome) }
    var feedback by remember(card.id) { mutableStateOf(current?.feedback.orEmpty()) }
    var busy by remember(card.id) { mutableStateOf(false) }
    var technicalError by remember(card.id) { mutableStateOf("") }
    var hydrated by remember(card.id) { mutableStateOf(false) }
    val saveMutex = remember(card.id) { Mutex() }
    LaunchedEffect(note?.id, card.id) {
        if (note != null && current == null && attemptId == 0L && answer.isEmpty() && mode == AnswerMode.CLASSIC) {
            mode = card.modes.firstOrNull { it in availableModes } ?: AnswerMode.CLASSIC
        }
    }
    LaunchedEffect(current?.id, card.id) {
        if (current != null && !hydrated) {
            attemptId = current.id; mode = current.mode
            if (answer.isEmpty()) answer = current.answer
            submitted = current.state == AttemptState.EVALUATED
            automatic = current.automaticOutcome; finalOutcome = current.finalOutcome; feedback = current.feedback
            hydrated = true
        }
    }
    suspend fun saveDraft(): Attempt = saveMutex.withLock {
        hydrated = true
        val now = System.currentTimeMillis()
        val saved = repo.saveAttempt(Attempt(id = attemptId, cardId = card.id, mode = mode, answer = answer,
            state = AttemptState.DRAFT, createdAtMillis = current?.createdAtMillis ?: now, updatedAtMillis = now))
        attemptId = saved.id
        saved
    }
    LaunchedEffect(answer, mode, card.id, busy, submitted) {
        if (!submitted && !busy && (answer.isNotEmpty() || attemptId != 0L)) {
            kotlinx.coroutines.delay(350)
            try { saveDraft() } catch (e: Exception) { technicalError = "Bozza non salvata: ${e.message.orEmpty()}" }
        }
    }
    if (current != null && !hydrated) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Carta ${index + 1} di ${due.size}", style = MaterialTheme.typography.labelLarge)
        StudyFace(question, repo, Modifier.testTag("question"))
        if (AnswerMode.EXACT in card.modes && exactExpected == null && note != null) Text("Confronto esatto non disponibile: la risposta contiene solo media o più parti cloze.", style = MaterialTheme.typography.bodySmall)
        note?.source?.let { source ->
            if (submitted && source.excerpt.isNotBlank()) ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Estratto fonte", fontWeight = FontWeight.Bold)
                    Text(source.excerpt)
                    if (source.title.isNotBlank() || source.page.isNotBlank()) Text(listOf(source.title, source.page).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (availableModes.size > 1 && !submitted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                availableModes.sortedBy { it.ordinal }.forEach { option -> FilterChip(selected = mode == option, onClick = { mode = option; attemptId = 0L }, label = { Text(option.studyLabel()) }) }
            }
        } else Text("Modalità: ${mode.studyLabel()}", style = MaterialTheme.typography.labelMedium)
        if (mode != AnswerMode.CLASSIC) {
            OutlinedTextField(answer, { answer = it }, enabled = !submitted && !busy, label = { Text("La tua risposta") }, minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("answerInput"))
        }
        if (!submitted) {
            Button(enabled = !busy && (mode == AnswerMode.CLASSIC || answer.isNotBlank()), onClick = {
                if (busy) return@Button
                busy = true
                scope.launch {
                    technicalError = ""
                    try {
                        val draft = saveDraft()
                        when (mode) {
                            AnswerMode.CLASSIC -> {
                                val saved = repo.saveAttempt(draft.copy(state = AttemptState.EVALUATED, updatedAtMillis = System.currentTimeMillis()))
                                attemptId = saved.id; submitted = true
                            }
                            AnswerMode.EXACT -> {
                                val outcome = gradeExact(answer, exactExpected ?: textReference)
                                val saved = repo.saveAttempt(draft.copy(automaticOutcome = outcome, finalOutcome = outcome, state = AttemptState.EVALUATED, updatedAtMillis = System.currentTimeMillis()))
                                attemptId = saved.id; automatic = outcome; finalOutcome = outcome; submitted = true
                            }
                            AnswerMode.AI -> {
                                when (val result = ai.evaluate(EvaluationInput(question, textReference, answer, note?.source?.essentialPoints.orEmpty(), note?.source?.excerpt.orEmpty()), apiKey(), model())) {
                                    is EvaluationResult.Success -> {
                                        val outcome = result.evaluation.verdict.toOutcome()
                                        val details = result.evaluation.toStoredFeedback()
                                        val saved = repo.saveAttempt(draft.copy(automaticOutcome = outcome, finalOutcome = outcome, feedback = details, state = AttemptState.EVALUATED, updatedAtMillis = System.currentTimeMillis()))
                                        attemptId = saved.id; automatic = outcome; finalOutcome = outcome; feedback = details; submitted = true
                                    }
                                    is EvaluationResult.Failure -> technicalError = result.message
                                }
                            }
                        }
                    } catch (e: Exception) { technicalError = e.message ?: "Operazione non riuscita. La bozza rimane disponibile." }
                    busy = false
                }
            }, modifier = Modifier.fillMaxWidth().testTag("submitAnswer")) { Text(if (busy) "Attendi…" else if (mode == AnswerMode.CLASSIC) "Mostra risposta" else "Invia risposta") }
            if (mode == AnswerMode.AI) OutlinedButton(enabled = !busy && answer.isNotBlank(), onClick = {
                if (busy) return@OutlinedButton
                busy = true
                scope.launch {
                try {
                    val draft = saveDraft()
                    val saved = repo.saveAttempt(draft.copy(state = AttemptState.EVALUATED, updatedAtMillis = System.currentTimeMillis()))
                    attemptId = saved.id; automatic = null; finalOutcome = null; feedback = ""; submitted = true; technicalError = ""
                } catch (e: Exception) { technicalError = e.message ?: "Operazione non riuscita" }
                busy = false
            } }, modifier = Modifier.testTag("selfAssess")) { Text("Valuta da me") }
        }
        if (technicalError.isNotBlank()) Text(technicalError, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("technicalError"))
        if (submitted) {
            HorizontalDivider()
            Text("Risposta di riferimento", style = MaterialTheme.typography.titleMedium)
            StudyFace(reference, repo, Modifier.testTag("referenceAnswer"))
            if (automatic != null) Text("Esito automatico: ${automatic!!.studyLabel()}")
            val parsed = feedback.parseStoredFeedback()
            if (parsed != null) {
                Text(parsed.explanation)
                if (parsed.errors.isNotEmpty()) Text("Errori: ${parsed.errors.joinToString("; ")}")
                if (parsed.omissions.isNotEmpty()) Text("Omissioni: ${parsed.omissions.joinToString("; ")}")
                if (parsed.sourceConflict) Text("La fonte contraddice la risposta di riferimento: non valutabile.", color = MaterialTheme.colorScheme.error)
            }
            Text("Rettifica l'esito", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Outcome.entries.forEach { outcome ->
                    FilterChip(selected = finalOutcome == outcome, enabled = !busy, onClick = {
                        if (busy) return@FilterChip
                        busy = true
                        scope.launch { try {
                            val latest = repo.saveAttempt(Attempt(id = attemptId, cardId = card.id, mode = mode, answer = answer,
                                automaticOutcome = automatic, finalOutcome = outcome, feedback = feedback, state = AttemptState.EVALUATED,
                                createdAtMillis = current?.createdAtMillis ?: System.currentTimeMillis(), updatedAtMillis = System.currentTimeMillis()))
                            attemptId = latest.id; finalOutcome = outcome
                        } catch (e: Exception) { onError(e.message ?: "Rettifica non salvata") } finally { busy = false } }
                    }, label = { Text(outcome.studyLabel()) })
                }
            }
            if (petEnabled) Kitten(finalOutcome)
            Text("Come vuoi programmare la prossima volta?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Rating.entries.forEach { rating ->
                    FilledTonalButton(enabled = !busy, onClick = {
                        if (busy) return@FilledTonalButton
                        busy = true
                        scope.launch {
                        try { repo.commitReview(attemptId, rating, System.currentTimeMillis()); index++ }
                        catch (e: Exception) { onError(e.message ?: "Ripasso non salvato") }
                        busy = false
                    } }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.testTag("rate-${rating.name}")) { Text(rating.studyLabel(), style = MaterialTheme.typography.labelSmall) }
                }
            }
        } else if (petEnabled) Kitten(null)
    }
}

private fun AnswerMode.studyLabel() = when (this) { AnswerMode.CLASSIC -> "Classica"; AnswerMode.EXACT -> "Esatta"; AnswerMode.AI -> "AI" }
private fun Outcome.studyLabel() = when (this) { Outcome.CORRECT -> "Corretta"; Outcome.PARTIAL -> "Parziale"; Outcome.WRONG -> "Errata"; Outcome.UNGRADABLE -> "Non valutabile" }
private fun Rating.studyLabel() = when (this) { Rating.AGAIN -> "Da rifare"; Rating.HARD -> "Difficile"; Rating.GOOD -> "Buona"; Rating.EASY -> "Facile" }
private fun Verdict.toOutcome() = when (this) { Verdict.CORRECT -> Outcome.CORRECT; Verdict.PARTIAL -> Outcome.PARTIAL; Verdict.WRONG -> Outcome.WRONG; Verdict.UNGRADABLE -> Outcome.UNGRADABLE }

private fun Evaluation.toStoredFeedback(): String = JsonObject(mapOf(
    "explanation" to JsonPrimitive(explanation), "errors" to JsonArray(errors.map(::JsonPrimitive)),
    "omissions" to JsonArray(omissions.map(::JsonPrimitive)), "sourceConflict" to JsonPrimitive(sourceConflict),
)).toString()

internal fun String.parseStoredFeedback(): Evaluation? = try {
    val obj = Json.parseToJsonElement(this) as JsonObject
    Evaluation(Verdict.UNGRADABLE, (obj["explanation"] as JsonPrimitive).content,
        (obj["errors"] as JsonArray).map { (it as JsonPrimitive).content },
        (obj["omissions"] as JsonArray).map { (it as JsonPrimitive).content },
        (obj["sourceConflict"] as JsonPrimitive).content.toBoolean())
} catch (_: Exception) { null }

/** A silent, fixed-size vector pet; static poses also respect reduced-motion settings. */
@Composable
private fun Kitten(outcome: Outcome?) {
    val mood = when (outcome) { Outcome.CORRECT -> "festeggia"; Outcome.PARTIAL, Outcome.WRONG -> "incoraggia"; Outcome.UNGRADABLE -> "pensieroso"; null -> "attende" }
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val reducedMotion = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(outcome, reducedMotion) {
        if (!reducedMotion && outcome != null) {
            scale.snapTo(0.94f); scale.animateTo(1.08f, tween(130)); scale.animateTo(1f, tween(180))
        }
    }
    Canvas(Modifier.size(92.dp).graphicsLayer(scaleX = scale.value, scaleY = scale.value).semantics { contentDescription = "Gattino $mood" }) {
        val black = Color(0xFF171717)
        val white = Color.White
        val s = size.width / 100f
        val ears = Path().apply { moveTo(18*s, 38*s); lineTo(13*s, 5*s); lineTo(39*s, 24*s); moveTo(62*s, 24*s); lineTo(88*s, 5*s); lineTo(82*s, 38*s) }
        drawPath(ears, black, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 14*s))
        drawCircle(black, radius = 40*s, center = Offset(50*s, 54*s))
        if (dark) drawCircle(white, radius = 40*s, center = Offset(50*s, 54*s), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2*s))
        drawCircle(white, radius = 11*s, center = Offset(35*s, 49*s))
        drawCircle(white, radius = 11*s, center = Offset(65*s, 49*s))
        drawCircle(black, radius = 5*s, center = Offset(36*s, 50*s))
        drawCircle(black, radius = 5*s, center = Offset(64*s, 50*s))
        drawCircle(white, radius = 3*s, center = Offset(50*s, 68*s))
        if (outcome == Outcome.CORRECT) {
            drawCircle(white, radius = 2*s, center = Offset(44*s, 76*s))
            drawCircle(white, radius = 2*s, center = Offset(56*s, 76*s))
            drawCircle(white, radius = 3*s, center = Offset(8*s, 19*s))
            drawCircle(white, radius = 3*s, center = Offset(92*s, 19*s))
        } else if (outcome == Outcome.PARTIAL || outcome == Outcome.WRONG) {
            drawLine(white, Offset(29*s, 33*s), Offset(41*s, 38*s), strokeWidth = 2*s)
            drawLine(white, Offset(71*s, 33*s), Offset(59*s, 38*s), strokeWidth = 2*s)
            if (outcome == Outcome.WRONG) drawCircle(white, radius = 3*s, center = Offset(78*s, 66*s))
        } else if (outcome == Outcome.UNGRADABLE) {
            drawCircle(white, radius = 3*s, center = Offset(50*s, 79*s))
        }
    }
}
