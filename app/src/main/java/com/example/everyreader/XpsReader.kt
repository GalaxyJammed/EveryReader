package com.example.everyreader

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.sqrt
import org.w3c.dom.Element

private data class XMat(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    fun compose(child: XMat): XMat = XMat(
        a = a * child.a + c * child.b,
        b = b * child.a + d * child.b,
        c = a * child.c + c * child.d,
        d = b * child.c + d * child.d,
        e = a * child.e + c * child.f + e,
        f = b * child.e + d * child.f + f
    )

    fun pt(x: Float, y: Float): Pair<Float, Float> = (a * x + c * y + e) to (b * x + d * y + f)

    fun scale(): Float = sqrt(abs(a * d - b * c))
}

private val IDENTITY = XMat(1f, 0f, 0f, 1f, 0f, 0f)

private class XpsPage(
    val zip: ZipFile,
    val pagePath: String,
    val w: Float,
    val h: Float
) {
    val items = ArrayList<SlideItem>()
    val images = HashMap<String, ImageBitmap?>()
}

fun readXps(file: File): List<SlideData> = ZipFile(file).use { zip ->
    xpsPageParts(zip).mapNotNull { parseXpsPage(zip, it) }
}

private fun xpsPageParts(zip: ZipFile): List<String> {
    val seq = xZipDom(zip, "FixedDocumentSequence.fdseq")
    val docs = seq?.xAll("DocumentReference")?.map { xpsResolve("", it.getAttribute("Source")) }
        ?: zip.entries().asSequence().map { it.name }.filter { it.endsWith(".fdoc") }.toList()
    return docs.flatMap { docPath ->
        val dir = docPath.substringBeforeLast('/', "")
        xZipDom(zip, docPath)?.xAll("PageContent")
            ?.map { xpsResolve(dir, it.getAttribute("Source")) }
            ?: emptyList()
    }
}

private fun xpsResolve(baseDir: String, src: String): String {
    val combined = when {
        src.startsWith("/") -> src.trimStart('/')
        baseDir.isEmpty() -> src
        else -> "$baseDir/$src"
    }
    val out = ArrayList<String>()
    for (seg in combined.split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            else -> out.add(seg)
        }
    }
    return out.joinToString("/")
}

private fun parseXpsPage(zip: ZipFile, path: String): SlideData? {
    val root = xZipDom(zip, path) ?: return null
    val w = root.getAttribute("Width").toFloatOrNull() ?: 816f
    val h = root.getAttribute("Height").toFloatOrNull() ?: 1056f
    val page = XpsPage(zip, path, w, h)
    walkXps(root, IDENTITY, page)
    return SlideData(
        widthPt = w * 0.75f,
        aspect = w / h,
        background = Color.White,
        bgImage = null,
        items = page.items
    )
}

private fun walkXps(parent: Element, m: XMat, page: XpsPage) {
    for (child in parent.xElements()) {
        when (child.tagName) {
            "Canvas" -> walkXps(child, m.compose(elementMatrix(child)), page)
            "Path" -> addXpsPath(child, m.compose(elementMatrix(child)), page)
            "Glyphs" -> addXpsGlyphs(child, m.compose(elementMatrix(child)), page)
        }
    }
}

private fun elementMatrix(el: Element): XMat {
    val left = el.getAttribute("Canvas.Left").toFloatOrNull() ?: 0f
    val top = el.getAttribute("Canvas.Top").toFloatOrNull() ?: 0f
    val position = XMat(1f, 0f, 0f, 1f, left, top)
    val attr = el.getAttribute("RenderTransform").takeIf { it.isNotEmpty() }
    val child = el.xChild("${el.tagName}.RenderTransform")
        ?.xElements()?.firstOrNull { it.tagName == "MatrixTransform" }
        ?.getAttribute("Matrix")?.takeIf { it.isNotEmpty() }
    val render = (attr ?: child)?.let { xpsMatrix(it) } ?: IDENTITY
    return position.compose(render)
}

