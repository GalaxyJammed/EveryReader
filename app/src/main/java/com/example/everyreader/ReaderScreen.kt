package com.example.everyreader

import android.media.MediaScannerConnection
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PageMatch(val page: Int, val range: IntRange)

class TextHistory {
    val undo = ArrayList<Map<Int, String>>()
    val redo = ArrayList<Map<Int, String>>()
    var lastEditAt = 0L
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    fun sync() {
        canUndo = undo.isNotEmpty()
        canRedo = redo.isNotEmpty()
    }

    fun clear() {
        undo.clear()
        redo.clear()
        lastEditAt = 0L
        sync()
    }
}

fun splitIntoChunks(text: String, wordsPerChunk: Int = 100, maxChars: Int = 2000): List<String> {
    val chunks = ArrayList<String>()
    var start = 0
    var chunkWords = 0
    var inWord = false
    for (i in text.indices) {
        val ws = text[i].isWhitespace()
        val wordStart = !ws && !inWord
        val tooManyWords = wordStart && chunkWords == wordsPerChunk
        val tooLong = i - start >= maxChars
        if ((tooManyWords || tooLong) && i > start) {
            chunks += text.substring(start, i)
            start = i
            chunkWords = 0
        }
        if (wordStart) chunkWords++
        inWord = !ws
    }
    chunks += text.substring(start)
    return chunks
}

fun findRanges(text: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<IntRange>()
    var i = text.indexOf(query, 0, ignoreCase = true)
    while (i >= 0) {
        out += i until i + query.length
        i = text.indexOf(query, i + query.length, ignoreCase = true)
    }
    return out
}

fun findAll(source: List<String>, overrides: Map<Int, String>, query: String): List<PageMatch> {
    val out = ArrayList<PageMatch>()
    source.forEachIndexed { page, original ->
        val text = overrides[page] ?: original
        findRanges(text, query).forEach { out += PageMatch(page, it) }
    }
    return out
}

class FindHighlight(
    private val ranges: List<IntRange>,
    private val current: Int
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            append(text)
            ranges.forEachIndexed { i, r ->
                if (r.last < text.length) {
                    addStyle(
                        SpanStyle(
                            color = Color.Black,
                            background = if (i == current) Color(0xFFFFA000) else Color(0xAAFFEB3B)
                        ),
                        r.first,
                        r.last + 1
                    )
                }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

@Composable
fun FindUndoRedoBar(
    findOpen: Boolean,
    onToggleFind: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    ShieldCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleFind) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Find in page",
                    tint = if (findOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
        }
    }
}

@Composable
fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    current: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    ShieldCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Find in file") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
            Text(
                text = "$current/$total",
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(onClick = onPrev, enabled = total > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match")
            }
            IconButton(onClick = onNext, enabled = total > 0) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match")
            }
        }
    }
}

private fun hexOf(c: Color): String = "%06X".format(c.toArgb() and 0xFFFFFF)

