package com.example.everyreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints

private const val DEBUG_BOXES = false
private const val MIN_FIT = 0.6f

data class SlideRun(
    val text: String,
    val sizePt: Float,
    val bold: Boolean,
    val italic: Boolean,
    val color: Color
)

data class SlideParagraph(
    val runs: List<SlideRun>,
    val align: TextAlign,
    val bullet: String?,
    val indentPt: Float,
    val bulletColor: Color? = null
)

sealed interface SlideItem
sealed interface PathOp
data class MoveOp(val x: Float, val y: Float) : PathOp
data class LineOp(val x: Float, val y: Float) : PathOp
data class CubicOp(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float) : PathOp
data class QuadOp(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : PathOp
data class ArcOp(val left: Float, val top: Float, val width: Float, val height: Float, val start: Float, val sweep: Float) : PathOp
data object CloseOp : PathOp

data class SlideTextBox(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val fill: Color?,
    val line: Color?,
    val lineWpt: Float,
    val anchor: String,
    val insetLr: Float,
    val insetTb: Float,
    val paragraphs: List<SlideParagraph>,
    val shape: String = "rect",
    val path: List<PathOp>? = null,
    val noWrap: Boolean = false
) : SlideItem

data class SlidePicture(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val cropL: Float,
    val cropT: Float,
    val cropR: Float,
    val cropB: Float,
    val image: ImageBitmap,
    val line: Color? = null,
    val lineWpt: Float = 0f
) : SlideItem

data class SlideData(
    val widthPt: Float,
    val aspect: Float,
    val background: Color,
    val bgImage: ImageBitmap?,
    val items: List<SlideItem>
)

private data class Rect(val x: Double, val y: Double, val w: Double, val h: Double)

private data class Xf(val sx: Double, val sy: Double, val ox: Double, val oy: Double) {
    fun apply(r: Rect): Rect = Rect(r.x * sx + ox, r.y * sy + oy, r.w * sx, r.h * sy)
}

private data class PptRel(val type: String, val path: String)

private val IDENTITY = Xf(1.0, 1.0, 0.0, 0.0)

fun readPptx(file: File): List<SlideData> = ZipFile(file).use { zip ->
    val pres = loadXml(zip, "ppt/presentation.xml") ?: return@use emptyList()
    val sz = pres.byTag("p:sldSz").firstOrNull()
    val w = sz?.attrOrNull("cx")?.toDoubleOrNull() ?: 9144000.0
    val h = sz?.attrOrNull("cy")?.toDoubleOrNull() ?: 6858000.0
    val presDefault = pres.child("p:defaultTextStyle")
    val cache = HashMap<String, ImageBitmap?>()
    zip.entries().asSequence()
        .map { it.name }
        .filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
        .sortedBy { it.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: 0 }
        .toList()
        .map { parseSlide(zip, it, w, h, presDefault, cache) }
}

private fun parseSlide(
    zip: ZipFile,
    path: String,
    w: Double,
    h: Double,
    presDefault: Element?,
    cache: HashMap<String, ImageBitmap?>
): SlideData {
    val root = loadXml(zip, path)
    val slideRels = loadRels(zip, path)
    val layoutRel = slideRels.values.firstOrNull { it.type.endsWith("/slideLayout") }
    val layoutRoot = layoutRel?.let { loadXml(zip, it.path) }
    val layoutRels = layoutRel?.let { loadRels(zip, it.path) }.orEmpty()
    val masterRel = layoutRels.values.firstOrNull { it.type.endsWith("/slideMaster") }
    val masterRoot = masterRel?.let { loadXml(zip, it.path) }
    val masterRels = masterRel?.let { loadRels(zip, it.path) }.orEmpty()
    val themeRel = masterRels.values.firstOrNull { it.type.endsWith("/theme") }

    val ctx = Ctx(
        zip = zip,
        theme = parseTheme(themeRel?.let { loadXml(zip, it.path) }),
        clrMap = clrMapOf(masterRoot),
        layoutPh = indexPh(layoutRoot),
        masterPh = indexPh(masterRoot),
        masterRoot = masterRoot,
        presDefault = presDefault,
        slideW = w,
        slideH = h,
        imageCache = cache
    )

    val slideCSld = root?.child("p:cSld")
    val layoutCSld = layoutRoot?.child("p:cSld")
    val masterCSld = masterRoot?.child("p:cSld")

    var bgColor: Color? = null
    var bgImage: ImageBitmap? = null
    for ((cSld, rels) in listOf(slideCSld to slideRels, layoutCSld to layoutRels, masterCSld to masterRels)) {
        val bg = cSld?.child("p:bg") ?: continue
        if (bgColor != null || bgImage != null) break
        val bgPr = bg.child("p:bgPr")
        if (bgPr != null) {
            bgImage = bgPr.child("a:blipFill")?.child("a:blip")?.getAttribute("r:embed")
                ?.takeIf { it.isNotEmpty() }
                ?.let { ctx.imageFor(rels, it) }
            bgColor = bgPr.child("a:solidFill")?.let { ctx.colorFromFill(it) }
                ?: bgPr.child("a:gradFill")?.byTag("a:gs")?.firstOrNull()?.let { ctx.colorFromFill(it) }
        } else {
            bgColor = ctx.colorFromFill(bg.child("p:bgRef") ?: continue)
        }
    }
    bgColor = bgColor ?: ctx.scheme("bg1")

    val items = ArrayList<SlideItem>()
    val slideShowsMaster = root?.attrOrNull("showMasterSp") != "0"
    val layoutShowsMaster = layoutRoot?.attrOrNull("showMasterSp") != "0"
    if (slideShowsMaster && layoutShowsMaster) {
        masterCSld?.child("p:spTree")?.let { ctx.walk(it, IDENTITY, items, masterRels, skipPh = true) }
    }
    if (slideShowsMaster) {
        layoutCSld?.child("p:spTree")?.let { ctx.walk(it, IDENTITY, items, layoutRels, skipPh = true) }
    }
    slideCSld?.child("p:spTree")?.let { ctx.walk(it, IDENTITY, items, slideRels, skipPh = false) }

    return SlideData(
        widthPt = (w / 12700.0).toFloat(),
        aspect = (w / h).toFloat(),
        background = bgColor ?: Color.White,
        bgImage = bgImage,
        items = items
    )
}

private class Ctx(
    val zip: ZipFile,
    val theme: Map<String, Color>,
    val clrMap: Map<String, String>,
    val layoutPh: Map<String, Element>,
    val masterPh: Map<String, Element>,
    val masterRoot: Element?,
    val presDefault: Element?,
    val slideW: Double,
    val slideH: Double,
    val imageCache: HashMap<String, ImageBitmap?>
) {
    fun imageFor(rels: Map<String, PptRel>, rid: String): ImageBitmap? {
        val path = rels[rid]?.path ?: return null
        return imageCache.getOrPut(path) { loadImage(path) }
    }

    private fun loadImage(path: String): ImageBitmap? {
        val entry = zip.getEntry(path) ?: return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        return decodeScaled(bytes)?.asImageBitmap()
    }

    fun scheme(name: String): Color? = theme[clrMap[name] ?: name] ?: theme[name]

    fun colorFromFill(el: Element): Color? {
        el.child("a:srgbClr")?.let { c ->
            return hexColor(c.getAttribute("val"))?.let { applyMods(it, c) }
        }
        el.child("a:schemeClr")?.let { c ->
            return scheme(c.getAttribute("val"))?.let { applyMods(it, c) }
        }
        return null
    }

    private fun applyMods(color: Color, el: Element): Color {
        val lumMod = el.child("a:lumMod")?.getAttribute("val")?.toFloatOrNull()?.div(100000f)
        val lumOff = el.child("a:lumOff")?.getAttribute("val")?.toFloatOrNull()?.div(100000f)
        val alpha = el.child("a:alpha")?.getAttribute("val")?.toFloatOrNull()?.div(100000f)
        var out = color
        if (lumMod != null || lumOff != null) {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(out.toArgb(), hsl)
            hsl[2] = (hsl[2] * (lumMod ?: 1f) + (lumOff ?: 0f)).coerceIn(0f, 1f)
            out = Color(ColorUtils.HSLToColor(hsl))
        }
        if (alpha != null) out = out.copy(alpha = alpha.coerceIn(0f, 1f))
        return out
    }

    private fun fillOf(spPr: Element?, style: Element?): Color? {
        if (spPr?.child("a:noFill") != null) return null
        spPr?.child("a:solidFill")?.let { return colorFromFill(it) }
        spPr?.child("a:gradFill")?.byTag("a:gs")?.firstOrNull()?.let { return colorFromFill(it) }
        val ref = style?.child("a:fillRef")
        if (ref != null && ref.getAttribute("idx") != "0") return colorFromFill(ref)
        return null
    }

    private fun lineOf(spPr: Element?, style: Element?): Pair<Color?, Float> {
        val ln = spPr?.child("a:ln")
        if (ln?.child("a:noFill") != null) return null to 0f
        val lw = (ln?.attrOrNull("w")?.toFloatOrNull() ?: 12700f) / 12700f
        ln?.child("a:solidFill")?.let { return colorFromFill(it) to lw }
        if (ln == null) {
            val ref = style?.child("a:lnRef")
            if (ref != null && ref.getAttribute("idx") != "0") return colorFromFill(ref) to 1f
        }
        return null to 0f
    }

    fun bgOf(cSld: Element?): Color? =
        cSld?.child("p:bg")?.child("p:bgPr")?.child("a:solidFill")?.let { colorFromFill(it) }

    fun walk(
        parent: Element,
        xf: Xf,
        items: MutableList<SlideItem>,
        rels: Map<String, PptRel>,
        skipPh: Boolean
    ) {
        for (i in 0 until parent.childNodes.length) {
            val el = parent.childNodes.item(i) as? Element ?: continue
            when (el.tagName) {
                "p:grpSp" -> walk(el, groupXf(el, xf), items, rels, skipPh)
                "p:sp" -> if (!(skipPh && phOf(el) != null)) addShape(el, xf, items)
                "p:pic" -> if (!(skipPh && picPh(el))) addPicture(el, xf, items, rels)
                "p:graphicFrame" -> if (!skipPh) {
                    addDiagram(el, xf, items, rels)
                    addTable(el, xf, items)
                }
                "mc:AlternateContent" -> el.child("mc:Choice")?.let { walk(it, xf, items, rels, skipPh) }
            }
        }
    }

    private fun groupXf(grp: Element, xf: Xf): Xf {
        val xfrm = grp.child("p:grpSpPr")?.child("a:xfrm") ?: return xf
        val own = rectOf(xfrm, "a:off", "a:ext") ?: return xf
        val ch = rectOf(xfrm, "a:chOff", "a:chExt") ?: return xf
        if (ch.w == 0.0 || ch.h == 0.0) return xf
        val kx = own.w / ch.w
        val ky = own.h / ch.h
        return Xf(
            xf.sx * kx,
            xf.sy * ky,
            xf.sx * (own.x - ch.x * kx) + xf.ox,
            xf.sy * (own.y - ch.y * ky) + xf.oy
        )
    }

    private fun phOf(sp: Element): Element? =
        sp.child("p:nvSpPr")?.child("p:nvPr")?.child("p:ph")

    private fun picPh(pic: Element): Boolean =
        pic.child("p:nvPicPr")?.child("p:nvPr")?.child("p:ph") != null

    private fun lookupPh(map: Map<String, Element>, ph: Element): Element? {
        val type = ph.getAttribute("type").ifEmpty { "obj" }
        val idx = ph.getAttribute("idx").ifEmpty { "0" }
        return map["$type|$idx"] ?: map["idx:$idx"] ?: map["type:$type"]
    }

    private fun shapeFrame(sp: Element, sources: List<Element>): Rect? {
        rectOf(sp.child("p:spPr")?.child("a:xfrm"), "a:off", "a:ext")?.let { return it }
        for (s in sources) {
            rectOf(s.child("p:spPr")?.child("a:xfrm"), "a:off", "a:ext")?.let { return it }
        }
        return null
    }

    private fun styleSourcesFor(ph: Element?): List<Element> {
        val styles = masterRoot?.child("p:txStyles")
        val type = ph?.getAttribute("type").orEmpty()
        val tag = when {
            ph == null -> "p:otherStyle"
            type == "title" || type == "ctrTitle" -> "p:titleStyle"
            type == "body" || type == "obj" || type == "subTitle" || type.isEmpty() -> "p:bodyStyle"
            else -> "p:otherStyle"
        }
        return listOfNotNull(styles?.child(tag), if (ph == null) presDefault else null)
    }

    private fun addShape(sp: Element, xf: Xf, items: MutableList<SlideItem>) {
        val ph = phOf(sp)
        val layoutSp = ph?.let { lookupPh(layoutPh, it) }
        val masterSp = ph?.let { lookupPh(masterPh, it) }
        val rect = shapeFrame(sp, listOfNotNull(layoutSp, masterSp)) ?: return
        val sources = listOfNotNull(
            sp.child("p:txBody")?.child("a:lstStyle"),
            layoutSp?.child("p:txBody")?.child("a:lstStyle")
        ) + styleSourcesFor(ph)
        buildBox(
            xf.apply(rect),
            sp.child("p:spPr"),
            sp.child("p:style"),
            sp.child("p:txBody"),
            sources,
            items
        )
    }

    private fun buildBox(
        r: Rect,
        spPr: Element?,
        style: Element?,
        txBody: Element?,
        sources: List<Element>,
        items: MutableList<SlideItem>
    ) {
        val fill = fillOf(spPr, style)
        val (line, lineW) = lineOf(spPr, style)
        val bodyPr = txBody?.child("a:bodyPr")
        val fontScale = bodyPr?.child("a:normAutofit")?.attrOrNull("fontScale")
            ?.toFloatOrNull()?.div(100000f) ?: 1f
        val fontRef = style?.child("a:fontRef")?.let { colorFromFill(it) }
        val paragraphs = txBody?.let { parseParagraphs(it, sources, fontScale, fontRef) }.orEmpty()
        val hasText = paragraphs.any { p -> p.runs.any { it.text.isNotBlank() } }
        val cust = spPr?.child("a:custGeom")
        val local = rectOf(spPr?.child("a:xfrm"), "a:off", "a:ext")
        val path = cust?.let { parseCustGeom(it, local?.w ?: r.w, local?.h ?: r.h) }
            ?.takeIf { it.isNotEmpty() }
        if (fill == null && line == null && !hasText) return
        items.add(
            SlideTextBox(
                x = (r.x / slideW).toFloat(),
                y = (r.y / slideH).toFloat(),
                w = (r.w / slideW).toFloat(),
                h = (r.h / slideH).toFloat(),
                fill = fill,
                line = line,
                lineWpt = lineW,
                anchor = bodyPr?.attrOrNull("anchor") ?: "t",
                insetLr = insetPt(bodyPr, "lIns", 91440f),
                insetTb = insetPt(bodyPr, "tIns", 45720f),
                paragraphs = paragraphs,
                shape = if (path != null) "custom" else geometryOf(spPr),
                path = path,
                noWrap = bodyPr?.attrOrNull("wrap") == "none"
            )
        )
    }

    private fun addPicture(pic: Element, xf: Xf, items: MutableList<SlideItem>, rels: Map<String, PptRel>) {
        val rect = rectOf(pic.child("p:spPr")?.child("a:xfrm"), "a:off", "a:ext") ?: return
        val r = xf.apply(rect)
        val blipFill = pic.child("p:blipFill") ?: return
        val embed = blipFill.child("a:blip")?.getAttribute("r:embed").orEmpty()
        val image = imageFor(rels, embed) ?: return
        val crop = blipFill.child("a:srcRect")
        val (pLine, pLineW) = lineOf(pic.child("p:spPr"), pic.child("p:style"))
        items.add(
            SlidePicture(
                x = (r.x / slideW).toFloat(),
                y = (r.y / slideH).toFloat(),
                w = (r.w / slideW).toFloat(),
                h = (r.h / slideH).toFloat(),
                cropL = cropOf(crop, "l"),
                cropT = cropOf(crop, "t"),
                cropR = cropOf(crop, "r"),
                cropB = cropOf(crop, "b"),
                image = image,
                line = pLine,
                lineWpt = pLineW
            )
        )
    }

    private fun addDiagram(gf: Element, xf: Xf, items: MutableList<SlideItem>, rels: Map<String, PptRel>) {
        val data = gf.byTag("a:graphicData").firstOrNull() ?: return
        if (!data.getAttribute("uri").contains("diagram")) return
        val relIds = data.byTag("dgm:relIds").firstOrNull() ?: return
        val dmPath = rels[relIds.getAttribute("r:dm")]?.path ?: return
        val dm = loadXml(zip, dmPath) ?: return
        val drawRid = dm.byTag("dsp:dataModelExt").firstOrNull()?.getAttribute("relId").orEmpty()
        val drawPath = rels[drawRid]?.path ?: return
        val drawing = loadXml(zip, drawPath) ?: return
        val frame = rectOf(gf.child("p:xfrm"), "a:off", "a:ext") ?: return
        val inner = Xf(xf.sx, xf.sy, xf.sx * frame.x + xf.ox, xf.sy * frame.y + xf.oy)
        val tree = drawing.child("dsp:spTree") ?: return
        for (sp in tree.children("dsp:sp")) {
            val rect = rectOf(sp.child("dsp:spPr")?.child("a:xfrm"), "a:off", "a:ext") ?: continue
            buildBox(
                inner.apply(rect),
                sp.child("dsp:spPr"),
                sp.child("dsp:style"),
                sp.child("dsp:txBody"),
                emptyList(),
                items
            )
        }
    }

    private fun addTable(gf: Element, xf: Xf, items: MutableList<SlideItem>) {
        val data = gf.byTag("a:graphicData").firstOrNull() ?: return
        if (!data.getAttribute("uri").endsWith("/table")) return
        val tbl = data.child("a:tbl") ?: return
        val frame = rectOf(gf.child("p:xfrm"), "a:off", "a:ext") ?: return
        val cols = tbl.child("a:tblGrid")?.children("a:gridCol")
            ?.map { it.attrOrNull("w")?.toDoubleOrNull() ?: 0.0 }
            ?: return
        var y = frame.y
        for (tr in tbl.children("a:tr")) {
            val rowH = tr.attrOrNull("h")?.toDoubleOrNull() ?: 0.0
            var x = frame.x
            tr.children("a:tc").forEachIndexed { c, tc ->
                val w = cols.getOrElse(c) { 0.0 }
                val merged = tc.attrOrNull("hMerge") == "1" || tc.attrOrNull("vMerge") == "1"
                if (!merged) {
                    buildBox(
                        xf.apply(Rect(x, y, w, rowH)),
                        tc.child("a:tcPr"),
                        null,
                        tc.child("a:txBody"),
                        emptyList(),
                        items
                    )
                }
                x += w
            }
            y += rowH
        }
    }

    private fun parseParagraphs(
        txBody: Element,
        sources: List<Element>,
        fontScale: Float,
        fontRef: Color?
    ): List<SlideParagraph> =
        txBody.children("a:p").map { p ->
            val pPr = p.child("a:pPr")
            val lvl = pPr?.attrOrNull("lvl")?.toIntOrNull() ?: 0
            val algn = pPr?.attrOrNull("algn") ?: firstLvlAttr(sources, lvl, "algn")
            val marL = pPr?.attrOrNull("marL")?.toFloatOrNull()
                ?: firstLvlAttr(sources, lvl, "marL")?.toFloatOrNull()
                ?: 0f

            val runs = ArrayList<SlideRun>()
            for (i in 0 until p.childNodes.length) {
                val node = p.childNodes.item(i) as? Element ?: continue
                when (node.tagName) {
                    "a:r", "a:fld" -> runs.add(
                        makeRun(node, node.child("a:t")?.textContent.orEmpty(), sources, lvl, fontScale, fontRef)
                    )
                    "a:br" -> runs.add(makeRun(node, "\n", sources, lvl, fontScale, fontRef))
                }
            }
            if (runs.isEmpty()) runs.add(makeRun(null, "", sources, lvl, fontScale, fontRef))

            val hasText = runs.any { it.text.isNotBlank() }
            val bullet = if (hasText) bulletOf(pPr, sources, lvl) else null
            SlideParagraph(runs, alignOf(algn), bullet, marL / 12700f, bulletColorOf(pPr, sources, lvl))
        }

    private fun makeRun(
        el: Element?,
        text: String,
        sources: List<Element>,
        lvl: Int,
        fontScale: Float,
        fontRef: Color?
    ): SlideRun {
        val rPr = el?.child("a:rPr")
        val sz = rPr?.attrOrNull("sz")?.toFloatOrNull()
            ?: defRPrAttr(sources, lvl, "sz")?.toFloatOrNull()
            ?: 1800f
        val bold = (rPr?.attrOrNull("b") ?: defRPrAttr(sources, lvl, "b")) == "1"
        val italic = (rPr?.attrOrNull("i") ?: defRPrAttr(sources, lvl, "i")) == "1"
        val color = rPr?.child("a:solidFill")?.let { colorFromFill(it) }
            ?: defRPrColor(sources, lvl)
            ?: fontRef
            ?: scheme("tx1")
            ?: Color.Black
        return SlideRun(text, sz / 100f * fontScale, bold, italic, color)
    }

    private fun defRPrAttr(sources: List<Element>, lvl: Int, attr: String): String? {
        for (s in sources) {
            val d = lvlPPr(s, lvl)?.child("a:defRPr") ?: continue
            d.attrOrNull(attr)?.let { return it }
        }
        return null
    }

    private fun defRPrColor(sources: List<Element>, lvl: Int): Color? {
        for (s in sources) {
            val fill = lvlPPr(s, lvl)?.child("a:defRPr")?.child("a:solidFill") ?: continue
            colorFromFill(fill)?.let { return it }
        }
        return null
    }

    private fun bulletOf(pPr: Element?, sources: List<Element>, lvl: Int): String? {
        if (pPr != null) {
            if (pPr.child("a:buNone") != null) return null
            pPr.child("a:buChar")?.let { return bulletText(it.getAttribute("char"), pPr.child("a:buFont")) }
            if (pPr.child("a:buAutoNum") != null) return "•"
        }
        for (s in sources) {
            val lp = lvlPPr(s, lvl) ?: continue
            if (lp.child("a:buNone") != null) return null
            lp.child("a:buChar")?.let { return bulletText(it.getAttribute("char"), lp.child("a:buFont")) }
        }
        return null
    }

    private fun bulletColorOf(pPr: Element?, sources: List<Element>, lvl: Int): Color? {
        pPr?.child("a:buClr")?.let { return colorFromFill(it) }
        for (s in sources) {
            lvlPPr(s, lvl)?.child("a:buClr")?.let { return colorFromFill(it) }
        }
        return null
    }

    private fun loadXmlPart(path: String): Element? = loadXml(zip, path)

    fun loadRelsFor(path: String): Map<String, PptRel> = loadRels(zip, path)
}

private fun loadXml(zip: ZipFile, path: String): Element? {
    val entry = zip.getEntry(path) ?: return null
    return zip.getInputStream(entry).use {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
    }
}

private fun loadRels(zip: ZipFile, partPath: String): Map<String, PptRel> {
    val dir = partPath.substringBeforeLast('/')
    val name = partPath.substringAfterLast('/')
    val root = loadXml(zip, "$dir/_rels/$name.rels") ?: return emptyMap()
    return root.children("Relationship").associate { r ->
        r.getAttribute("Id") to PptRel(
            type = r.getAttribute("Type"),
            path = resolvePath(dir, r.getAttribute("Target"))
        )
    }
}

private fun resolvePath(dir: String, target: String): String {
    val parts = dir.split('/').filter { it.isNotEmpty() }.toMutableList()
    for (seg in target.split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(seg)
        }
    }
    return parts.joinToString("/")
}

