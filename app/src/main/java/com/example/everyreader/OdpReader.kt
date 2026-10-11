package com.example.everyreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import java.io.File
import java.util.zip.ZipFile
import org.w3c.dom.Element
import org.w3c.dom.Node

fun readSlides(file: File): List<SlideData> =
    when (file.extension.lowercase()) {
        "odp", "otp" -> readOdp(file)
        else -> readPptx(file)
    }

private class OdpStyle(
    val fill: Color?,
    val stroke: Color?,
    val strokeWpt: Float,
    val sizePt: Float?,
    val bold: Boolean?,
    val italic: Boolean?,
    val color: Color?,
    val align: TextAlign?,
    val anchor: String?
)

fun readOdp(file: File): List<SlideData> = ZipFile(file).use { zip ->
    val content = xZipDom(zip, "content.xml") ?: return@use emptyList()
    val stylesRoot = xZipDom(zip, "styles.xml")
    val roots = listOfNotNull(stylesRoot, content)
    val styles = odpStyles(roots)
    val (pw, ph) = odpPageSize(roots)
    content.xAll("draw:page").map { page ->
        val items = ArrayList<SlideItem>()
        for (el in page.xElements()) {
            when (el.tagName) {
                "draw:frame" -> {
                    val img = el.xChild("draw:image")
                    if (img != null) {
                        odpPicture(el, img, pw, ph, zip, items)
                    } else {
                        odpBox(el, el.xChild("draw:text-box") ?: el, styles, pw, ph, items)
                    }
                }
                "draw:custom-shape", "draw:rect", "draw:ellipse" ->
                    odpBox(el, el, styles, pw, ph, items)
            }
        }
        val bg = styles[page.getAttribute("draw:style-name")]?.fill ?: Color.White
        SlideData(pw, pw / ph, bg, null, items)
    }
}

private fun odpLen(v: String): Float? {
    val m = Regex("^(-?[\\d.]+)\\s*(cm|mm|in|pt|pc|px)?$").find(v.trim()) ?: return null
    val n = m.groupValues[1].toFloatOrNull() ?: return null
    val factor = when (m.groupValues[2]) {
        "cm" -> 28.3465f
        "mm" -> 2.83465f
        "in" -> 72f
        "pc" -> 12f
        "px" -> 0.75f
        else -> 1f
    }
    return n * factor
}

private fun odpColor(s: String): Color? =
    if (s.length == 7 && s.startsWith("#")) s.substring(1).toLongOrNull(16)?.let { Color(0xFF000000L or it) } else null

private fun odpPageSize(roots: List<Element>): Pair<Float, Float> {
    for (root in roots) {
        for (pl in root.xAll("style:page-layout-properties")) {
            val w = odpLen(pl.getAttribute("fo:page-width"))
            val h = odpLen(pl.getAttribute("fo:page-height"))
            if (w != null && h != null && w > 0f && h > 0f) return w to h
        }
    }
    return 720f to 540f
}

private fun odpStyles(roots: List<Element>): Map<String, OdpStyle> {
    val out = HashMap<String, OdpStyle>()
    for (root in roots) {
        for (st in root.xAll("style:style")) {
            val name = st.getAttribute("style:name")
            if (name.isEmpty()) continue
            val gp = st.xChild("style:graphic-properties")
            val dp = st.xChild("style:drawing-page-properties")
            val tp = st.xChild("style:text-properties")
            val pp = st.xChild("style:paragraph-properties")
            val fillMode = (gp ?: dp)?.getAttribute("draw:fill").orEmpty()
            val fillColor = odpColor((gp ?: dp)?.getAttribute("draw:fill-color").orEmpty())
            val fill = if (fillMode == "none") null else fillColor
            val stroke = gp?.takeIf { it.getAttribute("draw:stroke") == "solid" }
                ?.let { odpColor(it.getAttribute("svg:stroke-color")) }
            val strokeW = odpLen(gp?.getAttribute("svg:stroke-width").orEmpty()) ?: 1f
            val size = odpLen(tp?.getAttribute("fo:font-size").orEmpty())
            val weight = tp?.getAttribute("fo:font-weight").orEmpty()
            val bold = if (weight.isEmpty()) null else weight == "bold" || (weight.toIntOrNull() ?: 0) >= 600
            val italicAttr = tp?.getAttribute("fo:font-style").orEmpty()
            val italic = if (italicAttr.isEmpty()) null else italicAttr == "italic"
            val color = odpColor(tp?.getAttribute("fo:color").orEmpty())
            val align = when (pp?.getAttribute("fo:text-align")) {
                "center" -> TextAlign.Center
                "end" -> TextAlign.End
                "justify" -> TextAlign.Justify
                "start" -> TextAlign.Start
                else -> null
            }
            val anchor = when (gp?.getAttribute("draw:textarea-vertical-align")) {
                "middle" -> "ctr"
                "bottom" -> "b"
                "top" -> "t"
                else -> null
            }
            out[name] = OdpStyle(fill, stroke, strokeW, size, bold, italic, color, align, anchor)
        }
    }
    return out
}

