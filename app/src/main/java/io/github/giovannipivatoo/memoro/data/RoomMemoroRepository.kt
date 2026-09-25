// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import io.github.giovannipivatoo.memoro.study.Fsrs6
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

class RoomMemoroRepository private constructor(private val context: Context, private val db: MemoroDatabase) : MemoroRepository {
    private val dao = db.dao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        fun open(context: Context): RoomMemoroRepository {
            val app = context.applicationContext
            return RoomMemoroRepository(app, Room.databaseBuilder(app, MemoroDatabase::class.java, "memoro.db").build())
        }
    }

    fun close() = db.close()

    override fun observeDecks(): Flow<List<Deck>> = dao.decks().map { rows -> rows.map { json.decodeFromString<Deck>(it.body) } }
    override fun observeNotes(deckId: Long): Flow<List<Note>> = dao.notes(deckId).map { rows -> rows.map { json.decodeFromString<Note>(it.body) } }
    override fun observeCards(deckId: Long): Flow<List<Card>> = dao.cards(deckId).map { rows -> rows.map { json.decodeFromString<Card>(it.body) } }
    override fun observeAttempts(cardId: Long): Flow<List<Attempt>> = dao.attempts(cardId).map { rows -> rows.map { json.decodeFromString<Attempt>(it.body) } }
    override fun observeReviews(cardId: Long): Flow<List<Review>> = dao.reviews(cardId).map { rows -> rows.map { json.decodeFromString<Review>(it.body) } }
    override suspend fun getDeck(id: Long): Deck? = dao.deck(id)?.let { json.decodeFromString(it.body) }
    override suspend fun getNote(id: Long): Note? = dao.note(id)?.let { json.decodeFromString(it.body) }
    override suspend fun getCard(id: Long): Card? = dao.card(id)?.let { json.decodeFromString(it.body) }
    override suspend fun dueCards(nowMillis: Long, deckId: Long?): List<Card> = dao.dueCards(nowMillis, deckId).map { json.decodeFromString(it.body) }

    override suspend fun saveDeck(deck: Deck): Deck = db.withTransaction {
        require(deck.name.isNotBlank())
        val id = dao.putDeck(DeckRow(deck.id, deck.anki?.originalId, json.encodeToString(deck), deck.anki?.collectionKey))
        deck.copy(id = if (deck.id == 0L) id else deck.id).also { dao.putDeck(DeckRow(it.id, it.anki?.originalId, json.encodeToString(it), it.anki?.collectionKey)) }
    }

    override suspend fun deleteDeck(id: Long) = db.withTransaction {
        dao.deleteAttemptsForDeck(id); dao.deleteReviewsForDeck(id)
        dao.deleteCardsForDeck(id); dao.deleteNotesForDeck(id); dao.deleteDeck(id)
    }

    override suspend fun saveNote(note: Note): Note = saveNoteInternal(note, importing = false)

    override suspend fun saveNoteAndCards(note: Note, cards: List<Card>): Note = db.withTransaction {
        require(note.id > 0 && note.anki != null) { "Atomic card edit requires an imported note" }
        require(cards.map { it.ordinal }.distinct().size == cards.size)
        val existing = dao.cardsForNote(note.id).associateBy { it.id }
        require(cards.all { it.id > 0 && it.noteId == note.id && it.deckId == existing[it.id]?.deckId })
        require(cards.map { it.id }.toSet() == existing.keys) { "All imported cards must be updated together" }
        val saved = saveNoteInternal(note, importing = false)
        cards.forEach { saveCard(it) }
        saved
    }

    private suspend fun saveNoteInternal(note: Note, importing: Boolean): Note = db.withTransaction {
        require(dao.deck(note.deckId) != null) { "Unknown deck" }
        require(note.fields.isNotEmpty()) { "At least one field required" }
        require(note.anki != null || note.fields.size >= 2) { "Native notes need two fields" }
        val previous = if (note.id != 0L) dao.note(note.id)?.let { json.decodeFromString<Note>(it.body) } else null
        val previousGuid = previous?.guid
        val rawGuid = note.anki?.rawJson?.let { runCatching { JSONObject(it).optString("guid") }.getOrNull() }
        val changed = previous == null || previous.fields != note.fields || previous.source != note.source ||
            previous.tags != note.tags || previous.kind != note.kind || previous.deckId != note.deckId
        val incomingMod = note.modifiedAtMillis.takeIf { it > 0 } ?: note.anki?.rawJson?.let {
            runCatching { JSONObject(it).optLong("mod") * 1000L }.getOrNull()?.takeIf { millis -> millis > 0 }
        } ?: 0L
        val modified = if (importing) incomingMod else if (changed) maxOf(System.currentTimeMillis(), (previous?.modifiedAtMillis ?: 0L) + 1) else previous?.modifiedAtMillis ?: incomingMod
        val prepared = note.copy(
            guid = note.guid?.takeIf { it.isNotBlank() } ?: previousGuid ?: rawGuid?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            locallyEdited = if (importing) note.locallyEdited else note.locallyEdited || previous?.locallyEdited == true || (previous?.anki != null && changed),
            modifiedAtMillis = modified)
        val id = dao.putNote(NoteRow(prepared.id, prepared.deckId, prepared.anki?.originalId, json.encodeToString(prepared), prepared.anki?.collectionKey))
        val saved = prepared.copy(id = if (prepared.id == 0L) id else prepared.id)
        dao.putNote(NoteRow(saved.id, saved.deckId, saved.anki?.originalId, json.encodeToString(saved), saved.anki?.collectionKey))
        if (saved.anki == null) regenerateCards(saved)
        saved
    }

    private suspend fun regenerateCards(note: Note) {
        val existing = dao.cardsForNote(note.id).map { json.decodeFromString<Card>(it.body) }.associateBy { it.ordinal }
        val generated = when (note.kind) {
            NoteKind.BASIC -> listOf(0 to (note.fields[0] to note.fields[1]))
            NoteKind.REVERSE -> listOf(0 to (note.fields[0] to note.fields[1]), 1 to (note.fields[1] to note.fields[0]))
            NoteKind.CLOZE -> {
                val regex = Regex("\\{\\{c(\\d+)::(.*?)(?:::[^}]*)?\\}\\}", RegexOption.DOT_MATCHES_ALL)
                regex.findAll(note.fields[0]).map { it.groupValues[1].toInt() }.distinct().sorted().map { ordinal ->
                    val front = regex.replace(note.fields[0]) { match -> if (match.groupValues[1].toInt() == ordinal) "[…]" else match.groupValues[2] }
                    val back = regex.replace(note.fields[0]) { it.groupValues[2] } +
                        note.fields.getOrNull(1).orEmpty().takeIf { it.isNotBlank() }?.let { "<br>$it" }.orEmpty()
                    (ordinal - 1) to (front to back)
                }.toList()
            }
        }
        for ((ordinal, pair) in generated) {
            val old = existing[ordinal]
            val card = (old ?: Card(noteId = note.id, deckId = note.deckId, ordinal = ordinal, front = "", back = "")).copy(deckId = note.deckId, front = pair.first, back = pair.second, archived = false)
            val id = dao.putCard(CardRow(card.id, card.deckId, card.noteId, card.anki?.originalId, card.scheduling.dueAtMillis, card.scheduling.importedQueue, card.archived, json.encodeToString(card), card.anki?.collectionKey))
            if (card.id == 0L) dao.putCard(CardRow(id, card.deckId, card.noteId, card.anki?.originalId, card.scheduling.dueAtMillis, card.scheduling.importedQueue, card.archived, json.encodeToString(card.copy(id = id)), card.anki?.collectionKey))
        }
        for (old in existing.values.filter { old -> generated.none { it.first == old.ordinal } }) {
            val retired = old.copy(archived = true, deckId = note.deckId)
            dao.putCard(CardRow(retired.id, retired.deckId, retired.noteId, retired.anki?.originalId, retired.scheduling.dueAtMillis, retired.scheduling.importedQueue, true, json.encodeToString(retired), retired.anki?.collectionKey))
        }
    }

    override suspend fun deleteNote(id: Long) = db.withTransaction {
        dao.deleteAttemptsForNote(id); dao.deleteReviewsForNote(id); dao.deleteCardsForNote(id); dao.deleteNote(id)
    }

    override suspend fun saveCard(card: Card): Card = db.withTransaction {
        require(dao.note(card.noteId) != null) { "Unknown note" }
        require(card.modes.isNotEmpty())
        val id = dao.putCard(CardRow(card.id, card.deckId, card.noteId, card.anki?.originalId, card.scheduling.dueAtMillis, card.scheduling.importedQueue, card.archived, json.encodeToString(card), card.anki?.collectionKey))
        card.copy(id = if (card.id == 0L) id else card.id).also { dao.putCard(CardRow(it.id, it.deckId, it.noteId, it.anki?.originalId, it.scheduling.dueAtMillis, it.scheduling.importedQueue, it.archived, json.encodeToString(it), it.anki?.collectionKey)) }
    }

    override suspend fun saveAttempt(attempt: Attempt): Attempt = db.withTransaction {
        val card = dao.card(attempt.cardId) ?: error("Unknown card")
        require(attempt.mode == AnswerMode.CLASSIC || attempt.mode in json.decodeFromString<Card>(card.body).modes)
        require(attempt.state != AttemptState.REVIEWED) { "Use commitReview" }
        if (attempt.id != 0L) {
            val old = dao.attempt(attempt.id)?.let { json.decodeFromString<Attempt>(it.body) } ?: error("Unknown attempt")
            require(old.cardId == attempt.cardId && old.mode == attempt.mode && old.state != AttemptState.REVIEWED)
            require(old.state != AttemptState.EVALUATED || attempt.state == AttemptState.EVALUATED) { "Evaluated attempt cannot become draft" }
        }
        val id = dao.putAttempt(AttemptRow(attempt.id, attempt.cardId, attempt.state.name, json.encodeToString(attempt)))
        attempt.copy(id = if (attempt.id == 0L) id else attempt.id).also { dao.putAttempt(AttemptRow(it.id, it.cardId, it.state.name, json.encodeToString(it))) }
    }

    override suspend fun deleteAttemptPersonalData(attemptId: Long) = db.withTransaction {
        val row = dao.attempt(attemptId) ?: return@withTransaction
        val attempt = json.decodeFromString<Attempt>(row.body)
        if (dao.reviewForAttempt(attemptId) == null) dao.deleteAttempt(attemptId)
        else {
            val scrubbed = attempt.copy(answer = "", automaticOutcome = null, finalOutcome = null, feedback = "")
            dao.putAttempt(AttemptRow(attemptId, attempt.cardId, scrubbed.state.name, json.encodeToString(scrubbed)))
        }
    }

    override suspend fun commitReview(attemptId: Long, rating: Rating, nowMillis: Long): Review = db.withTransaction {
        dao.reviewForAttempt(attemptId)?.let { return@withTransaction json.decodeFromString<Review>(it.body) }
        val attempt = dao.attempt(attemptId)?.let { json.decodeFromString<Attempt>(it.body) } ?: error("Unknown attempt")
        require(attempt.state == AttemptState.EVALUATED) { "Attempt must be evaluated" }
        val card = dao.card(attempt.cardId)?.let { json.decodeFromString<Card>(it.body) } ?: error("Unknown card")
        val after = Fsrs6.review(card.scheduling, rating, nowMillis).copy(
            importedIntervalDays = null, importedQueue = null, importedType = null, importedDue = null)
        val review = Review(attemptId = attemptId, cardId = card.id, rating = rating, reviewedAtMillis = nowMillis, schedulingAfter = after)
        val id = dao.putReview(ReviewRow(0, attemptId, card.id, null, nowMillis, json.encodeToString(review)))
        val saved = review.copy(id = id)
        dao.putReview(ReviewRow(id, attemptId, card.id, null, nowMillis, json.encodeToString(saved)))
        val updatedCard = card.copy(scheduling = after)
        dao.putCard(CardRow(card.id, card.deckId, card.noteId, card.anki?.originalId, after.dueAtMillis, null, card.archived, json.encodeToString(updatedCard), card.anki?.collectionKey))
        val reviewedAttempt = attempt.copy(state = AttemptState.REVIEWED, updatedAtMillis = nowMillis)
        dao.putAttempt(AttemptRow(attempt.id, attempt.cardId, reviewedAttempt.state.name, json.encodeToString(reviewedAttempt)))
        saved
    }

    override suspend fun snapshot(): ArchiveSnapshot = db.withTransaction {
        ArchiveSnapshot(
            ankiCollectionJson = dao.metadata("ankiCollectionJson"),
            originalPackagePath = dao.metadata("originalPackagePath"),
            originalPackagePaths = dao.metadata("originalPackagePaths")?.let { json.decodeFromString<List<String>>(it) } ?: emptyList(),
            decks = dao.allDecks().map { json.decodeFromString(it.body) },
            notes = dao.allNotes().map { json.decodeFromString(it.body) },
            cards = dao.allCards().map { json.decodeFromString(it.body) },
            attempts = dao.allAttempts().map { json.decodeFromString(it.body) },
            reviews = dao.allReviews().map { json.decodeFromString(it.body) },
            files = dao.allFiles().map { json.decodeFromString(it.body) },
        )
    }

    override suspend fun importSnapshot(snapshot: ArchiveSnapshot) = db.withTransaction {
        validateSnapshot(snapshot)
        val deckIds = mutableMapOf<Long, Long>()
        for (deck in snapshot.decks) {
            val current = dao.allDecks().firstOrNull { json.decodeFromString<Deck>(it.body).name == deck.name }
            val saved = saveDeck(deck.copy(id = current?.id ?: 0))
            deckIds[deck.id] = saved.id
        }
        val noteIds = mutableMapOf<Long, Long>()
        for (note in snapshot.notes) {
            val guid = note.guid ?: note.anki?.rawJson?.let { runCatching { JSONObject(it).optString("guid") }.getOrNull() }.orEmpty()
            require(note.anki == null || guid.isNotBlank()) { "Anki note missing GUID: ${note.id}" }
            val current = dao.allNotes().firstOrNull { row -> json.decodeFromString<Note>(row.body).guid == guid }
            val existingModel = current?.let { json.decodeFromString<Note>(it.body).anki?.originalModelId }
            if (existingModel != null && note.anki?.originalModelId != null && existingModel != note.anki.originalModelId) {
                val priorNote = json.decodeFromString<Note>(current!!.body)
                val oldFields = modelFieldNames(dao.metadata("ankiCollectionJson"), existingModel)
                val newFields = modelFieldNames(snapshot.ankiCollectionJson, note.anki.originalModelId)
                require(priorNote.kind == note.kind && oldFields != null && oldFields == newFields) {
                    "Anki GUID/model conflict: $guid"
                }
            }
            val priorNote = current?.let { json.decodeFromString<Note>(it.body) }
            val incomingMod = note.modifiedAtMillis.takeIf { it > 0 } ?: note.anki?.rawJson?.let {
                runCatching { JSONObject(it).optLong("mod") * 1000L }.getOrNull()
            } ?: 0L
            val incoming = note.copy(id = current?.id ?: 0, deckId = deckIds[note.deckId] ?: error("Missing imported deck"), modifiedAtMillis = incomingMod)
            val keepLocalFields = priorNote?.locallyEdited == true && priorNote.modifiedAtMillis >= incomingMod
            val merged = incoming.copy(
                deckId = if (keepLocalFields) priorNote!!.deckId else incoming.deckId,
                fields = if (keepLocalFields) priorNote!!.fields else incoming.fields,
                source = priorNote?.source?.takeUnless { it == SourceReference() } ?: incoming.source,
                tags = if (keepLocalFields) priorNote!!.tags else incoming.tags,
                kind = if (keepLocalFields) priorNote!!.kind else incoming.kind,
                locallyEdited = keepLocalFields,
                modifiedAtMillis = if (keepLocalFields) priorNote!!.modifiedAtMillis else incomingMod,
                anki = if (keepLocalFields) priorNote!!.anki ?: incoming.anki else incoming.anki)
            val saved = saveNoteInternal(merged, importing = true)
            noteIds[note.id] = saved.id
        }
        val cardIds = mutableMapOf<Long, Long>()
        for (card in snapshot.cards) {
            val mappedNoteId = noteIds[card.noteId] ?: error("Missing imported note")
            val current = dao.cardsForNote(mappedNoteId).firstOrNull { row -> json.decodeFromString<Card>(row.body).ordinal == card.ordinal }
            val currentCard = current?.let { json.decodeFromString<Card>(it.body) }
            val hasLocalProgress = current != null && dao.lastLocalReview(current.id) != null
            val imported = card.copy(id = current?.id ?: 0, noteId = mappedNoteId, deckId = deckIds[card.deckId] ?: error("Missing imported deck"))
            val localNote = dao.note(mappedNoteId)?.let { json.decodeFromString<Note>(it.body) }
            val saved = saveCard(imported.copy(
                deckId = if (localNote?.locallyEdited == true) localNote.deckId else imported.deckId,
                scheduling = if (hasLocalProgress) currentCard!!.scheduling else imported.scheduling,
                modes = currentCard?.modes ?: imported.modes,
                front = if (localNote?.locallyEdited == true) currentCard?.front ?: imported.front else imported.front,
                back = if (localNote?.locallyEdited == true) currentCard?.back ?: imported.back else imported.back))
            cardIds[card.id] = saved.id
        }
        for (review in snapshot.reviews) {
            val id = review.anki?.originalId ?: continue
            val mappedCardId = cardIds[review.cardId] ?: error("Missing imported card")
            val native = dao.review(id)?.takeIf { it.cardId == mappedCardId }
            if (native?.attemptId != null) continue
            if (dao.localReviewsAt(mappedCardId, review.reviewedAtMillis).any { json.decodeFromString<Review>(it.body).rating == review.rating }) continue
            val current = dao.reviewForAnkiCard(id, mappedCardId)
            val mapped = review.copy(id = current?.id ?: 0, cardId = mappedCardId, attemptId = null)
            val rowId = dao.putReview(ReviewRow(mapped.id, null, mapped.cardId, id, mapped.reviewedAtMillis, json.encodeToString(mapped), mapped.anki?.collectionKey))
            dao.putReview(ReviewRow(rowId, null, mapped.cardId, id, mapped.reviewedAtMillis, json.encodeToString(mapped.copy(id = rowId)), mapped.anki?.collectionKey))
        }
        for (file in snapshot.files) dao.putFile(FileRow(file.path, json.encodeToString(file)))
        snapshot.ankiCollectionJson?.let { dao.putMetadata(MetadataRow("ankiCollectionJson", it)) }
        snapshot.originalPackagePath?.let { dao.putMetadata(MetadataRow("originalPackagePath", it)) }
        val priorPackages = dao.metadata("originalPackagePaths")?.let { json.decodeFromString<List<String>>(it) } ?: emptyList()
        val packagePaths = (priorPackages + snapshot.originalPackagePaths + listOfNotNull(snapshot.originalPackagePath)).distinct()
        if (packagePaths.isNotEmpty()) dao.putMetadata(MetadataRow("originalPackagePaths", json.encodeToString(packagePaths)))
    }

    override suspend fun restoreSnapshot(snapshot: ArchiveSnapshot, fileRootName: String?) = db.withTransaction {
        validateSnapshot(snapshot)
        val activeFileRoot = fileRootName ?: dao.metadata("activeFileRoot") ?: "archive-files"
        require(activeFileRoot.matches(Regex("archive-files(?:-[0-9a-f]+)?")))
        dao.clearReviews(); dao.clearAttempts(); dao.clearCards(); dao.clearNotes(); dao.clearDecks(); dao.clearFiles(); dao.clearMetadata()
        snapshot.decks.forEach { dao.putDeck(DeckRow(it.id, it.anki?.originalId, json.encodeToString(it), it.anki?.collectionKey)) }
        snapshot.notes.forEach { dao.putNote(NoteRow(it.id, it.deckId, it.anki?.originalId, json.encodeToString(it), it.anki?.collectionKey)) }
        snapshot.cards.forEach { dao.putCard(CardRow(it.id, it.deckId, it.noteId, it.anki?.originalId, it.scheduling.dueAtMillis, it.scheduling.importedQueue, it.archived, json.encodeToString(it), it.anki?.collectionKey)) }
        snapshot.attempts.forEach { dao.putAttempt(AttemptRow(it.id, it.cardId, it.state.name, json.encodeToString(it))) }
        snapshot.reviews.forEach { dao.putReview(ReviewRow(it.id, it.attemptId, it.cardId, it.anki?.originalId, it.reviewedAtMillis, json.encodeToString(it), it.anki?.collectionKey)) }
        snapshot.files.forEach { dao.putFile(FileRow(it.path, json.encodeToString(it))) }
        snapshot.ankiCollectionJson?.let { dao.putMetadata(MetadataRow("ankiCollectionJson", it)) }
        snapshot.originalPackagePath?.let { dao.putMetadata(MetadataRow("originalPackagePath", it)) }
        if (snapshot.originalPackagePaths.isNotEmpty()) dao.putMetadata(MetadataRow("originalPackagePaths", json.encodeToString(snapshot.originalPackagePaths)))
        dao.putMetadata(MetadataRow("activeFileRoot", activeFileRoot))
    }

    override suspend fun putFile(path: String, input: InputStream, mimeType: String?): StoredFile = withContext(Dispatchers.IO) {
        db.withTransaction {
            val file = safeFile(path, dao.metadata("activeFileRoot") ?: "archive-files")
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            try {
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        size += count
                        require(size <= 512_000_000L) { "File too large" }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                val current = dao.file(path)?.let { json.decodeFromString<StoredFile>(it.body) }
                if (current != null) require(current.sha256 == sha) { "File path collision: $path" }
                if (file.exists()) {
                    if (current == null) try {
                        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } else check(temp.renameTo(file)) { "Could not store file" }
                StoredFile(path, sha, size, mimeType).also {
                    dao.putFile(FileRow(path, json.encodeToString(it)))
                }
            } finally { temp.delete() }
        }
    }

    override suspend fun openFile(path: String): InputStream? = withContext(Dispatchers.IO) {
        if (dao.file(path) == null) null else safeFile(path, dao.metadata("activeFileRoot") ?: "archive-files").takeIf { it.isFile }?.inputStream()
    }

    private fun safeFile(path: String, rootName: String): File {
        require(rootName.matches(Regex("archive-files(?:-[0-9a-f]+)?")))
        val blobRoot = File(context.filesDir, rootName)
        require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it.isBlank() || it == "." || it == ".." || '\\' in it })
        val target = File(blobRoot, path).canonicalFile
        require(target.path.startsWith(blobRoot.canonicalPath + File.separator))
        return target
    }
}

