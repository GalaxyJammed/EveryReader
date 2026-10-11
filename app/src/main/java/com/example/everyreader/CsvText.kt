package com.example.everyreader

import java.io.File

private fun linesOf(file: File): List<String> =
    file.readText().removePrefix("\uFEFF").lines().let {
        if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it
    }

private fun delimiterFor(file: File, lines: List<String>): Char = when (file.extension.lowercase()) {
    "tsv" -> '\t'
    "psv" -> '|'
    "ssv" -> ';'
    else -> {
        val first = lines.firstOrNull().orEmpty()
        if (first.count { it == ';' } > first.count { it == ',' }) ';' else ','
    }
}

private fun parseCsvLine(line: String, delim: Char): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                sb.append('"')
                i++
            }
            ch == '"' -> quoted = !quoted
            ch == delim && !quoted -> {
                out.add(sb.toString())
                sb.clear()
            }
            else -> sb.append(ch)
        }
        i++
    }
    out.add(sb.toString())
    return out
}

private fun escapeCsv(s: String, delim: Char): String =
    if (s.any { it == '"' || it == '\n' || it == '\r' || it == delim }) {
        "\"" + s.replace("\"", "\"\"") + "\""
    } else {
        s
    }

fun readCsvRows(file: File): List<List<String>> {
    val lines = linesOf(file)
    val delim = delimiterFor(file, lines)
    return lines.map { parseCsvLine(it, delim) }
}

fun writeCsvCell(file: File, row: Int, col: Int, value: String): Boolean =
    runCatching {
        val lines = linesOf(file)
        val delim = delimiterFor(file, lines)
        val rows = lines.map { parseCsvLine(it, delim).toMutableList() }.toMutableList()
        while (rows.size <= row) rows.add(mutableListOf())
        val target = rows[row]
        while (target.size <= col) target.add("")
        target[col] = value
        val out = rows.joinToString("\n") { r ->
            r.joinToString(delim.toString()) { escapeCsv(it, delim) }
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(out + "\n")
        if (tmp.renameTo(file)) true else {
            tmp.delete()
            false
        }
    }.getOrDefault(false)