// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.anki

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.giovannipivatoo.memoro.data.NoteKind
import io.github.giovannipivatoo.memoro.data.Deck
import io.github.giovannipivatoo.memoro.data.Note
import io.github.giovannipivatoo.memoro.data.MultipleChoice
import io.github.giovannipivatoo.memoro.data.Attempt
import io.github.giovannipivatoo.memoro.data.AttemptState
import io.github.giovannipivatoo.memoro.data.AnswerMode
import io.github.giovannipivatoo.memoro.data.Rating
import io.github.giovannipivatoo.memoro.data.RoomMemoroRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnkiInteropTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext

    @Test fun multipleChoiceExportsAsStaticAnkiCardAndRestoresInteractiveMetadata() = runBlocking {
        app.deleteDatabase("memoro.db")
        val service = AnkiService(app)
        val choice = MultipleChoice(listOf("3", "4", "5"), 1)
        val sourceRepo = RoomMemoroRepository.open(app)
        val bytes = try {
            val repo = sourceRepo
            val deck = repo.saveDeck(Deck(name = "Arithmetic"))
            repo.saveNote(Note(deckId = deck.id, fields = listOf("Quanto fa {{c1::2}} + 2?", "4"), multipleChoice = choice), AnswerMode.MULTIPLE_CHOICE)
            val output = ByteArrayOutputStream()
            val report = service.exportApkg(output, repo)
            assertTrue(report.warnings.any { "opzioni statiche" in it })
            output.toByteArray().also { app.getExternalFilesDir(null)!!.resolve("memoro-multiple-choice-oracle.apkg").writeBytes(it) }
        } finally { sourceRepo.close() }
        val packageFile = File.createTempFile("memoro-mc-", ".apkg", app.cacheDir)
        val dbFile = File.createTempFile("memoro-mc-", ".db", app.cacheDir)
        try {
            packageFile.writeBytes(bytes)
            ZipFile(packageFile).use { zip ->
                zip.getInputStream(zip.getEntry("collection.anki21")).use { input -> dbFile.outputStream().use(input::copyTo) }
            }
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT flds,mid FROM notes", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    val fields = rows.getString(0).split('\u001f')
                    assertEquals(3, fields.size)
                    assertTrue(fields[0].contains("<li>3</li>"))
                    assertTrue(fields[0].contains("<li>4</li>"))
                    assertEquals("4", fields[1])
                    assertTrue(fields[2].startsWith("MemoroMC:v1:"))
                    val modelId = rows.getLong(1)
                    db.rawQuery("SELECT models FROM col", null).use { col ->
                        assertTrue(col.moveToFirst())
                        val model = JSONObject(col.getString(0)).getJSONObject(modelId.toString())
                        assertEquals("Memoro Multiple Choice", model.getString("name"))
                        val template = model.getJSONArray("tmpls").getJSONObject(0)
                        assertEquals("{{Front}}", template.getString("qfmt"))
                        assertFalse(template.getString("qfmt").contains("<script"))
                    }
                }
            }
            app.deleteDatabase("memoro.db")
            val importedRepo = RoomMemoroRepository.open(app)
            try {
                val repo = importedRepo
                bytes.inputStream().use { service.importApkg(it, repo) }
                val first = repo.snapshot()
                assertEquals(1, first.notes.size)
                assertEquals(listOf("Quanto fa {{c1::2}} + 2?", "4"), first.notes.single().fields)
                assertEquals(NoteKind.BASIC, first.notes.single().kind)
                assertEquals(choice, first.notes.single().multipleChoice)
                assertEquals(setOf(AnswerMode.MULTIPLE_CHOICE), first.cards.single().modes)
                bytes.inputStream().use { service.importApkg(it, repo) }
                assertEquals(1, repo.snapshot().notes.size)
                val secondExport = ByteArrayOutputStream()
                service.exportApkg(secondExport, repo)
                secondExport.toByteArray().inputStream().use { service.importApkg(it, repo) }
                assertEquals(choice, repo.snapshot().notes.single().multipleChoice)
                assertEquals(1, repo.snapshot().cards.size)
            } finally { importedRepo.close() }
        } finally { packageFile.delete(); dbFile.delete() }
    }

    @Test fun removingChoiceFromReimportedNoteKeepsThreeAnkiFieldsWithoutResurrectingIt() = runBlocking {
        app.deleteDatabase("memoro.db")
        val repo = RoomMemoroRepository.open(app)
        val service = AnkiService(app)
        try {
            val deck = repo.saveDeck(Deck(name = "Arithmetic"))
            repo.saveNote(Note(deckId = deck.id, fields = listOf("2 + 2?", "4"),
                multipleChoice = MultipleChoice(listOf("3", "4"), 1)), AnswerMode.MULTIPLE_CHOICE)
            val first = ByteArrayOutputStream()
            service.exportApkg(first, repo)
            first.toByteArray().inputStream().use { service.importApkg(it, repo) }
            val imported = repo.snapshot()
            assertNotNull(imported.notes.single().anki)
            repo.saveNoteAndCards(imported.notes.single().copy(multipleChoice = null),
                imported.cards.map { it.copy(modes = setOf(AnswerMode.WRITTEN)) })
            val removed = ByteArrayOutputStream()
            service.exportApkg(removed, repo)
            val bytes = removed.toByteArray()
            app.getExternalFilesDir(null)!!.resolve("memoro-multiple-choice-removed-oracle.apkg").writeBytes(bytes)
            val packageFile = File.createTempFile("memoro-mc-removed-", ".apkg", app.cacheDir)
            val dbFile = File.createTempFile("memoro-mc-removed-", ".db", app.cacheDir)
            try {
                packageFile.writeBytes(bytes)
                ZipFile(packageFile).use { zip ->
                    zip.getInputStream(zip.getEntry("collection.anki21")).use { input -> dbFile.outputStream().use(input::copyTo) }
                }
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT flds FROM notes", null).use { rows ->
                        assertTrue(rows.moveToFirst())
                        assertEquals("2 + 2?\u001f4\u001f", rows.getString(0))
                    }
                }
            } finally { packageFile.delete(); dbFile.delete() }
            bytes.inputStream().use { service.importApkg(it, repo) }
            assertNull(repo.snapshot().notes.single().multipleChoice)
            assertEquals(setOf(AnswerMode.WRITTEN), repo.snapshot().cards.single().modes)
        } finally { repo.close() }
    }

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
            val before = repo.snapshot()
            assertEquals(6, before.notes.size)
            assertEquals(9, before.cards.size)
            assertEquals(2, before.reviews.size)
            out.inputStream().use { service.importApkg(it, repo) }
            val after = repo.snapshot()
            assertEquals(before.notes.size, after.notes.size)
            assertEquals(before.cards.size, after.cards.size)
            assertEquals(before.reviews.size, after.reviews.size)
            assertEquals(before.attempts, after.attempts)
        } finally { repo.close() }
    }

    @Test fun nativeOnlyExportsLegacyPackageAndReimportsWithoutDuplicates() = runBlocking {
        app.deleteDatabase("memoro.db")
        val repo = RoomMemoroRepository.open(app)
        try {
            val deck = repo.saveDeck(Deck(name = "Native legacy"))
            repo.saveNote(Note(deckId = deck.id, kind = NoteKind.BASIC, fields = listOf("One", "Uno")))
            repo.saveNote(Note(deckId = deck.id, kind = NoteKind.REVERSE, fields = listOf("Two", "Due")))
            repo.saveNote(Note(deckId = deck.id, kind = NoteKind.CLOZE, fields = listOf("{{c1::Three}}", "Tre")))
            val service = AnkiService(app)
            val out = app.getExternalFilesDir(null)!!.resolve("memoro-native-legacy.apkg")
            out.outputStream().use { service.exportApkg(it, repo) }
            ZipFile(out).use { assertNotNull(it.getEntry("collection.anki21")) }
            out.inputStream().use { service.importApkg(it, repo) }
            assertEquals(3, repo.snapshot().notes.size)
            assertEquals(4, repo.snapshot().cards.size)
        } finally { repo.close() }
    }
}
