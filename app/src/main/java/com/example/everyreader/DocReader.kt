package com.example.everyreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val DOC_WIDTH_FACTOR = 0.93f
private const val LINE_FACTOR = 1.22f
private const val IMG_PAD = 28f
private const val KNOB_GAP = 18f
private const val HIT_DP = 22f

sealed interface DocBlock

data class DocPara(
    val para: SlideParagraph,
    val lineMult: Float = 1.08f,
    val before: Float = 0f,
    val after: Float = 6f,
    val srcIndex: Int = -1,
    val underlineColor: Color? = null
) : DocBlock

data class DocTable(val rows: List<List<String>>) : DocBlock

data class DocImage(
    val image: ImageBitmap,
    val widthPt: Float,
    val heightPt: Float,
    val drawIdx: Int = -1,
    val rot: Float = 0f,
    val floating: Boolean = false,
    val offX: Float = 0f,
    val offY: Float = 0f
) : DocBlock

data object DocPageBreak : DocBlock

data class DocSection(
    val blocks: List<DocBlock>,
    val widthPt: Float = 612f,
    val heightPt: Float = 792f,
    val marginLeft: Float = 72f,
    val marginTop: Float = 72f,
    val marginRight: Float = 72f,
    val marginBottom: Float = 72f
)

private class OdfStyle(
    val bold: Boolean?,
    val italic: Boolean?,
    val sizePt: Float?,
    val color: Color?,
    val align: TextAlign?
)

enum class DocTool { NONE, IMAGE, COLOR, UNDERLINE }

class DocEditState {
    var tool by mutableStateOf(DocTool.NONE)
    var color by mutableStateOf(Color.Black)
    var underlineColor by mutableStateOf(Color.Black)
    var selectedImg by mutableStateOf<Int?>(null)
    var imgX by mutableFloatStateOf(0f)
    var imgY by mutableFloatStateOf(0f)
    var imgW by mutableFloatStateOf(0f)
    var imgH by mutableFloatStateOf(0f)
    var imgRot by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var viewportW by mutableFloatStateOf(0f)
    var viewportH by mutableFloatStateOf(0f)
    var zoomOpen by mutableStateOf(false)
    val shownHints = mutableSetOf<String>()
    val inlinePos = HashMap<Int, Offset>()
    var selectedImage: ImageBitmap? = null

    fun selectImage(img: DocImage) {
        selectedImg = img.drawIdx
        selectedImage = img.image
        val measured = inlinePos[img.drawIdx]
        imgX = if (img.floating) img.offX else measured?.x ?: 0f
        imgY = if (img.floating) img.offY else measured?.y ?: 0f
        imgW = img.widthPt
        imgH = img.heightPt
        imgRot = img.rot
    }

    fun clearSelection() {
        selectedImg = null
    }
}

fun readDocument(file: File): List<DocSection> {
    val raw: List<DocSection> = when (file.extension.lowercase()) {
        "docx", "docm", "dotx", "dotm" -> readDocx(file)
        "odt", "ott" -> readOdt(file)
        "rtf" -> readRtf(file)
        "mht", "mhtml" -> readMht(file)
        "epub" -> readEpub(file)
        "fb2" -> readFb2(file)
        else -> emptyList()
    }
    return raw.flatMap { paginate(it) }
}

internal fun docPara(
    text: String,
    size: Float = 11f,
    bold: Boolean = false,
    align: TextAlign = TextAlign.Start
): DocPara =
    DocPara(SlideParagraph(listOf(SlideRun(text, size, bold, false, Color.Unspecified)), align, null, 0f))

internal fun paginate(section: DocSection): List<DocSection> {
    val contentW = section.widthPt - section.marginLeft - section.marginRight
    val contentH = section.heightPt - section.marginTop - section.marginBottom
    val pages = ArrayList<List<DocBlock>>()
    var cur = ArrayList<DocBlock>()
    var used = 0f

    fun flush() {
        pages.add(cur)
        cur = ArrayList()
        used = 0f
    }

    fun place(block: DocBlock, h: Float) {
        if (used + h > contentH && cur.isNotEmpty()) flush()
        cur.add(block)
        used += h
    }

    for (block in section.blocks) {
        when (block) {
            is DocPageBreak -> if (cur.isNotEmpty()) flush()
            is DocImage -> if (block.floating) {
                place(block, 0f)
            } else {
                val s = minOf(1f, contentW / block.widthPt, contentH / block.heightPt)
                val scaled = block.copy(widthPt = block.widthPt * s, heightPt = block.heightPt * s)
                place(scaled, scaled.heightPt + 6f)
            }
            is DocTable -> place(block, tableHeight(block.rows, contentW))
            is DocPara -> place(block, paraHeight(block, contentW))
        }
    }
    if (cur.isNotEmpty() || pages.isEmpty()) flush()

    return pages.map {
        DocSection(
            blocks = it,
            widthPt = section.widthPt,
            heightPt = section.heightPt,
            marginLeft = section.marginLeft,
            marginTop = section.marginTop,
            marginRight = section.marginRight,
            marginBottom = section.marginBottom
        )
    }
}

