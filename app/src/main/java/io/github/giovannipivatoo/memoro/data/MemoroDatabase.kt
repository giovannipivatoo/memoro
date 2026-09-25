// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "decks") data class DeckRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val ankiId: Long? = null, val body: String, val collectionKey: String? = null)
@Entity(tableName = "notes") data class NoteRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val deckId: Long, val ankiId: Long? = null, val body: String, val collectionKey: String? = null)
@Entity(tableName = "cards") data class CardRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val deckId: Long, val noteId: Long, val ankiId: Long? = null, val dueAtMillis: Long, val importedQueue: Int? = null, val archived: Boolean = false, val body: String, val collectionKey: String? = null)
@Entity(tableName = "attempts") data class AttemptRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val cardId: Long, val state: String, val body: String)
@Entity(tableName = "reviews", indices = [Index(value = ["attemptId"], unique = true)]) data class ReviewRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val attemptId: Long? = null, val cardId: Long, val ankiId: Long? = null, val reviewedAtMillis: Long, val body: String, val collectionKey: String? = null)
@Entity(tableName = "files") data class FileRow(@PrimaryKey val path: String, val body: String)
@Entity(tableName = "metadata") data class MetadataRow(@PrimaryKey val key: String, val value: String)

@Dao interface MemoroDao {
    @Query("SELECT * FROM decks ORDER BY id") fun decks(): Flow<List<DeckRow>>
    @Query("SELECT * FROM notes WHERE deckId=:deckId ORDER BY id") fun notes(deckId: Long): Flow<List<NoteRow>>
    @Query("SELECT * FROM cards WHERE deckId=:deckId ORDER BY id") fun cards(deckId: Long): Flow<List<CardRow>>
    @Query("SELECT * FROM attempts WHERE cardId=:cardId ORDER BY id DESC") fun attempts(cardId: Long): Flow<List<AttemptRow>>
    @Query("SELECT * FROM reviews WHERE cardId=:cardId ORDER BY reviewedAtMillis DESC") fun reviews(cardId: Long): Flow<List<ReviewRow>>
    @Query("SELECT * FROM decks WHERE id=:id") suspend fun deck(id: Long): DeckRow?
    @Query("SELECT * FROM notes WHERE id=:id") suspend fun note(id: Long): NoteRow?
    @Query("SELECT * FROM cards WHERE id=:id") suspend fun card(id: Long): CardRow?
    @Query("SELECT * FROM cards WHERE noteId=:id") suspend fun cardsForNote(id: Long): List<CardRow>
    @Query("SELECT * FROM attempts WHERE id=:id") suspend fun attempt(id: Long): AttemptRow?
    @Query("SELECT * FROM reviews WHERE id=:id") suspend fun review(id: Long): ReviewRow?
    @Query("SELECT * FROM reviews WHERE attemptId=:id LIMIT 1") suspend fun reviewForAttempt(id: Long): ReviewRow?
    @Query("SELECT * FROM decks WHERE ankiId=:id AND collectionKey IS :collectionKey LIMIT 1") suspend fun deckForAnki(id: Long, collectionKey: String?): DeckRow?
    @Query("SELECT * FROM notes WHERE ankiId=:id AND collectionKey IS :collectionKey LIMIT 1") suspend fun noteForAnki(id: Long, collectionKey: String?): NoteRow?
    @Query("SELECT * FROM cards WHERE ankiId=:id AND collectionKey IS :collectionKey LIMIT 1") suspend fun cardForAnki(id: Long, collectionKey: String?): CardRow?
    @Query("SELECT * FROM reviews WHERE ankiId=:id AND cardId=:cardId LIMIT 1") suspend fun reviewForAnkiCard(id: Long, cardId: Long): ReviewRow?
    @Query("SELECT * FROM reviews WHERE cardId=:id AND attemptId IS NOT NULL ORDER BY reviewedAtMillis DESC LIMIT 1") suspend fun lastLocalReview(id: Long): ReviewRow?
    @Query("SELECT * FROM reviews WHERE cardId=:cardId AND reviewedAtMillis=:reviewedAtMillis AND attemptId IS NOT NULL") suspend fun localReviewsAt(cardId: Long, reviewedAtMillis: Long): List<ReviewRow>
    @Query("SELECT * FROM cards WHERE dueAtMillis<=:nowMillis AND archived=0 AND (importedQueue IS NULL OR importedQueue>=0) AND (:deckId IS NULL OR deckId=:deckId) ORDER BY dueAtMillis,id") suspend fun dueCards(nowMillis: Long, deckId: Long?): List<CardRow>
    @Query("SELECT * FROM decks") suspend fun allDecks(): List<DeckRow>
    @Query("SELECT * FROM notes") suspend fun allNotes(): List<NoteRow>
    @Query("SELECT * FROM cards") suspend fun allCards(): List<CardRow>
    @Query("SELECT * FROM attempts") suspend fun allAttempts(): List<AttemptRow>
    @Query("SELECT * FROM reviews") suspend fun allReviews(): List<ReviewRow>
    @Query("SELECT * FROM files") suspend fun allFiles(): List<FileRow>
    @Query("SELECT * FROM files WHERE path=:path") suspend fun file(path: String): FileRow?
    @Query("SELECT value FROM metadata WHERE `key`=:key") suspend fun metadata(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDeck(row: DeckRow): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putNote(row: NoteRow): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCard(row: CardRow): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAttempt(row: AttemptRow): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putReview(row: ReviewRow): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFile(row: FileRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMetadata(row: MetadataRow)
    @Query("DELETE FROM decks WHERE id=:id") suspend fun deleteDeck(id: Long)
    @Query("DELETE FROM notes WHERE deckId=:id") suspend fun deleteNotesForDeck(id: Long)
    @Query("DELETE FROM notes WHERE id=:id") suspend fun deleteNote(id: Long)
    @Query("DELETE FROM attempts WHERE id=:id") suspend fun deleteAttempt(id: Long)
    @Query("DELETE FROM cards WHERE deckId=:id") suspend fun deleteCardsForDeck(id: Long)
    @Query("DELETE FROM cards WHERE noteId=:id") suspend fun deleteCardsForNote(id: Long)
    @Query("DELETE FROM attempts WHERE cardId IN (SELECT id FROM cards WHERE deckId=:id)") suspend fun deleteAttemptsForDeck(id: Long)
    @Query("DELETE FROM reviews WHERE cardId IN (SELECT id FROM cards WHERE deckId=:id)") suspend fun deleteReviewsForDeck(id: Long)
    @Query("DELETE FROM attempts WHERE cardId IN (SELECT id FROM cards WHERE noteId=:id)") suspend fun deleteAttemptsForNote(id: Long)
    @Query("DELETE FROM reviews WHERE cardId IN (SELECT id FROM cards WHERE noteId=:id)") suspend fun deleteReviewsForNote(id: Long)
    @Query("DELETE FROM reviews") suspend fun clearReviews()
    @Query("DELETE FROM attempts") suspend fun clearAttempts()
    @Query("DELETE FROM cards") suspend fun clearCards()
    @Query("DELETE FROM notes") suspend fun clearNotes()
    @Query("DELETE FROM decks") suspend fun clearDecks()
    @Query("DELETE FROM files") suspend fun clearFiles()
    @Query("DELETE FROM metadata") suspend fun clearMetadata()
}

@Database(entities = [DeckRow::class, NoteRow::class, CardRow::class, AttemptRow::class, ReviewRow::class, FileRow::class, MetadataRow::class], version = 1, exportSchema = false)
abstract class MemoroDatabase : RoomDatabase() { abstract fun dao(): MemoroDao }
