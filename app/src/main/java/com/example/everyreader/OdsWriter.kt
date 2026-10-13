package com.example.everyreader

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val ODS_ROW = "table:table-row"
private const val ODS_CELL = "table:table-cell"
private const val ODS_COVERED = "table:covered-table-cell"
private const val ODS_ROWS_REP = "table:number-rows-repeated"
private const val ODS_COLS_REP = "table:number-columns-repeated"
private val ODS_GROUPS = setOf("table:table-header-rows", "table:table-rows", "table:table-row-group")
private val ODS_NUMBER = Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?$")

fun writeOdsCells(file: File, cells: List<Pair<SheetCell, String>>): Boolean = runCatching {
    val isFods = file.extension.lowercase() == "fods"
    val parts: Map<String, ByteArray>? = if (isFods) null else partsOf(file)
    val rawContent: ByteArray = if (isFods) {
        file.readBytes()
    } else {
        parts?.get("content.xml") ?: return@runCatching false
    }
    val doc = xParse(rawContent.inputStream())
    val tables = odsSheetTables(doc)

    cells.groupBy { it.first.sheet }.forEach { (sheetIndex, list) ->
        val table = tables.getOrNull(sheetIndex) ?: return@runCatching false
        list.forEach { (cell, value) ->
            val row = odsRowAt(table, cell.row) ?: return@runCatching false
            val el = odsCellAt(row, cell.col) ?: return@runCatching false
            odsSetCell(doc, el, value)
        }
    }

    val newContent = odsSerialize(doc)
    val tmp = File(file.parentFile, "${file.name}.tmp")
    if (isFods) {
        tmp.writeBytes(newContent)
    } else {
        val out = LinkedHashMap(parts ?: emptyMap())
        out["content.xml"] = newContent
        writeOdsZip(tmp, out)
    }
    tmp.renameTo(file)
}.getOrDefault(false)

private fun odsSheetTables(doc: Document): List<Element> {
    val spreadsheet = doc.getElementsByTagName("office:spreadsheet").item(0) as? Element ?: return emptyList()
    return spreadsheet.xChildren("table:table")
}

private fun odsRowElements(table: Element): List<Element> {
    val out = ArrayList<Element>()
    fun walk(parent: Element) {
        for (c in parent.xElements()) {
            when {
                c.tagName == ODS_ROW -> out.add(c)
                c.tagName in ODS_GROUPS -> walk(c)
            }
        }
    }
    walk(table)
    return out
}

private fun odsRepeat(el: Element, attr: String): Int =
    el.getAttribute(attr).toIntOrNull()?.coerceAtLeast(1) ?: 1

private fun odsIsolate(parent: Element, names: Set<String>, attr: String, target: Int): Element? {
    var pos = 0
    for (child in parent.xElements()) {
        if (child.tagName !in names) continue
        val rep = odsRepeat(child, attr)
        if (target < pos + rep) {
            if (rep == 1) return child
            val offset = target - pos
            val after = rep - offset - 1
            if (offset > 0) {
                val before = child.cloneNode(true) as Element
                before.setAttribute(attr, offset.toString())
                parent.insertBefore(before, child)
            }
            if (after > 0) {
                val tail = child.cloneNode(true) as Element
                tail.setAttribute(attr, after.toString())
                parent.insertBefore(tail, child.nextSibling)
            }
            child.setAttribute(attr, "1")
            return child
        }
        pos += rep
    }
    return null
}

private fun odsRowAt(table: Element, index: Int): Element? {
    val rows = odsRowElements(table)
    var pos = 0
    for (row in rows) {
        val rep = odsRepeat(row, ODS_ROWS_REP)
        if (index < pos + rep) {
            val parent = row.parentNode as? Element ?: return null
            return odsIsolate(parent, setOf(ODS_ROW), ODS_ROWS_REP, index - pos)
        }
        pos += rep
    }
    val gap = index - pos + 1
    val filler = table.ownerDocument.createElement(ODS_ROW)
    if (gap > 1) filler.setAttribute(ODS_ROWS_REP, gap.toString())
    filler.appendChild(table.ownerDocument.createElement(ODS_CELL))
    table.appendChild(filler)
    return odsRowAt(table, index)
}

private fun odsCellAt(row: Element, index: Int): Element? {
    val names = setOf(ODS_CELL, ODS_COVERED)
    var pos = 0
    for (c in row.xElements()) {
        if (c.tagName !in names) continue
        val rep = odsRepeat(c, ODS_COLS_REP)
        if (index < pos + rep) return odsIsolate(row, names, ODS_COLS_REP, index - pos)
        pos += rep
    }
    val gap = index - pos + 1
    val filler = row.ownerDocument.createElement(ODS_CELL)
    if (gap > 1) filler.setAttribute(ODS_COLS_REP, gap.toString())
    row.appendChild(filler)
    return odsCellAt(row, index)
}

private fun odsSetCell(doc: Document, cell: Element, value: String) {
    val names = (0 until cell.attributes.length).map { cell.attributes.item(it).nodeName }
    names.filter { it.startsWith("office:") || it.startsWith("table:formula") || it.startsWith("calcext:") }
        .forEach { cell.removeAttribute(it) }
    while (cell.hasChildNodes()) cell.removeChild(cell.firstChild)
    if (value.isEmpty()) return

    if (ODS_NUMBER.matches(value)) {
        cell.setAttribute("office:value-type", "float")
        cell.setAttribute("office:value", value)
    } else {
        cell.setAttribute("office:value-type", "string")
    }
    value.split('\n').forEach { line ->
        val p = doc.createElement("text:p")
        p.textContent = line
        cell.appendChild(p)
    }
}

private fun odsSerialize(doc: Document): ByteArray {
    val t = TransformerFactory.newInstance().newTransformer()
    t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    t.setOutputProperty(OutputKeys.STANDALONE, "yes")
    val out = ByteArrayOutputStream()
    t.transform(DOMSource(doc), StreamResult(out))
    return out.toByteArray()
}

private fun writeOdsZip(dest: File, parts: Map<String, ByteArray>) {
    ZipOutputStream(dest.outputStream()).use { out ->
        val names = listOf("mimetype") + parts.keys.filter { it != "mimetype" }
        for (name in names) {
            val data = parts[name] ?: continue
            if (name == "mimetype") {
                val e = ZipEntry(name)
                e.method = ZipEntry.STORED
                e.size = data.size.toLong()
                e.compressedSize = data.size.toLong()
                e.crc = CRC32().apply { update(data) }.value
                out.putNextEntry(e)
            } else {
                out.putNextEntry(ZipEntry(name))
            }
            out.write(data)
            out.closeEntry()
        }
    }
}