private fun paraHeight(d: DocPara, contentW: Float): Float {
    val p = d.para
    val size = p.runs.maxOfOrNull { it.sizePt } ?: 11f
    val text = p.runs.joinToString("") { it.text }
    val bulletW = if (p.bullet != null) size else 0f
    val usable = max(size, contentW - p.indentPt - bulletW)
    val perLine = usable / (0.5f * size * DOC_WIDTH_FACTOR)
    val lines = text.split('\n').fold(0) { acc, seg ->
        acc + max(1, ceil(seg.length / perLine).toInt())
    }
    return d.before + lines * size * d.lineMult * LINE_FACTOR + d.after
}

private fun tableHeight(rows: List<List<String>>, contentW: Float): Float {
    val cols = max(1, rows.maxOfOrNull { it.size } ?: 1)
    val perLine = max(10f, contentW / cols - 6f) / 5f
    val body = rows.fold(0f) { acc, row ->
        val longest = row.maxOfOrNull { it.length } ?: 0
        acc + max(1, ceil(longest / perLine).toInt()) * 12f + 6f
    }
    return body + 6f
}

fun readDocx(file: File): List<DocSection> = readDocxParts(partsOf(file))

fun partsOf(file: File): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    ZipFile(file).use { zip ->
        for (e in zip.entries().asSequence()) {
            if (!e.isDirectory) out[e.name] = zip.getInputStream(e).use { it.readBytes() }
        }
    }
    return out
}

fun readDocxParts(parts: Map<String, ByteArray>): List<DocSection> {
    val docBytes = parts["word/document.xml"] ?: return emptyList()
    val root = xParse(docBytes.inputStream()).documentElement
    val body = root.xAll("w:body").firstOrNull() ?: return emptyList()
    val rels = docxRelsOf(parts)
    val allP = root.xAll("w:p")
    val allDraw = root.xAll("w:drawing")
    val blocks = ArrayList<DocBlock>()
    docxCollect(body, blocks, parts, rels, allP, allDraw)
    val sect = body.xChild("w:sectPr")
    val pg = sect?.xChild("w:pgSz")
    val mar = sect?.xChild("w:pgMar")
    return listOf(
        DocSection(
            blocks = blocks,
            widthPt = twips(pg?.getAttribute("w:w")) ?: 612f,
            heightPt = twips(pg?.getAttribute("w:h")) ?: 792f,
            marginLeft = twips(mar?.getAttribute("w:left")) ?: 72f,
            marginTop = twips(mar?.getAttribute("w:top")) ?: 72f,
            marginRight = twips(mar?.getAttribute("w:right")) ?: 72f,
            marginBottom = twips(mar?.getAttribute("w:bottom")) ?: 72f
        )
    )
}

private fun twips(v: String?): Float? = v?.toFloatOrNull()?.div(20f)

private fun docxRelsOf(parts: Map<String, ByteArray>): Map<String, String> {
    val bytes = parts["word/_rels/document.xml.rels"] ?: return emptyMap()
    val root = xParse(bytes.inputStream()).documentElement
    return root.xAll("Relationship").associate { r ->
        val target = r.getAttribute("Target")
        val path = if (target.startsWith("/")) target.removePrefix("/") else "word/$target"
        r.getAttribute("Id") to path
    }
}

private fun docxCollect(
    parent: Element,
    out: MutableList<DocBlock>,
    parts: Map<String, ByteArray>,
    rels: Map<String, String>,
    allP: List<Element>,
    allDraw: List<Element>
) {
    for (el in parent.xElements()) {
        when (el.tagName) {
            "w:p" -> {
                val para = docxPara(el)
                val images = docxImages(el, parts, rels, allDraw)
                val sp = docxSpacing(el)
                val idx = allP.indexOf(el)
                val underlineColor = el.xAll("w:u").mapNotNull { u ->
                    val v = u.getAttribute("w:val")
                    if (v.isEmpty() || v == "none") {
                        null
                    } else {
                        u.getAttribute("w:color").takeIf { it.isNotEmpty() && it != "auto" }
                            ?.let { xHex(it) } ?: Color.Black
                    }
                }.firstOrNull()
                if (para.runs.any { it.text.isNotBlank() } || images.isEmpty()) {
                    out.add(
                        DocPara(
                            para = para,
                            lineMult = sp.first,
                            before = sp.second,
                            after = sp.third,
                            srcIndex = idx,
                            underlineColor = underlineColor
                        )
                    )
                }
                out.addAll(images)
                if (el.xAll("w:br").any { it.getAttribute("w:type") == "page" }) out.add(DocPageBreak)
            }
            "w:tbl" -> out.add(DocTable(docxTable(el)))
            "w:sdt" -> el.xChild("w:sdtContent")?.let { docxCollect(it, out, parts, rels, allP, allDraw) }
        }
    }
}