private fun indexPh(root: Element?): Map<String, Element> {
    val out = HashMap<String, Element>()
    val tree = root?.child("p:cSld")?.child("p:spTree") ?: return out
    for (sp in tree.children("p:sp")) {
        val ph = sp.child("p:nvSpPr")?.child("p:nvPr")?.child("p:ph") ?: continue
        val type = ph.getAttribute("type").ifEmpty { "obj" }
        val idx = ph.getAttribute("idx").ifEmpty { "0" }
        out["$type|$idx"] = sp
        out.putIfAbsent("idx:$idx", sp)
        out.putIfAbsent("type:$type", sp)
    }
    return out
}

private fun parseTheme(root: Element?): Map<String, Color> {
    val out = HashMap<String, Color>()
    val scheme = root?.byTag("a:clrScheme")?.firstOrNull() ?: return out
    for (el in scheme.elementChildren()) {
        val name = el.tagName.removePrefix("a:")
        val c = el.child("a:srgbClr")?.let { hexColor(it.getAttribute("val")) }
            ?: el.child("a:sysClr")?.let { hexColor(it.getAttribute("lastClr")) }
        if (c != null) out[name] = c
    }
    return out
}

private fun clrMapOf(master: Element?): Map<String, String> {
    val cm = master?.child("p:clrMap") ?: return emptyMap()
    return listOf("bg1", "tx1", "bg2", "tx2").mapNotNull { k ->
        cm.attrOrNull(k)?.let { k to it }
    }.toMap()
}