private fun modelFieldNames(collectionJson: String?, modelId: Long): List<String>? = runCatching {
    val fields = JSONObject(collectionJson ?: return null).optJSONObject("models")
        ?.optJSONObject(modelId.toString())?.optJSONArray("flds") ?: return null
    (0 until fields.length()).map { fields.optJSONObject(it)?.optString("name").orEmpty() }
}.getOrNull()

fun validateSnapshot(snapshot: ArchiveSnapshot) {
    require(snapshot.version == 1) { "Unsupported backup version" }
    fun <T> unique(values: List<T>) { require(values.size == values.toSet().size) { "Duplicate IDs" } }
    require(snapshot.decks.all { it.id > 0 } && snapshot.notes.all { it.id > 0 } && snapshot.cards.all { it.id > 0 })
    require(snapshot.attempts.all { it.id > 0 } && snapshot.reviews.all { it.id > 0 })
    unique(snapshot.decks.map { it.id }); unique(snapshot.notes.map { it.id }); unique(snapshot.cards.map { it.id })
    unique(snapshot.attempts.map { it.id }); unique(snapshot.reviews.map { it.id }); unique(snapshot.files.map { it.path })
    val decks = snapshot.decks.map { it.id }.toSet()
    val notes = snapshot.notes.map { it.id }.toSet()
    val cards = snapshot.cards.map { it.id }.toSet()
    val attempts = snapshot.attempts.map { it.id }.toSet()
    require(snapshot.notes.all { it.deckId in decks })
    require(snapshot.cards.all { it.noteId in notes && it.deckId in decks })
    require(snapshot.attempts.all { it.cardId in cards })
    require(snapshot.reviews.all { it.cardId in cards && (it.attemptId == null || it.attemptId in attempts) })
    require(snapshot.reviews.mapNotNull { it.attemptId }.distinct().size == snapshot.reviews.count { it.attemptId != null })
    val attemptCards = snapshot.attempts.associate { it.id to it.cardId }
    require(snapshot.reviews.all { it.attemptId == null || attemptCards[it.attemptId] == it.cardId })
    val reviewedIds = snapshot.reviews.mapNotNull { it.attemptId }.toSet()
    require(snapshot.attempts.all { (it.state == AttemptState.REVIEWED) == (it.id in reviewedIds) })
    require(snapshot.files.all { it.path.isNotBlank() && !it.path.startsWith('/') && it.path.split('/').none { segment -> segment.isBlank() || segment == "." || segment == ".." || '\\' in segment } })
    require(snapshot.files.all { it.size >= 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) })
    val files = snapshot.files.map { it.path }.toSet()
    require(snapshot.originalPackagePath == null || snapshot.originalPackagePath in files)
    require(snapshot.originalPackagePaths.all { it in files })
}
