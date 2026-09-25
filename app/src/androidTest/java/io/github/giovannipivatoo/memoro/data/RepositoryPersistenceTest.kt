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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryPersistenceTest {
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
            assertTrue(!bytes.toString(Charsets.ISO_8859_1).contains("test-key-sentinel-98765"))
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