private fun odpBox(
    el: Element,
    container: Element,
    styles: Map<String, OdpStyle>,
    pw: Float,
    ph: Float,
    out: MutableList<SlideItem>
) {
    val x = odpLen(el.getAttribute("svg:x")) ?: return
    val y = odpLen(el.getAttribute("svg:y")) ?: return
    val w = odpLen(el.getAttribute("svg:width")) ?: return
    val h = odpLen(el.getAttribute("svg:height")) ?: return
    val st = styles[el.getAttribute("draw:style-name")]
    val cls = el.getAttribute("presentation:class")
    val paras = container.xElements()
        .filter { it.tagName == "text:p" || it.tagName == "text:h" }
        .map { odpPara(it, styles, cls) }
    val hasText = paras.any { p -> p.runs.any { it.text.isNotBlank() } }
    val fill = st?.fill
    val stroke = st?.stroke
    if (fill == null && stroke == null && !hasText) return
    out.add(
        SlideTextBox(
            x = x / pw,
            y = y / ph,
            w = w / pw,
            h = h / ph,
            fill = fill,
            line = stroke,
            lineWpt = if (stroke != null) (st?.strokeWpt ?: 0f) else 0f,
            anchor = st?.anchor ?: "t",
            insetLr = 7.2f,
            insetTb = 3.6f,
            paragraphs = paras
        )
    )
}

private fun odpPicture(
    frame: Element,
    img: Element,
    pw: Float,
    ph: Float,
    zip: ZipFile,
    out: MutableList<SlideItem>
) {
    val x = odpLen(frame.getAttribute("svg:x")) ?: return
    val y = odpLen(frame.getAttribute("svg:y")) ?: return
    val w = odpLen(frame.getAttribute("svg:width")) ?: return
    val h = odpLen(frame.getAttribute("svg:height")) ?: return
    val entry = zip.getEntry(img.getAttribute("xlink:href")) ?: return
    val bytes = zip.getInputStream(entry).use { it.readBytes() }
    val bmp = odpDecode(bytes) ?: return
    out.add(
        SlidePicture(
            x = x / pw,
            y = y / ph,
            w = w / pw,
            h = h / ph,
            cropL = 0f,
            cropT = 0f,
            cropR = 0f,
            cropB = 0f,
            image = bmp.asImageBitmap()
        )
    )
}

private fun odpPara(p: Element, styles: Map<String, OdpStyle>, cls: String): SlideParagraph {
    val pStyle = styles[p.getAttribute("text:style-name")]
    val (defSize, defBold) = when (cls) {
        "title" -> 40f to true
        "subtitle" -> 24f to false
        "outline" -> 20f to false
        else -> 18f to false
    }
    val baseSize = pStyle?.sizePt ?: defSize
    val baseBold = pStyle?.bold ?: defBold
    val baseItalic = pStyle?.italic ?: false
    val baseColor = pStyle?.color ?: Color.Black
    val runs = ArrayList<SlideRun>()

    fun add(text: String, s: OdpStyle?) {
        if (text.isEmpty()) return
        runs.add(
            SlideRun(
                text,
                s?.sizePt ?: baseSize,
                s?.bold ?: baseBold,
                s?.italic ?: baseItalic,
                s?.color ?: baseColor
            )
        )
    }

    fun walk(node: Node, s: OdpStyle?) {
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
    if (runs.isEmpty()) runs.add(SlideRun("", baseSize, baseBold, baseItalic, baseColor))
    return SlideParagraph(runs, pStyle?.align ?: TextAlign.Start, null, 0f)
}

private fun odpDecode(bytes: ByteArray): Bitmap? {
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