// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import io.github.giovannipivatoo.memoro.anki.AnkiService
import io.github.giovannipivatoo.memoro.data.AiCredentialStore
import io.github.giovannipivatoo.memoro.data.BackupManager
import io.github.giovannipivatoo.memoro.data.RoomMemoroRepository
import io.github.giovannipivatoo.memoro.data.SettingsStore
import io.github.giovannipivatoo.memoro.ui.AppActions
import io.github.giovannipivatoo.memoro.ui.MemoroApp
import io.github.giovannipivatoo.memoro.ui.MemoroTheme

class MemoroHost(application: Application) : AndroidViewModel(application) {
    val repository = RoomMemoroRepository.open(application)
    val settings = SettingsStore(application)
    val credentials = AiCredentialStore(application)
    val backup = BackupManager(application, repository)
    val anki = AnkiService(application)

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {
    private val host by viewModels<MemoroHost>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val current by host.settings.settings.collectAsState()
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MemoroTheme {
                MemoroApp(host.repository, AppActions(
                    apiKey = { host.credentials.read().orEmpty() },
                    saveApiKey = { if (it.isBlank()) host.credentials.clear() else host.credentials.save(it) },
                    model = { current.model },
                    saveModel = { host.settings.update(model = it) },
                    petEnabled = { current.petEnabled },
                    savePetEnabled = { host.settings.update(petEnabled = it) },
                    importApkg = { uri ->
                        val stream = contentResolver.openInputStream(uri) ?: error("Archivio non leggibile")
                        val report = stream.use { host.anki.importApkg(it, host.repository) }
                        "Importate ${report.notes} note e ${report.cards} carte." +
                            if (report.warnings.isEmpty()) "" else " Incompatibilità: ${report.warnings.joinToString("; ")}"
                    },
                    exportApkg = { uri ->
                        val stream = contentResolver.openOutputStream(uri) ?: error("Destinazione non scrivibile")
                        val report = stream.use { host.anki.exportApkg(it, host.repository) }
                        "Esportate ${report.cards} carte." +
                            if (report.warnings.isEmpty()) "" else " Avvisi: ${report.warnings.joinToString("; ")}"
                    },
                    backup = { uri ->
                        val stream = contentResolver.openOutputStream(uri) ?: error("Destinazione non scrivibile")
                        stream.use { host.backup.export(it) }
                        "Backup creato."
                    },
                    restore = { uri ->
                        val stream = contentResolver.openInputStream(uri) ?: error("Backup non leggibile")
                        val preventive = stream.use { host.backup.restore(it) }
                        "Archivio ripristinato. Copia preventiva: ${preventive.name}"
                    },
                ))
            }
        }
    }

}
