package com.example.everyreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo

val pdfColors = listOf(
    Color.Black,
    Color(0xFFD32F2F),
    Color(0xFF1976D2),
    Color(0xFF2E7D32)
)

private const val HANDLE_R = 0.04f
private const val ERASE_R = 0.025f
private const val KNOB = 0.05f
private const val BASE_THICKNESS = 0.01f

private val EraserIcon: ImageVector = ImageVector.Builder(
    name = "Eraser",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).path(
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 2f,
    strokeLineJoin = StrokeJoin.Round
) {
    moveTo(15f, 4f)
    lineTo(20f, 9f)
    lineTo(11f, 18f)
    lineTo(6f, 13f)
    close()
    moveTo(10.5f, 8.5f)
    lineTo(15.5f, 13.5f)
    moveTo(7f, 21f)
    lineTo(20f, 21f)
}.build()

enum class PdfTool { SELECT, DRAW, ERASE, TEXT, SIGN }
enum class PdfMarkKind { STROKE, SIGNATURE, TEXT }

data class PdfMark(
    val id: Long,
    val page: Int,
    val kind: PdfMarkKind,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val strokes: List<List<Offset>>,
    val color: Color,
    val text: String = "",
    val thickness: Float = BASE_THICKNESS
)

data class Placement(
    val page: Int,
    val cx: Float,
    val cy: Float,
    val kind: PdfMarkKind,
    val aspect: Float
)

data class Hover(val page: Int, val pos: Offset)

class PdfEditState {
    val marks = mutableStateListOf<PdfMark>()
    var draft by mutableStateOf<PdfMark?>(null)
    var selectedId by mutableStateOf<Long?>(null)
    var tool by mutableStateOf(PdfTool.SELECT)
    var color by mutableStateOf(Color.Black)
    var placing by mutableStateOf<Placement?>(null)
    var brush by mutableStateOf(1f)
    var eraserSize by mutableStateOf(1f)
    var hover by mutableStateOf<Hover?>(null)
    var scale by mutableStateOf(1f)
    var rotation by mutableStateOf(0f)
    var panX by mutableStateOf(0f)
    var panY by mutableStateOf(0f)
    var sizeOpen by mutableStateOf(false)
    var zoomOpen by mutableStateOf(false)
    var undoCount by mutableStateOf(0)
    var redoCount by mutableStateOf(0)
    val shownHints = mutableSetOf<String>()
    private val undoStack = mutableListOf<List<PdfMark>>()
    private val redoStack = mutableListOf<List<PdfMark>>()
    private var nextId = 1L

    fun newId(): Long = nextId++

    fun indexOf(id: Long): Int = marks.indexOfFirst { it.id == id }

    fun selected(): PdfMark? = selectedId?.let { id -> marks.firstOrNull { it.id == id } }