private fun rectOf(xfrm: Element?, posTag: String, sizeTag: String): Rect? {
    if (xfrm == null) return null
    val p = xfrm.child(posTag) ?: return null
    val s = xfrm.child(sizeTag) ?: return null
    val x = p.getAttribute("x").toDoubleOrNull() ?: return null
    val y = p.getAttribute("y").toDoubleOrNull() ?: return null
    val w = s.getAttribute("cx").toDoubleOrNull() ?: return null
    val h = s.getAttribute("cy").toDoubleOrNull() ?: return null
    return Rect(x, y, w, h)
}

private fun lvlPPr(container: Element, lvl: Int): Element? =
    container.child("a:lvl${lvl + 1}pPr")

private fun firstLvlAttr(sources: List<Element>, lvl: Int, attr: String): String? {
    for (s in sources) {
        lvlPPr(s, lvl)?.attrOrNull(attr)?.let { return it }
    }
    return null
}

private fun alignOf(v: String?): TextAlign = when (v) {
    "ctr" -> TextAlign.Center
    "r" -> TextAlign.End
    "just" -> TextAlign.Justify
    else -> TextAlign.Start
}

private fun bulletText(c: String, font: Element?): String {
    val ch = c.firstOrNull() ?: return "•"
    val face = font?.getAttribute("typeface").orEmpty()
    if (face.contains("Wingdings", ignoreCase = true) || face.contains("Symbol", ignoreCase = true)) {
        return when (ch.code) {
            0xD8, 0xF0 -> "➢"
            0xA7 -> "▪"
            0x6E -> "■"
            0x6C -> "●"
            0xFC -> "✓"
            0x76 -> "❖"
            else -> "•"
        }
    }
    return if (ch.code in 0x20..0x2BFF) ch.toString() else "•"
}

