// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.anki

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.giovannipivatoo.memoro.data.NoteKind
import io.github.giovannipivatoo.memoro.data.Deck
import io.github.giovannipivatoo.memoro.data.Note
import io.github.giovannipivatoo.memoro.data.Attempt
import io.github.giovannipivatoo.memoro.data.AttemptState
import io.github.giovannipivatoo.memoro.data.AnswerMode
import io.github.giovannipivatoo.memoro.data.Rating
import io.github.giovannipivatoo.memoro.data.RoomMemoroRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnkiInteropTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext

    @Test fun realLegacyAndModernPackagesImportAndReimportWithoutDuplicates() = runBlocking {
        app.deleteDatabase("memoro.db")
        val repo = RoomMemoroRepository.open(app)
        try {
            val service = AnkiService(app)
            for (name in listOf("legacy.apkg", "modern.apkg")) {
                instrument.context.assets.open("anki/$name").use { input ->
                    val report = service.importApkg(input, repo)
                    assertEquals(3, report.notes)
                    assertEquals(5, report.cards)
                    assertEquals(1, report.reviews)
                    assertEquals(2, report.media)
                }
                val snapshot = repo.snapshot()
                assertEquals(3, snapshot.notes.size)
                assertEquals(5, snapshot.cards.size)
                assertEquals(1, snapshot.reviews.size)
                assertTrue(snapshot.notes.any { it.kind == NoteKind.REVERSE })
                assertTrue(snapshot.notes.any { it.kind == NoteKind.CLOZE })
                assertTrue(snapshot.cards.any { it.scheduling.importedQueue == 2 && it.scheduling.importedIntervalDays == 5 })
            }
        } finally { repo.close() }
    }

    @Test fun exportContainsSqliteAndBothMediaFiles() = runBlocking {
        app.deleteDatabase("memoro.db")
        val repo = RoomMemoroRepository.open(app)
        try {
            val service = AnkiService(app)
            instrument.context.assets.open("anki/modern.apkg").use { service.importApkg(it, repo) }
            val output = ByteArrayOutputStream()
            val report = service.exportApkg(output, repo)
            assertEquals(3, report.notes)
            val file = File.createTempFile("memoro-test-", ".apkg", app.cacheDir)
            try {
                file.writeBytes(output.toByteArray())
                ZipFile(file).use { zip ->
                    assertNotNull(zip.getEntry("collection.anki21b"))
                    assertNotNull(zip.getEntry("media"))
                    assertNotNull(zip.getEntry("0"))
                    assertNotNull(zip.getEntry("1"))
                }
            } finally { file.delete() }
        } finally { repo.close() }
    }

    @Test fun clozeRenderingUsesTargetAndExactExpectedRejectsAmbiguity() {
        val note = io.github.giovannipivatoo.memoro.data.Note(deckId = 1, kind = NoteKind.CLOZE,
            fields = listOf("The {{c1::cat}} sleeps by the {{c2::fire}}.", "Extra"))
        val card = io.github.giovannipivatoo.memoro.data.Card(noteId = 1, deckId = 1, ordinal = 0, front = "", back = "")
        val rendered = AnkiRenderer.render(card, note)
        assertEquals("The […] sleeps by the fire.", rendered.question)
        assertEquals("cat", AnkiRenderer.exactExpected(card, note))
        val ambiguous = note.copy(fields = listOf("{{c1::red}} and {{c1::blue}}", ""))
        assertNull(AnkiRenderer.exactExpected(card, ambiguous))
    }

    @Test fun mixedImportNativeNotesAndReviewExportForExternalAnkiOracle() = runBlocking {
        app.deleteDatabase("memoro.db")
        val repo = RoomMemoroRepository.open(app)
        try {
            val service = AnkiService(app)
            instrument.context.assets.open("anki/modern.apkg").use { service.importApkg(it, repo) }
            val deck = repo.saveDeck(Deck(name = "Native Memoro"))
            repo.saveNote(Note(deckId = deck.id, kind = NoteKind.BASIC, fields = listOf("Water", "Acqua")))
            repo.saveNote(Note(deckId = deck.id, kind = NoteKind.REVERSE, fields = listOf("Sun", "Sole")))
            repo.saveNote(Note(deckId = deck.id, kind = NoteKind.CLOZE, fields = listOf("The {{c1::moon}} shines.", "")))
            val imported = repo.snapshot().cards.first { it.anki != null }
            val attempt = repo.saveAttempt(Attempt(cardId = imported.id, mode = AnswerMode.CLASSIC,
                state = AttemptState.EVALUATED, createdAtMillis = System.currentTimeMillis()))
            repo.commitReview(attempt.id, Rating.GOOD, System.currentTimeMillis())
            val out = app.getExternalFilesDir(null)!!.resolve("memoro-mixed-oracle.apkg")
            out.outputStream().use { service.exportApkg(it, repo) }
            assertTrue(out.length() > 100)
            val snapshot = repo.snapshot()
            assertEquals(6, snapshot.notes.size)
            assertEquals(9, snapshot.cards.size)
            assertEquals(2, snapshot.reviews.size)
        } finally { repo.close() }
    }
}
