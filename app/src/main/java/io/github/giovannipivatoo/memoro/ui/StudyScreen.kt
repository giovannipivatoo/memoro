// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.giovannipivatoo.memoro.ai.*
import io.github.giovannipivatoo.memoro.anki.AnkiRenderer
import io.github.giovannipivatoo.memoro.data.*
import io.github.giovannipivatoo.memoro.study.Fsrs6
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
    deckId: Long?,
    ai: DeepSeekClient,
    apiKey: () -> String,
    model: () -> String,
    petEnabled: Boolean,
    onError: (String) -> Unit,
    onDone: () -> Unit = {},
    practice: Boolean = false,
) {
    var due by remember(deckId, practice) { mutableStateOf<List<Card>>(emptyList()) }
    var loading by remember(deckId, practice) { mutableStateOf(true) }
    var reviewIndex by remember(deckId, practice) { mutableIntStateOf(0) }
    var practiceIndex by androidx.compose.runtime.saveable.rememberSaveable(deckId, practice) { mutableIntStateOf(0) }
    val index = if (practice) practiceIndex else reviewIndex
    LaunchedEffect(deckId, practice) {
        try {
            due = if (practice) repo.snapshot().cards.filter {
                (deckId == null || it.deckId == deckId) && !it.archived && (it.scheduling.importedQueue ?: 0) >= 0
            }.sortedBy { it.id } else repo.dueCards(System.currentTimeMillis(), deckId)
        } catch (e: Exception) { onError(e.message ?: "Impossibile caricare le carte") }
        loading = false
    }
    if (loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val card = due.getOrNull(index)
    if (card == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (petEnabled) MemoroPet(if (due.isEmpty()) null else Outcome.CORRECT, Modifier.size(96.dp))
                Text(if (due.isEmpty()) "Per ora hai finito" else "Sessione completata", style = MaterialTheme.typography.headlineMedium)
                Text(if (due.isEmpty()) { if (practice) "Questo mazzo non contiene carte attive." else "Nessuna carta in scadenza." } else if (practice) "Ripasso libero completato. Le scadenze non sono cambiate." else "Hai ripassato tutte le carte previste.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (deckId == null) "Torna alla home" else "Torna al mazzo") }
            }
        }
        return
    }
    key(card.id, practice) {
        CardStudyContent(repo, card, index, due.size, ai, apiKey, model, petEnabled, practice, onError) { if (practice) practiceIndex++ else reviewIndex++ }
    }
}