private fun insetPt(bodyPr: Element?, attr: String, default: Float): Float =
    (bodyPr?.attrOrNull(attr)?.toFloatOrNull() ?: default) / 12700f

private fun cropOf(el: Element?, attr: String): Float =
    (el?.attrOrNull(attr)?.toFloatOrNull() ?: 0f) / 100000f

private fun hexColor(v: String): Color? = v.toLongOrNull(16)?.let { Color(0xFF000000L or it) }

private fun decodeScaled(bytes: ByteArray, maxSide: Int = 1600): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )
}

private fun Element.attrOrNull(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

private fun Element.elementChildren(): List<Element> {
    val out = ArrayList<Element>()
    for (i in 0 until childNodes.length) {
        val n = childNodes.item(i)
        if (n is Element) out.add(n)
    }
    return out
}

private fun Element.children(tag: String): List<Element> =
    elementChildren().filter { it.tagName == tag }

private fun Element.child(tag: String): Element? =
    elementChildren().firstOrNull { it.tagName == tag }

private fun Element.byTag(tag: String): List<Element> {
    val nl = getElementsByTagName(tag)
    return List(nl.length) { nl.item(it) as Element }
}

@Composable
fun SlideView(slides: List<SlideData>) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val current = listState.firstVisibleItemIndex.coerceIn(0, slides.lastIndex)

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(slides) { _, slide -> SlideCard(slide) }
            }
            ScrollIndicator { listState.scrollMetrics() }
        }
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
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous slide")
                }
                Text(
                    text = "Slide ${current + 1} of ${slides.size}",
                    style = MaterialTheme.typography.bodyMedium
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem((current + 1).coerceAtMost(slides.lastIndex))
                        }
                    },
                    enabled = current < slides.lastIndex
                ) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next slide")
                }
            }
        }
    }
}

