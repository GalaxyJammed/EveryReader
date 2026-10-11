package com.example.everyreader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val DOC_PART = "word/document.xml"

private val RPR_ORDER = listOf(
    "w:rStyle", "w:rFonts", "w:b", "w:bCs", "w:i", "w:iCs", "w:caps", "w:smallCaps",
    "w:strike", "w:dstrike", "w:outline", "w:shadow", "w:emboss", "w:imprint", "w:noProof",
    "w:snapToGrid", "w:vanish", "w:webHidden", "w:color", "w:spacing", "w:w", "w:kern",
    "w:position", "w:sz", "w:szCs", "w:highlight", "w:u", "w:effect", "w:bdr", "w:shd",
    "w:fitText", "w:vertAlign", "w:rtl", "w:cs", "w:em", "w:lang", "w:eastAsianLayout",
    "w:specVanish", "w:oMath"
)

class DocxSession private constructor(
    private val file: File,
    private val parts: Map<String, ByteArray>,
    initial: ByteArray
) {
    private var docBytes: ByteArray = initial
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
        val current = parts.toMutableMap()
        current[DOC_PART] = docBytes
        sections = readDocxParts(current).flatMap { paginate(it) }
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun edit(mutate: (Document) -> Boolean): Boolean {
        val doc = parseXml(docBytes)
        if (!mutate(doc)) return false
        undoStack.add(docBytes)
        redoStack.clear()
        docBytes = serializeXml(doc)
        dirty = true
        refresh()
        return true
    }

    fun setParagraphText(index: Int, text: String): Boolean = edit { doc ->
        paragraphAt(doc, index)?.let { replaceParagraphText(doc, it, text); true } ?: false
    }

    fun setParagraphColor(index: Int, hex: String): Boolean = edit { doc ->
        paragraphAt(doc, index)?.let { p ->
            runsOf(p).forEach { run -> putInOrder(doc, rPrFor(doc, run), "w:color", mapOf("w:val" to hex)) }
            true
        } ?: false
    }

    fun setUnderline(index: Int, hex: String?): Boolean = edit { doc ->
        paragraphAt(doc, index)?.let { p ->
            runsOf(p).forEach { run ->
                val rPr = rPrFor(doc, run)
                if (hex == null) {
                    removeChildrenNamed(rPr, "w:u")
                } else {
                    putInOrder(doc, rPr, "w:u", mapOf("w:val" to "single", "w:color" to hex))
                }
            }
            true
        } ?: false
    }

    fun setImageFrame(drawIdx: Int, xPt: Float, yPt: Float, wPt: Float, hPt: Float, rotDeg: Float): Boolean =
        edit { doc ->
            drawingAt(doc, drawIdx)?.let { d ->
                val anchor = ensureAnchor(doc, d)
                setOffset(doc, anchor, "wp:positionH", xPt)
                setOffset(doc, anchor, "wp:positionV", yPt)
                setDrawingSize(anchor, wPt, hPt, rotDeg)
                true
            } ?: false
        }

    fun removeImage(drawIdx: Int): Boolean = edit { doc ->
        drawingAt(doc, drawIdx)?.let { d -> d.parentNode?.removeChild(d); true } ?: false
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.add(docBytes)
        docBytes = undoStack.removeAt(undoStack.lastIndex)
        dirty = true
        refresh()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.add(docBytes)
        docBytes = redoStack.removeAt(redoStack.lastIndex)
        dirty = true
        refresh()
        return true
    }

    fun save(overwrite: Boolean): File? {
        val target = if (overwrite) file else copyName(file)
        val tmp = File(file.parentFile, "${target.name}.tmp")
        val ok = runCatching {
            ZipOutputStream(tmp.outputStream()).use { out ->
                for ((name, bytes) in parts) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(if (name == DOC_PART) docBytes else bytes)
                    out.closeEntry()
                }
            }
            tmp.renameTo(target)
        }.getOrDefault(false)
        if (!ok) {
            tmp.delete()
            return null
        }
        dirty = false
        return target
    }

    companion object {
        fun open(file: File): DocxSession? = runCatching {
            val parts = LinkedHashMap<String, ByteArray>()
            ZipFile(file).use { zip ->
                for (e in zip.entries().asSequence()) {
                    if (!e.isDirectory) parts[e.name] = zip.getInputStream(e).use { it.readBytes() }
                }
            }
            val doc = parts[DOC_PART] ?: return@runCatching null
            DocxSession(file, parts, doc)
        }.getOrNull()
    }
}

private fun parseXml(bytes: ByteArray): Document =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bytes.inputStream())

private fun serializeXml(doc: Document): ByteArray {
    val t = TransformerFactory.newInstance().newTransformer()
    t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    t.setOutputProperty(OutputKeys.STANDALONE, "yes")
    val out = ByteArrayOutputStream()
    t.transform(DOMSource(doc), StreamResult(out))
    return out.toByteArray()
}

private fun paragraphAt(doc: Document, index: Int): Element? {
    val ps = doc.getElementsByTagName("w:p")
    return if (index in 0 until ps.length) ps.item(index) as Element else null
}

private fun drawingAt(doc: Document, index: Int): Element? {
    val ds = doc.getElementsByTagName("w:drawing")
    return if (index in 0 until ds.length) ds.item(index) as Element else null
}

private fun runsOf(p: Element): List<Element> {
    val nl = p.getElementsByTagName("w:r")
    return List(nl.length) { nl.item(it) as Element }
}

private fun childNamed(parent: Element, tag: String): Element? {
    for (i in 0 until parent.childNodes.length) {
        val c = parent.childNodes.item(i)
        if (c is Element && c.tagName == tag) return c
    }
    return null
}

