package com.example.everyreader

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import org.w3c.dom.Node
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap

private const val ODT_CONTENT = "content.xml"

private class OdtStyleInfo(
    val bold: Boolean?,
    val italic: Boolean?,
    val sizePt: Float?,
    val color: Color?,
    val align: TextAlign?
)

fun readOdtParts(parts: Map<String, ByteArray>): List<DocSection> {
    val content = parts[ODT_CONTENT]?.let { xParse(it.inputStream()).documentElement } ?: return emptyList()
    val stylesRoot = parts["styles.xml"]?.let { xParse(it.inputStream()).documentElement }
    val roots = listOfNotNull(stylesRoot, content)
    val styles = odtStyles(roots)
    val textRoot = content.xAll("office:text").firstOrNull() ?: return emptyList()

    val indexOf = HashMap<Element, Int>()
    odtParagraphs(textRoot).forEachIndexed { i, p -> indexOf[p] = i }

    val blocks = ArrayList<DocBlock>()
    odtCollect(textRoot, styles, indexOf, parts, blocks)

    val pl = roots.flatMap { it.xAll("style:page-layout-properties") }.firstOrNull()
    return listOf(
        DocSection(
            blocks = blocks,
            widthPt = odtLenPt(pl?.getAttribute("fo:page-width")) ?: 595f,
            heightPt = odtLenPt(pl?.getAttribute("fo:page-height")) ?: 842f,
            marginLeft = odtLenPt(pl?.getAttribute("fo:margin-left")) ?: 72f,
            marginTop = odtLenPt(pl?.getAttribute("fo:margin-top")) ?: 72f,
            marginRight = odtLenPt(pl?.getAttribute("fo:margin-right")) ?: 72f,
            marginBottom = odtLenPt(pl?.getAttribute("fo:margin-bottom")) ?: 72f
        )
    )
}

fun odtParagraphs(root: Element): List<Element> {
    val out = ArrayList<Element>()
    fun walk(n: Node) {
        for (i in 0 until n.childNodes.length) {
            val c = n.childNodes.item(i)
            if (c is Element) {
                if (c.tagName == "text:p" || c.tagName == "text:h") out.add(c)
                walk(c)
            }
        }
    }
    walk(root)
    return out
}

private fun odtCollect(
    parent: Element,
    styles: Map<String, OdtStyleInfo>,
    indexOf: Map<Element, Int>,
    parts: Map<String, ByteArray>,
    out: MutableList<DocBlock>
) {
    for (el in parent.xElements()) {
        when (el.tagName) {
            "text:p", "text:h" -> {
                val para = odtPara(el, styles)
                val hasText = para.runs.any { it.text.isNotBlank() }
                val frames = el.xAll("draw:frame")
                if (hasText || frames.isEmpty()) {
                    out.add(DocPara(para = para, srcIndex = indexOf[el] ?: -1))
                }
                frames.forEach { f -> odtImage(f, parts)?.let { out.add(it) } }
            }
            "draw:frame" -> odtImage(el, parts)?.let { out.add(it) }
            "table:table" -> out.add(DocTable(odtTable(el)))
            "text:list", "text:list-item", "text:section" -> odtCollect(el, styles, indexOf, parts, out)
        }
    }
}

private fun odtImage(frame: Element, parts: Map<String, ByteArray>): DocImage? {
    val img = frame.xChild("draw:image") ?: return null
    val href = img.getAttribute("xlink:href")
    val bytes = parts[href] ?: return null
    val bmp = decodeOdtBitmap(bytes) ?: return null
    val w = odtLenPt(frame.getAttribute("svg:width")) ?: bmp.width * 0.75f
    val h = odtLenPt(frame.getAttribute("svg:height")) ?: bmp.height * 0.75f
    return DocImage(bmp.asImageBitmap(), w, h)
}

private fun decodeOdtBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )
}

