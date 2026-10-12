package com.example.everyreader

import android.util.Xml
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.IntrinsicSize

data class SheetData(val name: String, val rows: List<List<String>>)

data class CellPos(val row: Int, val col: Int)

private const val EXTRA_ROWS = 30
private const val EXTRA_COLS = 5

fun readXlsx(file: File): List<SheetData> =
    ZipFile(file).use { zip ->
        val shared = zip.getEntry("xl/sharedStrings.xml")
            ?.let { parseSharedStrings(zip.getInputStream(it)) }
            ?: emptyList()
        val names = zip.getEntry("xl/workbook.xml")
            ?.let { parseSheetNames(zip.getInputStream(it)) }
            ?: emptyList()
        zip.entries().asSequence()
            .filter { it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml") }
            .sortedBy { it.name.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: 0 }
            .toList()
            .mapIndexed { i, entry ->
                SheetData(
                    name = names.getOrNull(i) ?: "Sheet ${i + 1}",
                    rows = parseSheet(zip.getInputStream(entry), shared)
                )
            }
    }

private fun newParser(input: InputStream): XmlPullParser =
    Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, "UTF-8")
    }

private fun parseSharedStrings(input: InputStream): List<String> {
    val out = ArrayList<String>()
    input.use {
        val p = newParser(it)
        val sb = StringBuilder()
        var inSi = false
        var inT = false
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> {
                        inSi = true
                        sb.clear()
                    }
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inSi && inT) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "si" -> {
                        inSi = false
                        out += sb.toString()
                    }
                }
            }
            event = p.next()
        }
    }
    return out
}

private fun parseSheetNames(input: InputStream): List<String> {
    val out = ArrayList<String>()
    input.use {
        val p = newParser(it)
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "sheet") {
                p.getAttributeValue(null, "name")?.let { out += it }
            }
            event = p.next()
        }
    }
    return out
}

private fun columnIndex(ref: String?): Int {
    if (ref == null) return 0
    var n = 0
    for (ch in ref) {
        if (!ch.isLetter()) break
        n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
    }
    return (n - 1).coerceAtLeast(0)
}

private fun parseSheet(input: InputStream, shared: List<String>): List<List<String>> {
    val rows = ArrayList<List<String>>()
    input.use {
        val p = newParser(it)
        var row = ArrayList<String>()
        var rowNum = 0
        var col = 0
        var type = ""
        var inV = false
        var inT = false
        val value = StringBuilder()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        row = ArrayList()
                        rowNum = p.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1) ?: rows.size
                    }
                    "c" -> {
                        col = columnIndex(p.getAttributeValue(null, "r"))
                        type = p.getAttributeValue(null, "t") ?: ""
                        value.clear()
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val text = if (type == "s") {
                            shared.getOrNull(value.toString().toIntOrNull() ?: -1).orEmpty()
                        } else {
                            value.toString()
                        }
                        while (row.size <= col) row.add("")
                        row[col] = text
                    }
                    "row" -> {
                        while (rows.size < rowNum) rows.add(emptyList())
                        if (rows.size == rowNum) rows.add(row) else rows[rowNum] = row
                    }
                }
            }
            event = p.next()
        }
    }
    return rows
}

@Composable
fun BoxScope.SheetView(
    file: File,
    sheets: List<SheetData>?,
    index: Int,
    editing: Boolean,
    editor: SheetEditor,
    onSelect: (Int) -> Unit
) {
    if (sheets == null) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        return
    }
    if (sheets.isEmpty()) {
        Text(
            text = "Could not open this spreadsheet.",
            modifier = Modifier.align(Alignment.Center)
        )
        return
    }
    SheetContent(
        file = file,
        current = sheets[index.coerceIn(0, sheets.lastIndex)],
        sheets = sheets,
        index = index,
        editing = editing,
        editor = editor,
        onSelect = onSelect
    )
}