private fun xpsMatrix(text: String): XMat? {
    val v = text.split(Regex("[ ,]+")).mapNotNull { it.toFloatOrNull() }
    return if (v.size == 6) XMat(v[0], v[1], v[2], v[3], v[4], v[5]) else null
}

private fun addXpsPath(el: Element, m: XMat, page: XpsPage) {
    val data = el.getAttribute("Data")
    if (data.isEmpty()) return
    val ops = parseXpsPath(data, m, page.w, page.h)
    if (ops.isEmpty()) return

    val fillBrush = el.xChild("Path.Fill") ?: el.xChild("Path.FillBrush")
    val fill = colorOf(el.getAttribute("Fill"))
        ?: fillBrush?.xAll("SolidColorBrush")?.firstOrNull()?.getAttribute("Color")?.let { colorOf(it) }
    val imageSrc = fillBrush?.xAll("ImageBrush")?.firstOrNull()?.getAttribute("ImageSource")
        ?.takeIf { it.isNotEmpty() }
    val stroke = colorOf(el.getAttribute("Stroke"))
    val strokePt = (el.getAttribute("StrokeThickness").toFloatOrNull() ?: 1f) * m.scale() * 0.75f

    if (fill != null || stroke != null) {
        page.items.add(
            SlideTextBox(
                x = 0f,
                y = 0f,
                w = 1f,
                h = 1f,
                fill = fill,
                line = stroke,
                lineWpt = if (stroke != null) strokePt else 0f,
                anchor = "t",
                insetLr = 0f,
                insetTb = 0f,
                paragraphs = emptyList(),
                shape = "custom",
                path = ops
            )
        )
    }

    if (imageSrc != null) {
        val img = xpsImage(page, imageSrc)
        if (img != null) {
            val b = boundsOf(ops)
            page.items.add(
                SlidePicture(
                    x = b[0],
                    y = b[1],
                    w = b[2] - b[0],
                    h = b[3] - b[1],
                    cropL = 0f,
                    cropT = 0f,
                    cropR = 0f,
                    cropB = 0f,
                    image = img
                )
            )
        }
    }
}

private fun addXpsGlyphs(el: Element, m: XMat, page: XpsPage) {
    val text = el.getAttribute("UnicodeString")
    if (text.isEmpty()) return
    val ox = el.getAttribute("OriginX").toFloatOrNull() ?: 0f
    val em = el.getAttribute("FontRenderingEmSize").toFloatOrNull() ?: 12f
    val oy = el.getAttribute("OriginY").toFloatOrNull() ?: em * 0.8f
    val (px, py) = m.pt(ox, oy)
    val emPx = em * m.scale()
    val color = colorOf(el.getAttribute("Fill")) ?: Color.Black
    val paragraph = SlideParagraph(
        runs = listOf(SlideRun(text, emPx * 0.75f, false, false, color)),
        align = TextAlign.Start,
        bullet = null,
        indentPt = 0f
    )
    page.items.add(
        SlideTextBox(
            x = px / page.w,
            y = (py - emPx) / page.h,
            w = text.length * emPx * 0.55f / page.w,
            h = emPx * 1.4f / page.h,
            fill = null,
            line = null,
            lineWpt = 0f,
            anchor = "t",
            insetLr = 0f,
            insetTb = 0f,
            paragraphs = listOf(paragraph),
            shape = "rect",
            path = null,
            noWrap = true
        )
    )
}

private fun xpsImage(page: XpsPage, src: String): ImageBitmap? {
    val path = if (src.startsWith("/")) {
        src.trimStart('/')
    } else {
        xpsResolve(page.pagePath.substringBeforeLast('/', ""), src)
    }
    return page.images.getOrPut(path) {
        page.zip.getEntry(path)?.let { entry ->
            xpsDecode(page.zip.getInputStream(entry).use { it.readBytes() })
        }
    }
}

private fun xpsDecode(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )?.asImageBitmap()
}

private fun colorOf(s: String?): Color? {
    val v = s?.takeIf { it.startsWith("#") }?.substring(1) ?: return null
    return when (v.length) {
        8 -> v.toLongOrNull(16)?.let { Color(it) }
        6 -> v.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
        else -> null
    }
}