@Composable
private fun SlideCard(slide: SlideData) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val wDp = maxWidth
        val hDp = wDp / slide.aspect
        val scale = wDp.value / slide.widthPt
        Box(
            modifier = Modifier
                .width(wDp)
                .height(hDp)
                .background(slide.background)
                .clipToBounds()
        ) {
            slide.bgImage?.let { bg ->
                Image(
                    bitmap = bg,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }
            slide.items.forEach { item ->
                when (item) {
                    is SlidePicture -> PictureItem(item, wDp, hDp, scale)
                    is SlideTextBox -> if (!isOutlineOnly(item)) TextItem(item, wDp, hDp, scale, fontScale)
                }
            }
            slide.items.forEach { item ->
                if (item is SlideTextBox && isOutlineOnly(item)) OutlineItem(item, wDp, hDp, scale)
            }
        }
    }
}

@Composable
private fun PictureItem(p: SlidePicture, wDp: Dp, hDp: Dp, scale: Float) {
    val boxW = wDp * p.w
    val boxH = hDp * p.h
    val fullW = boxW / (1f - p.cropL - p.cropR).coerceAtLeast(0.01f)
    val fullH = boxH / (1f - p.cropT - p.cropB).coerceAtLeast(0.01f)
    val outlined = if (p.line != null && p.lineWpt > 0f) {
        Modifier.border((p.lineWpt * scale).dp, p.line)
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .offset(x = wDp * p.x, y = hDp * p.y)
            .size(width = boxW, height = boxH)
            .then(outlined)
            .clipToBounds()
    ) {
        Image(
            bitmap = p.image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset(x = -fullW * p.cropL, y = -fullH * p.cropT)
                .size(width = fullW, height = fullH)
        )
    }
}

