package com.example.everyreader

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

private data class DbfField(val nameBytes: ByteArray, val type: Char, val length: Int)

fun readDbf(file: File): List<SheetData> {
    val bytes = file.readBytes()
    if (bytes.size < 32) return emptyList()
    val numRecords = le32(bytes, 4)
    val headerLen = le16(bytes, 8)
    val recordLen = le16(bytes, 10)

    val fields = parseFields(bytes, headerLen)
    val charset = dbfCharset(bytes)

    val rows = ArrayList<List<String>>()
    rows.add(fields.map { String(it.nameBytes, charset).trim() })
    if (recordLen <= 0) return listOf(SheetData(file.nameWithoutExtension, rows))

    for (r in 0 until numRecords) {
        val pos = headerLen + r * recordLen
        if (pos + recordLen > bytes.size) break
        if (bytes[pos] == '*'.code.toByte()) continue
        var p = pos + 1
        val row = ArrayList<String>(fields.size)
        for (f in fields) {
            val end = minOf(p + f.length, bytes.size)
            val raw = String(bytes, p, end - p, charset).trim()
            row.add(dbfValue(f.type, raw))
            p += f.length
        }
        rows.add(row)
    }
    return listOf(SheetData(file.nameWithoutExtension, rows))
}

private fun dbfCharset(bytes: ByteArray): Charset = Charsets.UTF_8

private fun parseFields(bytes: ByteArray, headerLen: Int): List<DbfField> {
    val fields = ArrayList<DbfField>()
    var off = 32
    while (off + 32 <= headerLen && off + 32 <= bytes.size && bytes[off].toInt() != 0x0D) {
        val nameBytes = bytes.copyOfRange(off, off + 11).takeWhile { it.toInt() != 0 }.toByteArray()
        fields.add(
            DbfField(
                nameBytes = nameBytes,
                type = (bytes[off + 11].toInt() and 0xFF).toChar(),
                length = bytes[off + 16].toInt() and 0xFF
            )
        )
        off += 32
    }
    return fields
}

private fun dbfValue(type: Char, raw: String): String = when (type) {
    'D' -> if (raw.length == 8 && raw.all { it.isDigit() }) {
        "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
    } else {
        raw
    }
    'L' -> when (raw.uppercase()) {
        "T", "Y" -> "TRUE"
        "F", "N" -> "FALSE"
        else -> ""
    }
    'M', 'B', 'G', 'P' -> if (raw.isBlank()) "" else "[memo]"
    else -> raw
}

private fun le16(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

private fun le32(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)