private fun docxImages(
    p: Element,
    parts: Map<String, ByteArray>,
    rels: Map<String, String>,
    allDraw: List<Element>
): List<DocBlock> {
    val out = ArrayList<DocBlock>()
    for (d in p.xAll("w:drawing")) {
        val blip = d.xAll("a:blip").firstOrNull() ?: continue
        val rid = blip.getAttribute("r:embed")
        val extent = d.xAll("wp:extent").firstOrNull()
        val wPt = (extent?.getAttribute("cx")?.toFloatOrNull() ?: 0f) / 12700f
        val hPt = (extent?.getAttribute("cy")?.toFloatOrNull() ?: 0f) / 12700f
        val rot = d.xAll("pic:spPr").firstOrNull()?.xAll("a:xfrm")?.firstOrNull()
            ?.getAttribute("rot")?.toFloatOrNull()?.div(60000f) ?: 0f
        val anchor = d.xChild("wp:anchor")
        val offX = anchor?.xChild("wp:positionH")?.xChild("wp:posOffset")
            ?.textContent?.trim()?.toFloatOrNull()?.div(12700f) ?: 0f
        val offY = anchor?.xChild("wp:positionV")?.xChild("wp:posOffset")
            ?.textContent?.trim()?.toFloatOrNull()?.div(12700f) ?: 0f
        loadDocImage(
            parts, rels[rid], wPt, hPt, allDraw.indexOf(d), rot,
            floating = anchor != null, offX = offX, offY = offY
        )?.let { out.add(it) }
    }
    for (pict in p.xAll("w:pict")) {
        val img = pict.xAll("v:imagedata").firstOrNull() ?: continue
        loadDocImage(parts, rels[img.getAttribute("r:id")], 0f, 0f, -1, 0f)?.let { out.add(it) }
    }
    return out
}

private fun loadDocImage(
    parts: Map<String, ByteArray>,
    path: String?,
    wPt: Float,
    hPt: Float,
    drawIdx: Int,
    rot: Float,
    floating: Boolean = false,
    offX: Float = 0f,
    offY: Float = 0f
): DocImage? {
    if (path == null) return null
    val bytes = parts[path] ?: return null
    val bmp = decodeDocBitmap(bytes) ?: return null
    val w = if (wPt > 0f) wPt else bmp.width * 0.75f
    val h = if (hPt > 0f) hPt else bmp.height * 0.75f
    return DocImage(bmp.asImageBitmap(), w, h, drawIdx, rot, floating, offX, offY)
}