@Composable
private fun TextItem(b: SlideTextBox, wDp: Dp, hDp: Dp, scale: Float, fontScale: Float) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val shape = shapeFor(b.shape)
    val path = b.path
    val base = Modifier
        .offset(x = wDp * b.x, y = hDp * b.y)
        .size(width = wDp * b.w, height = hDp * b.h)
    val withFill = if (path == null && b.fill != null) base.background(b.fill, shape) else base
    val debugged = if (DEBUG_BOXES) withFill.border(1.dp, Color.Red) else withFill
    val withLine = if (path == null && b.line != null && b.lineWpt > 0f) {
        debugged.border((b.lineWpt * scale).dp, b.line, shape)
    } else {
        debugged
    }

    val innerWpx = with(density) { (wDp * b.w - (b.insetLr * scale * 2).dp).toPx() }.toInt()
    val innerHpx = with(density) { (hDp * b.h - (b.insetTb * scale * 2).dp).toPx() }.toInt() - 2
    val fit = remember(b, innerWpx, innerHpx, scale, fontScale, density.density) {
        var k = 1f
        while (k > MIN_FIT && innerHpx > 0 &&
            paragraphsHeightPx(measurer, b, scale * k, fontScale, innerWpx, density.density) > innerHpx
        ) {
            k *= 0.97f
        }
        k
    }

    val contentHeight = if (b.anchor == "t") {
        Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
    } else {
        Modifier.fillMaxHeight()
    }
    Box(modifier = withLine) {
        if (path != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val p = buildPath(path, size.width, size.height)
                b.fill?.let { drawPath(p, it) }
                val ln = b.line
                if (ln != null && b.lineWpt > 0f) {
                    drawPath(p, ln, style = Stroke(width = b.lineWpt * scale * density.density))
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(contentHeight)
                .padding(
                    start = (b.insetLr * scale).dp,
                    end = (b.insetLr * scale).dp,
                    top = (b.insetTb * scale).dp,
                    bottom = (b.insetTb * scale).dp
                ),
            verticalArrangement = when (b.anchor) {
                "ctr" -> Arrangement.Center
                "b" -> Arrangement.Bottom
                else -> Arrangement.Top
            }
        ) {
            b.paragraphs.forEach { para ->
                val s = scale * fit
                val lineSp = (para.runs.maxOfOrNull { it.sizePt } ?: 18f) * s / fontScale
                Text(
                    text = paragraphText(para, s, fontScale),
                    textAlign = para.align,
                    softWrap = !b.noWrap,
                    style = TextStyle(
                        lineHeight = (lineSp * 1.2f).sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (para.indentPt * s).dp)
                )
            }
        }
    }
}