private fun removeChildrenNamed(parent: Element, tag: String) {
    val kids = (0 until parent.childNodes.length).map { parent.childNodes.item(it) }
    kids.filter { it is Element && it.tagName == tag }.forEach { parent.removeChild(it) }
}

private fun rPrFor(doc: Document, run: Element): Element {
    childNamed(run, "w:rPr")?.let { return it }
    val rPr = doc.createElement("w:rPr")
    run.insertBefore(rPr, run.firstChild)
    return rPr
}

private fun putInOrder(doc: Document, rPr: Element, tag: String, attrs: Map<String, String>) {
    removeChildrenNamed(rPr, tag)
    val el = doc.createElement(tag)
    attrs.forEach { (k, v) -> el.setAttribute(k, v) }
    val rank = RPR_ORDER.indexOf(tag)
    val before = (0 until rPr.childNodes.length).map { rPr.childNodes.item(it) }
        .firstOrNull { n -> n is Element && RPR_ORDER.indexOf(n.tagName) > rank }
    if (before != null) rPr.insertBefore(el, before) else rPr.appendChild(el)
}

private fun hasDrawing(run: Element): Boolean =
    run.getElementsByTagName("w:drawing").length > 0 || run.getElementsByTagName("w:pict").length > 0

private fun replaceParagraphText(doc: Document, p: Element, text: String) {
    val firstTextRun = runsOf(p).firstOrNull { !hasDrawing(it) }
    val rPr = firstTextRun?.let { childNamed(it, "w:rPr") }?.cloneNode(true)
    val kids = (0 until p.childNodes.length).map { p.childNodes.item(it) }
    kids.filter { k ->
        !(k is Element && k.tagName == "w:pPr") && !(k is Element && k.tagName == "w:r" && hasDrawing(k))
    }.forEach { p.removeChild(it) }
    val lines = text.split('\n')
    lines.forEachIndexed { i, line ->
        val r = doc.createElement("w:r")
        if (rPr != null) r.appendChild(rPr.cloneNode(true))
        val t = doc.createElement("w:t")
        t.setAttribute("xml:space", "preserve")
        t.textContent = line
        r.appendChild(t)
        p.appendChild(r)
        if (i < lines.size - 1) {
            val br = doc.createElement("w:r")
            br.appendChild(doc.createElement("w:br"))
            p.appendChild(br)
        }
    }
}

private fun ensureAnchor(doc: Document, d: Element): Element {
    childNamed(d, "wp:anchor")?.let { return it }
    val inline = childNamed(d, "wp:inline") ?: return d
    val anchor = doc.createElement("wp:anchor")
    listOf(
        "distT" to "0",
        "distB" to "0",
        "distL" to "0",
        "distR" to "0",
        "simplePos" to "0",
        "relativeHeight" to "251659264",
        "behindDoc" to "0",
        "locked" to "0",
        "layoutInCell" to "1",
        "allowOverlap" to "1"
    ).forEach { (k, v) -> anchor.setAttribute(k, v) }
    val simple = doc.createElement("wp:simplePos")
    simple.setAttribute("x", "0")
    simple.setAttribute("y", "0")
    anchor.appendChild(simple)
    anchor.appendChild(doc.createElement("wp:positionH"))
    anchor.appendChild(doc.createElement("wp:positionV"))
    childNamed(inline, "wp:extent")?.let { anchor.appendChild(it.cloneNode(true)) }
    childNamed(inline, "wp:effectExtent")?.let { anchor.appendChild(it.cloneNode(true)) }
    anchor.appendChild(doc.createElement("wp:wrapNone"))
    childNamed(inline, "wp:docPr")?.let { anchor.appendChild(it.cloneNode(true)) }
    childNamed(inline, "wp:cNvGraphicFramePr")?.let { anchor.appendChild(it.cloneNode(true)) }
    childNamed(inline, "a:graphic")?.let { anchor.appendChild(it.cloneNode(true)) }
    d.replaceChild(anchor, inline)
    return anchor
}

private fun setOffset(doc: Document, anchor: Element, tag: String, pt: Float) {
    val pos = childNamed(anchor, tag) ?: return
    pos.setAttribute("relativeFrom", "page")
    removeChildrenNamed(pos, "wp:posOffset")
    val off = doc.createElement("wp:posOffset")
    off.textContent = (pt * 12700f).toLong().toString()
    pos.appendChild(off)
}

private fun setDrawingSize(d: Element, wPt: Float, hPt: Float, rotDeg: Float) {
    val cx = (wPt * 12700f).toLong().toString()
    val cy = (hPt * 12700f).toLong().toString()
    val extent = d.getElementsByTagName("wp:extent")
    if (extent.length > 0) {
        (extent.item(0) as Element).setAttribute("cx", cx)
        (extent.item(0) as Element).setAttribute("cy", cy)
    }
    val spPr = d.getElementsByTagName("pic:spPr")
    if (spPr.length == 0) return
    val xfrmList = (spPr.item(0) as Element).getElementsByTagName("a:xfrm")
    if (xfrmList.length == 0) return
    val xfrm = xfrmList.item(0) as Element
    val r = ((rotDeg % 360f) + 360f) % 360f
    if (r == 0f) xfrm.removeAttribute("rot") else xfrm.setAttribute("rot", (r * 60000f).toLong().toString())
    val ext = xfrm.getElementsByTagName("a:ext")
    if (ext.length > 0) {
        (ext.item(0) as Element).setAttribute("cx", cx)
        (ext.item(0) as Element).setAttribute("cy", cy)
    }
}

private fun copyName(src: File): File {
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