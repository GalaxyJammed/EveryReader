package com.example.everyreader

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.apache.poi.xssf.usermodel.XSSFWorkbook

fun writeXlsxCell(file: File, sheetIndex: Int, row: Int, col: Int, value: String): Boolean =
    runCatching {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileInputStream(file).use { input ->
            XSSFWorkbook(input).use { wb ->
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