private fun paragraphText(p: SlideParagraph, scale: Float, fontScale: Float): AnnotatedString =
    buildAnnotatedString {
        val firstSize = (p.runs.firstOrNull()?.sizePt ?: 18f) * scale / fontScale
        p.bullet?.let {
            withStyle(
                SpanStyle(
                    fontSize = firstSize.sp,
                    color = p.bulletColor ?: p.runs.firstOrNull()?.color ?: Color.Black
                )
            ) {
                append("$it ")
            }
        }
        p.runs.forEach { r ->
            withStyle(
                SpanStyle(
                    fontSize = (r.sizePt * scale / fontScale).sp,
                    fontWeight = if (r.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (r.italic) FontStyle.Italic else FontStyle.Normal,
                    color = r.color
                )
            ) {
                append(r.text)
            }
        }
    }

private fun geometryOf(spPr: Element?): String =
    spPr?.child("a:prstGeom")?.getAttribute("prst")?.ifEmpty { "rect" } ?: "rect"

private fun parseCustGeom(cust: Element, shapeW: Double, shapeH: Double): List<PathOp> {
    val out = ArrayList<PathOp>()
    for (path in cust.child("a:pathLst")?.children("a:path").orEmpty()) {
        val pw = path.attrOrNull("w")?.toDoubleOrNull()?.takeIf { it > 0 } ?: shapeW
        val ph = path.attrOrNull("h")?.toDoubleOrNull()?.takeIf { it > 0 } ?: shapeH
        if (pw <= 0 || ph <= 0) continue
        var cx = 0.0
        var cy = 0.0
        fun nx(v: Double) = (v / pw).toFloat()
        fun ny(v: Double) = (v / ph).toFloat()
        for (cmd in path.elementChildren()) {
            val pts = cmd.children("a:pt").map { ptPair(it) }
            when (cmd.tagName) {
                "a:moveTo" -> if (pts.isNotEmpty()) {
                    val (x, y) = pts[0]
                    cx = x
                    cy = y
                    out.add(MoveOp(nx(x), ny(y)))
                }
                "a:lnTo" -> if (pts.isNotEmpty()) {
                    val (x, y) = pts[0]
                    cx = x
                    cy = y
                    out.add(LineOp(nx(x), ny(y)))
                }
                "a:cubicBezTo" -> if (pts.size == 3) {
                    val (x3, y3) = pts[2]
                    cx = x3
                    cy = y3
                    out.add(
                        CubicOp(
                            nx(pts[0].first), ny(pts[0].second),
                            nx(pts[1].first), ny(pts[1].second),
                            nx(x3), ny(y3)
                        )
                    )
                }
                "a:quadBezTo" -> if (pts.size == 2) {
                    val (x2, y2) = pts[1]
                    cx = x2
                    cy = y2
                    out.add(
                        QuadOp(nx(pts[0].first), ny(pts[0].second), nx(x2), ny(y2))
                    )
                }
                "a:arcTo" -> {
                    val wR = cmd.attrOrNull("wR")?.toDoubleOrNull() ?: 0.0
                    val hR = cmd.attrOrNull("hR")?.toDoubleOrNull() ?: 0.0
                    val st = (cmd.attrOrNull("stAng")?.toDoubleOrNull() ?: 0.0) / 60000.0
                    val sw = (cmd.attrOrNull("swAng")?.toDoubleOrNull() ?: 0.0) / 60000.0
                    if (wR > 0 && hR > 0) {
                        val s = Math.toRadians(st)
                        val e = Math.toRadians(st + sw)
                        val ccx = cx - wR * cos(s)
                        val ccy = cy - hR * sin(s)
                        out.add(
                            ArcOp(
                                nx(ccx - wR), ny(ccy - hR),
                                nx(2 * wR), ny(2 * hR),
                                st.toFloat(), sw.toFloat()
                            )
                        )
                        cx = ccx + wR * cos(e)
                        cy = ccy + hR * sin(e)
                    }
                }
                "a:close" -> out.add(CloseOp)
            }
        }
    }
    return out
}

private fun ptPair(el: Element): Pair<Double, Double> =
    (el.getAttribute("x").toDoubleOrNull() ?: 0.0) to (el.getAttribute("y").toDoubleOrNull() ?: 0.0)

private fun buildPath(ops: List<PathOp>, w: Float, h: Float): androidx.compose.ui.graphics.Path {
    val p = androidx.compose.ui.graphics.Path()
    for (op in ops) {
        when (op) {
            is MoveOp -> p.moveTo(op.x * w, op.y * h)
            is LineOp -> p.lineTo(op.x * w, op.y * h)
            is CubicOp -> p.cubicTo(op.x1 * w, op.y1 * h, op.x2 * w, op.y2 * h, op.x3 * w, op.y3 * h)
            is QuadOp -> p.quadraticBezierTo(op.x1 * w, op.y1 * h, op.x2 * w, op.y2 * h)
            is ArcOp -> p.arcTo(
                androidx.compose.ui.geometry.Rect(
                    op.left * w,
                    op.top * h,
                    (op.left + op.width) * w,
                    (op.top + op.height) * h
                ),
                op.start,
                op.sweep,
                false
            )
            CloseOp -> p.close()
        }
    }
    return p
}

private fun shapeFor(prst: String): Shape = when (prst) {
    "ellipse" -> CircleShape
    "roundRect" -> RoundedCornerShape(percent = 20)
    "hexagon" -> HexagonShape
    else -> RectangleShape
}

private val HexagonShape: Shape = GenericShape { size, _ ->
    val dx = size.width * 0.25f
    val cy = size.height / 2f
    moveTo(0f, cy)
    lineTo(dx, 0f)
    lineTo(size.width - dx, 0f)
    lineTo(size.width, cy)
    lineTo(size.width - dx, size.height)
    lineTo(dx, size.height)
    close()
}

private fun isOutlineOnly(b: SlideTextBox): Boolean =
    b.fill == null && b.line != null && b.lineWpt > 0f && b.path == null &&
            b.paragraphs.all { p -> p.runs.all { it.text.isBlank() } }

@Composable
private fun OutlineItem(b: SlideTextBox, wDp: Dp, hDp: Dp, scale: Float) {
    val shape = shapeFor(b.shape)
    Box(
        modifier = Modifier
            .offset(x = wDp * b.x, y = hDp * b.y)
            .size(width = wDp * b.w, height = hDp * b.h)
            .border((b.lineWpt * scale).dp, b.line!!, shape)
    )
}

private fun paragraphsHeightPx(
    measurer: TextMeasurer,
    b: SlideTextBox,
    scale: Float,
    fontScale: Float,
    innerWpx: Int,
    densityPx: Float
): Int = b.paragraphs.sumOf { para ->
    val lineSp = (para.runs.maxOfOrNull { it.sizePt } ?: 18f) * scale / fontScale
    val indentPx = (para.indentPt * scale * densityPx).toInt()
    measurer.measure(
        text = paragraphText(para, scale, fontScale),
        style = TextStyle(
            lineHeight = (lineSp * 1.2f).sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        ),
        softWrap = !b.noWrap,
        constraints = Constraints(
            maxWidth = if (b.noWrap) Constraints.Infinity else (innerWpx - indentPx).coerceAtLeast(1)
        )
    ).size.height
}