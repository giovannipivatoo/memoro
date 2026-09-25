// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.data

import java.io.InputStream
import kotlinx.coroutines.flow.Flow

interface MemoroRepository {
    fun observeDecks(): Flow<List<Deck>>
    fun observeNotes(deckId: Long): Flow<List<Note>>
    fun observeCards(deckId: Long): Flow<List<Card>>
    fun observeAttempts(cardId: Long): Flow<List<Attempt>>
    fun observeReviews(cardId: Long): Flow<List<Review>>
    suspend fun getDeck(id: Long): Deck?
    suspend fun getNote(id: Long): Note?
    suspend fun getCard(id: Long): Card?
    suspend fun dueCards(nowMillis: Long, deckId: Long? = null): List<Card>
    suspend fun saveDeck(deck: Deck): Deck
    suspend fun deleteDeck(id: Long)
    suspend fun saveNote(note: Note): Note
    suspend fun deleteNote(id: Long)
    suspend fun saveCard(card: Card): Card
    suspend fun saveAttempt(attempt: Attempt): Attempt
    suspend fun deleteAttemptPersonalData(attemptId: Long)
    suspend fun commitReview(attemptId: Long, rating: Rating, nowMillis: Long): Review
    suspend fun snapshot(): ArchiveSnapshot
    suspend fun importSnapshot(snapshot: ArchiveSnapshot)
    suspend fun restoreSnapshot(snapshot: ArchiveSnapshot)
    suspend fun putFile(path: String, input: InputStream, mimeType: String? = null): StoredFile
    suspend fun openFile(path: String): InputStream?
}

fun gradeExact(answer: String, expected: String): Outcome =
    if (answer.trim().equals(expected.trim(), ignoreCase = true)) Outcome.CORRECT else Outcome.WRONG