    fun recordChange(before: List<PdfMark>) {
        if (marks.toList() != before) {
            undoStack += before
            redoStack.clear()
            syncCounts()
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack += marks.toList()
        restore(undoStack.removeAt(undoStack.lastIndex))
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack += marks.toList()
        restore(redoStack.removeAt(redoStack.lastIndex))
    }

    private fun restore(list: List<PdfMark>) {
        marks.clear()
        marks.addAll(list)
        selectedId = null
        syncCounts()
    }

    private fun syncCounts() {
        undoCount = undoStack.size
        redoCount = redoStack.size
    }

    fun addText(p: Placement, text: String) {
        val before = marks.toList()
        val h = 0.03f
        marks += PdfMark(
            id = newId(),
            page = p.page,
            kind = PdfMarkKind.TEXT,
            cx = p.cx,
            cy = p.cy,
            width = text.length * 0.55f * h / p.aspect,
            height = h,
            rotation = 0f,
            strokes = emptyList(),
            color = color,
            text = text
        )
        selectedId = marks.last().id
        placing = null
        recordChange(before)
    }

    fun addSignature(p: Placement, strokes: List<List<Offset>>) {
        val before = marks.toList()
        val w = 0.3f
        val h = w * p.aspect * 0.5f
        marks += PdfMark(
            id = newId(),
            page = p.page,
            kind = PdfMarkKind.SIGNATURE,
            cx = p.cx,
            cy = p.cy,
            width = w,
            height = h,
            rotation = 0f,
            strokes = strokes,
            color = Color.Black
        )
        selectedId = marks.last().id
        placing = null
        recordChange(before)
    }

    fun discard() {
        marks.clear()
        draft = null
        selectedId = null
        tool = PdfTool.SELECT
        placing = null
        hover = null
        sizeOpen = false
        zoomOpen = false
        shownHints.clear()
        undoStack.clear()
        redoStack.clear()
        syncCounts()
    }
}

fun PdfEditState.showHintOnce(context: Context, key: String, message: String) {
    if (shownHints.add(key)) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

fun PdfEditState.exitEditing() {
    draft = null
    selectedId = null
    hover = null
    placing = null
    sizeOpen = false
    zoomOpen = false
}

class PdfPages(file: File) : AutoCloseable {
    private val lock = Any()
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)

    val pageCount: Int
        get() = synchronized(lock) { renderer.pageCount }

    fun aspect(index: Int): Float = synchronized(lock) {
        renderer.openPage(index).use { page -> page.width.toFloat() / page.height }
    }

    fun render(index: Int, widthPx: Int): Bitmap = synchronized(lock) {
        renderer.openPage(index).use { page ->
            val heightPx = widthPx * page.height / page.width
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            renderer.close()
            descriptor.close()
        }
    }
}

fun uniqueCopy(source: File): File {
    val base = source.nameWithoutExtension + "_edited"
    var candidate = File(source.parentFile, "$base.pdf")
    var n = 2
    while (candidate.exists()) {
        candidate = File(source.parentFile, "${base}_$n.pdf")
        n++
    }
    return candidate
}

fun savePdfEdits(
    context: Context,
    source: File,
    marks: List<PdfMark>,
    overwrite: Boolean
): File {
    PDFBoxResourceLoader.init(context.applicationContext)
    val target = if (overwrite) File(source.parentFile, "${source.name}.tmp") else uniqueCopy(source)
    try {
        val doc = PDDocument.load(source)
        try {
            val font = context.assets.open("fonts/NotoSans-Regular.ttf").use {
                PDType0Font.load(doc, it)
            }
            marks.groupBy { it.page }.forEach { (pageIndex, pageMarks) ->
                val page = doc.getPage(pageIndex)
                val box = page.cropBox
                val rot = ((page.rotation % 360) + 360) % 360
                val (vw, vh) = viewSize(box, rot)
                val a = vw / vh

                PDPageContentStream(
                    doc,
                    page,
                    PDPageContentStream.AppendMode.PREPEND,
                    true
                ).use { it.saveGraphicsState() }

                PDPageContentStream(
                    doc,
                    page,
                    PDPageContentStream.AppendMode.APPEND,
                    true
                ).use { cs ->
                    cs.restoreGraphicsState()
                    pageMarks.forEach { m ->
                        when (m.kind) {
                            PdfMarkKind.TEXT -> writeText(cs, m, box, rot, font)
                            else -> writeInk(cs, m, box, rot, a)
                        }
                    }
                }
            }
            target.outputStream().use { doc.save(it) }
        } finally {
            doc.close()
        }
        if (overwrite) {
            if (!target.renameTo(source)) throw IOException("Could not replace original")
            return source
        }
        return target
    } catch (e: Throwable) {
        if (overwrite) target.delete()
        throw e
    }
}

private fun viewSize(box: PDRectangle, rot: Int): Pair<Float, Float> =
    if (rot == 90 || rot == 270) box.height to box.width else box.width to box.height

private fun toUser(p: Offset, box: PDRectangle, rot: Int): Pair<Double, Double> {
    val x = p.x.toDouble()
    val y = p.y.toDouble()
    val w = box.width.toDouble()
    val h = box.height.toDouble()
    val (ux, uy) = when (rot) {
        90 -> (y * w) to (x * h)
        180 -> ((1 - x) * w) to (y * h)
        270 -> ((1 - y) * w) to ((1 - x) * h)
        else -> (x * w) to ((1 - y) * h)
    }
    return (box.lowerLeftX.toDouble() + ux) to (box.lowerLeftY.toDouble() + uy)
}

private fun writeInk(cs: PDPageContentStream, m: PdfMark, box: PDRectangle, rot: Int, a: Float) {
    val (vw, _) = viewSize(box, rot)
    val c = m.color
    cs.setStrokingColor(c.red, c.green, c.blue)
    cs.setLineCapStyle(1)
    cs.setLineJoinStyle(1)
    cs.setLineWidth(m.thickness * vw)
    m.strokes.forEach { stroke ->
        if (stroke.isEmpty()) return@forEach
        val base = stroke.map { localToPage(m, it, a) }
        val pts = if (base.size == 1) listOf(base[0], Offset(base[0].x + 0.0005f, base[0].y)) else base
        pts.forEachIndexed { i, p ->
            val (x, y) = toUser(p, box, rot)
            if (i == 0) cs.moveTo(x.toFloat(), y.toFloat()) else cs.lineTo(x.toFloat(), y.toFloat())
        }
        cs.stroke()
    }
}

private fun writeText(cs: PDPageContentStream, m: PdfMark, box: PDRectangle, rot: Int, font: PDType0Font) {
    val (_, vh) = viewSize(box, rot)
    val fsF = m.height * vh
    val fs = fsF.toDouble()
    val tw = font.getStringWidth(m.text) / 1000.0 * fs
    val (cx, cy) = toUser(Offset(m.cx, m.cy), box, rot)
    val theta = -Math.toRadians((m.rotation - rot).toDouble())
    val ct = cos(theta)
    val st = sin(theta)
    val ox = -tw / 2.0
    val oy = -fs * 0.35
    val x = cx + ox * ct - oy * st
    val y = cy + ox * st + oy * ct
    val c = m.color
    cs.beginText()
    cs.setFont(font, fsF)
    cs.setNonStrokingColor(c.red, c.green, c.blue)
    cs.setTextMatrix(ct, st, -st, ct, x, y)
    cs.showText(m.text)
    cs.endText()
}

@Composable
fun PdfContent(file: File, editing: Boolean, edit: PdfEditState, reloadKey: Int) {
    val pages = remember(file, reloadKey) { runCatching { PdfPages(file) }.getOrNull() }
    DisposableEffect(pages) {
        onDispose { pages?.close() }
    }
    if (pages == null) {
        Text(
            text = "Could not open this PDF.",
            style = MaterialTheme.typography.bodyLarge
        )
        return
    }
    val widthPx = (LocalConfiguration.current.screenWidthDp * LocalDensity.current.density).toInt()
    val limitPx = LocalConfiguration.current.screenHeightDp * LocalDensity.current.density / 2f
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snapFling = rememberSnapFlingBehavior(lazyListState = listState)
    val defaultFling = ScrollableDefaults.flingBehavior()
    val flingBehavior = if (editing) defaultFling else snapFling
    val gestureModifier = if (editing) {
        Modifier
    } else {
        Modifier
            .pdfTransformGesture(edit)
            .pdfPanGesture(edit, listState, scope, limitPx)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .then(gestureModifier)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = edit.scale
                    scaleY = edit.scale
                    rotationZ = edit.rotation
                    translationX = edit.panX
                    translationY = edit.panY
                },
            verticalArrangement = Arrangement.spacedBy(12.dp),
            flingBehavior = flingBehavior,
            userScrollEnabled = !editing && edit.scale <= 1.001f
        ) {
            items(pages.pageCount) { index ->
                PdfPage(pages, index, widthPx, editing, edit)
            }
        }
        if (!editing && edit.scale <= 1.001f) {
            ScrollIndicator { listState.scrollMetrics() }
        }
    }
}