private fun decodeDocBitmap(bytes: ByteArray): Bitmap? {
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

private fun docxSpacing(p: Element): Triple<Float, Float, Float> {
    val sp = p.xChild("w:pPr")?.xChild("w:spacing")
    val line = sp?.getAttribute("w:line")?.toFloatOrNull()
    val rule = sp?.getAttribute("w:lineRule").orEmpty()
    val lineMult = when {
        line == null -> 1.08f
        rule.isEmpty() || rule == "auto" -> line / 240f
        else -> 1.08f
    }
    val before = sp?.getAttribute("w:before")?.toFloatOrNull()?.div(20f) ?: 0f
    val after = sp?.getAttribute("w:after")?.toFloatOrNull()?.div(20f) ?: 8f
    return Triple(lineMult.coerceIn(0.8f, 2.5f), before, after)
}

private fun docxPara(p: Element): SlideParagraph {
    val pPr = p.xChild("w:pPr")
    val style = pPr?.xChild("w:pStyle")?.getAttribute("w:val").orEmpty()
    val headingSize = when {
        style.startsWith("Heading1") || style == "Title" -> 20f
        style.startsWith("Heading2") -> 16f
        style.startsWith("Heading") -> 14f
        else -> 0f
    }
    val align = when (pPr?.xChild("w:jc")?.getAttribute("w:val")) {
        "center" -> TextAlign.Center
        "right", "end" -> TextAlign.End
        "both" -> TextAlign.Justify
        else -> TextAlign.Start
    }
    val runs = ArrayList<SlideRun>()
    for (r in p.xAll("w:r")) {
        val rPr = r.xChild("w:rPr")
        val bold = headingSize > 0f || isOn(rPr?.xChild("w:b"))
        val italic = isOn(rPr?.xChild("w:i"))
        val size = rPr?.xChild("w:sz")?.getAttribute("w:val")?.toFloatOrNull()?.div(2f)
            ?: if (headingSize > 0f) headingSize else 11f
        val color = rPr?.xChild("w:color")?.getAttribute("w:val")
            ?.takeIf { it.isNotEmpty() && it != "auto" }
            ?.let { xHex(it) }
            ?: Color.Unspecified
        val text = docxRunText(r)
        if (text.isNotEmpty()) runs.add(SlideRun(text, size, bold, italic, color))
    }
    if (runs.isEmpty()) runs.add(SlideRun("", 11f, false, false, Color.Unspecified))
    return SlideParagraph(runs, align, null, 0f)
}

private fun isOn(el: Element?): Boolean {
    if (el == null) return false
    val v = el.getAttribute("w:val")
    return v != "0" && v != "false"
}

private fun docxRunText(r: Element): String {
    val sb = StringBuilder()
    for (c in r.xElements()) {
        when (c.tagName) {
            "w:t" -> sb.append(c.textContent)
            "w:tab" -> sb.append('\t')
            "w:br", "w:cr" -> if (c.getAttribute("w:type") != "page") sb.append('\n')
        }
    }
    return sb.toString()
}

private fun docxTable(tbl: Element): List<List<String>> =
    tbl.xChildren("w:tr").map { tr ->
        tr.xChildren("w:tc").map { tc ->
            tc.xChildren("w:p").joinToString("\n") { p ->
                docxPara(p).runs.joinToString("") { it.text }
            }
        }
    }

fun readOdt(file: File): List<DocSection> = readOdtParts(partsOf(file))

private fun odfLenPt(v: String?): Float? {
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

private fun odfPt(v: String): Float? =
    Regex("^([\\d.]+)pt$").find(v.trim())?.groupValues?.get(1)?.toFloatOrNull()

private fun odfTextStyles(roots: List<Element>): Map<String, OdfStyle> {
    val out = HashMap<String, OdfStyle>()
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
            val size = odfPt(tp?.getAttribute("fo:font-size").orEmpty())
            val colorAttr = tp?.getAttribute("fo:color").orEmpty()
            val color = if (colorAttr.startsWith("#")) xHex(colorAttr.removePrefix("#")) else null
            val align = when (pp?.getAttribute("fo:text-align")) {
                "center" -> TextAlign.Center
                "end" -> TextAlign.End
                "justify" -> TextAlign.Justify
                "start" -> TextAlign.Start
                else -> null
            }
            out[name] = OdfStyle(bold, italic, size, color, align)
        }
    }
    return out
}

private fun odfCollect(parent: Element, styles: Map<String, OdfStyle>, out: MutableList<DocBlock>) {
    for (el in parent.xElements()) {
        when (el.tagName) {
            "text:p", "text:h" -> out.add(DocPara(odfPara(el, styles)))
            "table:table" -> out.add(DocTable(odfTable(el)))
            "text:list", "text:list-item", "text:section" -> odfCollect(el, styles, out)
        }
    }
}