@Composable
fun SheetContent(
    file: File,
    current: SheetData,
    sheets: List<SheetData>,
    index: Int,
    editing: Boolean,
    editor: SheetEditor,
    onSelect: (Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val cols = maxOf(current.rows.maxOfOrNull { it.size } ?: 0, 1) + EXTRA_COLS
    val rowHeight = 28.dp
    val rowNumWidth = 44.dp
    val chipScroll = rememberScrollState()
    val tableScroll = rememberScrollState()
    val listState = rememberLazyListState()
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val cellBg = MaterialTheme.colorScheme.surface
    val editedBg = MaterialTheme.colorScheme.primaryContainer
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    val yellow = Color(0xAAFFEB3B)
    val orange = Color(0xFFFFA000)

    val originals = remember(current) {
        List(current.rows.size + EXTRA_ROWS) { r -> current.rows.getOrElse(r) { emptyList() } }
    }
    val cellWidths: List<Dp> = remember(originals, cols) {
        List(cols) { c ->
            val longest = originals.maxOfOrNull { row -> row.getOrElse(c) { "" }.length } ?: 0
            (longest * 7f + 16f).coerceIn(80f, 280f).dp
        }
    }
    val shown: List<List<String>> = remember(current, index, cols, editor.revision) {
        originals.mapIndexed { r, row ->
            List(cols) { c -> editor.valueOf(index, r, c, row.getOrElse(c) { "" }) }
        }
    }

    var query by remember(file) { mutableStateOf("") }
    var matchIndex by remember(file) { mutableIntStateOf(-1) }
    var findOpen by remember(file) { mutableStateOf(false) }
    var editCell by remember(file) { mutableStateOf<CellPos?>(null) }
    var editValue by remember(file) { mutableStateOf("") }

    val matches = remember(shown, query, editing, findOpen) {
        if (!editing || !findOpen || query.isEmpty()) {
            emptyList()
        } else {
            buildList {
                shown.forEachIndexed { r, row ->
                    row.forEachIndexed { c, text ->
                        if (text.contains(query, ignoreCase = true)) add(CellPos(r, c))
                    }
                }
            }
        }
    }
    val matchSet = remember(matches) { matches.toHashSet() }
    val n = matches.size

    LaunchedEffect(query, index) { matchIndex = -1 }
    LaunchedEffect(editing) {
        if (!editing) {
            query = ""
            findOpen = false
        }
    }

    val currentPos = if (matchIndex in 0 until n) matches[matchIndex] else null

    val gotoMatch: (Int) -> Unit = { delta ->
        if (n > 0) {
            val next = if (matchIndex < 0) {
                if (delta > 0) 0 else n - 1
            } else {
                ((matchIndex + delta) % n + n) % n
            }
            matchIndex = next
            val pos = matches[next]
            val x = with(density) { cellWidths.take(pos.col).fold(0.dp) { acc, w -> acc + w }.toPx() }
            scope.launch {
                listState.animateScrollToItem(pos.row)
                tableScroll.animateScrollTo(maxOf(0, x.toInt() - 60))
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(chipScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sheets.forEachIndexed { i, s ->
                FilterChip(
                    selected = i == index,
                    onClick = { onSelect(i) },
                    label = { Text(s.name) }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
            Box(
                modifier = Modifier
                    .width(rowNumWidth)
                    .fillMaxHeight()
                    .background(headerBg)
                    .border(0.5.dp, gridColor)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(tableScroll)
            ) {
                for (c in 0 until cols) {
                    Box(
                        modifier = Modifier
                            .width(cellWidths[c])
                            .fillMaxHeight()
                            .background(headerBg)
                            .border(0.5.dp, gridColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = columnLetter(c),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            itemsIndexed(shown) { r, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .defaultMinSize(minHeight = rowHeight)
                ) {
                    Box(
                        modifier = Modifier
                            .width(rowNumWidth)
                            .fillMaxHeight()
                            .background(headerBg)
                            .border(0.5.dp, gridColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (r + 1).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(tableScroll)
                    ) {
                        for (c in 0 until cols) {
                            val pos = CellPos(r, c)
                            val text = row.getOrElse(c) { "" }
                            val highlighted = pos in matchSet
                            val edited = editor.edits.containsKey(SheetCell(index, r, c))
                            val bg = when {
                                pos == currentPos -> orange
                                highlighted -> yellow
                                edited -> editedBg
                                else -> cellBg
                            }
                            Box(
                                modifier = Modifier
                                    .width(cellWidths[c])
                                    .fillMaxHeight()
                                    .background(bg)
                                    .border(0.5.dp, gridColor)
                                    .clickable(enabled = editing) {
                                        editCell = pos
                                        editValue = text
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (highlighted) Color.Black else Color.Unspecified
                                )
                            }
                        }
                    }
                }
            }
        }

        if (editing) {
            Spacer(modifier = Modifier.height(8.dp))
            FindUndoRedoBar(
                findOpen = findOpen,
                onToggleFind = {
                    findOpen = !findOpen
                    if (!findOpen) query = ""
                },
                canUndo = editor.canUndo,
                canRedo = editor.canRedo,
                onUndo = { editor.undo() },
                onRedo = { editor.redo() }
            )
            if (findOpen) {
                Spacer(modifier = Modifier.height(8.dp))
                FindBar(
                    query = query,
                    onQueryChange = { query = it },
                    current = if (n == 0) 0 else matchIndex.coerceIn(-1, n - 1) + 1,
                    total = n,
                    onPrev = { gotoMatch(-1) },
                    onNext = { gotoMatch(1) }
                )
            }
        }
    }

    editCell?.let { pos ->
        AlertDialog(
            onDismissRequest = { editCell = null },
            title = { Text("Edit ${columnLetter(pos.col)}${pos.row + 1}") },
            text = {
                OutlinedTextField(
                    value = editValue,
                    onValueChange = { editValue = it },
                    minLines = 1,
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val original = originals.getOrNull(pos.row)?.getOrNull(pos.col) ?: ""
                    editor.setCell(SheetCell(index, pos.row, pos.col), editValue, original)
                    editCell = null
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { editCell = null }) { Text("Cancel") }
            }
        )
    }
}

private fun columnLetter(index: Int): String {
    var n = index + 1
    val sb = StringBuilder()
    while (n > 0) {
        val rem = (n - 1) % 26
        sb.insert(0, 'A' + rem)
        n = (n - 1) / 26
    }
    return sb.toString()
}