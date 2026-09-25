// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.giovannipivatoo.memoro.anki.AnkiRenderer
import io.github.giovannipivatoo.memoro.data.AnswerMode
import io.github.giovannipivatoo.memoro.data.Card as StudyCard
import io.github.giovannipivatoo.memoro.data.Deck
import io.github.giovannipivatoo.memoro.data.MemoroRepository
import io.github.giovannipivatoo.memoro.data.Note
import io.github.giovannipivatoo.memoro.data.NoteKind
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val cardShape = RoundedCornerShape(24.dp)

@Composable
internal fun DecksScreen(
    repo: MemoroRepository,
    importApkg: suspend (Uri) -> String,
    petEnabled: Boolean,
    onOpen: (Long) -> Unit,
    onStudy: () -> Unit,
    onError: (String) -> Unit,
) {
    val decks by remember(repo) { repo.observeDecks() }.collectAsState(initial = emptyList())
    var dueCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var nextDueAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(decks) {
        val now = System.currentTimeMillis()
        dueCounts = repo.dueCards(now).groupingBy { it.deckId }.eachCount()
        nextDueAt = repo.snapshot().cards.asSequence().filter { !it.archived && (it.scheduling.importedQueue ?: 0) >= 0 && it.scheduling.dueAtMillis > now }.minOfOrNull { it.scheduling.dueAtMillis }
    }
    val dueTotal = dueCounts.values.sum()
    var editing by remember { mutableStateOf<Deck?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Deck?>(null) }
    var savingDeck by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importReport by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importing) {
            importing = true
            scope.launch { try { importReport = importApkg(uri) } catch (e: Exception) { importReport = e.message ?: "Importazione non riuscita" } finally { importing = false } }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = cardShape, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (dueTotal > 0) "Pronte per te" else "Tutto in ordine", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(if (dueTotal > 0) "$dueTotal ${if (dueTotal == 1) "carta" else "carte"} da ripassare" else if (decks.isEmpty()) "Il tuo percorso inizia qui" else "Per oggi hai finito", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                        Text(if (dueTotal > 0) "Bastano pochi minuti per fare progressi." else nextDueAt?.let { "Prossimo ripasso: ${DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.ITALIAN).format(Date(it))}" } ?: if (decks.isEmpty()) "Crea un mazzo o importa le tue carte." else "Aggiungi una nota per continuare a imparare.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (dueTotal > 0) Button(onClick = onStudy, modifier = Modifier.testTag("startReview")) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Inizia ripasso")
                        }
                    }
                    if (petEnabled) MemoroPet(null, Modifier.size(86.dp))
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("I tuoi mazzi (${decks.size})", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { creating = true }, modifier = Modifier.testTag("addDeck")) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Nuovo") }
            }
        }
        if (decks.isEmpty()) item {
            ElevatedCard(shape = cardShape, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Un posto per ciò che vuoi ricordare", style = MaterialTheme.typography.titleMedium)
                    Text("Raccogli le carte per materia, progetto o curiosità. Potrai studiarle quando vuoi.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { creating = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Crea un mazzo") }
                }
            }
        } else {
            items(decks, key = { it.id }) { deck ->
                var menu by remember { mutableStateOf(false) }
                ElevatedCard(onClick = { onOpen(deck.id) }, shape = cardShape, modifier = Modifier.fillMaxWidth().testTag("deck-${deck.id}")) {
                    Row(Modifier.padding(start = 20.dp, top = 16.dp, end = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(deck.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (deck.description.isNotBlank()) Text(deck.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${dueCounts[deck.id] ?: 0} da ripassare", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Opzioni mazzo ${deck.name}") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Modifica") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; editing = deck })
                                DropdownMenuItem(text = { Text("Elimina") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; deleting = deck })
                            }
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(enabled = !importing, onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth().testTag("importHome")) {
                Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text(if (importing) "Importazione…" else "Importa da Anki (.apkg)")
            }
        }
    }
    if (creating || editing != null) DeckDialog(editing, savingDeck, onDismiss = { creating = false; editing = null }, onSave = { deck ->
        if (!savingDeck) {
            savingDeck = true
            scope.launch { try { repo.saveDeck(deck); creating = false; editing = null } catch (e: Exception) { onError(e.message ?: "Mazzo non salvato") } finally { savingDeck = false } }
        }
    })
    deleting?.let { deck -> DeleteDialog("Eliminare «${deck.name}»?", "Saranno eliminate anche le sue note e carte.", onDismiss = { deleting = null }) {
        scope.launch { try { repo.deleteDeck(deck.id); deleting = null } catch (e: Exception) { onError(e.message ?: "Eliminazione non riuscita") } }
    } }
    importReport?.let { report -> AlertDialog(onDismissRequest = { importReport = null }, title = { Text("Importazione Anki") }, text = {
        Box(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) { Text(report) }
    }, confirmButton = { TextButton(onClick = { importReport = null }) { Text("Chiudi") } }) }
}

@Composable
private fun DeckDialog(original: Deck?, saving: Boolean, onDismiss: () -> Unit, onSave: (Deck) -> Unit) {
    var name by rememberSaveable(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var description by rememberSaveable(original?.id) { mutableStateOf(original?.description.orEmpty()) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(if (original == null) "Nuovo mazzo" else "Modifica mazzo") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome del mazzo") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("deckName"))
            OutlinedTextField(description, { description = it }, label = { Text("Descrizione (facoltativa)") }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { Button(enabled = name.isNotBlank() && !saving, onClick = { onSave((original ?: Deck(name = name.trim())).copy(name = name.trim(), description = description.trim())) }) { Text(if (saving) "Salvataggio…" else "Salva") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Annulla") } })
}

@Composable
internal fun DeleteDialog(title: String, body: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) }, confirmButton = { TextButton(onClick = onDelete) { Text("Elimina") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

@Composable
internal fun DeckScreen(repo: MemoroRepository, deckId: Long, onEdit: (Long) -> Unit, onStudy: () -> Unit, onError: (String) -> Unit) {
    val notes by remember(deckId) { repo.observeNotes(deckId) }.collectAsState(initial = emptyList())
    val cards by remember(deckId) { repo.observeCards(deckId) }.collectAsState(initial = emptyList())
    val foreignNotes by produceState<List<Note>>(emptyList(), cards, notes) {
        val localIds = notes.map { it.id }.toSet()
        value = cards.map { it.noteId }.distinct().filter { it !in localIds }.mapNotNull { repo.getNote(it) }
    }
    val displayNotes = (notes + foreignNotes).distinctBy { it.id }
    val deck by produceState<Deck?>(null, deckId) { value = repo.getDeck(deckId) }
    var query by rememberSaveable(deckId) { mutableStateOf("") }
    var selectionMode by rememberSaveable(deckId) { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var modeTargets by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var deleting by remember { mutableStateOf<Note?>(null) }
    var savingModes by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    val due = cards.count { !it.archived && (it.scheduling.importedQueue ?: 0) >= 0 && it.scheduling.dueAtMillis <= now }
    val visibleNotes = displayNotes.filter { note -> query.isBlank() || note.fields.any { it.contains(query, ignoreCase = true) } }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(deck?.description?.takeIf { it.isNotBlank() } ?: "Le tue note, pronte quando lo sei tu.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$due ${if (due == 1) "carta pronta" else "carte pronte"} · ${displayNotes.size} ${if (displayNotes.size == 1) "nota" else "note"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Button(enabled = due > 0 && !savingModes, onClick = onStudy, modifier = Modifier.fillMaxWidth().testTag("studyDeck")) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (savingModes) "Salvataggio…" else if (due > 0) "Studia ora" else "Nessuna carta pronta")
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Note", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { selectionMode = !selectionMode; selected = emptySet() }, enabled = cards.isNotEmpty() && !savingModes) { Text(if (selectionMode) "Fine" else "Seleziona") }
                }
            }
            item {
                OutlinedTextField(query, { query = it }, label = { Text("Cerca nelle note") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (displayNotes.isEmpty()) item {
                ElevatedCard(shape = cardShape) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Questo mazzo è ancora vuoto", style = MaterialTheme.typography.titleMedium)
                    Text("Aggiungi una nota: Memoro creerà le carte da studiare.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            if (displayNotes.isNotEmpty() && visibleNotes.isEmpty()) item { Text("Nessuna nota trovata.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(visibleNotes, key = { it.id }) { note ->
                val related = cards.filter { it.noteId == note.id && !it.archived }
                var noteMenu by remember { mutableStateOf(false) }
                ElevatedCard(shape = cardShape, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(note.preview(related), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${note.kind.label()} · ${related.size} ${if (related.size == 1) "carta" else "carte"}${if (note.deckId != deckId) " · nota in un altro mazzo" else ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box {
                                IconButton(onClick = { noteMenu = true }) { Icon(Icons.Default.MoreVert, "Opzioni nota") }
                                DropdownMenu(expanded = noteMenu, onDismissRequest = { noteMenu = false }) {
                                    DropdownMenuItem(text = { Text("Modifica nota") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { noteMenu = false; onEdit(note.id) })
                                    DropdownMenuItem(text = { Text(if (note.deckId != deckId) "Elimina nota da tutti i mazzi" else "Elimina nota") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { noteMenu = false; deleting = note })
                                }
                            }
                        }
                        related.forEach { card ->
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectionMode) Checkbox(checked = card.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + card.id else selected - card.id }, modifier = Modifier.testTag("selectCard-${card.id}"))
                                Column(Modifier.weight(1f)) {
                                    Text("Carta ${card.ordinal + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(card.modes.joinToString(" · ") { it.label() }, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (!selectionMode) {
                                    var cardMenu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { cardMenu = true }, modifier = Modifier.testTag("cardMode-${card.id}")) { Icon(Icons.Default.MoreVert, "Opzioni carta ${card.ordinal + 1}") }
                                        DropdownMenu(expanded = cardMenu, onDismissRequest = { cardMenu = false }) {
                                            DropdownMenuItem(text = { Text("Modalità risposta") }, onClick = { cardMenu = false; modeTargets = setOf(card.id) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode && selected.isNotEmpty()) {
                    Text("${selected.size} selezionate", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Button(enabled = !savingModes, onClick = { modeTargets = selected }) { Text("Imposta modalità") }
                } else {
                    Button(onClick = { onEdit(0) }, modifier = Modifier.fillMaxWidth().testTag("addNote")) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Nuova nota") }
                }
            }
        }
    }
    val targets = cards.filter { it.id in modeTargets }
    if (targets.isNotEmpty()) ModeDialog(targets, displayNotes, savingModes, onDismiss = { modeTargets = emptySet() }, onApply = { mode ->
        if (!savingModes) {
            savingModes = true
            scope.launch {
                try {
                    targets.forEach { card -> repo.getCard(card.id)?.let { repo.saveCard(it.copy(modes = setOf(mode))) } }
                    modeTargets = emptySet(); selected = emptySet(); selectionMode = false
                } catch (e: Exception) { onError(e.message ?: "Modalità non salvata") }
                finally { savingModes = false }
            }
        }
    })
    deleting?.let { note -> DeleteDialog("Eliminare questa nota?", "Saranno eliminate anche le sue carte e la cronologia associata.", onDismiss = { deleting = null }) {
        scope.launch { try { repo.deleteNote(note.id); deleting = null } catch (e: Exception) { onError(e.message ?: "Eliminazione non riuscita") } }
    } }
}

@Composable
private fun ModeDialog(targets: List<StudyCard>, notes: List<Note>, saving: Boolean, onDismiss: () -> Unit, onApply: (AnswerMode) -> Unit) {
    var choice by remember(targets.map { it.id }) { mutableStateOf(targets.singleOrNull()?.modes?.singleOrNull() ?: AnswerMode.CLASSIC) }
    val noteById = notes.associateBy { it.id }
    fun incompatible(mode: AnswerMode): Int = targets.count { card ->
        val note = noteById[card.noteId]
        when (mode) {
            AnswerMode.CLASSIC -> false
            AnswerMode.EXACT -> note == null || AnkiRenderer.exactExpected(card, note) == null
            AnswerMode.AI -> note == null || AnkiRenderer.render(card, note).answer.replace(Regex("\\[(image|audio):[^]]+]"), "").isBlank()
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(if (targets.size == 1) "Modalità risposta" else "Modalità per ${targets.size} carte") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AnswerMode.entries.forEach { mode ->
                val count = incompatible(mode)
                val eligible = count == 0
                Row(Modifier.fillMaxWidth().selectable(selected = choice == mode, enabled = eligible && !saving, role = Role.RadioButton, onClick = { choice = mode }).testTag("mode-${mode.name}"), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = choice == mode, onClick = null, enabled = eligible && !saving)
                    Column {
                        Text(mode.label(), style = MaterialTheme.typography.bodyLarge)
                        Text(if (eligible) mode.description() else "$count ${if (count == 1) "carta non compatibile" else "carte non compatibili"}: serve una risposta testuale chiara", style = MaterialTheme.typography.bodySmall, color = if (eligible) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }, confirmButton = { Button(enabled = !saving && incompatible(choice) == 0, onClick = { onApply(choice) }) { Text("Salva modalità") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Annulla") } })
}

internal fun NoteKind.label() = when (this) { NoteKind.BASIC -> "Base"; NoteKind.REVERSE -> "Fronte e inversa"; NoteKind.CLOZE -> "Cloze" }
internal fun AnswerMode.label() = when (this) { AnswerMode.CLASSIC -> "Classica"; AnswerMode.EXACT -> "Esatta"; AnswerMode.AI -> "AI" }
private fun AnswerMode.description() = when (this) {
    AnswerMode.CLASSIC -> "Guarda la risposta e valuta tu"
    AnswerMode.EXACT -> "Scrivi la risposta; confronto locale"
    AnswerMode.AI -> "Scrivi la risposta; valutazione DeepSeek"
}

private fun Note.preview(cards: List<StudyCard>): String =
    (cards.firstOrNull()?.let { AnkiRenderer.render(it, this).question } ?: fields.firstOrNull().orEmpty())
        .replace(Regex("\\[(?:image|audio):[^]]+]")) { if (it.value.startsWith("[image:")) "Immagine" else "Audio" }
        .replace(Regex("\\s+"), " ").trim().ifBlank { "Nota senza titolo" }