private fun odfPara(p: Element, styles: Map<String, OdfStyle>): SlideParagraph {
    val pStyle = styles[p.getAttribute("text:style-name")]
    val heading = p.tagName == "text:h"
    val baseSize = pStyle?.sizePt ?: if (heading) 16f else 11f
    val baseBold = pStyle?.bold ?: heading
    val runs = ArrayList<SlideRun>()

    fun add(text: String, s: OdfStyle?) {
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

    fun walk(node: Node, s: OdfStyle?) {
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

private fun odfTable(t: Element): List<List<String>> =
    t.xAll("table:table-row").map { row ->
        row.xElements()
            .filter { it.tagName == "table:table-cell" || it.tagName == "table:covered-table-cell" }
            .map { cell -> cell.xAll("text:p").joinToString("\n") { it.textContent } }
    }

private fun pageText(p: SlideParagraph, scale: Float, fontScale: Float): AnnotatedString =
    buildAnnotatedString {
        p.runs.forEach { r ->
            withStyle(
                SpanStyle(
                    fontSize = (r.sizePt * scale * DOC_WIDTH_FACTOR / fontScale).sp,
                    fontWeight = if (r.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (r.italic) FontStyle.Italic else FontStyle.Normal,
                    color = if (r.color == Color.Unspecified) Color.Black else r.color
                )
            ) {
                append(r.text)
            }
        }
    }

private class PageRef {
    var coords: LayoutCoordinates? = null
}

@Composable
fun DocView(
    sections: List<DocSection>,
    edit: DocEditState,
    editing: Boolean,
    onParaTap: (DocPara) -> Unit,
    onImageTap: (DocImage) -> Unit,
    onImageCommit: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val last = (sections.size - 1).coerceAtLeast(0)
    val current = listState.firstVisibleItemIndex.coerceIn(0, last)
    val zoomed = edit.zoom > 1.001f

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged {
                    edit.viewportW = it.width.toFloat()
                    edit.viewportH = it.height.toFloat()
                }
                .then(
                    if (editing) {
                        Modifier
                    } else {
                        Modifier.docZoomGesture { z, pan ->
                            val nz = (edit.zoom * z).coerceIn(1f, 4f)
                            edit.zoom = nz
                            if (nz <= 1.001f) {
                                edit.panX = 0f
                                edit.panY = 0f
                            } else {
                                edit.panX = clampPan(edit.panX + pan.x, nz, edit.viewportW)
                            }
                        }
                    }
                )
                .then(
                    if (editing) Modifier else Modifier.docPanGesture(edit, scope, listState)
                )
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 12.dp)
                    .graphicsLayer {
                        scaleX = edit.zoom
                        scaleY = edit.zoom
                        translationX = edit.panX
                        translationY = edit.panY
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = !(editing && edit.tool == DocTool.IMAGE && edit.selectedImg != null) &&
                        (editing || !zoomed)
            ) {
                itemsIndexed(sections) { _, page ->
                    DocPageCard(page, editing, edit, onParaTap, onImageTap, onImageCommit)
                }
            }
            ScrollIndicator { listState.scrollMetrics() }
        }
        if (!editing) {
            Spacer(modifier = Modifier.height(8.dp))
            ShieldCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = { scope.launch { listState.animateScrollToItem((current - 1).coerceAtLeast(0)) } },
                        enabled = current > 0
                    ) {
                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous page")
                    }
                    Text(
                        text = "Page ${current + 1} of ${sections.size}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    IconButton(
                        onClick = { scope.launch { listState.animateScrollToItem((current + 1).coerceAtMost(last)) } },
                        enabled = current < last
                    ) {
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next page")
                    }
                }
            }
        }
    }
}

@Composable
private fun DocPageCard(
    page: DocSection,
    editing: Boolean,
    edit: DocEditState,
    onParaTap: (DocPara) -> Unit,
    onImageTap: (DocImage) -> Unit,
    onImageCommit: () -> Unit
) {
    val fontScale = LocalDensity.current.fontScale
    val pageRef = remember { PageRef() }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val scale = maxWidth.value / page.widthPt
        Box(
            modifier = Modifier
                .width(maxWidth)
                .height((page.heightPt * scale).dp)
                .background(Color.White)
                .border(1.dp, Color(0x33000000))
                .clipToBounds()
                .onGloballyPositioned { pageRef.coords = it }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = (page.marginLeft * scale).dp,
                        end = (page.marginRight * scale).dp,
                        top = (page.marginTop * scale).dp,
                        bottom = (page.marginBottom * scale).dp
                    )
            ) {
                page.blocks.forEach { block ->
                    PageBlock(block, scale, fontScale, editing, edit, onParaTap, onImageTap, pageRef)
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                page.blocks.forEach { b ->
                    if (b is DocImage && b.floating && !(editing && edit.selectedImg == b.drawIdx)) {
                        FloatingImage(b, scale, editing, edit, onImageTap)
                    }
                }
                val ownsSelection = editing && edit.tool == DocTool.IMAGE && edit.selectedImg != null &&
                        page.blocks.any { it is DocImage && it.drawIdx == edit.selectedImg }
                if (ownsSelection) {
                    ImageEditor(page, scale, edit, onImageCommit)
                }
            }
        }
    }
}

@Composable
private fun PageBlock(
    block: DocBlock,
    scale: Float,
    fontScale: Float,
    editing: Boolean,
    edit: DocEditState,
    onParaTap: (DocPara) -> Unit,
    onImageTap: (DocImage) -> Unit,
    pageRef: PageRef
) {
    when (block) {
        is DocPara -> ParaBlock(block, scale, fontScale, editing, onParaTap)
        is DocTable -> PageTable(block.rows, scale, fontScale)
        is DocImage -> if (!block.floating) {
            InlineImage(block, scale, editing, edit, onImageTap, pageRef)
        }
        is DocPageBreak -> Unit
    }
}

