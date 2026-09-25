// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.anki

import android.text.Html
import io.github.giovannipivatoo.memoro.data.Card
import io.github.giovannipivatoo.memoro.data.Note
import io.github.giovannipivatoo.memoro.data.NoteKind

data class RenderedCard(val question: String, val answer: String, val media: List<String>, val warnings: List<String> = emptyList())

/** Converts supported Anki fields to text and inert media markers for native Compose display. */
object AnkiRenderer {
    private val image = Regex("<img\\b[^>]*?\\bsrc\\s*=\\s*(['\"])(.*?)\\1[^>]*>", RegexOption.IGNORE_CASE)
    private val sound = Regex("\\[sound:([^]\\r\\n]+)]", RegexOption.IGNORE_CASE)
    private val cloze = Regex("\\{\\{c(\\d+)::(.*?)(?:::(.*?))?\\}\\}", RegexOption.DOT_MATCHES_ALL)
    private val dangerous = Regex("<(script|style|iframe|object|embed|form)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun basicFaces(fields: List<String>, kind: NoteKind, ordinal: Int): Pair<String, String> {
        val first = fields.getOrNull(0).orEmpty()
        val second = fields.getOrNull(1).orEmpty()
        return when (kind) {
            NoteKind.BASIC -> first to second
            NoteKind.REVERSE -> if (ordinal == 0) first to second else second to first
            NoteKind.CLOZE -> {
                val target = ordinal + 1
                val question = cloze.replace(first) { match ->
                    if (match.groupValues[1].toIntOrNull() == target) "[…]" else match.groupValues[2]
                }
                val answer = cloze.replace(first) { it.groupValues[2] } + (if (second.isNotEmpty()) "\n$second" else "")
                question to answer
            }
        }
    }

    fun render(card: Card, note: Note): RenderedCard {
        val faces = if (note.anki != null || (card.front.isBlank() && card.back.isBlank()))
            basicFaces(note.fields, note.kind, card.ordinal) else card.front to card.back
        val media = linkedSetOf<String>()
        val question = sanitize(faces.first, media)
        val answer = sanitize(faces.second, media)
        return RenderedCard(question, answer, media.toList())
    }

    /** Null means a written answer would be ambiguous or depend on media. */
    fun exactExpected(card: Card, note: Note): String? {
        if (note.kind == NoteKind.CLOZE) {
            val targets = cloze.findAll(note.fields.firstOrNull().orEmpty())
                .filter { it.groupValues[1].toIntOrNull() == card.ordinal + 1 }
                .map { it.groupValues[2] }.toList()
            if (targets.size != 1) return null
            val clean = sanitize(targets.single(), linkedSetOf())
            return clean.takeIf { it.isNotBlank() && "[image:" !in it && "[audio:" !in it }
        }
        val answer = render(card, note).answer
        return answer.takeIf { it.isNotBlank() && "[image:" !in it && "[audio:" !in it }
    }

    private fun sanitize(raw: String, media: MutableSet<String>): String {
        var text = dangerous.replace(raw, "")
        text = image.replace(text) { match ->
            val name = Html.fromHtml(match.groupValues[2], Html.FROM_HTML_MODE_LEGACY).toString()
            if (safeMediaName(name)) { media += "media/$name"; " [image:media/$name] " } else " [immagine non disponibile] "
        }
        text = sound.replace(text) { match ->
            val name = match.groupValues[1]
            if (safeMediaName(name)) { media += "media/$name"; " [audio:media/$name] " } else " [audio non disponibile] "
        }
        text = text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(?:p|div|li)>", RegexOption.IGNORE_CASE), "\n")
        return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }
}
