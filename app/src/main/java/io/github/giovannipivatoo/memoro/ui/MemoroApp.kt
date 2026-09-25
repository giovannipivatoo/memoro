// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.github.giovannipivatoo.memoro.ai.DeepSeekClient
import io.github.giovannipivatoo.memoro.data.Deck
import io.github.giovannipivatoo.memoro.data.MemoroRepository
import kotlinx.coroutines.launch

private sealed interface Page {
    data object Decks : Page
    data class DeckDetail(val id: Long) : Page
    data class NoteEditor(val deckId: Long, val noteId: Long = 0) : Page
    data class Study(val deckId: Long?, val practice: Boolean = false) : Page
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
        is Page.Study -> listOf("study", page.deckId?.toString().orEmpty(), page.practice.toString())
    } },
    restore = { parts -> when (parts.firstOrNull()) {
        "history" -> Page.History
        "settings" -> Page.Settings
        "deck" -> Page.DeckDetail(parts[1].toLong())
        "note" -> Page.NoteEditor(parts[1].toLong(), parts[2].toLong())
        "study" -> Page.Study(parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLong(), parts.getOrNull(2)?.toBoolean() ?: false)
        else -> Page.Decks
    } },
)

/** Settings and package actions are supplied by the Android host; study state lives in the repository. */
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
    var editorDirty by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun showError(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun goBack() {
        page = when (val current = page) {
            is Page.NoteEditor -> if (editorDirty) { confirmDiscard = true; return } else Page.DeckDetail(current.deckId)
            is Page.Study -> current.deckId?.let { Page.DeckDetail(it) } ?: Page.Decks
            else -> Page.Decks
        }
    }
    BackHandler(enabled = page != Page.Decks) { goBack() }

    val deckId = when (val current = page) {
        is Page.DeckDetail -> current.id
        is Page.NoteEditor -> current.deckId
        is Page.Study -> current.deckId
        else -> null
    }
    val deck by produceState<Deck?>(null, deckId) { value = deckId?.let { repo.getDeck(it) } }
    val title = when (val current = page) {
        Page.Decks -> "memoro"
        is Page.DeckDetail -> deck?.name ?: "Mazzo"
        is Page.NoteEditor -> if (current.noteId == 0L) "Nuova nota" else "Modifica nota"
        is Page.Study -> if (current.practice) "Ripasso libero" else if (current.deckId == null) "Ripasso" else deck?.name ?: "Ripasso"
        Page.History -> "Cronologia"
        Page.Settings -> "Impostazioni"
    }
    val showNav = page !is Page.NoteEditor && page !is Page.Study
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, style = if (page == Page.Decks) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (page is Page.DeckDetail || page is Page.NoteEditor || page is Page.Study) {
                        IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
                    }
                },
            )
        },
        bottomBar = {
            if (showNav) NavigationBar {
                NavigationBarItem(selected = page == Page.Decks || page is Page.DeckDetail, onClick = { page = Page.Decks }, icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) }, label = { Text("Mazzi") })
                NavigationBarItem(selected = page == Page.History, onClick = { page = Page.History }, icon = { Icon(Icons.Default.History, contentDescription = null) }, label = { Text("Cronologia") })
                NavigationBarItem(selected = page == Page.Settings, onClick = { page = Page.Settings }, icon = { Icon(Icons.Default.Settings, contentDescription = null) }, label = { Text("Impostazioni") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val current = page) {
                Page.Decks -> DecksScreen(repo, actions.importApkg, actions.petEnabled(), onOpen = { page = Page.DeckDetail(it) }, onStudy = { page = Page.Study(null) }, onError = ::showError)
                is Page.DeckDetail -> DeckScreen(repo, current.id, onEdit = { page = Page.NoteEditor(current.id, it); editorDirty = false }, onStudy = { page = Page.Study(current.id) }, onPractice = { page = Page.Study(current.id, practice = true) }, onError = ::showError)
                is Page.NoteEditor -> NoteEditorScreen(repo, current.deckId, current.noteId, onDirtyChange = { editorDirty = it }, onDone = { editorDirty = false; page = Page.DeckDetail(current.deckId) }, onError = ::showError)
                is Page.Study -> StudyScreen(repo, current.deckId, ai, actions.apiKey, actions.model, actions.petEnabled(), onError = ::showError, onDone = { page = current.deckId?.let { Page.DeckDetail(it) } ?: Page.Decks }, practice = current.practice)
                Page.History -> HistoryScreen(repo)
                Page.Settings -> SettingsScreen(actions, ai, onMessage = ::showError)
            }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Scartare le modifiche?") },
        text = { Text("La nota contiene modifiche non salvate.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; editorDirty = false; (page as? Page.NoteEditor)?.let { page = Page.DeckDetail(it.deckId) } }) { Text("Scarta") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Continua a modificare") } },
    )
}