@Composable
private fun InlineImage(
    block: DocImage,
    scale: Float,
    editing: Boolean,
    edit: DocEditState,
    onImageTap: (DocImage) -> Unit,
    pageRef: PageRef
) {
    val dens = LocalDensity.current.density
    val selected = editing && edit.tool == DocTool.IMAGE && block.drawIdx >= 0 &&
            edit.selectedImg == block.drawIdx
    val tappable = editing && edit.tool == DocTool.IMAGE && block.drawIdx >= 0
    val base = Modifier
        .padding(bottom = (6f * scale).dp)
        .size(width = (block.widthPt * scale).dp, height = (block.heightPt * scale).dp)
    if (selected) {
        Spacer(modifier = base)
        return
    }
    Box(
        modifier = base
            .onGloballyPositioned { c ->
                val pc = pageRef.coords
                if (pc != null && pc.isAttached && block.drawIdx >= 0) {
                    val p = pc.localPositionOf(c, Offset.Zero)
                    val pxPerPt = scale * dens
                    edit.inlinePos[block.drawIdx] = Offset(p.x / pxPerPt, p.y / pxPerPt)
                }
            }
            .then(if (tappable) Modifier.clickable { onImageTap(block) } else Modifier)
    ) {
        Image(
            bitmap = block.image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().rotate(block.rot)
        )
    }
}

@Composable
private fun FloatingImage(
    block: DocImage,
    scale: Float,
    editing: Boolean,
    edit: DocEditState,
    onImageTap: (DocImage) -> Unit
) {
    val tappable = editing && edit.tool == DocTool.IMAGE && block.drawIdx >= 0
    Box(
        modifier = Modifier
            .offset(x = (block.offX * scale).dp, y = (block.offY * scale).dp)
            .size(width = (block.widthPt * scale).dp, height = (block.heightPt * scale).dp)
            .then(if (tappable) Modifier.clickable { onImageTap(block) } else Modifier)
    ) {
        Image(
            bitmap = block.image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().rotate(block.rot)
        )
    }
}

@Composable
private fun ParaBlock(
    block: DocPara,
    scale: Float,
    fontScale: Float,
    editing: Boolean,
    onParaTap: (DocPara) -> Unit
) {
    val size = block.para.runs.maxOfOrNull { it.sizePt } ?: 11f
    val editable = editing && block.srcIndex >= 0
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val uc = block.underlineColor
    val underlineMod = if (uc != null) {
        Modifier.drawWithContent {
            drawContent()
            val l = layout
            if (l != null) {
                val w = 1.dp.toPx()
                for (i in 0 until l.lineCount) {
                    val y = l.getLineBottom(i) - w
                    drawLine(uc, Offset(l.getLineLeft(i), y), Offset(l.getLineRight(i), y), strokeWidth = w)
                }
            }
        }
    } else {
        Modifier
    }
    Text(
        text = pageText(block.para, scale, fontScale),
        textAlign = block.para.align,
        onTextLayout = { layout = it },
        style = TextStyle(
            lineHeight = (size * block.lineMult * LINE_FACTOR * scale / fontScale).sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (editable) {
                    Modifier
                        .border(1.dp, Color(0xFF6A1B9A))
                        .clickable { onParaTap(block) }
                } else {
                    Modifier
                }
            )
            .padding(
                start = (block.para.indentPt * scale).dp,
                top = (block.before * scale).dp,
                bottom = (block.after * scale).dp
            )
            .then(underlineMod)
    )
}

private data class ImgFrame(val cx: Float, val cy: Float, val hw: Float, val hh: Float, val rot: Float)

private fun frameOf(edit: DocEditState, pxPerPt: Float): ImgFrame {
    val hw = edit.imgW * pxPerPt / 2f
    val hh = edit.imgH * pxPerPt / 2f
    return ImgFrame(edit.imgX * pxPerPt + hw, edit.imgY * pxPerPt + hh, hw, hh, edit.imgRot)
}

private fun ImgFrame.center(): Offset = Offset(cx, cy)

private fun ImgFrame.corner(sx: Int, sy: Int): Offset {
    val v = rotVec(hw * sx, hh * sy, rot)
    return Offset(cx + v.first, cy + v.second)
}

private fun ImgFrame.knob(dens: Float): Offset {
    val v = rotVec(0f, -(hh + KNOB_GAP * dens), rot)
    return Offset(cx + v.first, cy + v.second)
}

private fun ImgFrame.contains(p: Offset): Boolean {
    val v = rotVec(p.x - cx, p.y - cy, -rot)
    return abs(v.first) <= hw && abs(v.second) <= hh
}