private fun parseXpsPath(data: String, m: XMat, w: Float, h: Float): List<PathOp> {
    val tokens = Regex("[A-Za-z]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
        .findAll(data).map { it.value }.toList()
    val out = ArrayList<PathOp>()
    var i = 0
    var cmd = 'M'
    var cx = 0f
    var cy = 0f
    var sx = 0f
    var sy = 0f

    fun num(): Float? {
        if (i >= tokens.size || tokens[i][0].isLetter()) return null
        return tokens[i++].toFloatOrNull()
    }

    fun nums(n: Int): FloatArray? {
        val a = FloatArray(n)
        for (k in 0 until n) a[k] = num() ?: return null
        return a
    }

    fun norm(x: Float, y: Float): Pair<Float, Float> {
        val (tx, ty) = m.pt(x, y)
        return (tx / w) to (ty / h)
    }

    while (i < tokens.size) {
        val t = tokens[i]
        if (t[0].isLetter()) {
            i++
            cmd = t[0]
            when (cmd) {
                'Z', 'z' -> {
                    out.add(CloseOp)
                    cx = sx
                    cy = sy
                }
                'F', 'f' -> num()
            }
            continue
        }
        val rel = cmd.isLowerCase()
        val ox = if (rel) cx else 0f
        val oy = if (rel) cy else 0f
        when (cmd.uppercaseChar()) {
            'M' -> {
                val a = nums(2) ?: break
                cx = a[0] + ox
                cy = a[1] + oy
                sx = cx
                sy = cy
                val (px, py) = norm(cx, cy)
                out.add(MoveOp(px, py))
                cmd = if (rel) 'l' else 'L'
            }
            'L' -> {
                val a = nums(2) ?: break
                cx = a[0] + ox
                cy = a[1] + oy
                val (px, py) = norm(cx, cy)
                out.add(LineOp(px, py))
            }
            'H' -> {
                val a = nums(1) ?: break
                cx = a[0] + ox
                val (px, py) = norm(cx, cy)
                out.add(LineOp(px, py))
            }
            'V' -> {
                val a = nums(1) ?: break
                cy = a[0] + oy
                val (px, py) = norm(cx, cy)
                out.add(LineOp(px, py))
            }
            'C' -> {
                val a = nums(6) ?: break
                val p1 = norm(a[0] + ox, a[1] + oy)
                val p2 = norm(a[2] + ox, a[3] + oy)
                cx = a[4] + ox
                cy = a[5] + oy
                val p3 = norm(cx, cy)
                out.add(CubicOp(p1.first, p1.second, p2.first, p2.second, p3.first, p3.second))
            }
            'Q' -> {
                val a = nums(4) ?: break
                val p1 = norm(a[0] + ox, a[1] + oy)
                cx = a[2] + ox
                cy = a[3] + oy
                val p2 = norm(cx, cy)
                out.add(QuadOp(p1.first, p1.second, p2.first, p2.second))
            }
            'A' -> {
                val a = nums(7) ?: break
                cx = a[5] + ox
                cy = a[6] + oy
                val (px, py) = norm(cx, cy)
                out.add(LineOp(px, py))
            }
            else -> i++
        }
    }
    return out
}

private fun boundsOf(ops: List<PathOp>): FloatArray {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    fun add(x: Float, y: Float) {
        minX = minOf(minX, x)
        minY = minOf(minY, y)
        maxX = maxOf(maxX, x)
        maxY = maxOf(maxY, y)
    }
    ops.forEach { op ->
        when (op) {
            is MoveOp -> add(op.x, op.y)
            is LineOp -> add(op.x, op.y)
            is CubicOp -> {
                add(op.x1, op.y1)
                add(op.x2, op.y2)
                add(op.x3, op.y3)
            }
            is QuadOp -> {
                add(op.x1, op.y1)
                add(op.x2, op.y2)
            }
            else -> Unit
        }
    }
    return floatArrayOf(minX, minY, maxX, maxY)
}