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
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

class Fb2Session private constructor(private val file: File, initial: ByteArray) {
    private var bytes: ByteArray = initial
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
        sections = runCatching { readFb2Parts(bytes).flatMap { paginate(it) } }.getOrDefault(emptyList())
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun edit(mutate: (Document) -> Boolean): Boolean {
        val doc = fb2Parse(bytes)
        if (!mutate(doc)) return false
        undoStack.add(bytes)
        redoStack.clear()
        bytes = fb2Serialize(doc)
        dirty = true
        refresh()
        return true
    }

    fun setParagraphText(index: Int, text: String): Boolean = edit { doc ->
        val p = fb2Paragraph(doc, index) ?: return@edit false
        p.textContent = text.replace('\n', ' ')
        true
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.add(bytes)
        bytes = undoStack.removeAt(undoStack.lastIndex)
        dirty = true
        refresh()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.add(bytes)
        bytes = redoStack.removeAt(redoStack.lastIndex)
        dirty = true
        refresh()
        return true
    }

    fun save(overwrite: Boolean): File? {
        val target = if (overwrite) file else fb2CopyName(file)
        val tmp = File(file.parentFile, "${target.name}.tmp")
        val ok = runCatching {
            tmp.writeBytes(bytes)
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
        fun open(file: File): Fb2Session? =
            runCatching { Fb2Session(file, file.readBytes()) }.getOrNull()
    }
}

fun readFb2(file: File): List<DocSection> = readFb2Parts(file.readBytes())

fun readFb2Parts(bytes: ByteArray): List<DocSection> {
    val root = xParse(bytes.inputStream()).documentElement
    val body = root.xAll("body").firstOrNull() ?: return emptyList()
    val allP = body.xAll("p")
    val sections = body.xChildren("section")
    return if (sections.isNotEmpty()) {
        sections.map { DocSection(fb2Blocks(it, allP)) }
    } else {
        listOf(DocSection(fb2Blocks(body, allP)))
    }
}

private fun fb2Blocks(el: Element, allP: List<Element>): List<DocBlock> {
    val out = ArrayList<DocBlock>()
    fun walk(e: Element) {
        for (c in e.xElements()) {
            when (c.tagName) {
                "title", "subtitle" -> fb2Block(c.textContent, true, -1)?.let { out.add(it) }
                "p" -> fb2Block(c.textContent, false, allP.indexOf(c))?.let { out.add(it) }
                "v", "text-author" -> fb2Block(c.textContent, false, -1)?.let { out.add(it) }
                "section", "poem", "stanza", "cite", "epigraph" -> walk(c)
            }
        }
    }
    walk(el)
    return out
}

private fun fb2Block(text: String, heading: Boolean, srcIndex: Int): DocBlock? {
    val t = text.replace(Regex("\\s+"), " ").trim()
    if (t.isEmpty()) return null
    val size = if (heading) 16f else 11f
    val para = SlideParagraph(
        listOf(SlideRun(t, size, heading, false, Color.Unspecified)),
        TextAlign.Start,
        null,
        0f
    )
    return DocPara(para, srcIndex = srcIndex)
}

private fun fb2Parse(bytes: ByteArray): Document =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bytes.inputStream())

private fun fb2Serialize(doc: Document): ByteArray {
    val t = TransformerFactory.newInstance().newTransformer()
    t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    val out = ByteArrayOutputStream()
    t.transform(DOMSource(doc), StreamResult(out))
    return out.toByteArray()
}

private fun fb2Paragraph(doc: Document, index: Int): Element? {
    val body = doc.getElementsByTagName("body").item(0) as? Element ?: return null
    val ps = body.getElementsByTagName("p")
    return if (index in 0 until ps.length) ps.item(index) as Element else null
}

private fun fb2CopyName(src: File): File {
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
fun Fb2EditToolbar(session: Fb2Session, onSave: () -> Unit) {
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