// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.giovannipivatoo.memoro.anki.AnkiService
import io.github.giovannipivatoo.memoro.data.AiCredentialStore
import io.github.giovannipivatoo.memoro.data.BackupManager
import io.github.giovannipivatoo.memoro.data.RoomMemoroRepository
import io.github.giovannipivatoo.memoro.data.SettingsStore
import io.github.giovannipivatoo.memoro.ui.AppActions
import io.github.giovannipivatoo.memoro.ui.MemoroApp

class MainActivity : ComponentActivity() {
    private val repository by lazy { RoomMemoroRepository.open(applicationContext) }
    private val settings by lazy { SettingsStore(applicationContext) }
    private val credentials by lazy { AiCredentialStore(applicationContext) }
    private val backup by lazy { BackupManager(applicationContext, repository) }
    private val anki by lazy { AnkiService(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val current by settings.settings.collectAsState()
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                MemoroApp(repository, AppActions(
                    apiKey = { credentials.read().orEmpty() },
                    saveApiKey = { if (it.isBlank()) credentials.clear() else credentials.save(it) },
                    model = { current.model },
                    saveModel = { settings.update(model = it) },
                    petEnabled = { current.petEnabled },
                    savePetEnabled = { settings.update(petEnabled = it) },
                    importApkg = { uri ->
                        val stream = contentResolver.openInputStream(uri) ?: error("Archivio non leggibile")
                        val report = stream.use { anki.importApkg(it, repository) }
                        "Importate ${report.notes} note e ${report.cards} carte." +
                            if (report.warnings.isEmpty()) "" else " Incompatibilità: ${report.warnings.joinToString("; ")}"
                    },
                    exportApkg = { uri ->
                        val stream = contentResolver.openOutputStream(uri) ?: error("Destinazione non scrivibile")
                        val report = stream.use { anki.exportApkg(it, repository) }
                        "Esportate ${report.cards} carte." +
                            if (report.warnings.isEmpty()) "" else " Avvisi: ${report.warnings.joinToString("; ")}"
                    },
                    backup = { uri ->
                        val stream = contentResolver.openOutputStream(uri) ?: error("Destinazione non scrivibile")
                        stream.use { backup.export(it) }
                        "Backup creato."
                    },
                    restore = { uri ->
                        val stream = contentResolver.openInputStream(uri) ?: error("Backup non leggibile")
                        val preventive = stream.use { backup.restore(it) }
                        "Archivio ripristinato. Copia preventiva: ${preventive.name}"
                    },
                ))
            }
        }
    }
}
