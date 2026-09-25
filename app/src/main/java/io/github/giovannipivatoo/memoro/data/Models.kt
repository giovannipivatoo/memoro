// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.data

import kotlinx.serialization.Serializable

@Serializable enum class NoteKind { BASIC, REVERSE, CLOZE }
@Serializable enum class AnswerMode { CLASSIC, EXACT, AI, WRITTEN, MULTIPLE_CHOICE }
@Serializable enum class Outcome { CORRECT, PARTIAL, WRONG, UNGRADABLE }
@Serializable enum class Rating { AGAIN, HARD, GOOD, EASY }
@Serializable enum class AttemptState { DRAFT, EVALUATED, REVIEWED, PRACTICED }

@Serializable data class MultipleChoice(val options: List<String>, val correctIndex: Int)

@Serializable data class AnkiMetadata(
    val originalId: Long? = null,
    val packageSha256: String? = null,
    val collectionKey: String? = null,
    val originalDeckId: Long? = null,
    val originalModelId: Long? = null,
    val originalOrdinal: Int? = null,
    val rawJson: String? = null,
)

@Serializable data class SourceReference(
    val excerpt: String = "",
    val title: String = "",
    val url: String = "",
    val page: String = "",
    val essentialPoints: String = "",
)

@Serializable data class Deck(
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val anki: AnkiMetadata? = null,
)

@Serializable data class Note(
    val id: Long = 0,
    val guid: String? = null,
    val deckId: Long,
    val kind: NoteKind = NoteKind.BASIC,
    val fields: List<String>,
    val source: SourceReference = SourceReference(),
    val tags: List<String> = emptyList(),
    val locallyEdited: Boolean = false,
    val modifiedAtMillis: Long = 0,
    val anki: AnkiMetadata? = null,
    val multipleChoice: MultipleChoice? = null,
)

@Serializable data class Scheduling(
    val dueAtMillis: Long = 0,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val lastReviewAtMillis: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
    val learningStep: Int? = null,
    val relearning: Boolean = false,
    val importedIntervalDays: Int? = null,
    val importedQueue: Int? = null,
    val importedType: Int? = null,
    val importedDue: Long? = null,
)

@Serializable data class Card(
    val id: Long = 0,
    val noteId: Long,
    val deckId: Long,
    val ordinal: Int = 0,
    val front: String,
    val back: String,
    val modes: Set<AnswerMode> = setOf(AnswerMode.CLASSIC),
    val archived: Boolean = false,
    val scheduling: Scheduling = Scheduling(),
    val anki: AnkiMetadata? = null,
)

@Serializable data class Attempt(
    val id: Long = 0,
    val cardId: Long,
    val mode: AnswerMode,
    val answer: String = "",
    val automaticOutcome: Outcome? = null,
    val finalOutcome: Outcome? = null,
    val feedback: String = "",
    val state: AttemptState = AttemptState.DRAFT,
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
    val isPractice: Boolean = false,
)

@Serializable data class Review(
    val id: Long = 0,
    val attemptId: Long? = null,
    val cardId: Long,
    val rating: Rating,
    val reviewedAtMillis: Long,
    val schedulingAfter: Scheduling,
    val anki: AnkiMetadata? = null,
)

@Serializable data class StoredFile(
    val path: String,
    val sha256: String,
    val size: Long,
    val mimeType: String? = null,
)

@Serializable data class ArchiveSnapshot(
    val version: Int = 2,
    val ankiCollectionJson: String? = null,
    val originalPackagePath: String? = null,
    val originalPackagePaths: List<String> = emptyList(),
    val decks: List<Deck> = emptyList(),
    val notes: List<Note> = emptyList(),
    val cards: List<Card> = emptyList(),
    val attempts: List<Attempt> = emptyList(),
    val reviews: List<Review> = emptyList(),
    val files: List<StoredFile> = emptyList(),
)
