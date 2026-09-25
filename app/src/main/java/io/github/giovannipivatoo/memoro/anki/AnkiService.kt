// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.anki

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.giovannipivatoo.memoro.data.*
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

data class ImportReport(val decks: Int, val notes: Int, val cards: Int, val reviews: Int, val media: Int, val warnings: List<String>)
data class ExportReport(val notes: Int, val cards: Int, val media: Int, val warnings: List<String>)

/** A ZIP/SQLite adapter. It does not execute imported HTML, JavaScript, or templates. */
class AnkiService(private val context: Context) {
    suspend fun exportApkg(output: OutputStream, repository: MemoroRepository): ExportReport =
        AnkiExporter(context).export(output, repository)

    suspend fun importApkg(input: InputStream, repository: MemoroRepository): ImportReport = withContext(Dispatchers.IO) {
        val packageFile = File.createTempFile("memoro-import-", ".apkg", context.cacheDir)
        val dbFile = File.createTempFile("memoro-import-", ".db", context.cacheDir)
        try {
            input.use { source -> packageFile.outputStream().use(source::copyTo) }
            val digest = packageFile.inputStream().use { hash(it) }
            val packagePath = "anki/original/$digest.apkg"
            ZipFile(packageFile).use { zip ->
                require(zip.getEntry("collection.anki2") != null || zip.getEntry("collection.anki21") != null || zip.getEntry("collection.anki21b") != null) { "Archivio APKG senza collezione" }
                val modern = zip.getEntry("collection.anki21b") != null
                val entry = zip.getEntry(if (modern) "collection.anki21b" else if (zip.getEntry("collection.anki21") != null) "collection.anki21" else "collection.anki2")
                require(entry.size <= MAX_DATABASE_BYTES || entry.size < 0) { "Database APKG troppo grande" }
                zip.getInputStream(entry).use { source ->
                    (if (modern) ZstdInputStream(source) else source).use { decoded ->
                        dbFile.outputStream().use { boundedCopy(decoded, it, MAX_DATABASE_BYTES) }
                    }
                }
                if (modern) normalizeTempCollation(dbFile)
                val warnings = mutableListOf<String>()
                val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    db.rawQuery("PRAGMA quick_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") { "Database Anki danneggiato" } }
                    val col = db.query("col", null, null, null, null, null, null).use { if (it.moveToFirst()) row(it) else error("Collezione vuota") }
                    val crt = col.optLong("crt") ?: 0
                    val modernDatabase = col.optInt("ver") >= 18
                    val sourceDecks = if (modernDatabase) modernDecks(db) else jsonObject(col.optString("decks"))
                    val sourceModels = if (modernDatabase) modernModels(db) else jsonObject(col.optString("models"))
                    val prior = jsonObject(repository.snapshot().ankiCollectionJson)
                    val (deckDefs, deckIds) = mergeDefinitions(prior.optJSONObject("decks"), sourceDecks, "name")
                    val (modelDefs, modelIds) = mergeDefinitions(prior.optJSONObject("models"), sourceModels, "name")
                    val decks = mutableListOf<Deck>()
                    val deckNames = mutableMapOf<Long, String>()
                    for (key in sourceDecks.keys()) {
                        val item = sourceDecks.optJSONObject(key) ?: continue
                        val rawId = item.optLong("id", key.toLongOrNull() ?: continue)
                        val id = deckIds[rawId] ?: rawId
                        val name = item.optString("name", "Deck $id")
                        deckNames[id] = name
                        decks += Deck(id = id, name = name, description = item.optString("desc"), anki = AnkiMetadata(originalId = rawId, packageSha256 = digest, rawJson = item.toString()))
                    }
                    val noteRows = rows(db, "notes")
                    val cardRows = rows(db, "cards")
                    val reviewRows = rows(db, "revlog")
                    val latestReview = reviewRows.groupBy { it.optLong("cid") }.mapValues { (_, v) -> v.maxOfOrNull { it.optLong("id") } }
                    val byNote = cardRows.groupBy { it.optLong("nid") }
                    val notes = noteRows.map { raw ->
                        val rawMid = raw.optLong("mid")
                        val mid = modelIds[rawMid] ?: rawMid
                        val model = modelDefs.optJSONObject(mid.toString())
                        val fields = raw.optString("flds").split('\u001f')
                        val cards = byNote[raw.optLong("id")].orEmpty()
                        val kind = when {
                            model?.optInt("type") == 1 || fields.any { "{{c" in it && "::" in it } -> NoteKind.CLOZE
                            cards.map { it.optInt("ord") }.distinct().size > 1 -> NoteKind.REVERSE
                            else -> NoteKind.BASIC
                        }
                        val rawDid = cards.firstOrNull()?.optLong("did") ?: 1L
                        val did = deckIds[rawDid] ?: rawDid
                        if (!deckNames.containsKey(did)) {
                            deckNames[did] = "Deck $did"
                            decks += Deck(id = did, name = "Deck $did", anki = AnkiMetadata(originalId = rawDid, packageSha256 = digest))
                        }
                        val templates = model?.optJSONArray("tmpls")
                        if (templates != null && !standardTemplate(kind, templates)) warnings += "Nota ${raw.optLong("id")}: template personalizzato conservato nell'originale; anteprima semplificata"
                        Note(id = raw.optLong("id"), deckId = did, guid = raw.optString("guid"),
                            modifiedAtMillis = raw.optLong("mod") * 1000, kind = kind, fields = fields,
                            tags = raw.optString("tags").trim().split(Regex("\\s+")).filter(String::isNotEmpty),
                            anki = AnkiMetadata(originalId = raw.optLong("id"), packageSha256 = digest, originalModelId = mid, rawJson = raw.toString()))
                    }
                    val notesById = notes.associateBy(Note::id)
                    val cards = cardRows.map { raw ->
                        val nid = raw.optLong("nid")
                        val note = notesById[nid]
                        val rawDid = raw.optLong("did")
                        val did = deckIds[rawDid] ?: rawDid
                        val ord = raw.optInt("ord")
                        val queue = raw.optInt("queue")
                        val due = raw.optLong("due")
                        val dueMillis = when (queue) {
                            1 -> due * 1000
                            2, 3 -> (crt + due * 86400) * 1000
                            -1, -2, -3 -> Long.MAX_VALUE
                            else -> 0
                        }
                        val cardData = jsonObject(raw.optString("data"))
                        val face = AnkiRenderer.basicFaces(note?.fields.orEmpty(), note?.kind ?: NoteKind.BASIC, ord)
                        Card(id = raw.optLong("id"), noteId = nid, deckId = did, ordinal = ord,
                            front = face.first, back = face.second,
                            scheduling = Scheduling(dueAtMillis = dueMillis, stability = cardData.optDouble("s", 0.0), difficulty = cardData.optDouble("d", 0.0),
                                lastReviewAtMillis = cardData.optLong("lrt").takeIf { it > 0 }?.times(1000) ?: latestReview[raw.optLong("id")],
                                reps = raw.optInt("reps"), lapses = raw.optInt("lapses"),
                                importedIntervalDays = raw.optInt("ivl"), importedQueue = queue, importedType = raw.optInt("type"), importedDue = due),
                            anki = AnkiMetadata(originalId = raw.optLong("id"), packageSha256 = digest, originalDeckId = rawDid, originalModelId = note?.anki?.originalModelId,
                                originalOrdinal = ord, rawJson = raw.toString()))
                    }
                    val reviews = reviewRows.map { raw ->
                        val ease = raw.optInt("ease")
                        val rating = when (ease) { 1 -> Rating.AGAIN; 2 -> Rating.HARD; 4 -> Rating.EASY; else -> Rating.GOOD }
                        Review(id = raw.optLong("id"), cardId = raw.optLong("cid"), rating = rating,
                            reviewedAtMillis = raw.optLong("id"), schedulingAfter = Scheduling(importedIntervalDays = raw.optInt("ivl")),
                            anki = AnkiMetadata(originalId = raw.optLong("id"), packageSha256 = digest, rawJson = raw.toString()))
                    }
                    val collectionJson = JSONObject().put("col", col).put("models", modelDefs).put("decks", deckDefs)
                        .put("format", if (modern) "modern" else "legacy").toString()
                    val importedMedia = importMedia(zip, modern, repository, warnings)
                    val fixedNotes = notes.map { note -> note.copy(fields = note.fields.map { renameMediaRefs(it, importedMedia.renamed) }) }
                    val fixedNotesById = fixedNotes.associateBy(Note::id)
                    val fixedCards = cards.map { card ->
                        val note = fixedNotesById[card.noteId]
                        if (note == null) card else {
                            val faces = AnkiRenderer.basicFaces(note.fields, note.kind, card.ordinal)
                            card.copy(front = faces.first, back = faces.second)
                        }
                    }
                    val storedPackage = packageFile.inputStream().use { repository.putFile(packagePath, it, "application/zip") }
                    repository.importSnapshot(ArchiveSnapshot(ankiCollectionJson = collectionJson, originalPackagePath = packagePath,
                        decks = decks, notes = fixedNotes, cards = fixedCards, reviews = reviews,
                        files = importedMedia.files + storedPackage))
                    return@withContext ImportReport(decks.size, notes.size, cards.size, reviews.size, importedMedia.files.size, warnings.distinct())
                } finally { db.close() }
            }
        } finally { packageFile.delete(); dbFile.delete() }
    }

    private data class MediaImport(val files: List<StoredFile>, val renamed: Map<String, String>)

    private suspend fun importMedia(zip: ZipFile, modern: Boolean, repository: MemoroRepository, warnings: MutableList<String>): MediaImport {
        val entry = zip.getEntry("media") ?: return MediaImport(emptyList(), emptyMap())
        val raw = zip.getInputStream(entry).use { input -> (if (modern) ZstdInputStream(input) else input).use { it.readBytesLimited(MAX_MEDIA_MAP_BYTES) } }
        val names = if (modern) AnkiWire.decodeMediaMap(raw) else {
            val map = jsonObject(raw.toString(Charsets.UTF_8)); map.keys().asSequence().mapNotNull { key -> key.toIntOrNull()?.let { it to map.optString(key) } }.toMap()
        }
        require(names.size <= MAX_MEDIA_COUNT) { "Troppi media nell'archivio" }
        val files = mutableListOf<StoredFile>()
        val renamed = mutableMapOf<String, String>()
        val existing = repository.snapshot().files.associateBy(StoredFile::path)
        var total = 0L
        for ((index, name) in names) {
            if (!safeMediaName(name)) { warnings += "Media rifiutato: nome non sicuro"; continue }
            val mediaEntry = zip.getEntry(index.toString())
            if (mediaEntry == null) { warnings += "Media mancante: $name"; continue }
            if (mediaEntry.size > MAX_MEDIA_BYTES) { warnings += "Media troppo grande: $name"; continue }
            val bytes = zip.getInputStream(mediaEntry).use { input -> (if (modern) ZstdInputStream(input) else input).use { it.readBytesLimited(MAX_MEDIA_BYTES) } }
            total += bytes.size
            require(total <= MAX_TOTAL_MEDIA_BYTES) { "Media complessivi troppo grandi" }
            val sha = hash(bytes.inputStream())
            val path = "media/$name"
            val storedPath = if (existing[path] != null && existing[path]?.sha256 != sha) {
                val dot = name.lastIndexOf('.')
                val stem = if (dot > 0) name.substring(0, dot) else name
                val ext = if (dot > 0) name.substring(dot) else ""
                "media/$stem-${sha.take(10)}$ext"
            } else path
            if (storedPath != path) { renamed[name] = storedPath.removePrefix("media/"); warnings += "Media $name rinominato per collisione" }
            files += repository.putFile(storedPath, bytes.inputStream())
        }
        return MediaImport(files, renamed)
    }

    private fun renameMediaRefs(text: String, names: Map<String, String>): String {
        var result = text
        for ((old, new) in names) {
            result = result.replace("[sound:$old]", "[sound:$new]")
            result = result.replace("src=\"$old\"", "src=\"$new\"").replace("src='$old'", "src='$new'")
        }
        return result
    }

    private fun modernDecks(db: SQLiteDatabase): JSONObject {
        val out = JSONObject()
        db.rawQuery("SELECT id,name FROM decks", null).use { c -> while (c.moveToNext()) {
            val id = c.getLong(0); out.put(id.toString(), JSONObject().put("id", id).put("name", c.getString(1)))
        } }
        return out
    }

    private fun modernModels(db: SQLiteDatabase): JSONObject {
        val out = JSONObject()
        db.rawQuery("SELECT id,name,config FROM notetypes", null).use { c -> while (c.moveToNext()) {
            val id = c.getLong(0); out.put(id.toString(), JSONObject().put("id", id).put("name", c.getString(1)).put("flds", JSONArray()).put("tmpls", JSONArray())
                .put("css", AnkiWire.stringField(c.getBlob(2), 3) ?: ""))
        } }
        db.rawQuery("SELECT ntid,ord,name FROM fields ORDER BY ntid,ord", null).use { c -> while (c.moveToNext()) {
            out.optJSONObject(c.getLong(0).toString())?.optJSONArray("flds")?.put(JSONObject().put("ord", c.getInt(1)).put("name", c.getString(2)))
        } }
        db.rawQuery("SELECT ntid,ord,name,config FROM templates ORDER BY ntid,ord", null).use { c -> while (c.moveToNext()) {
            val format = AnkiWire.decodeTemplate(c.getBlob(3))
            out.optJSONObject(c.getLong(0).toString())?.optJSONArray("tmpls")?.put(JSONObject().put("ord", c.getInt(1)).put("name", c.getString(2))
                .put("qfmt", format.first).put("afmt", format.second))
        } }
        for (key in out.keys()) {
            val item = out.optJSONObject(key) ?: continue
            val templates = item.optJSONArray("tmpls") ?: continue
            val isCloze = (0 until templates.length()).any { templates.optJSONObject(it)?.optString("qfmt")?.contains("{{cloze:") == true }
            item.put("type", if (isCloze) 1 else 0).put("sortf", 0).put("mod", 0).put("usn", -1)
                .put("latexPre", "").put("latexPost", "").put("req", JSONArray())
        }
        return out
    }

    private fun mergeDefinitions(previous: JSONObject?, incoming: JSONObject, property: String): Pair<JSONObject, Map<Long, Long>> {
        val merged = jsonObject(previous?.toString())
        val mapped = mutableMapOf<Long, Long>()
        for (key in incoming.keys()) {
            val item = incoming.optJSONObject(key) ?: continue
            val sourceId = key.toLongOrNull() ?: continue
            val direct = merged.optJSONObject(key)
            val directMatch = direct != null && direct.optString(property).equals(item.optString(property), ignoreCase = true)
            val same = if (directMatch) sourceId else merged.keys().asSequence().mapNotNull { target ->
                val candidate = merged.optJSONObject(target) ?: return@mapNotNull null
                val sameName = candidate.optString(property).equals(item.optString(property), ignoreCase = true)
                val sameShape = property != "name" || fieldNames(candidate) == fieldNames(item)
                target.toLongOrNull()?.takeIf { sameName && sameShape }
            }.firstOrNull()
            val targetId = when {
                same != null -> same
                !merged.has(key) -> sourceId
                else -> (merged.keys().asSequence().mapNotNull(String::toLongOrNull).maxOrNull() ?: sourceId) + 1
            }
            mapped[sourceId] = targetId
            if (!merged.has(targetId.toString())) merged.put(targetId.toString(), JSONObject(item.toString()).put("id", targetId))
        }
        return merged to mapped
    }

    private fun fieldNames(model: JSONObject): List<String> {
        val fields = model.optJSONArray("flds") ?: return emptyList()
        return (0 until fields.length()).map { fields.optJSONObject(it)?.optString("name").orEmpty() }
    }

    private fun normalizeTempCollation(file: File) {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.execSQL("PRAGMA writable_schema=ON")
            db.execSQL("UPDATE sqlite_master SET sql=replace(sql, 'COLLATE unicase', 'COLLATE BINARY') WHERE sql LIKE '%COLLATE unicase%'")
            db.execSQL("PRAGMA writable_schema=OFF")
        } finally { db.close() }
    }

    private fun rows(db: SQLiteDatabase, table: String): List<JSONObject> = db.rawQuery("SELECT * FROM $table", null).use { c ->
        buildList { while (c.moveToNext()) add(row(c)) }
    }

    private fun row(c: Cursor): JSONObject = JSONObject().also { out ->
        for (i in 0 until c.columnCount) when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> out.put(c.getColumnName(i), JSONObject.NULL)
            Cursor.FIELD_TYPE_INTEGER -> out.put(c.getColumnName(i), c.getLong(i))
            Cursor.FIELD_TYPE_FLOAT -> out.put(c.getColumnName(i), c.getDouble(i))
            Cursor.FIELD_TYPE_STRING -> out.put(c.getColumnName(i), c.getString(i))
            Cursor.FIELD_TYPE_BLOB -> out.put(c.getColumnName(i), android.util.Base64.encodeToString(c.getBlob(i), android.util.Base64.NO_WRAP))
        }
    }

    private fun standardTemplate(kind: NoteKind, templates: JSONArray): Boolean =
        (templates.length() <= 2 && (0 until templates.length()).all { i ->
            val t = templates.optJSONObject(i) ?: return@all false
            val q = t.optString("qfmt").replace(Regex("\\s+"), "")
            val a = t.optString("afmt").replace(Regex("\\s+"), "")
                .replace(Regex("<hr(?:id=['\"]?answer['\"]?)?/?>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<br/?>", RegexOption.IGNORE_CASE), "")
            val expectedQuestion = when {
                kind == NoteKind.CLOZE -> "{{cloze:Text}}"
                kind == NoteKind.REVERSE && i == 1 -> "{{Back}}"
                else -> "{{Front}}"
            }
            val expectedAnswer = when {
                kind == NoteKind.CLOZE -> "{{cloze:Text}}{{BackExtra}}"
                kind == NoteKind.REVERSE && i == 1 -> "{{FrontSide}}{{Front}}"
                else -> "{{FrontSide}}{{Back}}"
            }
            q == expectedQuestion && a == expectedAnswer
        })

    companion object {
        const val MAX_DATABASE_BYTES = 512L * 1024 * 1024
        const val MAX_MEDIA_BYTES = 64L * 1024 * 1024
        const val MAX_MEDIA_MAP_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_MEDIA_BYTES = 512L * 1024 * 1024
        const val MAX_MEDIA_COUNT = 10000
    }
}

