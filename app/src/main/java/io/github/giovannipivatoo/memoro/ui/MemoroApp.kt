// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ui

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.giovannipivatoo.memoro.ai.DeepSeekClient
import io.github.giovannipivatoo.memoro.anki.AnkiRenderer
import io.github.giovannipivatoo.memoro.ai.EvaluationInput
import io.github.giovannipivatoo.memoro.ai.EvaluationResult
import io.github.giovannipivatoo.memoro.data.*
import kotlinx.coroutines.launch
import java.util.UUID

private sealed interface Page {
    data object Decks : Page
    data class DeckDetail(val id: Long) : Page
    data class NoteEditor(val deckId: Long, val noteId: Long = 0) : Page
    data class Study(val deckId: Long) : Page
    data object History : Page
    data object Settings : Page
}

private val pageSaver = listSaver<Page, String>(
    save = { page -> when (page) {
        Page.Decks -> listOf("decks")
        Page.History -> listOf("history")
        Page.Settings -> listOf("settings")
        is Page.DeckDetail -> listOf("deck", page.id.toString())
        is Page.NoteEditor -> listOf("note", page.deckId.toString(), page.noteId.toString())
        is Page.Study -> listOf("study", page.deckId.toString())
    } },
    restore = { parts -> when (parts.firstOrNull()) {
        "history" -> Page.History
        "settings" -> Page.Settings
        "deck" -> Page.DeckDetail(parts[1].toLong())
        "note" -> Page.NoteEditor(parts[1].toLong(), parts[2].toLong())
        "study" -> Page.Study(parts[1].toLong())
        else -> Page.Decks
    } },
)