private fun odtPara(p: Element, styles: Map<String, OdtStyleInfo>): SlideParagraph {
    val pStyle = styles[p.getAttribute("text:style-name")]
    val heading = p.tagName == "text:h"
    val baseSize = pStyle?.sizePt ?: if (heading) 16f else 11f
    val baseBold = pStyle?.bold ?: heading
    val runs = ArrayList<SlideRun>()

    fun add(text: String, s: OdtStyleInfo?) {
        if (text.isEmpty()) return
        runs.add(
            SlideRun(
                text,
                s?.sizePt ?: baseSize,
                s?.bold ?: baseBold,
                s?.italic ?: (pStyle?.italic ?: false),
                s?.color ?: pStyle?.color ?: Color.Unspecified
            )
        )
    }

    fun walk(node: Node, s: OdtStyleInfo?) {
        for (i in 0 until node.childNodes.length) {
            val c = node.childNodes.item(i)
            if (c.nodeType == Node.TEXT_NODE) {
                add(c.nodeValue.orEmpty(), s)
            } else if (c is Element) {
                when (c.tagName) {
                    "text:span" -> walk(c, styles[c.getAttribute("text:style-name")] ?: s)
                    "text:s" -> add(" ".repeat(c.getAttribute("text:c").toIntOrNull() ?: 1), s)
                    "text:tab" -> add("\t", s)
                    "text:line-break" -> add("\n", s)
                    "text:a" -> walk(c, s)
                }
            }
        }
    }

    walk(p, null)
    if (runs.isEmpty()) runs.add(SlideRun("", baseSize, baseBold, false, Color.Unspecified))
    return SlideParagraph(runs, pStyle?.align ?: TextAlign.Start, null, 0f)
}

private fun odtTable(t: Element): List<List<String>> =
    t.xAll("table:table-row").map { row ->
        row.xElements()
            .filter { it.tagName == "table:table-cell" || it.tagName == "table:covered-table-cell" }
            .map { cell -> cell.xAll("text:p").joinToString("\n") { it.textContent } }
    }

private fun odtStyles(roots: List<Element>): Map<String, OdtStyleInfo> {
    val out = HashMap<String, OdtStyleInfo>()
    for (root in roots) {
        for (st in root.xAll("style:style")) {
            val name = st.getAttribute("style:name")
            if (name.isEmpty()) continue
            val tp = st.xChild("style:text-properties")
            val pp = st.xChild("style:paragraph-properties")
            val weight = tp?.getAttribute("fo:font-weight").orEmpty()
            val bold = if (weight.isEmpty()) null else weight == "bold" || (weight.toIntOrNull() ?: 0) >= 600
            val italicAttr = tp?.getAttribute("fo:font-style").orEmpty()
            val italic = if (italicAttr.isEmpty()) null else italicAttr == "italic"
            val size = odtPt(tp?.getAttribute("fo:font-size").orEmpty())
            val colorAttr = tp?.getAttribute("fo:color").orEmpty()
            val color = if (colorAttr.startsWith("#")) xHex(colorAttr.removePrefix("#")) else null
            val align = when (pp?.getAttribute("fo:text-align")) {
                "center" -> TextAlign.Center
                "end" -> TextAlign.End
                "justify" -> TextAlign.Justify
                "start" -> TextAlign.Start
                else -> null
            }
            out[name] = OdtStyleInfo(bold, italic, size, color, align)
        }
    }
    return out
}

private fun odtPt(v: String): Float? =
    Regex("^([\\d.]+)pt$").find(v.trim())?.groupValues?.get(1)?.toFloatOrNull()

private fun odtLenPt(v: String?): Float? {
    val m = Regex("^(-?[\\d.]+)\\s*(cm|mm|in|pt|pc)$").find(v?.trim().orEmpty()) ?: return null
    val n = m.groupValues[1].toFloatOrNull() ?: return null
    val factor = when (m.groupValues[2]) {
        "cm" -> 28.3465f
        "mm" -> 2.83465f
        "in" -> 72f
        "pc" -> 12f
        else -> 1f
    }
    return n * factor
}

