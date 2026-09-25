// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.anki

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.github.luben.zstd.ZstdOutputStream
import io.github.giovannipivatoo.memoro.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class AnkiExporter(private val context: Context) {
    suspend fun export(output: OutputStream, repository: MemoroRepository): ExportReport = withContext(Dispatchers.IO) {
        val snapshot = repository.snapshot()
        val metadata = jsonObject(snapshot.ankiCollectionJson)
        val modern = metadata.optString("format") == "modern"
        val dbFile = File.createTempFile("memoro-export-", ".db", context.cacheDir)
        val warnings = mutableListOf<String>()
        try {
            createLegacyDb(dbFile, metadata)
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                db.beginTransaction()
                try {
                    db.execSQL("DELETE FROM revlog")
                    db.execSQL("DELETE FROM cards")
                    db.execSQL("DELETE FROM notes")
                    writeEntities(db, snapshot, metadata, warnings)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                db.rawQuery("PRAGMA integrity_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") }
            } finally { db.close() }
            val media = snapshot.files.filter { it.path.startsWith("media/") && safeMediaName(it.path.removePrefix("media/")) }
            val mediaRecords = media.map { file ->
                val digest = MessageDigest.getInstance("SHA-1")
                var size = 0L
                val source = repository.openFile(file.path) ?: error("Media assente: ${file.path}")
                source.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); size += n }
                }
                AnkiWire.MediaRecord(file.path.removePrefix("media/"), size, digest.digest())
            }
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("meta")); zip.write(AnkiWire.encodeVersion(if (modern) 3 else 2)); zip.closeEntry()
                zip.putNextEntry(ZipEntry(if (modern) "collection.anki21b" else "collection.anki21"))
                dbFile.inputStream().use { input ->
                    if (modern) ZstdOutputStream(nonClosing(zip)).use { input.copyTo(it) }
                    else input.copyTo(zip)
                }
                zip.closeEntry()
                val names = mediaRecords.map { it.name }
                zip.putNextEntry(ZipEntry("media"))
                val mediaMap = if (modern) AnkiWire.encodeMediaMap(mediaRecords) else JSONObject(names.withIndex().associate { it.index.toString() to it.value }).toString().toByteArray()
                if (modern) ZstdOutputStream(nonClosing(zip)).use { it.write(mediaMap) } else zip.write(mediaMap)
                zip.closeEntry()
                for ((index, file) in media.withIndex()) {
                    val source = repository.openFile(file.path) ?: error("Media assente: ${file.path}")
                    zip.putNextEntry(ZipEntry(index.toString()))
                    source.use { if (modern) ZstdOutputStream(nonClosing(zip)).use { z -> it.copyTo(z) } else it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return@withContext ExportReport(snapshot.notes.size, snapshot.cards.size, media.size, warnings.distinct())
        } finally { dbFile.delete() }
    }

    private fun createLegacyDb(file: File, metadata: JSONObject) {
        val col = metadata.optJSONObject("col") ?: JSONObject()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE col (id integer PRIMARY KEY, crt integer NOT NULL, mod integer NOT NULL, scm integer NOT NULL, ver integer NOT NULL, dty integer NOT NULL, usn integer NOT NULL, ls integer NOT NULL, conf text NOT NULL, models text NOT NULL, decks text NOT NULL, dconf text NOT NULL, tags text NOT NULL)")
            db.execSQL("CREATE TABLE notes (id integer PRIMARY KEY, guid text NOT NULL, mid integer NOT NULL, mod integer NOT NULL, usn integer NOT NULL, tags text NOT NULL, flds text NOT NULL, sfld text NOT NULL, csum integer NOT NULL, flags integer NOT NULL, data text NOT NULL)")
            db.execSQL("CREATE TABLE cards (id integer PRIMARY KEY, nid integer NOT NULL, did integer NOT NULL, ord integer NOT NULL, mod integer NOT NULL, usn integer NOT NULL, type integer NOT NULL, queue integer NOT NULL, due integer NOT NULL, ivl integer NOT NULL, factor integer NOT NULL, reps integer NOT NULL, lapses integer NOT NULL, left integer NOT NULL, odue integer NOT NULL, odid integer NOT NULL, flags integer NOT NULL, data text NOT NULL)")
            db.execSQL("CREATE TABLE revlog (id integer PRIMARY KEY, cid integer NOT NULL, usn integer NOT NULL, ease integer NOT NULL, ivl integer NOT NULL, lastIvl integer NOT NULL, factor integer NOT NULL, time integer NOT NULL, type integer NOT NULL)")
            db.execSQL("CREATE TABLE graves (usn integer NOT NULL, oid integer NOT NULL, type integer NOT NULL)")
            val row = JSONObject().put("id", 1).put("crt", col.optLong("crt", System.currentTimeMillis() / 86400000 * 86400))
                .put("mod", System.currentTimeMillis()).put("scm", System.currentTimeMillis()).put("ver", 11)
                .put("dty", 0).put("usn", -1).put("ls", 0).put("conf", col.optString("conf", "{}"))
                .put("models", metadata.optJSONObject("models")?.toString() ?: "{}")
                .put("decks", metadata.optJSONObject("decks")?.toString() ?: "{}")
                .put("dconf", col.optString("dconf", "{}")) .put("tags", col.optString("tags", "{}"))
            db.insertOrThrow("col", null, values(row))
        } finally { db.close() }
    }

    private fun writeEntities(db: SQLiteDatabase, snapshot: ArchiveSnapshot, metadata: JSONObject, warnings: MutableList<String>) {
        val now = System.currentTimeMillis() / 1000
        val models = metadata.optJSONObject("models") ?: JSONObject()
        val decks = metadata.optJSONObject("decks") ?: JSONObject()
        val nativeModelIds = mutableMapOf<NoteKind, Long>()
        var nextModelId = (models.keys().asSequence().mapNotNull(String::toLongOrNull).maxOrNull() ?: 1000L) + 1
        for (kind in snapshot.notes.filter { it.anki == null }.map(Note::kind).distinct()) {
            val id = nextModelId++
            nativeModelIds[kind] = id
            models.put(id.toString(), model(id, kind))
        }
        val defaultModelId = models.keys().asSequence().firstOrNull()?.toLongOrNull() ?: 1000L
        val deckIds = allocateIds(snapshot.decks.map { it.id to it.anki?.originalId })
        val noteIds = allocateIds(snapshot.notes.map { it.id to it.anki?.originalId })
        val cardIds = allocateIds(snapshot.cards.map { it.id to it.anki?.originalId })
        val reviewIds = allocateIds(snapshot.reviews.map { it.id to (it.anki?.originalId ?: it.reviewedAtMillis) })
        for (deck in snapshot.decks) {
            val id = deckIds.getValue(deck.id)
            val item = jsonObject(deck.anki?.rawJson).takeIf { it.has("name") } ?: JSONObject()
            item.put("id", id).put("name", deck.name)
            putDefault(item, "mod", now); putDefault(item, "usn", -1); putDefault(item, "dyn", 0); putDefault(item, "conf", 1)
            decks.put(id.toString(), item)
        }
        for (note in snapshot.notes) {
            val id = note.anki?.originalModelId ?: nativeModelIds[note.kind] ?: defaultModelId
            if (!models.has(id.toString())) models.put(id.toString(), model(id, note.kind))
        }
        val col = ContentValues().apply { put("models", models.toString()); put("decks", decks.toString()) }
        db.update("col", col, null, null)
        val localReviews = snapshot.reviews.filter { it.anki == null }.groupBy(Review::cardId)
        for (note in snapshot.notes) {
            val id = noteIds.getValue(note.id)
            val raw = jsonObject(note.anki?.rawJson)
            val fields = if (note.anki == null) note.fields.map(::nativeMediaToAnki) else note.fields
            raw.put("id", id).put("mid", note.anki?.originalModelId ?: nativeModelIds[note.kind] ?: defaultModelId).put("flds", fields.joinToString("\u001f"))
                .put("tags", if (note.tags.isEmpty()) "" else " ${note.tags.joinToString(" ")} ")
            putDefault(raw, "guid", requireNotNull(note.guid) { "Nota ${note.id} senza GUID persistente" }); putDefault(raw, "mod", now); putDefault(raw, "usn", -1)
            putDefault(raw, "sfld", fields.firstOrNull().orEmpty()); putDefault(raw, "csum", 0); putDefault(raw, "flags", 0); putDefault(raw, "data", "")
            insert(db, "notes", raw)
        }
        val crt = db.rawQuery("SELECT crt FROM col", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        for (card in snapshot.cards) {
            val raw = jsonObject(card.anki?.rawJson)
            val locallyReviewed = !localReviews[card.id].isNullOrEmpty()
            val queue = if (locallyReviewed) {
                if (card.scheduling.learningStep != null) 1 else 2
            } else card.scheduling.importedQueue ?: if (card.scheduling.reps == 0) 0 else 2
            val due = if (!locallyReviewed && card.anki != null && card.scheduling.importedDue != null && card.scheduling.dueAtMillis == rawDueMillis(raw, crt))
                card.scheduling.importedDue else when (queue) {
                1 -> card.scheduling.dueAtMillis / 1000
                2, 3 -> (card.scheduling.dueAtMillis / 1000 - crt) / 86400
                else -> card.id
            }
            raw.put("id", cardIds.getValue(card.id)).put("nid", noteIds.getValue(card.noteId)).put("did", deckIds.getValue(card.deckId))
                .put("ord", card.anki?.originalOrdinal ?: card.ordinal).put("queue", queue).put("due", due)
                .put("type", if (locallyReviewed) if (queue == 1) 1 else 2 else card.scheduling.importedType ?: if (card.scheduling.reps == 0) 0 else 2)
                .put("ivl", if (locallyReviewed) ((card.scheduling.dueAtMillis - (card.scheduling.lastReviewAtMillis ?: card.scheduling.dueAtMillis)) / 86400000L).coerceAtLeast(0).toInt() else card.scheduling.importedIntervalDays ?: 0)
                .put("reps", card.scheduling.reps).put("lapses", card.scheduling.lapses)
            if (locallyReviewed) {
                val cardData = jsonObject(raw.optString("data"))
                cardData.put("s", card.scheduling.stability).put("d", card.scheduling.difficulty)
                card.scheduling.lastReviewAtMillis?.let { cardData.put("lrt", it / 1000) }
                raw.put("data", cardData.toString()).put("mod", now).put("usn", -1)
            }
            for (key in listOf("mod", "usn", "factor", "left", "odue", "odid", "flags")) putDefault(raw, key, if (key == "mod") now else if (key == "usn") -1 else 0)
            putDefault(raw, "data", "")
            insert(db, "cards", raw)
        }
        val previousInterval = mutableMapOf<Long, Int>()
        for (review in snapshot.reviews.sortedWith(compareBy<Review> { it.reviewedAtMillis }.thenBy { it.id })) {
            val raw = jsonObject(review.anki?.rawJson)
            val id = reviewIds.getValue(review.id)
            raw.put("id", id).put("cid", cardIds[review.cardId] ?: continue)
                .put("ease", when (review.rating) { Rating.AGAIN -> 1; Rating.HARD -> 2; Rating.GOOD -> 3; Rating.EASY -> 4 })
            val elapsed = review.schedulingAfter.dueAtMillis - review.reviewedAtMillis
            val interval = if (review.anki != null) raw.optInt("ivl") else if (elapsed in 1 until 86_400_000L) -(elapsed / 1000L).coerceAtLeast(1).toInt()
                else (elapsed / 86_400_000L).coerceAtLeast(1).toInt()
            putDefault(raw, "usn", -1)
            putDefault(raw, "ivl", interval)
            putDefault(raw, "lastIvl", previousInterval[review.cardId] ?: 0)
            putDefault(raw, "factor", 2500)
            putDefault(raw, "time", 0)
            putDefault(raw, "type", if ((previousInterval[review.cardId] ?: 0) > 0) 1 else 0)
            previousInterval[review.cardId] = interval
            insert(db, "revlog", raw)
        }
    }

    private fun allocateIds(items: List<Pair<Long, Long?>>): Map<Long, Long> {
        val used = mutableSetOf<Long>()
        val output = mutableMapOf<Long, Long>()
        for ((local, preferred) in items.sortedBy { it.first }) {
            var candidate = preferred?.takeIf { it > 0 } ?: (8_000_000_000_000_000L + local)
            while (!used.add(candidate)) candidate++
            output[local] = candidate
        }
        return output
    }

    private fun rawDueMillis(raw: JSONObject, crt: Long): Long = when (raw.optInt("queue")) {
        1 -> raw.optLong("due") * 1000
        2, 3 -> (crt + raw.optLong("due") * 86400) * 1000
        else -> 0
    }

    private fun model(id: Long, kind: NoteKind): JSONObject {
        val cloze = kind == NoteKind.CLOZE
        val fields = if (cloze) listOf("Text", "Extra") else listOf("Front", "Back")
        val templates = if (cloze) listOf(JSONObject().put("name", "Cloze").put("ord", 0).put("qfmt", "{{cloze:Text}}").put("afmt", "{{cloze:Text}}<br>{{Extra}}")) else
            listOf(JSONObject().put("name", "Card 1").put("ord", 0).put("qfmt", "{{Front}}").put("afmt", "{{FrontSide}}<hr id=answer>{{Back}}")) +
                (if (kind == NoteKind.REVERSE) listOf(JSONObject().put("name", "Card 2").put("ord", 1).put("qfmt", "{{Back}}").put("afmt", "{{FrontSide}}<hr id=answer>{{Front}}")) else emptyList())
        return JSONObject().put("id", id).put("name", "Memoro ${kind.name}").put("type", if (cloze) 1 else 0)
            .put("mod", System.currentTimeMillis() / 1000).put("usn", -1).put("sortf", 0).put("did", JSONObject.NULL)
            .put("tmpls", org.json.JSONArray(templates)).put("flds", org.json.JSONArray(fields.mapIndexed { i, s -> JSONObject().put("name", s).put("ord", i) }))
            .put("css", ".card { font-family: sans-serif; font-size: 20px; }").put("latexPre", "").put("latexPost", "").put("req", org.json.JSONArray())
    }

    private fun putDefault(obj: JSONObject, key: String, value: Any) { if (!obj.has(key)) obj.put(key, value) }
    private fun nativeMediaToAnki(value: String): String = value
        .replace(Regex("\\[image:media/([^]\\r\\n]+)]")) { match ->
            val name = match.groupValues[1]
            if (safeMediaName(name)) "<img src=\"$name\">" else match.value
        }
        .replace(Regex("\\[audio:media/([^]\\r\\n]+)]")) { match ->
            val name = match.groupValues[1]
            if (safeMediaName(name)) "[sound:$name]" else match.value
        }

    private fun insert(db: SQLiteDatabase, table: String, row: JSONObject) { db.insertOrThrow(table, null, values(row)) }
    private fun values(row: JSONObject): ContentValues = ContentValues().apply {
        for (key in row.keys()) when (val value = row.get(key)) {
            is Int -> put(key, value)
            is Long -> put(key, value)
            is Double -> put(key, value)
            is String -> put(key, value)
            JSONObject.NULL -> putNull(key)
        }
    }
}

private fun nonClosing(output: OutputStream): OutputStream = object : java.io.FilterOutputStream(output) {
    override fun close() { flush() }
}
