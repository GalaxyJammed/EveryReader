package com.example.everyreader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

data class SheetCell(val sheet: Int, val row: Int, val col: Int)

class SheetEditor {
    val edits = mutableStateMapOf<SheetCell, String>()
    var revision by mutableIntStateOf(0)
        private set
    private val undoStack = ArrayList<Map<SheetCell, String>>()
    private val redoStack = ArrayList<Map<SheetCell, String>>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    val dirty: Boolean get() = edits.isNotEmpty()

    fun valueOf(sheet: Int, row: Int, col: Int, original: String): String =
        edits[SheetCell(sheet, row, col)] ?: original

    fun pendingCells(): List<Pair<SheetCell, String>> =
        edits.entries
            .map { it.key to it.value }
            .sortedWith(compareBy<Pair<SheetCell, String>>({ it.first.sheet }, { it.first.row }, { it.first.col }))

    fun setCell(cell: SheetCell, text: String, original: String) {
        val before = edits.toMap()
        if (text == original) edits.remove(cell) else edits[cell] = text
        if (edits.toMap() == before) return
        undoStack.add(before)
        redoStack.clear()
        changed()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(edits.toMap())
        restore(undoStack.removeAt(undoStack.lastIndex))
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(edits.toMap())
        restore(redoStack.removeAt(redoStack.lastIndex))
    }

    private fun restore(map: Map<SheetCell, String>) {
        edits.clear()
        edits.putAll(map)
        changed()
    }

    private fun changed() {
        revision++
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    fun reset() {
        edits.clear()
        undoStack.clear()
        redoStack.clear()
        changed()
    }
}

fun writeSheetCells(file: File, cells: List<Pair<SheetCell, String>>): Boolean =
    cells.all { (cell, value) -> writeSheetCell(file, cell.sheet, cell.row, cell.col, value) }