private fun Modifier.pdfTransformGesture(edit: PdfEditState): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    edit.scale = (edit.scale * event.calculateZoom()).coerceIn(1f, 5f)
                    edit.rotation += event.calculateRotation()
                    if (edit.scale <= 1.001f) {
                        edit.panX = 0f
                        edit.panY = 0f
                    } else {
                        val pan = event.calculatePan()
                        edit.panX += pan.x
                        edit.panY += pan.y
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }

private fun Modifier.pdfPanGesture(
    edit: PdfEditState,
    listState: LazyListState,
    scope: CoroutineScope,
    limitPx: Float
): Modifier =
    pointerInput(limitPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            var last = down.position
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || event.changes.count { it.pressed } > 1) break
                if (edit.scale > 1.001f) {
                    val d = change.position - last
                    edit.panX += d.x
                    edit.panY += d.y
                    if (abs(edit.panY) > limitPx) {
                        val step = edit.panY
                        edit.panY = 0f
                        scope.launch { listState.scrollBy(-step / edit.scale) }
                    }
                    change.consume()
                }
                last = change.position
            }
        }
    }

@Composable
private fun PdfPage(
    pages: PdfPages,
    index: Int,
    widthPx: Int,
    editing: Boolean,
    edit: PdfEditState
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, pages, index, widthPx) {
        value = withContext(Dispatchers.IO) {
            runCatching { pages.render(index, widthPx) }.getOrNull()
        }
    }
    val aspect = remember(pages, index) { pages.aspect(index) }
    val shown = bitmap
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(aspect)) {
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        PageMarks(index, aspect, edit, editing)
    }
}

