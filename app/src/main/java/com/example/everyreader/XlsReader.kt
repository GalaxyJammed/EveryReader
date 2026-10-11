package com.example.everyreader

import java.io.File
import java.io.FileInputStream
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType

enum class SheetKind { OLE, ZIP, TEXT }

fun sheetKind(file: File): SheetKind {
    val head = ByteArray(4)
    val n = FileInputStream(file).use { it.read(head) }
    return when {
        n >= 4 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() &&
                head[2] == 0x11.toByte() && head[3] == 0xE0.toByte() -> SheetKind.OLE
        n >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() -> SheetKind.ZIP
        else -> SheetKind.TEXT
    }
}

fun readSheets(file: File): List<SheetData> {
    val ext = file.extension.lowercase()
    if (ext == "ods" || ext == "fods") return readOds(file)
    return when (sheetKind(file)) {
        SheetKind.OLE -> readXls(file)
        SheetKind.ZIP -> readXlsx(file)
        SheetKind.TEXT -> listOf(SheetData(file.nameWithoutExtension, readCsvRows(file)))
    }
}

fun readXls(file: File): List<SheetData> =
    FileInputStream(file).use { input ->
        HSSFWorkbook(input).use { wb ->
            (0 until wb.numberOfSheets).map { i ->
                val sheet = wb.getSheetAt(i)
                val rows = ArrayList<List<String>>()
                for (r in 0..sheet.lastRowNum) {
                    val row = sheet.getRow(r)
                    if (row == null) {
                        rows.add(emptyList<String>())
                    } else {
                        val cells = ArrayList<String>()
                        for (c in 0 until row.lastCellNum.toInt()) {
                            cells.add(xlsCellText(row.getCell(c)))
                        }
                        rows.add(cells)
                    }
                }
                SheetData(wb.getSheetName(i), rows)
            }
        }
    }

private fun xlsCellText(cell: Cell?): String =
    runCatching {
        when (cell?.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> numberText(cell.numericCellValue)
            CellType.BOOLEAN -> cell.booleanCellValue.toString().uppercase()
            CellType.FORMULA -> when (cell.cachedFormulaResultType) {
                CellType.STRING -> cell.stringCellValue
                CellType.BOOLEAN -> cell.booleanCellValue.toString().uppercase()
                else -> numberText(cell.numericCellValue)
            }
            else -> ""
        }
    }.getOrDefault("")

private fun numberText(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()