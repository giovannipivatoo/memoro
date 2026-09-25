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
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class AnkiExporter(private val context: Context) {
    suspend fun export(output: OutputStream, repository: MemoroRepository): ExportReport = withContext(Dispatchers.IO) {
        val snapshot = repository.snapshot()
        val metadata = jsonObject(snapshot.ankiCollectionJson)
        val modern = metadata.optString("format") == "modern"
        val dbFile = File.createTempFile("memoro-export-", ".db", context.cacheDir)
        val warnings = mutableListOf<String>()
        if (modern && metadata.optJSONObject("col")?.optString("dconf").isNullOrBlank()) {
            warnings += "Preset Anki avanzati esportati con valori predefiniti; pacchetto originale conservato"
        }
        if (modern) warnings += "Impostazioni avanzate dei tipi di nota Anki non rappresentate nel formato legacy interno; pacchetto originale conservato"
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
            val row = JSONObject().put("id", 1).put("crt", col.optLong("crt", defaultCreationSeconds()).takeIf { it > 0 } ?: defaultCreationSeconds())
                .put("mod", System.currentTimeMillis()).put("scm", System.currentTimeMillis()).put("ver", 11)
                .put("dty", 0).put("usn", -1).put("ls", 0).put("conf", col.optString("conf").takeIf(String::isNotBlank) ?: "{}")
                .put("models", metadata.optJSONObject("models")?.toString() ?: "{}")
                .put("decks", metadata.optJSONObject("decks")?.toString() ?: "{}")
                .put("dconf", col.optString("dconf").takeIf(String::isNotBlank) ?: defaultDeckConfig().toString())
                .put("tags", col.optString("tags").takeIf(String::isNotBlank) ?: "{}")
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
        val nativeChoiceModelId = if (snapshot.notes.any { it.anki == null && it.multipleChoice != null }) nextModelId++.also {
            models.put(it.toString(), model(it, NoteKind.BASIC, multipleChoice = true))
        } else null
        if (snapshot.notes.any { it.multipleChoice != null }) {
            warnings += "Scelta multipla esportata in Anki con opzioni statiche; la risposta interattiva è disponibile solo in Memoro"
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
            completeDeck(item)
            decks.put(id.toString(), item)
        }
        for (note in snapshot.notes) {
            val id = requireNotNull(note.anki?.originalModelId ?: if (note.multipleChoice != null) nativeChoiceModelId else nativeModelIds[note.kind] ?: defaultModelId) {
                "Tipo nota mancante per ${note.id}"
            }
            if (!models.has(id.toString())) models.put(id.toString(), model(id, note.kind, note.multipleChoice != null))
        }
        for (key in models.keys()) models.optJSONObject(key)?.let(::completeModel)
        val col = ContentValues().apply { put("models", models.toString()); put("decks", decks.toString()) }
        db.update("col", col, null, null)
        val localReviews = snapshot.reviews.filter { it.anki == null }.groupBy(Review::cardId)
        for (note in snapshot.notes) {
            val id = noteIds.getValue(note.id)
            val raw = jsonObject(note.anki?.rawJson)
            val modelId = note.anki?.originalModelId ?: if (note.multipleChoice != null) nativeChoiceModelId else nativeModelIds[note.kind] ?: defaultModelId
            val choiceModel = models.optJSONObject(modelId.toString())?.optString("name") == AnkiMultipleChoice.MODEL_NAME
            val fields = when {
                note.multipleChoice != null -> AnkiMultipleChoice.exportFields(note, ::nativeMediaToAnki)
                choiceModel -> listOf(note.fields.getOrElse(0) { "" }, note.fields.getOrElse(1) { "" }).map(::nativeMediaToAnki) + ""
                note.anki == null -> note.fields.map(::nativeMediaToAnki)
                else -> note.fields
            }
            raw.put("id", id).put("mid", modelId).put("flds", fields.joinToString("\u001f"))
                .put("tags", if (note.tags.isEmpty()) "" else " ${note.tags.joinToString(" ")} ")
            putDefault(raw, "guid", requireNotNull(note.guid) { "Nota ${note.id} senza GUID persistente" })
            raw.put("mod", maxOf(raw.optLong("mod"), note.modifiedAtMillis / 1000, now.takeIf { note.anki == null } ?: 0))
            putDefault(raw, "usn", -1)
            if (choiceModel) raw.put("sfld", fields.first()) else putDefault(raw, "sfld", fields.firstOrNull().orEmpty())
            putDefault(raw, "csum", 0); putDefault(raw, "flags", 0); putDefault(raw, "data", "")
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
                .put("type", if (locallyReviewed) if (queue == 1) {
                    if (card.scheduling.relearning) 3 else 1
                } else 2 else card.scheduling.importedType ?: if (card.scheduling.reps == 0) 0 else 2)
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
            if (review.anki == null) raw.put("ease", when (review.rating) { Rating.AGAIN -> 1; Rating.HARD -> 2; Rating.GOOD -> 3; Rating.EASY -> 4 })
            val elapsed = review.schedulingAfter.dueAtMillis - review.reviewedAtMillis
            val interval = if (review.anki != null) raw.optInt("ivl") else if (elapsed in 1 until 86_400_000L) -(elapsed / 1000L).coerceAtLeast(1).toInt()
                else (elapsed / 86_400_000L).coerceAtLeast(1).toInt()
            putDefault(raw, "usn", -1)
            putDefault(raw, "ivl", interval)
            putDefault(raw, "lastIvl", previousInterval[review.cardId] ?: 0)
            putDefault(raw, "factor", 2500)
            putDefault(raw, "time", 0)
            putDefault(raw, "type", if (review.schedulingAfter.relearning) 2 else if ((previousInterval[review.cardId] ?: 0) > 0) 1 else 0)
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

    private fun model(id: Long, kind: NoteKind, multipleChoice: Boolean = false): JSONObject {
        val cloze = kind == NoteKind.CLOZE
        val fields = if (cloze) listOf("Text", "Extra") else if (multipleChoice) listOf("Front", "Back", AnkiMultipleChoice.FIELD_NAME) else listOf("Front", "Back")
        val templates = if (cloze) listOf(JSONObject().put("name", "Cloze").put("ord", 0).put("qfmt", "{{cloze:Text}}").put("afmt", "{{cloze:Text}}<br>{{Extra}}")) else
            listOf(JSONObject().put("name", "Card 1").put("ord", 0).put("qfmt", "{{Front}}").put("afmt", "{{FrontSide}}<hr id=answer>{{Back}}")) +
                (if (kind == NoteKind.REVERSE) listOf(JSONObject().put("name", "Card 2").put("ord", 1).put("qfmt", "{{Back}}").put("afmt", "{{FrontSide}}<hr id=answer>{{Front}}")) else emptyList())
        return JSONObject().put("id", id).put("name", if (multipleChoice) AnkiMultipleChoice.MODEL_NAME else "Memoro ${kind.name}").put("type", if (cloze) 1 else 0)
            .put("mod", System.currentTimeMillis() / 1000).put("usn", -1).put("sortf", 0).put("did", JSONObject.NULL)
            .put("tmpls", org.json.JSONArray(templates)).put("flds", org.json.JSONArray(fields.mapIndexed { i, s -> JSONObject().put("name", s).put("ord", i) }))
            .put("css", ".card { font-family: sans-serif; font-size: 20px; }").put("latexPre", "").put("latexPost", "").put("req", org.json.JSONArray())
    }

    private fun completeModel(model: JSONObject) {
        putDefault(model, "type", 0); putDefault(model, "sortf", 0); putDefault(model, "mod", 0); putDefault(model, "usn", -1)
        putDefault(model, "did", JSONObject.NULL); putDefault(model, "css", "")
        putDefault(model, "latexPre", ""); putDefault(model, "latexPost", ""); putDefault(model, "latexsvg", false)
        putDefault(model, "originalStockKind", 0)
        val fields = model.optJSONArray("flds") ?: org.json.JSONArray().also { model.put("flds", it) }
        for (i in 0 until fields.length()) {
            val field = fields.optJSONObject(i) ?: continue
            putDefault(field, "ord", i); putDefault(field, "sticky", false); putDefault(field, "rtl", false)
            putDefault(field, "font", "Arial"); putDefault(field, "size", 20); putDefault(field, "description", "")
            putDefault(field, "plainText", false); putDefault(field, "collapsed", false); putDefault(field, "excludeFromSearch", false)
            putDefault(field, "id", (model.optLong("id") + i + 1).coerceAtLeast(1)); putDefault(field, "tag", JSONObject.NULL)
            putDefault(field, "preventDeletion", false)
        }
        val templates = model.optJSONArray("tmpls") ?: org.json.JSONArray().also { model.put("tmpls", it) }
        for (i in 0 until templates.length()) {
            val template = templates.optJSONObject(i) ?: continue
            putDefault(template, "ord", i); putDefault(template, "bqfmt", ""); putDefault(template, "bafmt", "")
            putDefault(template, "did", JSONObject.NULL); putDefault(template, "bfont", ""); putDefault(template, "bsize", 0)
            putDefault(template, "id", (model.optLong("id") + 100 + i).coerceAtLeast(1))
        }
        if (!model.has("req") || model.optJSONArray("req")?.length() == 0) {
            val requirements = org.json.JSONArray()
            if (model.optInt("type") == 0) for (i in 0 until templates.length()) {
                requirements.put(org.json.JSONArray().put(i).put("any").put(org.json.JSONArray().put(if (i == 0) 0 else 1)))
            }
            model.put("req", requirements)
        }
    }

    private fun completeDeck(deck: JSONObject) {
        for (key in listOf("lrnToday", "revToday", "newToday", "timeToday")) putDefault(deck, key, org.json.JSONArray().put(0).put(0))
        putDefault(deck, "collapsed", true); putDefault(deck, "browserCollapsed", true); putDefault(deck, "desc", "")
        putDefault(deck, "extendNew", 0); putDefault(deck, "extendRev", 0)
        for (key in listOf("reviewLimit", "newLimit", "reviewLimitToday", "newLimitToday", "desiredRetention")) putDefault(deck, key, JSONObject.NULL)
    }

    private fun defaultDeckConfig(): JSONObject = JSONObject("""{"1":{"id":1,"mod":0,"name":"Default","usn":0,"maxTaken":60,"autoplay":true,"timer":0,"replayq":true,"new":{"bury":false,"delays":[1,10],"initialFactor":2500,"ints":[1,4,0],"order":1,"perDay":20},"rev":{"bury":false,"ease4":1.3,"ivlFct":1,"maxIvl":36500,"perDay":200,"hardFactor":1.2},"lapse":{"delays":[10],"leechAction":1,"leechFails":8,"minInt":1,"mult":0},"dyn":false,"newMix":0,"newPerDayMinimum":0,"interdayLearningMix":0,"reviewOrder":0,"newSortOrder":0,"newGatherPriority":0,"buryInterdayLearning":false,"fsrsWeights":[],"fsrsParams5":[],"fsrsParams6":[],"desiredRetention":0.9,"ignoreRevlogsBeforeDate":"","easyDaysPercentages":[1,1,1,1,1,1,1],"stopTimerOnAnswer":false,"secondsToShowQuestion":0,"secondsToShowAnswer":0,"questionAction":0,"answerAction":0,"waitForAudio":true,"sm2Retention":0.9,"weightSearch":""}}""")

    private fun defaultCreationSeconds(): Long {
        val now = ZonedDateTime.now()
        var rollover = now.toLocalDate().atTime(4, 0).atZone(now.zone)
        if (now.isBefore(rollover)) rollover = rollover.minusDays(1)
        return rollover.toEpochSecond()
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
