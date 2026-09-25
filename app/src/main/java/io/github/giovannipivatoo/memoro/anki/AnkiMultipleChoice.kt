// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.anki

import android.text.TextUtils
import io.github.giovannipivatoo.memoro.data.MultipleChoice
import io.github.giovannipivatoo.memoro.data.Note
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** A regular Anki Basic card with static choices and a separate, ignored recovery field. */
internal object AnkiMultipleChoice {
    const val MODEL_NAME = "Memoro Multiple Choice"
    const val FIELD_NAME = "MemoroMC"
    private const val PREFIX = "MemoroMC:v1:"

    data class Decoded(val prompt: String, val choice: MultipleChoice)

    fun exportFields(note: Note, renderMedia: (String) -> String): List<String> {
        val choice = requireNotNull(note.multipleChoice)
        require(note.kind == io.github.giovannipivatoo.memoro.data.NoteKind.BASIC && valid(choice) && note.fields.getOrNull(1) == choice.options[choice.correctIndex])
        val prompt = note.fields.first()
        val front = buildString {
            append(renderMedia(prompt))
            append("<ol>")
            choice.options.forEach { option ->
                append("<li>")
                append(renderMedia(TextUtils.htmlEncode(option).replace("\n", "<br>")))
                append("</li>")
            }
            append("</ol>")
        }
        val back = renderMedia(TextUtils.htmlEncode(choice.options[choice.correctIndex]).replace("\n", "<br>"))
        val metadata = JSONObject().put("version", 1).put("prompt", prompt)
            .put("options", JSONArray(choice.options)).put("correctIndex", choice.correctIndex)
            .put("frontHash", sha256(front)).put("backHash", sha256(back))
        val marker = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(metadata.toString().toByteArray(Charsets.UTF_8))
        return listOf(front, back, marker)
    }

    fun decode(fields: List<String>, model: JSONObject?): Decoded? = runCatching {
        if (model?.optString("name") != MODEL_NAME || fields.size != 3) return null
        val names = model.optJSONArray("flds") ?: return null
        if (names.length() != 3 || names.optJSONObject(0)?.optString("name") != "Front" ||
            names.optJSONObject(1)?.optString("name") != "Back" || names.optJSONObject(2)?.optString("name") != FIELD_NAME) return null
        val marker = fields[2]
        if (!marker.startsWith(PREFIX) || marker.length > 131_072) return null
        val obj = JSONObject(String(Base64.getUrlDecoder().decode(marker.removePrefix(PREFIX)), Charsets.UTF_8))
        if (obj.optInt("version") != 1 || sha256(fields[0]) != obj.optString("frontHash") ||
            sha256(fields[1]) != obj.optString("backHash")) return null
        val optionsJson = obj.optJSONArray("options") ?: return null
        val options = (0 until optionsJson.length()).map { optionsJson.optString(it) }
        val choice = MultipleChoice(options, obj.optInt("correctIndex", -1))
        if (!valid(choice)) return null
        Decoded(obj.getString("prompt"), choice)
    }.getOrNull()

    private fun valid(choice: MultipleChoice): Boolean = choice.options.size in 2..6 &&
        choice.correctIndex in choice.options.indices && choice.options.all { it.isNotBlank() } &&
        choice.options.map { it.trim().lowercase() }.distinct().size == choice.options.size

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