/** Settings and package actions are supplied by the Android host; all study state lives in the repository. */
data class AppActions(
    val apiKey: () -> String,
    val saveApiKey: (String) -> Unit,
    val model: () -> String,
    val saveModel: (String) -> Unit,
    val petEnabled: () -> Boolean,
    val savePetEnabled: (Boolean) -> Unit,
    val importApkg: suspend (Uri) -> String,
    val exportApkg: suspend (Uri) -> String,
    val backup: suspend (Uri) -> String,
    val restore: suspend (Uri) -> String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoroApp(repo: MemoroRepository, actions: AppActions, ai: DeepSeekClient = remember { DeepSeekClient() }) {
    var page by rememberSaveable(stateSaver = pageSaver) { mutableStateOf<Page>(Page.Decks) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun runAction(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Operazione non riuscita") } } }
    val title = when (page) {
        Page.Decks -> "Mazzi"
        is Page.DeckDetail -> "Carte e note"
        is Page.NoteEditor -> "Modifica nota"
        is Page.Study -> "Studio"
        Page.History -> "Cronologia"
        Page.Settings -> "Impostazioni"
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) }, navigationIcon = {
                if (page !is Page.Decks && page !is Page.History && page !is Page.Settings) IconButton(onClick = {
                    page = when (val p = page) {
                        is Page.NoteEditor -> Page.DeckDetail(p.deckId)
                        is Page.Study -> Page.DeckDetail(p.deckId)
                        else -> Page.Decks
                    }
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = page is Page.Decks, onClick = { page = Page.Decks }, icon = { Text("▦") }, label = { Text("Mazzi") })
                NavigationBarItem(selected = page is Page.History, onClick = { page = Page.History }, icon = { Text("◷") }, label = { Text("Cronologia") })
                NavigationBarItem(selected = page is Page.Settings, onClick = { page = Page.Settings }, icon = { Text("⚙") }, label = { Text("Impostazioni") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val p = page) {
                Page.Decks -> DecksScreen(repo, onOpen = { page = Page.DeckDetail(it) }, onError = { runAction { snackbar.showSnackbar(it) } })
                is Page.DeckDetail -> DeckScreen(repo, p.id, onEdit = { page = Page.NoteEditor(p.id, it) }, onStudy = { page = Page.Study(p.id) }, onError = { runAction { snackbar.showSnackbar(it) } })
                is Page.NoteEditor -> NoteEditorScreen(repo, p.deckId, p.noteId, onDone = { page = Page.DeckDetail(p.deckId) }, onError = { runAction { snackbar.showSnackbar(it) } })
                is Page.Study -> StudyScreen(repo, p.deckId, ai, actions.apiKey, actions.model, actions.petEnabled(), onError = { runAction { snackbar.showSnackbar(it) } })
                Page.History -> HistoryScreen(repo)
                Page.Settings -> SettingsScreen(actions, onMessage = { runAction { snackbar.showSnackbar(it) } })
            }
        }
    }
}

@Composable
private fun DecksScreen(repo: MemoroRepository, onOpen: (Long) -> Unit, onError: (String) -> Unit) {
    val decks by repo.observeDecks().collectAsState(initial = emptyList())
    var dueCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    LaunchedEffect(decks) {
        val now = System.currentTimeMillis()
        dueCounts = decks.associate { it.id to repo.dueCards(now, it.id).size }
    }
    var editing by remember { mutableStateOf<Deck?>(null) }
    var creating by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<Deck?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("I tuoi mazzi", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            FilledIconButton(onClick = { creating = true }, modifier = Modifier.testTag("addDeck")) { Icon(Icons.Default.Add, "Nuovo mazzo") }
        }
        if (decks.isEmpty()) Text("Crea un mazzo per iniziare.", modifier = Modifier.padding(top = 24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(decks, key = { it.id }) { deck ->
                ElevatedCard(onClick = { onOpen(deck.id) }, modifier = Modifier.fillMaxWidth().testTag("deck-${deck.id}")) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(deck.name, style = MaterialTheme.typography.titleMedium); if (deck.description.isNotBlank()) Text(deck.description); Text("In scadenza: ${dueCounts[deck.id] ?: 0}", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { editing = deck }) { Text("Modifica") }
                        IconButton(onClick = { delete = deck }) { Icon(Icons.Default.Delete, "Elimina mazzo") }
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        val original = editing
        var name by remember(original, creating) { mutableStateOf(original?.name.orEmpty()) }
        var description by remember(original, creating) { mutableStateOf(original?.description.orEmpty()) }
        AlertDialog(onDismissRequest = { creating = false; editing = null }, title = { Text(if (original == null) "Nuovo mazzo" else "Modifica mazzo") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("deckName"))
                OutlinedTextField(description, { description = it }, label = { Text("Descrizione") }, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            scope.launch { try { repo.saveDeck((original ?: Deck(name = name.trim())).copy(name = name.trim(), description = description.trim())); creating = false; editing = null } catch (e: Exception) { onError(e.message ?: "Salvataggio non riuscito") } }
        }) { Text("Salva") } }, dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Annulla") } })
    }
    delete?.let { deck -> ConfirmDelete("Eliminare il mazzo «${deck.name}» e tutte le sue note?", onDismiss = { delete = null }) {
        scope.launch { try { repo.deleteDeck(deck.id); delete = null } catch (e: Exception) { onError(e.message ?: "Eliminazione non riuscita") } }
    } }
}

@Composable
private fun ConfirmDelete(message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Conferma eliminazione") }, text = { Text(message) }, confirmButton = { TextButton(onClick = onConfirm) { Text("Elimina") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

@Composable
private fun DeckScreen(repo: MemoroRepository, deckId: Long, onEdit: (Long) -> Unit, onStudy: () -> Unit, onError: (String) -> Unit) {
    val notes by remember(deckId) { repo.observeNotes(deckId) }.collectAsState(initial = emptyList())
    val cards by remember(deckId) { repo.observeCards(deckId) }.collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var delete by remember { mutableStateOf<Note?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${notes.size} note · ${cards.size} carte", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onStudy, modifier = Modifier.testTag("studyDeck")) { Text("Studia") }
            FilledIconButton(onClick = { onEdit(0) }, modifier = Modifier.testTag("addNote")) { Icon(Icons.Default.Add, "Nuova nota") }
        }
        if (selected.isNotEmpty()) {
            Text("Modalità per ${selected.size} carte")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AnswerMode.entries.forEach { mode ->
                    val targets = cards.filter { it.id in selected }
                    val allHave = targets.isNotEmpty() && targets.all { mode in it.modes }
                    FilterChip(selected = allHave, onClick = { scope.launch {
                        try {
                            targets.forEach { card ->
                                val note = notes.firstOrNull { it.id == card.noteId }
                                if (mode == AnswerMode.EXACT && (note == null || AnkiRenderer.exactExpected(card, note) == null)) return@forEach
                                if (mode == AnswerMode.AI && card.back.replace(Regex("\\[(image|audio):[^]]+]"), "").isBlank()) return@forEach
                                val next = if (allHave) card.modes - mode else card.modes + mode
                                repo.saveCard(card.copy(modes = next.ifEmpty { setOf(AnswerMode.CLASSIC) }))
                            }
                        } catch (e: Exception) { onError(e.message ?: "Aggiornamento non riuscito") }
                    } }, label = { Text(mode.label()) })
                }
            }
        }
        if (notes.isEmpty()) Text("Aggiungi una nota. Puoi scegliere fronte/retro, inversa o cloze.", modifier = Modifier.padding(top = 24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notes, key = { it.id }) { note ->
                val related = cards.filter { it.noteId == note.id }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(note.fields.firstOrNull().orEmpty().ifBlank { "Nota senza titolo" }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text("${note.kind.label()} · ${related.size} carte", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onEdit(note.id) }) { Text("Modifica") }
                            IconButton(onClick = { delete = note }) { Icon(Icons.Default.Delete, "Elimina nota") }
                        }
                        related.forEach { card ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = card.id in selected, onCheckedChange = { selected = if (it) selected + card.id else selected - card.id }, modifier = Modifier.testTag("selectCard-${card.id}"))
                                Text("Carta ${card.ordinal + 1}: ${card.modes.joinToString { it.label() }}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    delete?.let { note -> ConfirmDelete("Eliminare questa nota e le sue carte?", onDismiss = { delete = null }) {
        scope.launch { try { repo.deleteNote(note.id); delete = null } catch (e: Exception) { onError(e.message ?: "Eliminazione non riuscita") } }
    } }
}

private fun NoteKind.label() = when (this) { NoteKind.BASIC -> "Base"; NoteKind.REVERSE -> "Inversa"; NoteKind.CLOZE -> "Cloze" }
private fun AnswerMode.label() = when (this) { AnswerMode.CLASSIC -> "Classica"; AnswerMode.EXACT -> "Esatta"; AnswerMode.AI -> "AI" }
private fun Outcome.label() = when (this) { Outcome.CORRECT -> "Corretta"; Outcome.PARTIAL -> "Parziale"; Outcome.WRONG -> "Errata"; Outcome.UNGRADABLE -> "Non valutabile" }

@Composable
private fun NoteEditorScreen(repo: MemoroRepository, deckId: Long, noteId: Long, onDone: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by rememberSaveable(noteId) { mutableStateOf(false) }
    var originalLoaded by remember(noteId) { mutableStateOf(false) }
    var original by remember(noteId) { mutableStateOf<Note?>(null) }
    var kind by rememberSaveable(noteId) { mutableStateOf(NoteKind.BASIC) }
    var front by rememberSaveable(noteId) { mutableStateOf("") }
    var back by rememberSaveable(noteId) { mutableStateOf("") }
    var source by rememberSaveable(noteId) { mutableStateOf("") }
    var sourceTitle by rememberSaveable(noteId) { mutableStateOf("") }
    var sourceUrl by rememberSaveable(noteId) { mutableStateOf("") }
    var sourcePage by rememberSaveable(noteId) { mutableStateOf("") }
    var essential by rememberSaveable(noteId) { mutableStateOf("") }
    var mediaTarget by remember { mutableStateOf(true) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { try {
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (!mime.startsWith("image/") && !mime.startsWith("audio/")) { onError("Scegli un'immagine o un audio."); return@launch }
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
                ?: if (mime.startsWith("image/")) "png" else "mp3"
            val path = "media/${UUID.randomUUID()}.$extension"
            val file = context.contentResolver.openInputStream(uri)?.use { repo.putFile(path, it, mime) } ?: error("File non leggibile")
            val marker = if (mime.startsWith("image/")) "[image:${file.path}]" else "[audio:${file.path}]"
            if (mediaTarget) front += " $marker" else back += " $marker"
        } catch (e: Exception) { onError(e.message ?: "Media non salvato") } }
    }
    LaunchedEffect(noteId) {
        if (noteId != 0L) repo.getNote(noteId)?.let { note ->
            original = note
            if (!loaded) {
                kind = note.kind; front = note.fields.getOrNull(0).orEmpty(); back = note.fields.getOrNull(1).orEmpty()
                source = note.source.excerpt; sourceTitle = note.source.title; sourceUrl = note.source.url; sourcePage = note.source.page; essential = note.source.essentialPoints
            }
        }
        loaded = true
        originalLoaded = true
    }
    if (!originalLoaded) { CircularProgressIndicator(); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tipo di nota", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NoteKind.entries.forEach { option -> FilterChip(selected = kind == option, enabled = original?.anki == null, onClick = { kind = option }, label = { Text(option.label()) }) } }
        if (original?.anki != null) Text("Il tipo delle note importate resta fisso per conservare il modello Anki.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(front, { front = it }, label = { Text(if (kind == NoteKind.CLOZE) "Testo con {{c1::risposta}}" else "Fronte") }, modifier = Modifier.fillMaxWidth().testTag("noteFront"), minLines = 3)
        if (kind != NoteKind.CLOZE) OutlinedTextField(back, { back = it }, label = { Text("Retro") }, modifier = Modifier.fillMaxWidth().testTag("noteBack"), minLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { mediaTarget = true; mediaPicker.launch(arrayOf("image/*", "audio/*")) }) { Text("Media nel fronte") }
            OutlinedButton(onClick = { mediaTarget = false; mediaPicker.launch(arrayOf("image/*", "audio/*")) }) { Text("Media nel retro") }
        }
        Text("Immagini e audio appaiono durante lo studio.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(essential, { essential = it }, label = { Text("Punti essenziali (facoltativi)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(source, { source = it }, label = { Text("Estratto fonte (facoltativo)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(sourceTitle, { sourceTitle = it }, label = { Text("Titolo fonte") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sourceUrl, { sourceUrl = it }, label = { Text("Link fonte (solo riferimento)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sourcePage, { sourcePage = it }, label = { Text("Pagina") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = front.isNotBlank() && (kind == NoteKind.CLOZE || back.isNotBlank()), onClick = { scope.launch { try {
                val fields = if (original?.anki != null && original?.fields?.size == 1) listOf(front)
                    else listOf(front, back) + original?.fields.orEmpty().drop(2)
                repo.saveNote((original ?: Note(deckId = deckId, fields = emptyList())).copy(
                    kind = kind, fields = fields,
                    source = SourceReference(source, sourceTitle, sourceUrl, sourcePage, essential),
                )); onDone()
        } catch (e: Exception) { onError(e.message ?: "Nota non salvata") } } }, modifier = Modifier.fillMaxWidth().testTag("saveNote")) { Text("Salva nota") }
    }
}