internal fun JSONObject.optLong(name: String): Long = optLong(name, 0L)
internal fun JSONObject.optString(name: String): String = optString(name, "")
internal fun jsonObject(s: String?): JSONObject = try { JSONObject(s ?: "{}") } catch (_: Exception) { JSONObject() }
internal fun safeMediaName(name: String): Boolean = name.isNotBlank() && name.length < 240 && name != "." && name != ".." &&
    '/' !in name && '\\' !in name && name.none { it.isISOControl() || it in "<>\"'&" } && !name.startsWith('.')
internal fun InputStream.readBytesLimited(max: Long): ByteArray { val out = java.io.ByteArrayOutputStream(); boundedCopy(this, out, max); return out.toByteArray() }
internal fun boundedCopy(input: InputStream, output: OutputStream, max: Long) { val bytes = ByteArray(8192); var size = 0L; while (true) { val n = input.read(bytes); if (n < 0) break; size += n; require(size <= max) { "Archivio troppo grande" }; output.write(bytes, 0, n) } }
internal fun hash(input: InputStream): String { val d = MessageDigest.getInstance("SHA-256"); val b = ByteArray(8192); while (true) { val n = input.read(b); if (n < 0) break; d.update(b, 0, n) }; return d.digest().joinToString("") { "%02x".format(it) } }
