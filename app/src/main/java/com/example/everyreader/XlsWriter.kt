package com.example.everyreader

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.apache.poi.hssf.usermodel.HSSFWorkbook

fun writeSheetCell(file: File, sheetIndex: Int, row: Int, col: Int, value: String): Boolean =
    when (sheetKind(file)) {
        SheetKind.OLE -> writeXlsCell(file, sheetIndex, row, col, value)
        SheetKind.ZIP -> writeXlsxCell(file, sheetIndex, row, col, value)
        SheetKind.TEXT -> writeCsvCell(file, row, col, value)
    }

fun writeXlsCell(file: File, sheetIndex: Int, row: Int, col: Int, value: String): Boolean =
    runCatching {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileInputStream(file).use { input ->
            HSSFWorkbook(input).use { wb ->
                val sheet = wb.getSheetAt(sheetIndex)
                val r = sheet.getRow(row) ?: sheet.createRow(row)
                val cell = r.getCell(col) ?: r.createCell(col)
                cell.setCellValue(value)
                FileOutputStream(tmp).use { wb.write(it) }
            }
        }
        if (tmp.renameTo(file)) true else {
            tmp.delete()
            false
        }
    }.getOrDefault(false)