@Composable
fun ReaderScreen(file: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ext = file.extension.lowercase()
    val isPdf = ext == "pdf"
    val isSheet = ext in listOf("xlsx", "xls", "xlsm", "xltx", "xltm", "ods", "fods", "csv", "tsv", "psv", "ssv")
    val canEditSheet = ext in listOf("xlsx", "xls", "csv", "tsv", "psv", "ssv")
    val isDoc = ext in listOf("docx", "docm", "dotx", "dotm", "odt", "ott", "rtf", "mht", "mhtml", "epub", "fb2")
    val isDocx = ext in listOf("docx", "docm", "dotx", "dotm")
    val isFb2 = ext == "fb2"
    val isPptx = ext in listOf("pptx", "pptm", "potx", "potm", "ppsx", "ppsm", "odp", "otp")

    var chunks by remember(file) { mutableStateOf<List<String>?>(null) }
    var pageIndex by remember(file) { mutableStateOf(0) }
    var editing by remember(file) { mutableStateOf(false) }
    var editField by remember(file) { mutableStateOf(TextFieldValue("")) }
    val pending = remember(file) { mutableStateMapOf<Int, String>() }
    val textHistory = remember(file) { TextHistory() }
    var textFindOpen by remember(file) { mutableStateOf(false) }
    var findQuery by remember(file) { mutableStateOf("") }
    var matchIndex by remember(file) { mutableIntStateOf(0) }
    var showJump by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    val textScroll = rememberScrollState()
    var textViewport by remember { mutableIntStateOf(0) }

    var sheets by remember(file) { mutableStateOf<List<SheetData>?>(null) }
    var sheetIndex by remember(file) { mutableIntStateOf(0) }
    var sheetError by remember(file) { mutableStateOf<String?>(null) }
    var sheetVersion by remember(file) { mutableIntStateOf(0) }
    var sheetEditing by remember(file) { mutableStateOf(false) }
    val sheetEditor = remember(file) { SheetEditor() }

    var slides by remember(file) { mutableStateOf<List<SlideData>?>(null) }
    var docs by remember(file) { mutableStateOf<List<DocSection>?>(null) }
    var docLoaded by remember(file) { mutableStateOf(false) }
    var docSession by remember(file) { mutableStateOf<DocxSession?>(null) }
    var fb2Session by remember(file) { mutableStateOf<Fb2Session?>(null) }
    var fb2Editing by remember(file) { mutableStateOf(false) }
    val docEdit = remember(file) { DocEditState() }
    var docEditing by remember(file) { mutableStateOf(false) }
    var docTextTarget by remember(file) { mutableStateOf<DocPara?>(null) }
    var docText by remember(file) { mutableStateOf("") }

    val pdfEdit = remember(file) { PdfEditState() }
    var pdfEditing by remember(file) { mutableStateOf(false) }
    var pdfReload by remember(file) { mutableStateOf(0) }
    var showSaveChoice by remember { mutableStateOf(false) }
    var showUnsaved by remember { mutableStateOf(false) }

    val list = chunks
    val docShown: List<DocSection>? = when {
        isDocx -> docSession?.sections
        isFb2 -> fb2Session?.sections
        else -> docs
    }
    val docDirty = isDocx && docSession?.dirty == true
    val fb2Dirty = isFb2 && fb2Session?.dirty == true
    val textDirty = editing && list != null &&
            (pending.isNotEmpty() || editField.text != list[pageIndex])
    val hasUnsaved = (isPdf && pdfEdit.marks.isNotEmpty()) || docDirty || fb2Dirty

    LaunchedEffect(file, sheetVersion) {
        if (isSheet) {
            var error: String? = null
            val result = withContext(Dispatchers.IO) {
                runCatching { readSheets(file) }.getOrElse { e ->
                    error = e.javaClass.name + ": " + (e.message ?: "no message")
                    emptyList()
                }
            }
            sheets = result
            sheetError = error
        } else if (isDocx) {
            if (docSession == null) {
                docSession = withContext(Dispatchers.IO) { DocxSession.open(file) }
            }
            docLoaded = true
        } else if (isFb2) {
            if (fb2Session == null) {
                fb2Session = withContext(Dispatchers.IO) { Fb2Session.open(file) }
            }
            docLoaded = true
        } else if (isDoc) {
            docs = withContext(Dispatchers.IO) {
                runCatching { readDocument(file) }.getOrElse { emptyList() }
            }
            docLoaded = true
        } else if (isPptx) {
            slides = withContext(Dispatchers.IO) {
                runCatching { readSlides(file) }.getOrElse { emptyList() }
            }
        } else if (ext == "txt") {
            chunks = withContext(Dispatchers.IO) {
                runCatching { splitIntoChunks(file.readText()) }
                    .getOrElse { listOf("Could not open this file.") }
            }
        }
    }

    LaunchedEffect(pageIndex) { textScroll.scrollTo(0) }
    LaunchedEffect(findQuery) { matchIndex = 0 }
    LaunchedEffect(editing) {
        if (!editing) {
            findQuery = ""
            textFindOpen = false
        }
    }

    val pendingSnap = pending.toMap()
    val matches by produceState(
        initialValue = emptyList<PageMatch>(),
        list, findQuery, editing, pageIndex, editField.text, pendingSnap
    ) {
        val source = list
        if (!editing || findQuery.isEmpty() || source == null) {
            value = emptyList()
            return@produceState
        }
        delay(200)
        value = withContext(Dispatchers.Default) {
            val overrides = pendingSnap + (pageIndex to editField.text)
            findAll(source, overrides, findQuery)
        }
    }

    val exitPdfEdit: () -> Unit = {
        pdfEdit.exitEditing()
        pdfEditing = false
    }

    val cancelTextEdit: () -> Unit = {
        pending.clear()
        textHistory.clear()
        editing = false
    }

    val requestLeave: () -> Unit = {
        when {
            showUnsaved -> showUnsaved = false
            pdfEditing -> exitPdfEdit()
            sheetEditing -> if (sheetEditor.dirty) showUnsaved = true else sheetEditing = false
            docEditing -> docEditing = false
            fb2Editing -> fb2Editing = false
            editing -> if (textDirty) showUnsaved = true else cancelTextEdit()
            hasUnsaved -> showUnsaved = true
            else -> onBack()
        }
    }

    BackHandler(enabled = true) { requestLeave() }

    val startTextEdit: (List<String>) -> Unit = { source ->
        editField = TextFieldValue(source[pageIndex])
        matchIndex = 0
        textHistory.clear()
        editing = true
    }

    val saveText: () -> Unit = {
        val source = list
        if (source != null) {
            val pagesEdited = pending.toMap() + (pageIndex to editField.text)
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val merged = source.mapIndexed { i, t -> pagesEdited[i] ?: t }
                        file.writeText(merged.joinToString(""))
                        merged
                    }
                }
                result.onSuccess { merged ->
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                    chunks = merged
                    pending.clear()
                    textHistory.clear()
                    editing = false
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                }
                result.onFailure {
                    Toast.makeText(context, "Could not save", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val commitEditPage: (List<String>) -> Unit = { source ->
        if (editField.text == source[pageIndex]) {
            pending.remove(pageIndex)
        } else {
            pending[pageIndex] = editField.text
        }
    }

    val normalize: (Map<Int, String>) -> Map<Int, String> = { map ->
        val source = list
        map.filter { (page, text) -> text != source?.getOrNull(page) }
    }

    val textState: () -> Map<Int, String> = {
        normalize(pending.toMap() + (pageIndex to editField.text))
    }

    val applyTextState: (Map<Int, String>) -> Unit = { target ->
        val source = list
        val before = textState()
        val after = normalize(target)
        pending.clear()
        pending.putAll(after)
        val changedPages = (before.keys + after.keys).filter { k ->
            (before[k] ?: source?.getOrNull(k) ?: "") != (after[k] ?: source?.getOrNull(k) ?: "")
        }
        changedPages.minOrNull()?.let { pageIndex = it }
        editField = TextFieldValue(pending[pageIndex] ?: (source?.getOrNull(pageIndex) ?: ""))
        textHistory.lastEditAt = 0L
    }

    val undoText: () -> Unit = {
        if (textHistory.undo.isNotEmpty()) {
            textHistory.redo.add(textState())
            applyTextState(textHistory.undo.removeAt(textHistory.undo.lastIndex))
            textHistory.sync()
        }
    }

    val redoText: () -> Unit = {
        if (textHistory.redo.isNotEmpty()) {
            textHistory.undo.add(textState())
            applyTextState(textHistory.redo.removeAt(textHistory.redo.lastIndex))
            textHistory.sync()
        }
    }

    val moveMatch: (Int) -> Unit = { delta ->
        val source = list
        val n = matches.size
        if (source != null && n > 0) {
            val base = matchIndex.coerceIn(0, n - 1)
            matchIndex = ((base + delta) % n + n) % n
            val m = matches[matchIndex]
            if (m.page != pageIndex) {
                commitEditPage(source)
                pageIndex = m.page
                editField = TextFieldValue(pending[m.page] ?: source[m.page])
            }
            val len = editField.text.length
            val start = m.range.first.coerceIn(0, len)
            val end = (m.range.last + 1).coerceIn(start, len)
            editField = editField.copy(selection = TextRange(start, end))
        }
    }

    val savePdf: (Boolean) -> Unit = { overwrite ->
        showSaveChoice = false
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { savePdfEdits(context, file, pdfEdit.marks.toList(), overwrite) }
            }
            result.onSuccess { out ->
                MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), null, null)
                pdfEdit.discard()
                pdfEditing = false
                pdfReload++
                Toast.makeText(context, "Saved ${out.name}", Toast.LENGTH_SHORT).show()
            }
            result.onFailure { e ->
                Toast.makeText(context, "Could not save: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val saveDocx: (Boolean) -> Unit = { overwrite ->
        showSaveChoice = false
        val session = docSession
        if (session != null) {
            scope.launch {
                val out = withContext(Dispatchers.IO) { session.save(overwrite) }
                if (out != null) {
                    MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), null, null)
                    Toast.makeText(context, "Saved ${out.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Could not save", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val saveFb2: (Boolean) -> Unit = { overwrite ->
        showSaveChoice = false
        val session = fb2Session
        if (session != null) {
            scope.launch {
                val out = withContext(Dispatchers.IO) { session.save(overwrite) }
                if (out != null) {
                    MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), null, null)
                    Toast.makeText(context, "Saved ${out.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Could not save", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val saveSheet: () -> Unit = {
        if (!sheetEditor.dirty) {
            sheetEditing = false
        } else {
            val cells = sheetEditor.pendingCells()
            scope.launch {
                val ok = withContext(Dispatchers.IO) { writeSheetCells(file, cells) }
                if (ok) {
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                    sheetEditor.reset()
                    sheetEditing = false
                    sheetVersion++
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Could not save", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val onParaTap: (DocPara) -> Unit = { para ->
        val session = docSession
        when (docEdit.tool) {
            DocTool.NONE -> {
                docText = para.para.runs.joinToString("") { it.text }
                docTextTarget = para
            }
            DocTool.COLOR -> session?.setParagraphColor(para.srcIndex, hexOf(docEdit.color))
            DocTool.UNDERLINE -> {
                val current = para.underlineColor
                val same = current != null && current.toArgb() == docEdit.underlineColor.toArgb()
                session?.setUnderline(para.srcIndex, if (same) null else hexOf(docEdit.underlineColor))
            }
            DocTool.IMAGE -> Unit
        }
    }

    val onFb2ParaTap: (DocPara) -> Unit = { para ->
        docText = para.para.runs.joinToString("") { it.text }
        docTextTarget = para
    }

    val onImageTap: (DocImage) -> Unit = { img -> docEdit.selectImage(img) }

    val onImageCommit: () -> Unit = {
        val s = docEdit.selectedImg
        if (s != null) {
            docSession?.setImageFrame(s, docEdit.imgX, docEdit.imgY, docEdit.imgW, docEdit.imgH, docEdit.imgRot)
        }
    }

    val total = matches.size
    val shownIndex = if (total == 0) 0 else matchIndex.coerceIn(0, total - 1) + 1
    val firstOnPage = matches.indexOfFirst { it.page == pageIndex }
    val localRanges = remember(editField.text, findQuery, editing) {
        if (editing) findRanges(editField.text, findQuery) else emptyList()
    }
    val currentLocal = if (matches.getOrNull(matchIndex)?.page == pageIndex) {
        val local = matchIndex - firstOnPage
        if (local in localRanges.indices) local else -1
    } else {
        -1
    }
    val highlight = remember(localRanges, currentLocal) { FindHighlight(localRanges, currentLocal) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ShieldCard {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = requestLeave,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 96.dp)
                    )
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            isPdf -> IconButton(onClick = { if (pdfEditing) exitPdfEdit() else pdfEditing = true }) {
                                Icon(
                                    imageVector = if (pdfEditing) Icons.Default.Check else Icons.Default.Edit,
                                    contentDescription = if (pdfEditing) "Done editing" else "Edit"
                                )
                            }
                            isSheet && sheets != null && canEditSheet ->
                                IconButton(onClick = { if (sheetEditing) saveSheet() else sheetEditing = true }) {
                                    Icon(
                                        imageVector = if (sheetEditing) Icons.Default.Check else Icons.Default.Edit,
                                        contentDescription = if (sheetEditing) "Save" else "Edit"
                                    )
                                }
                            isDocx && docShown != null ->
                                IconButton(onClick = { docEditing = !docEditing }) {
                                    Icon(
                                        imageVector = if (docEditing) Icons.Default.Check else Icons.Default.Edit,
                                        contentDescription = if (docEditing) "Done editing" else "Edit"
                                    )
                                }
                            isFb2 && fb2Session != null ->
                                IconButton(onClick = { fb2Editing = !fb2Editing }) {
                                    Icon(
                                        imageVector = if (fb2Editing) Icons.Default.Check else Icons.Default.Edit,
                                        contentDescription = if (fb2Editing) "Done editing" else "Edit"
                                    )
                                }
                            list != null -> IconButton(
                                onClick = { if (editing) saveText() else startTextEdit(list) }
                            ) {
                                Icon(
                                    imageVector = if (editing) Icons.Default.Check else Icons.Default.Edit,
                                    contentDescription = if (editing) "Save" else "Edit"
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .onSizeChanged { textViewport = it.height }
            ) {
                when {
                    isPdf -> PdfContent(file, pdfEditing, pdfEdit, pdfReload)
                    isSheet -> SheetView(file, sheets, sheetIndex, sheetEditing, sheetEditor) { sheetIndex = it }
                    isDoc -> {
                        val d = docShown
                        when {
                            d == null && !docLoaded -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            d == null || d.isEmpty() -> Text(
                                text = "Could not open this document.",
                                modifier = Modifier.align(Alignment.Center)
                            )
                            else -> if (isFb2) {
                                DocView(d, docEdit, fb2Editing, onFb2ParaTap, onImageTap, onImageCommit)
                            } else {
                                DocView(d, docEdit, docEditing, onParaTap, onImageTap, onImageCommit)
                            }
                        }
                    }
                    isPptx -> {
                        val deck = slides
                        if (deck == null) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        } else if (deck.isEmpty()) {
                            Text(
                                text = "Could not open this presentation.",
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            SlideView(deck)
                        }
                    }
                    ext == "ppt" -> Text(
                        text = "Old .ppt files aren't supported yet. Save as .pptx to view them.",
                        modifier = Modifier.align(Alignment.Center)
                    )
                    list == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    editing -> OutlinedTextField(
                        value = editField,
                        onValueChange = { newValue ->
                            if (newValue.text != editField.text) {
                                val now = System.currentTimeMillis()
                                if (now - textHistory.lastEditAt > 700L) {
                                    textHistory.undo.add(textState())
                                    textHistory.redo.clear()
                                    textHistory.sync()
                                }
                                textHistory.lastEditAt = now
                            }
                            editField = newValue
                        },
                        modifier = Modifier.fillMaxSize(),
                        visualTransformation = highlight
                    )
                    else -> Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(textScroll)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                text = list[pageIndex],
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        ScrollIndicator { textScroll.scrollMetrics(textViewport) }
                    }
                }
                if (isPdf && pdfEditing) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 4.dp)
                    ) {
                        PdfEditToolbar(pdfEdit, onSave = { showSaveChoice = true })
                    }
                }
                val session = docSession
                if (isDocx && docEditing && session != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 4.dp)
                    ) {
                        DocEditToolbar(
                            edit = docEdit,
                            session = session,
                            onSave = { showSaveChoice = true },
                            onRemoveImage = { idx ->
                                session.removeImage(idx)
                                docEdit.clearSelection()
                            }
                        )
                    }
                }
                val fb2 = fb2Session
                if (isFb2 && fb2Editing && fb2 != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 4.dp)
                    ) {
                        Fb2EditToolbar(fb2, onSave = { showSaveChoice = true })
                    }
                }
            }

            if (!isPdf && list != null) {
                if (editing) {
                    FindUndoRedoBar(
                        findOpen = textFindOpen,
                        onToggleFind = {
                            textFindOpen = !textFindOpen
                            if (!textFindOpen) findQuery = ""
                        },
                        canUndo = textHistory.canUndo,
                        canRedo = textHistory.canRedo,
                        onUndo = undoText,
                        onRedo = redoText
                    )
                    if (textFindOpen) {
                        FindBar(
                            query = findQuery,
                            onQueryChange = { findQuery = it },
                            current = shownIndex,
                            total = total,
                            onPrev = { moveMatch(-1) },
                            onNext = { moveMatch(1) }
                        )
                    }
                } else {
                    ShieldCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = { pageIndex-- },
                                enabled = pageIndex > 0
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowLeft,
                                    contentDescription = "Previous page"
                                )
                            }
                            TextButton(
                                onClick = {
                                    jumpText = (pageIndex + 1).toString()
                                    showJump = true
                                }
                            ) {
                                Text(
                                    text = "Page ${pageIndex + 1} of ${list.size}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            IconButton(
                                onClick = { pageIndex++ },
                                enabled = pageIndex < list.size - 1
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowRight,
                                    contentDescription = "Next page"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showJump && list != null) {
        AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text("Jump to page") },
            text = {
                OutlinedTextField(
                    value = jumpText,
                    onValueChange = { jumpText = it.filter(Char::isDigit) },
                    label = { Text("1 to ${list.size}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    jumpText.toIntOrNull()?.let { n ->
                        pageIndex = n.coerceIn(1, list.size) - 1
                    }
                    showJump = false
                }) {
                    Text("Go")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJump = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    docTextTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { docTextTarget = null },
            title = { Text("Edit paragraph") },
            text = {
                OutlinedTextField(
                    value = docText,
                    onValueChange = { docText = it },
                    minLines = 3
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newText = docText
                    val idx = target.srcIndex
                    docTextTarget = null
                    if (isFb2) {
                        fb2Session?.setParagraphText(idx, newText)
                    } else {
                        docSession?.setParagraphText(idx, newText)
                    }
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { docTextTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showSaveChoice) {
        SaveChoiceDialog(
            onCopy = {
                when {
                    isFb2 -> saveFb2(false)
                    isDocx -> saveDocx(false)
                    else -> savePdf(false)
                }
            },
            onOverwrite = {
                when {
                    isFb2 -> saveFb2(true)
                    isDocx -> saveDocx(true)
                    else -> savePdf(true)
                }
            },
            onDismiss = { showSaveChoice = false }
        )
    }

    if (showUnsaved) {
        AlertDialog(
            onDismissRequest = { showUnsaved = false },
            title = { Text("You have unsaved changes") },
            text = { Text("Leave without saving? Your edits will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showUnsaved = false
                    when {
                        sheetEditing -> {
                            sheetEditor.reset()
                            sheetEditing = false
                        }
                        editing -> cancelTextEdit()
                        else -> {
                            pdfEdit.discard()
                            onBack()
                        }
                    }
                }) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { showUnsaved = false }) { Text("Stay") }
            }
        )
    }

    val placing = pdfEdit.placing
    if (placing != null && placing.kind == PdfMarkKind.TEXT) {
        TextInputDialog(
            onConfirm = { text -> pdfEdit.addText(placing, text) },
            onDismiss = { pdfEdit.placing = null }
        )
    }
    if (placing != null && placing.kind == PdfMarkKind.SIGNATURE) {
        SignaturePadDialog(
            onSave = { strokes -> pdfEdit.addSignature(placing, strokes) },
            onDismiss = { pdfEdit.placing = null }
        )
    }
}