@Composable
private fun CardStudyContent(
    repo: MemoroRepository,
    card: Card,
    index: Int,
    dueSize: Int,
    ai: DeepSeekClient,
    apiKey: () -> String,
    model: () -> String,
    petEnabled: Boolean,
    practice: Boolean,
    onError: (String) -> Unit,
    onReviewed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val attempts by produceState<List<Attempt>?>(initialValue = null, card.id) {
        repo.observeAttempts(card.id).collect { value = it }
    }
    val attemptList = attempts
    if (attemptList == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val current = attemptList.firstOrNull { it.isPractice == practice && it.state in setOf(AttemptState.DRAFT, AttemptState.EVALUATED) }
    val note by produceState<Note?>(initialValue = null, card.noteId) { value = repo.getNote(card.noteId) }
    val faces = remember(card, note) { note?.let { AnkiRenderer.render(card, it) } }
    val question = faces?.question ?: card.front
    val reference = faces?.answer ?: card.back
    val textReference = reference.replace(Regex("\\[(image|audio):[^]]+]"), "").trim()
    val exactExpected = note?.let { AnkiRenderer.exactExpected(card, it) }
    val choices = note?.multipleChoice
    val availableModes = (card.modes + AnswerMode.CLASSIC + AnswerMode.WRITTEN).filterTo(mutableSetOf()) {
        when (it) {
            AnswerMode.CLASSIC, AnswerMode.WRITTEN -> true
            AnswerMode.EXACT -> exactExpected != null
            AnswerMode.AI -> textReference.isNotBlank()
            AnswerMode.MULTIPLE_CHOICE -> choices != null
        }
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
    var modeExpanded by remember(card.id) { mutableStateOf(false) }
    var outcomeDialog by remember(card.id) { mutableStateOf(false) }
    var sourceExpanded by remember(card.id) { mutableStateOf(false) }
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
        val saved = repo.saveAttempt(Attempt(id = attemptId, cardId = card.id, mode = mode, answer = answer, isPractice = practice,
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
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${if (practice) "RIPASSO LIBERO" else "STUDIO"}  ·  ${index + 1} / ${dueSize}", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { index.toFloat() / dueSize }, modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("DOMANDA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                StudyFace(question, repo, Modifier.fillMaxWidth().testTag("question"), MaterialTheme.typography.headlineSmall)
            }
        }
        if (AnswerMode.EXACT in card.modes && exactExpected == null && note != null) {
            Text("Confronto esatto non disponibile per questa risposta.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!submitted) {
            if (practice) Text("Esercitati senza cambiare le scadenze.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mode !in availableModes) Text("Questa modalità non è più disponibile per la carta. Scegline un'altra per continuare; la bozza resta salvata.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            if (availableModes.size > 1 || mode !in availableModes) Box {
                OutlinedButton(onClick = { modeExpanded = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Come rispondere: ${mode.studyLabel()}")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                    availableModes.sortedBy { it.ordinal }.forEach { option ->
                        DropdownMenuItem(text = { Text(option.studyLabel()) }, onClick = {
                            modeExpanded = false
                            if (option != mode) {
                                busy = true
                                scope.launch {
                                    var newDraftSaved = false
                                    try {
                                        saveMutex.withLock {
                                            val previousId = attemptId
                                            val now = System.currentTimeMillis()
                                            val saved = repo.saveAttempt(Attempt(cardId = card.id, mode = option, answer = answer, isPractice = practice,
                                                state = AttemptState.DRAFT, createdAtMillis = current?.createdAtMillis ?: now, updatedAtMillis = now))
                                            attemptId = saved.id; mode = option; hydrated = true
                                            newDraftSaved = true
                                            if (previousId != 0L) repo.deleteAttemptPersonalData(previousId)
                                        }
                                    } catch (e: Exception) { technicalError = if (newDraftSaved) "Modalità salvata; vecchia bozza non rimossa" else e.message ?: "Modalità non cambiata" }
                                    finally { busy = false }
                                }
                            }
                        })
                    }
                }
            } else Text("Come rispondere: ${mode.studyLabel()}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mode == AnswerMode.MULTIPLE_CHOICE && choices != null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Scegli una risposta", style = MaterialTheme.typography.titleMedium)
                    choices.options.forEachIndexed { optionIndex, option ->
                        Surface(shape = RoundedCornerShape(18.dp),
                            color = if (answer == option) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().selectable(selected = answer == option, enabled = !busy,
                                role = Role.RadioButton, onClick = { answer = option }).testTag("choice-$optionIndex").padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = answer == option, onClick = null, enabled = !busy)
                                Spacer(Modifier.width(10.dp))
                                Text(option, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else if (mode != AnswerMode.CLASSIC) {
                if (mode == AnswerMode.WRITTEN) Text("Scrivi ciò che ricordi, poi confronta la risposta e valuta tu.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(answer, { answer = it }, enabled = !busy, label = { Text("La tua risposta") }, minLines = 3,
                    shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().testTag("answerInput"))
            }
            Button(enabled = !busy && mode in availableModes && (mode == AnswerMode.CLASSIC || (answer.isNotBlank() && (mode != AnswerMode.MULTIPLE_CHOICE || answer in choices?.options.orEmpty()))), onClick = {
                if (busy) return@Button
                busy = true
                scope.launch {
                    technicalError = ""
                    try {
                        val draft = saveDraft()
                        when (mode) {
                            AnswerMode.CLASSIC, AnswerMode.WRITTEN -> {
                                val saved = repo.saveAttempt(draft.copy(state = AttemptState.EVALUATED, updatedAtMillis = System.currentTimeMillis()))
                                attemptId = saved.id; submitted = true
                            }
                            AnswerMode.EXACT, AnswerMode.MULTIPLE_CHOICE -> {
                                val expected = if (mode == AnswerMode.MULTIPLE_CHOICE) requireNotNull(choices).let { it.options[it.correctIndex] } else requireNotNull(exactExpected)
                                val outcome = gradeExact(answer, expected)
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
            }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("submitAnswer")) {
                Text(if (busy) "Attendi…" else if (mode == AnswerMode.CLASSIC) "Mostra risposta" else "Invia risposta")
            }
            if (mode == AnswerMode.AI) OutlinedButton(enabled = !busy && answer.isNotBlank() && mode in availableModes, onClick = {
                if (busy) return@OutlinedButton
                busy = true
                scope.launch {
                    try {
                        val draft = saveDraft()
                        val saved = repo.saveAttempt(draft.copy(state = AttemptState.EVALUATED, updatedAtMillis = System.currentTimeMillis()))
                        attemptId = saved.id; automatic = null; finalOutcome = null; feedback = ""; submitted = true; technicalError = ""
                    } catch (e: Exception) { technicalError = e.message ?: "Operazione non riuscita" }
                    busy = false
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("selfAssess")) { Text("Valuta da me") }
        }
        if (technicalError.isNotBlank()) Text(technicalError, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("technicalError"))
        if (submitted) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("RISPOSTA DI RIFERIMENTO", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    StudyFace(reference, repo, Modifier.fillMaxWidth().testTag("referenceAnswer"), MaterialTheme.typography.titleLarge)
                    if (mode != AnswerMode.CLASSIC && answer.isNotBlank()) Text("La tua risposta: $answer", style = MaterialTheme.typography.bodyLarge)
                    if (automatic != null) Text("Esito automatico: ${automatic!!.studyLabel()}", style = MaterialTheme.typography.bodyMedium)
                    if (finalOutcome != null && finalOutcome != automatic) Text("Esito finale: ${finalOutcome!!.studyLabel()}", fontWeight = FontWeight.SemiBold)
                    val parsed = feedback.parseStoredFeedback()
                    if (parsed != null) {
                        if (parsed.explanation.isNotBlank()) Text(parsed.explanation)
                        if (parsed.errors.isNotEmpty()) Text("Errori: ${parsed.errors.joinToString("; ")}")
                        if (parsed.omissions.isNotEmpty()) Text("Omissioni: ${parsed.omissions.joinToString("; ")}")
                        if (parsed.sourceConflict) Text("La fonte contraddice la risposta di riferimento: non valutabile.", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { outcomeDialog = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (finalOutcome == null) "Valuta la tua risposta" else "Modifica esito")
                    }
                }
            }
            note?.source?.takeIf { it.excerpt.isNotBlank() }?.let { source ->
                OutlinedButton(onClick = { sourceExpanded = !sourceExpanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (sourceExpanded) "Nascondi fonte" else "Vedi fonte")
                }
                if (sourceExpanded) Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Estratto fonte", fontWeight = FontWeight.Bold)
                        Text(source.excerpt)
                        if (source.title.isNotBlank() || source.page.isNotBlank()) Text(listOf(source.title, source.page).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (petEnabled) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MemoroPet(finalOutcome, Modifier.size(96.dp))
            }
            if (practice) {
                Button(enabled = !busy, onClick = {
                    if (busy) return@Button
                    busy = true
                    scope.launch {
                        try { repo.finishPractice(attemptId, System.currentTimeMillis()); onReviewed() }
                        catch (e: Exception) { onError(e.message ?: "Esercitazione non salvata") }
                        finally { busy = false }
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("nextPractice")) {
                    Text(if (index + 1 == dueSize) "Completa ripasso libero" else "Prossima carta")
                }
                Text("La risposta resta in cronologia. La scadenza della carta non cambia.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
            Text("Quanto è stato facile ricordarla?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val previewAt = remember(card.id, submitted) { System.currentTimeMillis() }
            val estimates = remember(card.scheduling, previewAt) { Rating.entries.associateWith { rating ->
                runCatching { (Fsrs6.review(card.scheduling, rating, previewAt).dueAtMillis - previewAt).coerceAtLeast(0) }
                    .getOrNull()?.let(::studyInterval) ?: "—"
            } }
            Rating.entries.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { rating ->
                        FilledTonalButton(enabled = !busy, onClick = {
                            if (busy) return@FilledTonalButton
                            busy = true
                            scope.launch {
                                try { repo.commitReview(attemptId, rating, System.currentTimeMillis()); onReviewed() }
                                catch (e: Exception) { onError(e.message ?: "Ripasso non salvato") }
                                finally { busy = false }
                            }
                        }, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(12.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp).testTag("rate-${rating.name}")) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(rating.studyLabel(), style = MaterialTheme.typography.labelLarge)
                                Text(estimates[rating].orEmpty(), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            }
        } else if (petEnabled) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MemoroPet(null, Modifier.size(96.dp))
        }
    }
    if (outcomeDialog) AlertDialog(onDismissRequest = { outcomeDialog = false }, title = { Text("Modifica esito") },
        text = {
            Column {
                Outcome.entries.forEach { outcome ->
                    TextButton(enabled = !busy, onClick = {
                        if (busy) return@TextButton
                        busy = true
                        scope.launch {
                            try {
                                val latest = repo.saveAttempt(Attempt(id = attemptId, cardId = card.id, mode = mode, answer = answer, isPractice = practice,
                                    automaticOutcome = automatic, finalOutcome = outcome, feedback = feedback, state = AttemptState.EVALUATED,
                                    createdAtMillis = current?.createdAtMillis ?: System.currentTimeMillis(), updatedAtMillis = System.currentTimeMillis()))
                                attemptId = latest.id; finalOutcome = outcome; outcomeDialog = false
                            } catch (e: Exception) { onError(e.message ?: "Rettifica non salvata") }
                            finally { busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(outcome.studyLabel()) }
                }
            }
        }, confirmButton = { TextButton(onClick = { outcomeDialog = false }) { Text("Chiudi") } })
}

private fun AnswerMode.studyLabel() = when (this) { AnswerMode.CLASSIC -> "Classica"; AnswerMode.EXACT -> "Esatta"; AnswerMode.AI -> "AI"; AnswerMode.WRITTEN -> "Scritta"; AnswerMode.MULTIPLE_CHOICE -> "Scelta multipla" }
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

private fun studyInterval(millis: Long): String = when {
    millis < 60_000 -> "< 1 min"
    millis < 3_600_000 -> "${(millis / 60_000).coerceAtLeast(1)} min"
    millis < 86_400_000 -> (millis / 3_600_000).coerceAtLeast(1).let { "$it ${if (it == 1L) "ora" else "ore"}" }
    else -> (millis / 86_400_000).coerceAtLeast(1).let { "$it ${if (it == 1L) "giorno" else "giorni"}" }
}
