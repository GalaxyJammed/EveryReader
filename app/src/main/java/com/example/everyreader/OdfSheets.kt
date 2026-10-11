package com.example.everyreader

import java.io.File
import java.util.zip.ZipFile
import org.w3c.dom.Element

private const val ODS_MAX_ROWS = 1000
private const val ODS_MAX_COLS = 60

fun readOds(file: File): List<SheetData> {
    val root: Element = if (file.extension.lowercase() == "fods") {
        file.inputStream().use { xParse(it).documentElement }
    } else {
        ZipFile(file).use { zip -> xZipDom(zip, "content.xml") } ?: return emptyList()
    }
    return root.xAll("table:table").map { table ->
        SheetData(table.getAttribute("table:name").ifEmpty { "Sheet" }, odsRows(table))
    }
}

private fun odsRows(table: Element): List<List<String>> {
    val rows = ArrayList<List<String>>()
    for (rowEl in table.xAll("table:table-row")) {
        if (rows.size >= ODS_MAX_ROWS) break
        val times = rowEl.getAttribute("table:number-rows-repeated").toIntOrNull() ?: 1
        val cells = odsCells(rowEl)
        repeat(minOf(times, ODS_MAX_ROWS - rows.size)) { rows.add(cells) }
    }
    return rows
}

private fun odsCells(row: Element): List<String> {
    val out = ArrayList<String>()
    for (cell in row.xElements()) {
        if (cell.tagName != "table:table-cell" && cell.tagName != "table:covered-table-cell") continue
        if (out.size >= ODS_MAX_COLS) break
        val times = cell.getAttribute("table:number-columns-repeated").toIntOrNull() ?: 1
        val text = odsCellText(cell)
        repeat(minOf(times, ODS_MAX_COLS - out.size)) { out.add(text) }
    }
    return out
}

private fun odsCellText(cell: Element): String {
    val type = cell.getAttribute("office:value-type")
    val value = cell.getAttribute("office:value")
    if (value.isNotEmpty() && type in setOf("float", "percentage", "currency")) {
        val d = value.toDoubleOrNull() ?: return value
        return if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
    }
    if (type == "boolean") return cell.getAttribute("office:boolean-value").uppercase()
    return cell.xChildren("text:p").joinToString("\n") { it.textContent }
}