@Composable
private fun ImageEditor(
    page: DocSection,
    scale: Float,
    edit: DocEditState,
    onCommit: () -> Unit
) {
    val bitmap = edit.selectedImage ?: return
    val dens = LocalDensity.current.density
    val pxPerPt = scale * dens
    val pageWpx = page.widthPt * pxPerPt
    val pageHpx = page.heightPt * pxPerPt
    val ringColor = Color(0xFF6A1B9A)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(scale, page.widthPt, page.heightPt) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val f0 = frameOf(edit, pxPerPt)
                    val hit = HIT_DP * dens
                    val mode = when {
                        (down.position - f0.knob(dens)).getDistance() < hit -> 1
                        (down.position - f0.corner(1, 1)).getDistance() < hit -> 2
                        f0.contains(down.position) -> 0
                        else -> -1
                    }
                    if (mode < 0) return@awaitEachGesture
                    down.consume()

                    val startW = edit.imgW
                    val startH = edit.imgH
                    val startRot = edit.imgRot
                    val center = f0.center()
                    val startAngle = angleDeg(down.position, center)
                    val anchor = f0.corner(-1, -1)
                    var lastPos = down.position
                    var changed = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val p = change.position
                        when (mode) {
                            0 -> {
                                val step = p - lastPos
                                edit.imgX = (edit.imgX + step.x / pxPerPt)
                                    .coerceIn(0f, max(0f, page.widthPt - edit.imgW))
                                edit.imgY = (edit.imgY + step.y / pxPerPt)
                                    .coerceIn(0f, max(0f, page.heightPt - edit.imgH))
                            }
                            1 -> edit.imgRot = normDeg(startRot + angleDeg(p, center) - startAngle)
                            else -> {
                                val fp = Offset(p.x.coerceIn(0f, pageWpx), p.y.coerceIn(0f, pageHpx))
                                val local = rotVec(fp.x - anchor.x, fp.y - anchor.y, -startRot)
                                val aw = startW * pxPerPt
                                val ah = startH * pxPerPt
                                val minK = 4f / minOf(startW, startH)
                                val k = ((local.first * aw + local.second * ah) / (aw * aw + ah * ah))
                                    .coerceAtLeast(minK)
                                val newW = aw * k
                                val newH = ah * k
                                val c = rotVec(newW / 2f, newH / 2f, startRot)
                                val cx = anchor.x + c.first
                                val cy = anchor.y + c.second
                                edit.imgW = newW / pxPerPt
                                edit.imgH = newH / pxPerPt
                                edit.imgX = (cx - newW / 2f) / pxPerPt
                                edit.imgY = (cy - newH / 2f) / pxPerPt
                            }
                        }
                        lastPos = p
                        changed = true
                        change.consume()
                    }
                    if (changed) onCommit()
                }
            }
    ) {
        Box(
            modifier = Modifier
                .offset((edit.imgX * scale).dp, (edit.imgY * scale).dp)
                .size((edit.imgW * scale).dp, (edit.imgH * scale).dp)
                .rotate(edit.imgRot)
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        }
        Canvas(modifier = Modifier.matchParentSize()) {
            val f = frameOf(edit, pxPerPt)
            val corners = listOf(f.corner(-1, -1), f.corner(1, -1), f.corner(1, 1), f.corner(-1, 1))
            val outline = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                lineTo(corners[1].x, corners[1].y)
                lineTo(corners[2].x, corners[2].y)
                lineTo(corners[3].x, corners[3].y)
                close()
            }
            drawPath(outline, ringColor, style = Stroke(width = 2.dp.toPx()))
            val knob = f.knob(dens)
            drawLine(ringColor, f.corner(0, -1), knob, strokeWidth = 2.dp.toPx())
            drawCircle(Color.White, radius = 7.dp.toPx(), center = knob)
            drawCircle(ringColor, radius = 7.dp.toPx(), center = knob, style = Stroke(2.dp.toPx()))
            val handle = f.corner(1, 1)
            drawCircle(Color.White, radius = 7.dp.toPx(), center = handle)
            drawCircle(ringColor, radius = 7.dp.toPx(), center = handle, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun PageTable(rows: List<List<String>>, scale: Float, fontScale: Float) {
    val cols = rows.maxOfOrNull { it.size } ?: 0
    if (cols == 0) return
    val lineColor = Color.Black.copy(alpha = 0.6f)
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = (6f * scale).dp)) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                for (c in 0 until cols) {
                    Text(
                        text = row.getOrElse(c) { "" },
                        fontSize = (10f * scale / fontScale).sp,
                        color = Color.Black,
                        modifier = Modifier
                            .weight(1f)
                            .border(0.5.dp, lineColor)
                            .padding((3f * scale).dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DocEditToolbar(
    edit: DocEditState,
    session: DocxSession,
    onSave: () -> Unit,
    onRemoveImage: (Int) -> Unit
) {
    val context = LocalContext.current
    ShieldCard {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (edit.zoomOpen) {
                Column {
                    Text(
                        text = "Zoom ${"%.1f".format(edit.zoom)}x",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = edit.zoom,
                        onValueChange = { v ->
                            edit.zoom = v
                            if (v <= 1.001f) {
                                edit.panX = 0f
                                edit.panY = 0f
                            }
                        },
                        valueRange = 1f..4f
                    )
                }
            }
            if (edit.tool == DocTool.COLOR || edit.tool == DocTool.UNDERLINE) {
                val active = if (edit.tool == DocTool.COLOR) edit.color else edit.underlineColor
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pdfColors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (c == active) 3.dp else 1.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                                .clickable {
                                    if (edit.tool == DocTool.COLOR) edit.color = c else edit.underlineColor = c
                                }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DocToolButton(Icons.Default.OpenWith, "Move image", edit.tool == DocTool.IMAGE) {
                    edit.toggleTool(DocTool.IMAGE)
                    if (edit.tool == DocTool.IMAGE) {
                        edit.hintOnce(context, "docimage", "Press on an image to resize, rotate or move it")
                    }
                }
                DocToolButton(Icons.Default.FormatColorText, "Text color", edit.tool == DocTool.COLOR) {
                    edit.toggleTool(DocTool.COLOR)
                    if (edit.tool == DocTool.COLOR) {
                        edit.hintOnce(context, "doccolor", "Pick a color, then tap text to apply it")
                    }
                }
                DocToolButton(Icons.Default.FormatUnderlined, "Underline", edit.tool == DocTool.UNDERLINE) {
                    edit.toggleTool(DocTool.UNDERLINE)
                    if (edit.tool == DocTool.UNDERLINE) {
                        edit.hintOnce(context, "docunderline", "Pick a color, then tap text to underline it")
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DocToolButton(Icons.Default.Search, "Zoom", edit.zoomOpen) {
                    edit.zoomOpen = !edit.zoomOpen
                }
                IconButton(
                    onClick = {
                        session.undo()
                        edit.clearSelection()
                    },
                    enabled = session.canUndo
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = {
                    val s = edit.selectedImg
                    if (s != null) {
                        onRemoveImage(s)
                    } else {
                        edit.hintOnce(context, "docdelete", "Press on an image to remove it")
                    }
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                }
                IconButton(
                    onClick = {
                        session.redo()
                        edit.clearSelection()
                    },
                    enabled = session.canRedo
                ) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                }
                IconButton(onClick = onSave, enabled = session.dirty) {
                    Icon(Icons.Default.Save, contentDescription = "Save")
                }
            }
        }
    }
}

@Composable
private fun DocToolButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun DocEditState.toggleTool(t: DocTool) {
    tool = if (tool == t) DocTool.NONE else t
    clearSelection()
}

private fun DocEditState.hintOnce(context: Context, key: String, message: String) {
    if (shownHints.add(key)) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun clampPan(pan: Float, zoom: Float, width: Float): Float {
    val max = width * (zoom - 1f) / 2f
    return pan.coerceIn(-max, max)
}

private fun rotVec(x: Float, y: Float, deg: Float): Pair<Float, Float> {
    val r = Math.toRadians(deg.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    return (x * c - y * s) to (x * s + y * c)
}

private fun angleDeg(p: Offset, c: Offset): Float =
    Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat() + 90f

private fun normDeg(v: Float): Float = ((v % 360f) + 360f) % 360f

private fun Modifier.docZoomGesture(onZoom: (Float, Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    onZoom(event.calculateZoom(), event.calculatePan())
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }

private fun Modifier.docPanGesture(
    edit: DocEditState,
    scope: CoroutineScope,
    listState: LazyListState
): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var last = down.position
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || event.changes.count { it.pressed } > 1) break
                if (edit.zoom > 1.001f) {
                    val d = change.position - last
                    edit.panX = clampPan(edit.panX + d.x, edit.zoom, edit.viewportW)
                    edit.panY += d.y
                    val limit = edit.viewportH / 2f
                    if (abs(edit.panY) > limit) {
                        val step = edit.panY
                        edit.panY = 0f
                        scope.launch { listState.scrollBy(-step / edit.zoom) }
                    }
                    change.consume()
                }
                last = change.position
            }
        }
    }