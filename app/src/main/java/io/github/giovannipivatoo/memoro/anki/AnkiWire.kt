// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.anki

import java.io.ByteArrayOutputStream

/** Minimal protocol buffer wire handling for the documented APKG media map. */
internal object AnkiWire {
    data class MediaRecord(val name: String, val size: Long, val sha1: ByteArray)
    fun stringField(bytes: ByteArray, number: Int): String? {
        val reader = Reader(bytes)
        var result: String? = null
        while (!reader.end()) {
            val tag = reader.varint().toInt()
            if (tag == number * 8 + 2) result = reader.lengthDelimited().toString(Charsets.UTF_8)
            else reader.skip(tag and 7)
        }
        return result
    }
    fun decodeTemplate(bytes: ByteArray): Pair<String, String> {
        val reader = Reader(bytes)
        var question = ""
        var answer = ""
        while (!reader.end()) {
            val tag = reader.varint().toInt()
            when (tag) {
                10 -> question = reader.lengthDelimited().toString(Charsets.UTF_8)
                18 -> answer = reader.lengthDelimited().toString(Charsets.UTF_8)
                else -> reader.skip(tag and 7)
            }
        }
        return question to answer
    }

    fun decodeMediaMap(bytes: ByteArray): Map<Int, String> {
        val reader = Reader(bytes)
        val output = linkedMapOf<Int, String>()
        var index = 0
        while (!reader.end()) {
            val tag = reader.varint().toInt()
            if (tag == 10) {
                val entry = Reader(reader.lengthDelimited())
                var name = ""
                var legacyIndex: Int? = null
                while (!entry.end()) {
                    val entryTag = entry.varint().toInt()
                    when (entryTag) {
                        10 -> name = entry.lengthDelimited().toString(Charsets.UTF_8)
                        2040 -> legacyIndex = entry.varint().toInt()
                        else -> entry.skip(entryTag and 7)
                    }
                }
                if (name.isNotEmpty()) output[legacyIndex ?: index] = name
                index++
            } else reader.skip(tag and 7)
        }
        return output
    }

    fun encodeMediaMap(records: List<MediaRecord>): ByteArray = ByteArrayOutputStream().apply {
        for (record in records) {
            val entry = ByteArrayOutputStream().apply {
                field(1, record.name.toByteArray(Charsets.UTF_8))
                varint(16); varint(record.size)
                field(3, record.sha1)
            }.toByteArray()
            field(1, entry)
        }
    }.toByteArray()

    fun encodeVersion(version: Int): ByteArray = ByteArrayOutputStream().apply { varint(8); varint(version.toLong()) }.toByteArray()

    private fun ByteArrayOutputStream.field(number: Int, data: ByteArray) { varint((number * 8 + 2).toLong()); varint(data.size.toLong()); write(data) }
    private fun ByteArrayOutputStream.varint(value: Long) { var v = value; while (v >= 128) { write(((v and 127) or 128).toInt()); v = v ushr 7 }; write(v.toInt()) }

    private class Reader(private val bytes: ByteArray) {
        private var at = 0
        fun end() = at == bytes.size
        fun varint(): Long { var shift = 0; var value = 0L; while (shift < 64) {
            require(at < bytes.size) { "Mappa media protobuf troncata" }
            val b = bytes[at++].toInt() and 255
            value = value or ((b and 127).toLong() shl shift)
            if (b < 128) return value
            shift += 7
        }; error("Varint non valido") }
        fun lengthDelimited(): ByteArray { val size = varint(); require(size in 0..(bytes.size - at).toLong()) { "Lunghezza protobuf non valida" }; return bytes.copyOfRange(at, at + size.toInt()).also { at += size.toInt() } }
        fun skip(wire: Int) { when (wire) { 0 -> varint(); 1 -> skipBytes(8); 2 -> lengthDelimited(); 5 -> skipBytes(4); else -> error("Wire protobuf non supportato") } }
        private fun skipBytes(n: Int) { require(at + n <= bytes.size); at += n }
    }
}