private sealed interface Target {
    data object None : Target
    data class Body(val id: Long) : Target
    data class Resize(val id: Long) : Target
    data class Rotate(val id: Long) : Target
}

@Composable
private fun PageMarks(index: Int, aspect: Float, edit: PdfEditState, editing: Boolean) {
    val measurer = rememberTextMeasurer()
    val interact = if (editing) {
        Modifier.pointerInput(index, aspect) {
            val sizeOf = { size }
            awaitEachGesture {
                val before = edit.marks.toList()
                pageGesture(index, aspect, edit, sizeOf)
                edit.recordChange(before)
            }
        }
    } else {
        Modifier
    }
    Canvas(modifier = Modifier.fillMaxSize().then(interact)) {
        edit.marks.forEach { m ->
            if (m.page == index) drawMark(m, aspect, measurer, editing && m.id == edit.selectedId)
        }
        edit.draft?.takeIf { it.page == index }?.let { drawMark(it, aspect, measurer, false) }
        edit.hover?.takeIf { editing && it.page == index && edit.tool == PdfTool.ERASE }?.let { h ->
            val c = Offset(h.pos.x * size.width, h.pos.y * size.height)
            val r = ERASE_R * edit.eraserSize * size.height
            drawCircle(Color.Gray.copy(alpha = 0.25f), radius = r, center = c)
            drawCircle(Color.Gray, radius = r, center = c, style = Stroke(2.dp.toPx()))
        }
    }
}