class OdtSession private constructor(
    private val file: File,
    private val parts: Map<String, ByteArray>,
    initial: ByteArray
) {
    private var contentBytes: ByteArray = initial
    private val undoStack = ArrayList<ByteArray>()
    private val redoStack = ArrayList<ByteArray>()

    var sections by mutableStateOf<List<DocSection>>(emptyList())
        private set
    var dirty by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    init {
        refresh()
    }

    private fun refresh() {
        sections = runCatching {
            readOdtParts(parts + (ODT_CONTENT to contentBytes)).flatMap { paginate(it) }
        }.getOrDefault(emptyList())
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun edit(mutate: (Document) -> Boolean): Boolean {
        val doc = xParse(contentBytes.inputStream())
        if (!mutate(doc)) return false
        undoStack.add(contentBytes)
        redoStack.clear()
        contentBytes = odtSerialize(doc)
        dirty = true
        refresh()
        return true
    }

    fun setParagraphText(index: Int, text: String): Boolean = edit { doc ->
        val p = odtParagraphAt(doc, index)
        if (p == null) {
            false
        } else {
            replaceOdtParagraph(doc, p, text)
            true
        }
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.add(contentBytes)
        contentBytes = undoStack.removeAt(undoStack.lastIndex)
        dirty = true
        refresh()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.add(contentBytes)
        contentBytes = redoStack.removeAt(redoStack.lastIndex)
        dirty = true
        refresh()
        return true
    }

    fun save(overwrite: Boolean): File? {
        val target = if (overwrite) file else odtCopyName(file)
        val tmp = File(file.parentFile, "${target.name}.tmp")
        val ok = runCatching {
            writeOdtZip(tmp, parts, contentBytes)
            tmp.renameTo(target)
        }.getOrDefault(false)
        if (!ok) {
            tmp.delete()
            return null
        }
        if (overwrite) dirty = false
        return target
    }

    companion object {
        fun open(file: File): OdtSession? = runCatching {
            val parts = partsOf(file)
            val content = parts[ODT_CONTENT] ?: return@runCatching null
            OdtSession(file, parts, content)
        }.getOrNull()
    }
}

private fun odtParagraphAt(doc: Document, index: Int): Element? {
    val textRoot = doc.getElementsByTagName("office:text").item(0) as? Element ?: return null
    return odtParagraphs(textRoot).getOrNull(index)
}

private fun replaceOdtParagraph(doc: Document, p: Element, text: String) {
    while (p.hasChildNodes()) p.removeChild(p.firstChild)
    val lines = text.split('\n')
    lines.forEachIndexed { i, line ->
        appendOdtText(doc, p, line)
        if (i < lines.size - 1) p.appendChild(doc.createElement("text:line-break"))
    }
}

private fun appendOdtText(doc: Document, p: Element, line: String) {
    val sb = StringBuilder()
    fun flush() {
        if (sb.isNotEmpty()) {
            p.appendChild(doc.createTextNode(sb.toString()))
            sb.clear()
        }
    }
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '\t' -> {
                flush()
                p.appendChild(doc.createElement("text:tab"))
                i++
            }
            c == ' ' -> {
                var run = 0
                while (i < line.length && line[i] == ' ') {
                    run++
                    i++
                }
                sb.append(' ')
                if (run > 1) {
                    flush()
                    val s = doc.createElement("text:s")
                    s.setAttribute("text:c", (run - 1).toString())
                    p.appendChild(s)
                }
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    flush()
}

private fun odtSerialize(doc: Document): ByteArray {
    val t = TransformerFactory.newInstance().newTransformer()
    t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    t.setOutputProperty(OutputKeys.STANDALONE, "yes")
    val out = ByteArrayOutputStream()
    t.transform(DOMSource(doc), StreamResult(out))
    return out.toByteArray()
}

private fun writeOdtZip(dest: File, parts: Map<String, ByteArray>, content: ByteArray) {
    ZipOutputStream(dest.outputStream()).use { out ->
        val names = listOf("mimetype") + parts.keys.filter { it != "mimetype" }
        for (name in names) {
            val data: ByteArray = if (name == ODT_CONTENT) content else (parts[name] ?: continue)
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

private fun odtCopyName(src: File): File {
    val base = src.nameWithoutExtension + "_edited"
    val ext = src.extension
    var candidate = File(src.parentFile, "$base.$ext")
    var n = 2
    while (candidate.exists()) {
        candidate = File(src.parentFile, "${base}_$n.$ext")
        n++
    }
    return candidate
}

@Composable
fun OdtEditToolbar(session: OdtSession, onSave: () -> Unit) {
    ShieldCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { session.undo() }, enabled = session.canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = { session.redo() }, enabled = session.canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
            IconButton(onClick = onSave, enabled = session.dirty) {
                Icon(Icons.Default.Save, contentDescription = "Save")
            }
        }
    }
}