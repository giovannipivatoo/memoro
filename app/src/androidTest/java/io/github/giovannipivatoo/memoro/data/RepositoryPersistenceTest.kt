// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class RepositoryPersistenceTest {
    @Test fun versionOneBackupDecodesWithoutNewFields() {
        val legacy = """{"version":1,"decks":[{"id":1,"name":"Old"}],"notes":[{"id":2,"deckId":1,"fields":["Q","A"]}],"cards":[{"id":3,"noteId":2,"deckId":1,"front":"Q","back":"A"}],"attempts":[{"id":4,"cardId":3,"mode":"CLASSIC","createdAtMillis":1}]}"""
        val snapshot = Json.decodeFromString<ArchiveSnapshot>(legacy)
        validateSnapshot(snapshot)
        assertEquals(1, snapshot.version)
        assertEquals(null, snapshot.notes.single().multipleChoice)
        assertEquals(false, snapshot.attempts.single().isPractice)
        assertEquals(2, ArchiveSnapshot().version)
    }

    @Test fun multipleChoiceValidationAndPracticeRoundTrip() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("memoro.db")
        val repo = RoomMemoroRepository.open(context)
        try {
            val deck = repo.saveDeck(Deck(name = "Practice"))
            val choice = MultipleChoice(listOf("Roma", "Milano", "Torino"), 0)
            val invalid = Note(deckId = deck.id, fields = listOf("Capitale?", "Milano"), multipleChoice = choice)
            assertTrue(runCatching { repo.saveNote(invalid) }.isFailure)
            assertTrue(runCatching { repo.saveNote(invalid.copy(fields = listOf("Capitale?", "Roma"),
                multipleChoice = MultipleChoice(listOf("Roma", " roma "), 0))) }.isFailure)
            val note = repo.saveNote(invalid.copy(fields = listOf("Capitale?", "Roma")))
            val card = repo.getCard(repo.snapshot().cards.single().id)!!
            assertEquals(setOf(AnswerMode.MULTIPLE_CHOICE), card.modes)
            val before = card.scheduling
            val now = System.currentTimeMillis()
            val attempt = repo.saveAttempt(Attempt(cardId = card.id, mode = AnswerMode.MULTIPLE_CHOICE,
                answer = "Roma", automaticOutcome = Outcome.CORRECT, finalOutcome = Outcome.CORRECT,
                state = AttemptState.EVALUATED, createdAtMillis = now, isPractice = true))
            assertTrue(runCatching { repo.commitReview(attempt.id, Rating.GOOD, now) }.isFailure)
            val completed = repo.finishPractice(attempt.id, now + 1)
            assertEquals(AttemptState.PRACTICED, completed.state)
            assertEquals(completed, repo.finishPractice(attempt.id, now + 2))
            assertTrue(runCatching { repo.saveAttempt(completed.copy(state = AttemptState.EVALUATED)) }.isFailure)
            assertEquals(before, repo.getCard(card.id)!!.scheduling)
            assertTrue(repo.snapshot().reviews.isEmpty())
            assertEquals(choice, repo.getNote(note.id)!!.multipleChoice)
            val backup = ByteArrayOutputStream().also { BackupManager(context, repo).export(it) }.toByteArray()
            BackupManager(context, repo).restore(ByteArrayInputStream(backup))
            assertEquals(choice, repo.getNote(note.id)!!.multipleChoice)
            assertEquals(AttemptState.PRACTICED, repo.snapshot().attempts.single().state)
            assertEquals(before, repo.getCard(card.id)!!.scheduling)
            repo.saveNote(note.copy(multipleChoice = null))
            assertEquals(setOf(AnswerMode.WRITTEN), repo.getCard(card.id)!!.modes)
            assertEquals(before, repo.getCard(card.id)!!.scheduling)
            repo.saveNote(note.copy(multipleChoice = null), preferredMode = AnswerMode.CLASSIC)
            assertEquals(setOf(AnswerMode.CLASSIC), repo.getCard(card.id)!!.modes)
        } finally { repo.close() }
    }

    @Test fun reimportUsesGuidAndPreservesNewerLocalEdit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("memoro.db")
        val repo = RoomMemoroRepository.open(context)
        try {
            val raw = JSONObject().put("guid", "source-guid-1").put("mod", 1).toString()
            val imported = ArchiveSnapshot(
                decks = listOf(Deck(id = 1, name = "Shared", anki = AnkiMetadata(originalId = 1))),
                notes = listOf(Note(id = 2, guid = "source-guid-1", deckId = 1, fields = listOf("Original", "A"),
                    anki = AnkiMetadata(originalId = 2, originalModelId = 9, rawJson = raw))),
                cards = listOf(Card(id = 3, noteId = 2, deckId = 1, front = "Original", back = "A",
                    anki = AnkiMetadata(originalId = 3))),
            )
            repo.importSnapshot(imported)
            val note = repo.snapshot().notes.single()
            assertTrue(runCatching { repo.saveNoteAndCards(note.copy(fields = listOf("Rejected", "A")), emptyList()) }.isFailure)
            assertEquals("Original", repo.snapshot().notes.single().fields[0])
            val card = repo.snapshot().cards.single()
            repo.saveNoteAndCards(note.copy(fields = listOf("Local edit", "A")), listOf(card.copy(front = "Local edit")))
            repo.importSnapshot(imported)
            assertEquals("Local edit", repo.snapshot().notes.single().fields[0])
            assertEquals("Local edit", repo.snapshot().cards.single().front)
            assertEquals(1, repo.snapshot().notes.size)
            assertEquals(1, repo.snapshot().cards.size)
            val futureRaw = JSONObject().put("guid", "source-guid-1")
                .put("mod", System.currentTimeMillis() / 1000 + 3600).toString()
            repo.importSnapshot(imported.copy(notes = listOf(imported.notes.single().copy(
                fields = listOf("Remote edit", "A"), anki = imported.notes.single().anki!!.copy(rawJson = futureRaw))),
                cards = listOf(imported.cards.single().copy(front = "Remote edit"))))
            assertEquals("Remote edit", repo.snapshot().notes.single().fields[0])
            assertEquals("Remote edit", repo.snapshot().cards.single().front)
        } finally { repo.close() }
    }

    @Test fun clozeGenerationUsesZeroBasedOrdinalsOnAndroid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("memoro.db")
        val repo = RoomMemoroRepository.open(context)
        try {
            val deck = repo.saveDeck(Deck(name = "Cloze"))
            repo.saveNote(Note(deckId = deck.id, kind = NoteKind.CLOZE,
                fields = listOf("{{c1::Roma}} è in {{c2::Italia}}", "[audio:media/example.mp3]")))
            val cards = repo.dueCards(System.currentTimeMillis(), deck.id)
            assertEquals(listOf(0, 1), cards.map { it.ordinal })
            assertEquals("[…] è in Italia", cards[0].front)
            assertEquals("Roma è in […]", cards[1].front)
            assertTrue(cards[0].back.endsWith("<br>[audio:media/example.mp3]"))
        } finally { repo.close() }
    }

    @Test fun concurrentReviewAndBackupRestoreSurviveReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("memoro.db")
        val repo = RoomMemoroRepository.open(context)
        try {
            val deck = repo.saveDeck(Deck(name = "Test"))
            val note = repo.saveNote(Note(deckId = deck.id, fields = listOf("Q", "A")))
            val card = repo.dueCards(System.currentTimeMillis(), deck.id).single()
            assertEquals(note.id, card.noteId)
            val now = System.currentTimeMillis()
            val attempt = repo.saveAttempt(Attempt(cardId = card.id, mode = AnswerMode.CLASSIC,
                answer = "private answer", feedback = "private feedback", automaticOutcome = Outcome.CORRECT,
                state = AttemptState.EVALUATED, createdAtMillis = now))
            val first = async(Dispatchers.Default) { repo.commitReview(attempt.id, Rating.GOOD, now) }
            val second = async(Dispatchers.Default) { repo.commitReview(attempt.id, Rating.AGAIN, now + 1) }
            assertEquals(first.await().id, second.await().id)
            assertEquals(1, repo.snapshot().reviews.size)
            repo.putFile("media/test.txt", ByteArrayInputStream("stored".toByteArray()), "text/plain")
            val credentials = AiCredentialStore(context)
            credentials.save("test-key-sentinel-98765")
            val bytes = ByteArrayOutputStream().also { BackupManager(context, repo).export(it) }.toByteArray()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    assertTrue(!entry.name.contains("credential") && !entry.name.contains("key"))
                    assertTrue(!zip.readBytes().toString(Charsets.UTF_8).contains("test-key-sentinel-98765"))
                    zip.closeEntry()
                }
            }
            val corrupt = bytes.copyOf(bytes.size / 2)
            val failed = runCatching { BackupManager(context, repo).restore(ByteArrayInputStream(corrupt)) }
            assertTrue(failed.isFailure)
            assertEquals(1, repo.snapshot().reviews.size)
            assertEquals("stored", repo.openFile("media/test.txt")!!.bufferedReader().use { it.readText() })
            val digestMismatch = rewriteZip(bytes, "files/media/test.txt", "tampered".toByteArray())
            assertTrue(runCatching { BackupManager(context, repo).restore(ByteArrayInputStream(digestMismatch)) }.isFailure)
            assertEquals("stored", repo.openFile("media/test.txt")!!.bufferedReader().use { it.readText() })
            val traversal = rewriteZip(bytes, "files/media/test.txt", "bad".toByteArray(), "../escape")
            assertTrue(runCatching { BackupManager(context, repo).restore(ByteArrayInputStream(traversal)) }.isFailure)
            assertEquals(1, repo.snapshot().reviews.size)
            BackupManager(context, repo).restore(ByteArrayInputStream(bytes))
            assertEquals(1, repo.snapshot().reviews.size)
            assertEquals("stored", repo.openFile("media/test.txt")!!.bufferedReader().use { it.readText() })
            assertEquals("test-key-sentinel-98765", credentials.read())
            repo.deleteAttemptPersonalData(attempt.id)
            assertEquals("", repo.snapshot().attempts.single().answer)
            assertEquals("", repo.snapshot().attempts.single().feedback)
            assertEquals(null, repo.snapshot().attempts.single().automaticOutcome)
            credentials.clear()
        } finally { repo.close() }
        val reopened = RoomMemoroRepository.open(context)
        try {
            assertNotNull(reopened.getDeck(reopened.snapshot().decks.single().id))
            assertEquals(1, reopened.snapshot().reviews.size)
            assertEquals("stored", reopened.openFile("media/test.txt")!!.bufferedReader().use { it.readText() })
        } finally { reopened.close() }
    }

    private fun rewriteZip(original: ByteArray, target: String, contents: ByteArray, newPath: String = target): ByteArray {
        val output = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(original)).use { source ->
            ZipOutputStream(output).use { destination ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    destination.putNextEntry(ZipEntry(if (entry.name == target) newPath else entry.name))
                    if (entry.name == target) destination.write(contents) else source.copyTo(destination)
                    destination.closeEntry()
                    source.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }
}