private suspend fun AwaitPointerEventScope.pageGesture(
    index: Int,
    aspect: Float,
    edit: PdfEditState,
    sizeOf: () -> IntSize
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    val sz = sizeOf()
    fun norm(o: Offset) = Offset(o.x / sz.width, o.y / sz.height)
    val start = norm(down.position)
    val tool = edit.tool

    if (tool == PdfTool.DRAW) {
        edit.selectedId = null
        var pts = listOf(start)
        edit.draft = PdfMark(
            -1L, index, PdfMarkKind.STROKE, 0.5f, 0.5f, 1f, 1f, 0f,
            listOf(pts), edit.color, thickness = BASE_THICKNESS * edit.brush
        )
        dragLoop(down.id, { pos ->
            val p = norm(pos)
            if (p != pts.last()) {
                pts = pts + p
                edit.draft = edit.draft?.copy(strokes = listOf(pts))
            }
            true
        }) {
            edit.draft?.let { edit.marks.add(it.copy(id = edit.newId())) }
            edit.draft = null
        }
        return
    }

    if (tool == PdfTool.ERASE) {
        val r = ERASE_R * edit.eraserSize
        edit.hover = Hover(index, start)
        eraseAt(edit, index, aspect, start, r)
        dragLoop(down.id, { pos ->
            val p = norm(pos)
            edit.hover = Hover(index, p)
            eraseAt(edit, index, aspect, p, r)
            true
        }) {
            edit.hover = null
        }
        return
    }

    val selected = edit.selected()?.takeIf { it.page == index }
    val target: Target = when {
        selected != null && selected.kind != PdfMarkKind.STROKE &&
                physDist(resizeHandle(selected, aspect), start, aspect) < HANDLE_R ->
            Target.Resize(selected.id)
        selected != null && selected.kind != PdfMarkKind.STROKE &&
                physDist(rotateKnob(selected, aspect), start, aspect) < HANDLE_R ->
            Target.Rotate(selected.id)
        else -> edit.marks.lastOrNull { it.page == index && bodyHit(it, start, aspect) }
            ?.let { Target.Body(it.id) } ?: Target.None
    }

    val id: Long? = when (target) {
        is Target.Body -> target.id
        is Target.Resize -> target.id
        is Target.Rotate -> target.id
        Target.None -> null
    }
    if (id != null) edit.selectedId = id
    val base = id?.let { i -> edit.marks.firstOrNull { it.id == i } }
    val center = if (base != null) Offset(base.cx, base.cy) else Offset(0.5f, 0.5f)
    val startDist = physDist(start, center, aspect)
    val startAngle = angleDeg(start, center, aspect)
    var last = start
    var moved = 0f

    dragLoop(down.id, { pos ->
        val p = norm(pos)
        moved = maxOf(moved, physDist(p, start, aspect))
        if (base != null && id != null && target != Target.None) {
            val idx = edit.indexOf(id)
            if (idx >= 0) {
                val cur = edit.marks[idx]
                edit.marks[idx] = when (target) {
                    is Target.Body -> cur.copy(cx = cur.cx + p.x - last.x, cy = cur.cy + p.y - last.y)
                    is Target.Resize -> {
                        val s = if (startDist > 1e-4f) physDist(p, center, aspect) / startDist else 1f
                        cur.copy(width = base.width * s, height = base.height * s)
                    }
                    is Target.Rotate ->
                        cur.copy(rotation = base.rotation + (angleDeg(p, center, aspect) - startAngle))
                    Target.None -> cur
                }
            }
        }
        last = p
        target != Target.None && moved > 0.004f
    }) {
        if (target == Target.None && moved <= 0.01f) {
            val hasSelection = edit.selectedId != null
            when {
                tool == PdfTool.SELECT -> edit.selectedId = null
                hasSelection -> edit.selectedId = null
                tool == PdfTool.TEXT -> edit.placing = Placement(index, start.x, start.y, PdfMarkKind.TEXT, aspect)
                tool == PdfTool.SIGN -> edit.placing = Placement(index, start.x, start.y, PdfMarkKind.SIGNATURE, aspect)
                else -> Unit
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.dragLoop(
    pointerId: PointerId,
    onMove: (Offset) -> Boolean,
    onUp: () -> Unit
) {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId } ?: break
        if (!change.pressed) break
        if (onMove(change.position)) change.consume()
    }
    onUp()
}

private fun localToPage(m: PdfMark, p: Offset, a: Float): Offset {
    val dx = (p.x - 0.5f) * m.width * a
    val dy = (p.y - 0.5f) * m.height
    val r = Math.toRadians(m.rotation.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    val rx = dx * c - dy * s
    val ry = dx * s + dy * c
    return Offset((m.cx * a + rx) / a, m.cy + ry)
}

private fun pageToLocal(m: PdfMark, p: Offset, a: Float): Offset {
    val px = p.x * a - m.cx * a
    val py = p.y - m.cy
    val r = Math.toRadians(m.rotation.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    val dx = px * c + py * s
    val dy = -px * s + py * c
    return Offset(dx / (m.width * a) + 0.5f, dy / m.height + 0.5f)
}

private fun physDist(p: Offset, q: Offset, a: Float): Float {
    val dx = (p.x - q.x) * a
    val dy = p.y - q.y
    return sqrt(dx * dx + dy * dy)
}

private fun angleDeg(p: Offset, c: Offset, a: Float): Float =
    Math.toDegrees(atan2((p.y - c.y).toDouble(), ((p.x - c.x) * a).toDouble())).toFloat() + 90f

private fun resizeHandle(m: PdfMark, a: Float): Offset = localToPage(m, Offset(1f, 1f), a)

private fun rotateKnob(m: PdfMark, a: Float): Offset {
    val top = localToPage(m, Offset(0.5f, 0f), a)
    val r = Math.toRadians(m.rotation.toDouble())
    val s = sin(r).toFloat()
    val c = cos(r).toFloat()
    return Offset(top.x + KNOB * s / a, top.y - KNOB * c)
}

private fun bodyHit(m: PdfMark, p: Offset, a: Float): Boolean =
    if (m.kind == PdfMarkKind.STROKE) {
        m.strokes.any { stroke -> stroke.any { physDist(localToPage(m, it, a), p, a) < 0.02f } }
    } else {
        val l = pageToLocal(m, p, a)
        l.x in -0.05f..1.05f && l.y in -0.05f..1.05f
    }

private fun eraseAt(edit: PdfEditState, page: Int, a: Float, p: Offset, radius: Float) {
    var i = 0
    while (i < edit.marks.size) {
        val m = edit.marks[i]
        if (m.page != page || m.kind == PdfMarkKind.TEXT) {
            i++
            continue
        }
        val kept = m.strokes.flatMap { splitStroke(it, m, a, p, radius) }
        val before = m.strokes.sumOf { it.size }
        val after = kept.sumOf { it.size }
        when {
            after == before -> i++
            kept.isEmpty() -> edit.marks.removeAt(i)
            else -> {
                edit.marks[i] = m.copy(strokes = kept)
                i++
            }
        }
    }
}

private fun splitStroke(
    stroke: List<Offset>,
    m: PdfMark,
    a: Float,
    p: Offset,
    radius: Float
): List<List<Offset>> {
    val out = mutableListOf<List<Offset>>()
    var run = mutableListOf<Offset>()
    for (local in stroke) {
        if (physDist(localToPage(m, local, a), p, a) < radius) {
            if (run.size >= 2) out += run
            run = mutableListOf()
        } else {
            run += local
        }
    }
    if (run.size >= 2) out += run
    return out
}

private fun DrawScope.px(p: Offset): Offset = Offset(p.x * size.width, p.y * size.height)

private fun DrawScope.drawInk(points: List<Offset>, color: Color, widthPx: Float, highlight: Boolean) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        val p = points[0]
        if (highlight) drawCircle(Color.Gray.copy(alpha = 0.5f), radius = widthPx * 1.5f, center = p)
        drawCircle(color, radius = widthPx / 2f, center = p)
        return
    }
    val path = Path().apply {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    }
    if (highlight) {
        drawPath(
            path,
            color = Color.Gray.copy(alpha = 0.5f),
            style = Stroke(width = widthPx * 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
    drawPath(
        path,
        color = color,
        style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawMark(m: PdfMark, a: Float, measurer: TextMeasurer, selected: Boolean) {
    val w = m.thickness * size.width
    when (m.kind) {
        PdfMarkKind.STROKE -> m.strokes.forEach { s ->
            drawInk(s.map { px(localToPage(m, it, a)) }, m.color, w, selected)
        }
        PdfMarkKind.SIGNATURE -> {
            m.strokes.forEach { s ->
                drawInk(s.map { px(localToPage(m, it, a)) }, m.color, w, false)
            }
            if (selected) drawSelection(m, a)
        }
        PdfMarkKind.TEXT -> {
            drawTextMark(m, measurer)
            if (selected) drawSelection(m, a)
        }
    }
}

private fun DrawScope.drawTextMark(m: PdfMark, measurer: TextMeasurer) {
    val fontPx = m.height * size.height
    val layout = measurer.measure(m.text, TextStyle(fontSize = (fontPx / density).sp, color = m.color))
    val c = Offset(m.cx * size.width, m.cy * size.height)
    rotate(m.rotation, pivot = c) {
        drawText(
            layout,
            topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f)
        )
    }
}

private fun DrawScope.drawSelection(m: PdfMark, a: Float) {
    val corners = listOf(
        px(localToPage(m, Offset(0f, 0f), a)),
        px(localToPage(m, Offset(1f, 0f), a)),
        px(localToPage(m, Offset(1f, 1f), a)),
        px(localToPage(m, Offset(0f, 1f), a))
    )
    val box = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        lineTo(corners[1].x, corners[1].y)
        lineTo(corners[2].x, corners[2].y)
        lineTo(corners[3].x, corners[3].y)
        close()
    }
    drawPath(
        box,
        color = Color.Gray,
        style = Stroke(
            width = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
        )
    )
    val knob = px(rotateKnob(m, a))
    val topCenter = px(localToPage(m, Offset(0.5f, 0f), a))
    drawLine(Color.Gray, topCenter, knob, strokeWidth = 2.dp.toPx())
    drawCircle(Color.White, radius = 7.dp.toPx(), center = knob)
    drawCircle(Color.Gray, radius = 7.dp.toPx(), center = knob, style = Stroke(2.dp.toPx()))
    val res = corners[2]
    drawCircle(Color.White, radius = 7.dp.toPx(), center = res)
    drawCircle(Color.Gray, radius = 7.dp.toPx(), center = res, style = Stroke(2.dp.toPx()))
}

@Composable
fun PdfEditToolbar(edit: PdfEditState, onSave: () -> Unit) {
    val context = LocalContext.current
    val hasSelection = edit.selectedId != null
    val drawing = edit.tool == PdfTool.DRAW
    val erasing = edit.tool == PdfTool.ERASE
    ShieldCard {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (drawing || edit.tool == PdfTool.TEXT) {
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
                                    width = if (c == edit.color) 3.dp else 1.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                                .clickable { edit.color = c }
                        )
                    }
                    if (drawing) {
                        ToolButton(Icons.Default.Tune, "Brush size", edit.sizeOpen) {
                            edit.sizeOpen = !edit.sizeOpen
                        }
                    }
                }
            }
            if (erasing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolButton(Icons.Default.Tune, "Eraser size", edit.sizeOpen) {
                        edit.sizeOpen = !edit.sizeOpen
                    }
                }
            }
            if (edit.sizeOpen && (drawing || erasing)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (drawing) {
                        Text(
                            text = "Width ${"%.1f".format(edit.brush)}x",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = edit.brush,
                            onValueChange = { edit.brush = it },
                            valueRange = 1f..3f
                        )
                    } else {
                        Text(
                            text = "Eraser ${"%.1f".format(edit.eraserSize)}x",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = edit.eraserSize,
                            onValueChange = { edit.eraserSize = it },
                            valueRange = 0.5f..3f
                        )
                    }
                }
            }
            if (edit.zoomOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Zoom ${"%.1f".format(edit.scale)}x",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = edit.scale,
                        onValueChange = { v ->
                            edit.scale = v
                            if (v <= 1.001f) {
                                edit.panX = 0f
                                edit.panY = 0f
                            }
                        },
                        valueRange = 1f..5f
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolButton(Icons.Default.OpenWith, "Select", edit.tool == PdfTool.SELECT) {
                    edit.tool = PdfTool.SELECT
                    edit.showHintOnce(context, "select", "Press on elements to move them")
                }
                ToolButton(Icons.Default.Create, "Draw", edit.tool == PdfTool.DRAW) {
                    edit.tool = PdfTool.DRAW
                }
                ToolButton(EraserIcon, "Eraser", edit.tool == PdfTool.ERASE) {
                    edit.tool = PdfTool.ERASE
                }
                ToolButton(Icons.Default.TextFields, "Text", edit.tool == PdfTool.TEXT) {
                    edit.tool = PdfTool.TEXT
                }
                ToolButton(Icons.Default.Gesture, "Signature", edit.tool == PdfTool.SIGN) {
                    edit.tool = PdfTool.SIGN
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolButton(Icons.Default.Search, "Zoom", edit.zoomOpen) {
                    edit.zoomOpen = !edit.zoomOpen
                }
                IconButton(onClick = { edit.undo() }, enabled = edit.undoCount > 0) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo"
                    )
                }
                IconButton(onClick = {
                    val id = edit.selectedId
                    if (id != null) {
                        val before = edit.marks.toList()
                        edit.marks.removeAll { it.id == id }
                        edit.selectedId = null
                        edit.recordChange(before)
                    } else {
                        edit.showHintOnce(context, "delete", "Press on an element to remove it")
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete selected",
                        tint = if (hasSelection) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
                IconButton(onClick = { edit.redo() }, enabled = edit.redoCount > 0) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo"
                    )
                }
                IconButton(onClick = onSave, enabled = edit.marks.isNotEmpty()) {
                    Icon(imageVector = Icons.Default.Save, contentDescription = "Save")
                }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun TextInputDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add text") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SignaturePadDialog(onSave: (List<List<Offset>>) -> Unit, onDismiss: () -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signature") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f)
                        .background(Color.White)
                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            val sizeOf = { size }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val sz = sizeOf()
                                var pts = listOf(
                                    Offset(down.position.x / sz.width, down.position.y / sz.height)
                                )
                                current = pts
                                dragLoop(down.id, { pos ->
                                    pts = pts + Offset(pos.x / sz.width, pos.y / sz.height)
                                    current = pts
                                    true
                                }) {
                                    strokes.add(pts)
                                    current = emptyList()
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        (strokes.toList() + listOf(current)).forEach { s ->
                            drawInk(
                                s.map { Offset(it.x * size.width, it.y * size.height) },
                                Color.Black,
                                3.dp.toPx(),
                                false
                            )
                        }
                    }
                }
                TextButton(onClick = { strokes.clear() }) { Text("Clear") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(strokes.toList()) },
                enabled = strokes.isNotEmpty()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SaveChoiceDialog(onCopy: () -> Unit, onOverwrite: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save changes") },
        text = { Text("Save as a new copy, or overwrite the original?") },
        confirmButton = {
            TextButton(onClick = onCopy) { Text("Save as copy") }
        },
        dismissButton = {
            TextButton(onClick = onOverwrite) { Text("Overwrite") }
        }
    )
}