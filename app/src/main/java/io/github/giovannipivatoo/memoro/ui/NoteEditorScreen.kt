// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ui

import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.giovannipivatoo.memoro.anki.AnkiRenderer
import io.github.giovannipivatoo.memoro.data.MemoroRepository
import io.github.giovannipivatoo.memoro.data.Note
import io.github.giovannipivatoo.memoro.data.NoteKind
import io.github.giovannipivatoo.memoro.data.SourceReference
import kotlinx.coroutines.launch
import java.util.UUID

private val clozeInput = Regex("\\{\\{c[1-9]\\d*::[^}]+\\}\\}")

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun NoteEditorScreen(
    repo: MemoroRepository,
    deckId: Long,
    noteId: Long,
    onDirtyChange: (Boolean) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
) {
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
    var sourceExpanded by rememberSaveable(noteId) { mutableStateOf(false) }
    var mediaTarget by rememberSaveable(noteId) { mutableStateOf(true) }
    var requestedMedia by remember { mutableStateOf("image/*") }
    var saving by remember { mutableStateOf(false) }
    var attaching by remember { mutableStateOf(false) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !attaching) {
            attaching = true
            scope.launch {
                try {
                    val mime = context.contentResolver.getType(uri).orEmpty()
                    if ((requestedMedia == "image/*" && !mime.startsWith("image/")) || (requestedMedia == "audio/*" && !mime.startsWith("audio/"))) error("Il file scelto non corrisponde al tipo richiesto")
                    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
                        ?: if (mime.startsWith("image/")) "png" else "mp3"
                    val path = "media/${UUID.randomUUID()}.$extension"
                    val stored = context.contentResolver.openInputStream(uri)?.use { repo.putFile(path, it, mime) } ?: error("File non leggibile")
                    val marker = if (mime.startsWith("image/")) "[image:${stored.path}]" else "[audio:${stored.path}]"
                    if (mediaTarget || (original?.anki != null && original?.fields?.size == 1)) front = (front + " " + marker).trim() else back = (back + " " + marker).trim()
                } catch (e: Exception) { onError(e.message ?: "Allegato non salvato") }
                finally { attaching = false }
            }
        }
    }
    LaunchedEffect(noteId) {
        if (noteId != 0L) repo.getNote(noteId)?.let { note ->
            original = note
            if (!loaded) {
                kind = note.kind; front = note.fields.getOrNull(0).orEmpty(); back = note.fields.getOrNull(1).orEmpty()
                source = note.source.excerpt; sourceTitle = note.source.title; sourceUrl = note.source.url; sourcePage = note.source.page; essential = note.source.essentialPoints
                sourceExpanded = listOf(source, sourceTitle, sourceUrl, sourcePage, essential).any { it.isNotBlank() }
            }
        }
        loaded = true
        originalLoaded = true
    }
    LaunchedEffect(originalLoaded, original, kind, front, back, source, sourceTitle, sourceUrl, sourcePage, essential) {
        if (!originalLoaded) return@LaunchedEffect
        val note = original
        onDirtyChange(if (note == null) {
            kind != NoteKind.BASIC || listOf(front, back, source, sourceTitle, sourceUrl, sourcePage, essential).any { it.isNotBlank() }
        } else {
            kind != note.kind || front != note.fields.getOrNull(0).orEmpty() || back != note.fields.getOrNull(1).orEmpty() ||
                source != note.source.excerpt || sourceTitle != note.source.title || sourceUrl != note.source.url || sourcePage != note.source.page || essential != note.source.essentialPoints
        })
    }
    if (!originalLoaded) { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }; return }
    if (noteId != 0L && original == null) { Text("Nota non trovata", modifier = Modifier.padding(24.dp)); return }
    val importedOneField = original?.anki != null && original?.fields?.size == 1
    val valid = front.isNotBlank() && when (kind) { NoteKind.CLOZE -> clozeInput.containsMatchIn(front); else -> importedOneField || back.isNotBlank() }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (noteId == 0L) "Crea la tua nota" else "Modifica la tua nota", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Una nota può generare una o più carte da ripassare.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tipo di carta", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NoteKind.entries.forEach { option -> FilterChip(selected = kind == option, enabled = original?.anki == null && !saving, onClick = { kind = option }, label = { Text(option.label()) }) }
                }
                Text(when (kind) {
                    NoteKind.BASIC -> "Una domanda e una risposta."
                    NoteKind.REVERSE -> "Crea due carte: domanda → risposta e risposta → domanda."
                    NoteKind.CLOZE -> "Nascondi una parola o frase dentro il testo."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (original?.anki != null) Text("Il tipo delle note importate è fisso per conservare il modello Anki.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (kind == NoteKind.CLOZE) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Esempio: La capitale d'Italia è {{c1::Roma}}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(front, { front = it }, enabled = !saving, label = { Text("Testo con parola nascosta") }, supportingText = { if (front.isNotBlank() && !clozeInput.containsMatchIn(front)) Text("Inserisci almeno una parte {{c1::risposta}}") }, minLines = 4, modifier = Modifier.fillMaxWidth().testTag("noteFront"))
                }
            } else OutlinedTextField(front, { front = it }, enabled = !saving, label = { Text("Domanda") }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("noteFront"))
            if (!importedOneField) OutlinedTextField(back, { back = it }, enabled = !saving, label = { Text(if (kind == NoteKind.CLOZE) "Nota sul retro (facoltativa)" else "Risposta") }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("noteBack"))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Allegati", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = mediaTarget, onClick = { mediaTarget = true }, label = { Text(if (kind == NoteKind.CLOZE) "Nel testo" else "Nella domanda") })
                    if (!importedOneField) FilterChip(selected = !mediaTarget, onClick = { mediaTarget = false }, label = { Text("Nella risposta") })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(enabled = !attaching && !saving, onClick = { requestedMedia = "image/*"; mediaPicker.launch(arrayOf("image/*")) }) { Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp)); Text("Immagine") }
                    OutlinedButton(enabled = !attaching && !saving, onClick = { requestedMedia = "audio/*"; mediaPicker.launch(arrayOf("audio/*")) }) { Icon(Icons.Default.Audiotrack, null); Spacer(Modifier.width(6.dp)); Text("Audio") }
                }
            }
            HorizontalDivider()
            TextButton(onClick = { sourceExpanded = !sourceExpanded }, modifier = Modifier.fillMaxWidth()) {
                Text("Fonti e punti essenziali", modifier = Modifier.weight(1f))
                Icon(if (sourceExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (sourceExpanded) "Nascondi fonti" else "Mostra fonti")
            }
            if (sourceExpanded) {
                Text("Facoltativi. L'estratto aiuta la valutazione AI; il link è solo un riferimento.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(essential, { essential = it }, label = { Text("Punti essenziali") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(source, { source = it }, label = { Text("Estratto fonte") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sourceTitle, { sourceTitle = it }, label = { Text("Titolo fonte") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sourceUrl, { sourceUrl = it }, label = { Text("Link fonte") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sourcePage, { sourcePage = it }, label = { Text("Pagina") }, modifier = Modifier.fillMaxWidth())
            }
        }
        Surface(tonalElevation = 3.dp) {
            Button(enabled = valid && !saving && !attaching, onClick = {
                if (saving) return@Button
                saving = true
                scope.launch {
                    try {
                        val fields = if (importedOneField) listOf(front) else listOf(front, back) + original?.fields.orEmpty().drop(2)
                        val updated = (original ?: Note(deckId = deckId, fields = emptyList())).copy(kind = kind, fields = fields,
                            source = SourceReference(source, sourceTitle, sourceUrl, sourcePage, essential))
                        if (updated.anki != null && updated.id > 0) {
                            val cards = repo.snapshot().cards.filter { it.noteId == updated.id }.map { card ->
                                val faces = AnkiRenderer.basicFaces(updated.fields, updated.kind, card.ordinal)
                                card.copy(front = faces.first, back = faces.second)
                            }
                            repo.saveNoteAndCards(updated, cards)
                        } else repo.saveNote(updated)
                        onDone()
                    } catch (e: Exception) { onError(e.message ?: "Nota non salvata") }
                    finally { saving = false }
                }
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("saveNote")) { Text(if (saving) "Salvataggio…" else "Salva nota") }
        }
    }
}
