// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.giovannipivatoo.memoro.ai.DeepSeekClient
import io.github.giovannipivatoo.memoro.ai.EvaluationInput
import io.github.giovannipivatoo.memoro.ai.EvaluationResult
import io.github.giovannipivatoo.memoro.ai.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class ArchiveResult(val title: String, val details: String)

@Composable
internal fun SettingsScreen(actions: AppActions, ai: DeepSeekClient, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val initialStatus = remember { runCatching { actions.apiKey() }.fold(
        onSuccess = { (it.isNotBlank() to null) },
        onFailure = { false to "Chiave non disponibile. Rimuovila e inseriscila di nuovo." },
    ) }
    var configured by remember { mutableStateOf(initialStatus.first) }
    var keyIssue by remember { mutableStateOf(initialStatus.second) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(actions.model()) }
    var verification by rememberSaveable { mutableStateOf<String?>(null) }
    var verified by rememberSaveable { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var pet by remember { mutableStateOf(actions.petEnabled()) }
    var apiExpanded by rememberSaveable { mutableStateOf(false) }
    var modelExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingRemoveKey by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var licenses by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ArchiveResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun perform(uri: Uri?, operation: suspend (Uri) -> String) {
        if (uri != null && !busy) scope.launch {
            busy = true
            try { report = ArchiveResult("Operazione completata", operation(uri)) }
            catch (e: Exception) { report = ArchiveResult("Operazione non riuscita", e.message ?: "Riprova con un altro file.") }
            finally { busy = false }
        }
    }
    val importApkg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { perform(it, actions.importApkg) }
    val exportApkg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { perform(it, actions.exportApkg) }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { perform(it, actions.backup) }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (!busy) pendingRestore = it }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item { Text("Rendi Memoro tuo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        item {
            SettingsSection("Il tuo compagno", "Un piccolo segno di incoraggiamento durante lo studio") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MemoroPet(outcome = null, modifier = Modifier.size(76.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Mostra il gattino", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text("Silenzioso e discreto. Parte spento; puoi attivarlo quando vuoi.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = pet, onCheckedChange = {
                        try { actions.savePetEnabled(it); pet = it }
                        catch (e: Exception) { onMessage(e.message ?: "Impostazione non salvata") }
                    }, modifier = Modifier.testTag("petSwitch"))
                }
            }
        }
        item {
            SettingsSection("Correzione AI", "Facoltativa · usata solo quando invii una risposta") {
                Row(Modifier.fillMaxWidth().clickable { apiExpanded = !apiExpanded }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (keyIssue != null) "Da riconfigurare" else if (configured) "Chiave salvata" else "Da configurare", style = MaterialTheme.typography.titleMedium,
                            color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text("La chiave resta su questo dispositivo.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (apiExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (apiExpanded) "Chiudi impostazioni AI" else "Apri impostazioni AI")
                }
                if (apiExpanded) {
                    Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                    keyIssue?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(8.dp)) }
                    Text("Durante lo studio invii solo la carta e la risposta quando tocchi Invia risposta. I link fonte non vengono letti automaticamente.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = key, onValueChange = { key = it; verification = null },
                        label = { Text("Chiave API personale") },
                        placeholder = { Text(if (configured) "Inserisci una nuova chiave per sostituirla" else "Inserisci la chiave") },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showKey) "Nascondi chiave" else "Mostra chiave")
                        } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        singleLine = true, enabled = !verifying, modifier = Modifier.fillMaxWidth().testTag("apiKey"),
                    )
                    TextButton(onClick = { modelExpanded = !modelExpanded }) {
                        Text("Modello avanzato")
                        Spacer(Modifier.width(4.dp))
                        Icon(if (modelExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                    }
                    if (modelExpanded) OutlinedTextField(
                        value = model, onValueChange = { model = it; verification = null }, label = { Text("Modello DeepSeek") },
                        singleLine = true, enabled = !verifying, modifier = Modifier.fillMaxWidth().testTag("modelName"),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !verifying, onClick = {
                            try {
                                if (key.isNotBlank()) { actions.saveApiKey(key.trim()); configured = true; keyIssue = null; key = "" }
                                actions.saveModel(model.trim().ifBlank { "deepseek-flash" })
                                verification = null
                                onMessage("Impostazioni AI salvate")
                            } catch (_: Exception) { onMessage("Impostazioni AI non salvate. Riprova.") }
                        }, modifier = Modifier.testTag("saveSettings")) { Text("Salva") }
                        if (configured || keyIssue != null) TextButton(enabled = !verifying, onClick = { pendingRemoveKey = true }) { Text("Rimuovi chiave") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("La prova invia solo l'esempio fittizio «capitale d'Italia / Roma» a DeepSeek. Può consumare una piccola quota API.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val unsaved = key.isNotBlank() || model.trim().ifBlank { "deepseek-flash" } != actions.model()
                    OutlinedButton(enabled = configured && keyIssue == null && !unsaved && !verifying, onClick = {
                        verifying = true
                        verification = null
                        scope.launch {
                            try {
                                val result = ai.evaluate(EvaluationInput("Qual è la capitale d'Italia?", "Roma", "Roma"), actions.apiKey(), actions.model())
                                when (result) {
                                    is EvaluationResult.Success -> {
                                        verified = true
                                        val verdict = when (result.evaluation.verdict) {
                                            Verdict.CORRECT -> "corretta"
                                            Verdict.PARTIAL -> "parziale"
                                            Verdict.WRONG -> "errata"
                                            Verdict.UNGRADABLE -> "non valutabile"
                                        }
                                        verification = "DeepSeek funziona. L'esempio è stato valutato: $verdict."
                                    }
                                    is EvaluationResult.Failure -> { verified = false; verification = result.message }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { verified = false; verification = "Verifica non riuscita. La chiave resta salvata; riprova." }
                            finally { verifying = false }
                        }
                    }, modifier = Modifier.testTag("testDeepSeek")) { Text(if (verifying) "Verifica in corso…" else "Prova correzione") }
                    if (unsaved) Text("Salva prima la chiave e il modello per provarli.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    verification?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("deepSeekStatus")) }
                }
            }
        }
        item {
            SettingsSection("Archivio e backup", "I tuoi dati restano disponibili anche senza rete") {
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Operazione in corso…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                ArchiveAction(Icons.Default.ArrowDownward, "Importa da Anki", "Apri un pacchetto .apkg con carte e media", !busy) {
                    importApkg.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }
                HorizontalDivider()
                ArchiveAction(Icons.Default.ArrowUpward, "Esporta per Anki", "Crea un pacchetto .apkg da condividere", !busy) {
                    exportApkg.launch("memoro.apkg")
                }
                HorizontalDivider()
                ArchiveAction(Icons.Default.Save, "Crea un backup", "Salva note, ripassi, media e pacchetti originali", !busy) {
                    createBackup.launch("memoro-backup.zip")
                }
                HorizontalDivider()
                ArchiveAction(Icons.Default.Restore, "Ripristina un backup", "Verifica il file e crea prima una copia di sicurezza", !busy) {
                    restoreBackup.launch(arrayOf("application/zip", "*/*"))
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                TextButton(onClick = { licenses = true }) { Text("Licenze open source") }
                Text("La chiave API non entra nei backup.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
    if (pendingRemoveKey) AlertDialog(
        onDismissRequest = { pendingRemoveKey = false }, title = { Text("Rimuovere la chiave API?") },
        text = { Text("Potrai inserirne una nuova in qualsiasi momento.") },
        confirmButton = { TextButton(onClick = {
            try { actions.saveApiKey(""); configured = false; keyIssue = null; key = ""; verification = null; onMessage("Chiave rimossa") }
            catch (_: Exception) { onMessage("Chiave non rimossa. Riprova.") }
            pendingRemoveKey = false
        }) { Text("Rimuovi") } },
        dismissButton = { TextButton(onClick = { pendingRemoveKey = false }) { Text("Annulla") } },
    )
    pendingRestore?.let { uri -> AlertDialog(
        onDismissRequest = { pendingRestore = null }, title = { Text("Ripristinare il backup?") },
        text = { Text("Le note, le carte e la cronologia correnti saranno sostituite. Il file verrà verificato e una copia preventiva resterà sul dispositivo.") },
        confirmButton = { TextButton(onClick = { pendingRestore = null; perform(uri, actions.restore) }) { Text("Ripristina") } },
        dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Annulla") } },
    ) }
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Licenze open source") }, text = {
        val contents = remember { context.assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name ->
            "$name\n" + context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
        } }
        Box(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { Text(contents) }
    }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Chiudi") } })
    report?.let { result -> AlertDialog(onDismissRequest = { report = null }, title = { Text(result.title) }, text = {
        Box(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) { Text(result.details) }
    }, confirmButton = { TextButton(onClick = { report = null }) { Text("Chiudi") } }) }
}

@Composable
private fun SettingsSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
        }
    }
}

@Composable
private fun ArchiveAction(icon: ImageVector, title: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(10.dp).size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
