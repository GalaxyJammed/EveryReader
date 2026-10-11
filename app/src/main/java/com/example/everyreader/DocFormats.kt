package com.example.everyreader

import android.util.Base64
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

private val CP1252: Charset =
    runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

private val RTF_SKIP = listOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer")

fun readRtf(file: File): List<DocSection> {
    val text = rtfToText(file.readText(Charsets.ISO_8859_1))
    return listOf(DocSection(textBlocks(text)))
}

private fun rtfToText(raw: String): String {
    val sb = StringBuilder()
    var depth = 0
    var skipAt = -1
    var i = 0
    val n = raw.length
    while (i < n) {
        val c = raw[i]
        when (c) {
            '{' -> {
                depth++
                if (skipAt < 0 && (raw.startsWith("\\*", i + 1) || RTF_SKIP.any { raw.startsWith("\\$it", i + 1) })) {
                    skipAt = depth
                }
                i++
            }
            '}' -> {
                if (skipAt == depth) skipAt = -1
                depth--
                i++
            }
            '\\' -> {
                if (i + 1 >= n) {
                    i++
                    continue
                }
                val d = raw[i + 1]
                when {
                    d.isLetter() -> {
                        var j = i + 1
                        while (j < n && raw[j].isLetter()) j++
                        val word = raw.substring(i + 1, j)
                        var k = j
                        if (k < n && raw[k] == '-') k++
                        while (k < n && raw[k].isDigit()) k++
                        val param = raw.substring(j, k).toIntOrNull()
                        if (k < n && raw[k] == ' ') k++
                        if (skipAt < 0) {
                            when (word) {
                                "par", "line" -> sb.append('\n')
                                "tab" -> sb.append('\t')
                                "u" -> if (param != null) {
                                    sb.append((if (param < 0) param + 65536 else param).toChar())
                                    k++
                                }
                            }
                        }
                        i = k
                    }
                    d == '\'' -> {
                        if (i + 3 < n) {
                            val v = raw.substring(i + 2, i + 4).toIntOrNull(16)
                            if (skipAt < 0 && v != null) {
                                sb.append(String(byteArrayOf(v.toByte()), CP1252))
                            }
                            i += 4
                        } else {
                            i += 2
                        }
                    }
                    d == '\\' || d == '{' || d == '}' -> {
                        if (skipAt < 0) sb.append(d)
                        i += 2
                    }
                    else -> i += 2
                }
            }
            '\r', '\n' -> i++
            else -> {
                if (skipAt < 0) sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

private fun textBlocks(text: String): List<DocBlock> =
    text.split('\n')
        .filter { it.isNotBlank() }
        .map { docPara(it.trimEnd()) }

fun readMht(file: File): List<DocSection> {
    val raw = String(file.readBytes(), Charsets.ISO_8859_1)
    val idx = raw.indexOf("text/html", ignoreCase = true)
    if (idx < 0) return emptyList()
    val headerEnd = Regex("\\r?\\n\\r?\\n").find(raw, idx)?.range?.last?.plus(1) ?: return emptyList()
    val headers = raw.substring(idx, headerEnd)
    val bodyEnd = Regex("\\r?\\n------").find(raw, headerEnd)?.range?.first ?: raw.length
    val body = raw.substring(headerEnd, bodyEnd)
    val enc = Regex("Content-Transfer-Encoding:\\s*(\\S+)", RegexOption.IGNORE_CASE)
        .find(headers)?.groupValues?.get(1)?.lowercase()
    val charsetName = Regex("charset=\"?([\\w.-]+)", RegexOption.IGNORE_CASE)
        .find(headers)?.groupValues?.get(1)
    val bytes = when (enc) {
        "quoted-printable" -> decodeQp(body)
        "base64" -> Base64.decode(body.filter { !it.isWhitespace() }, Base64.DEFAULT)
        else -> body.toByteArray(Charsets.ISO_8859_1)
    }
    val charset = runCatching { Charset.forName(charsetName ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
    return listOf(DocSection(htmlBlocks(String(bytes, charset))))
}

private fun decodeQp(s: String): ByteArray {
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '=') {
            when {
                i + 1 < s.length && s[i + 1] == '\n' -> i += 2
                i + 2 < s.length && s[i + 1] == '\r' && s[i + 2] == '\n' -> i += 3
                i + 2 < s.length && s.substring(i + 1, i + 3).toIntOrNull(16) != null -> {
                    out.write(s.substring(i + 1, i + 3).toInt(16))
                    i += 3
                }
                else -> {
                    out.write('='.code)
                    i++
                }
            }
        } else {
            out.write(c.code)
            i++
        }
    }
    return out.toByteArray()
}

fun readEpub(file: File): List<DocSection> = ZipFile(file).use { zip ->
    val container = xZipText(zip, "META-INF/container.xml") ?: return@use emptyList()
    val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
        ?: return@use emptyList()
    val opf = xZipText(zip, opfPath) ?: return@use emptyList()
    val base = opfPath.substringBeforeLast('/', "")
    val manifest = HashMap<String, String>()
    Regex("<item\\b[^>]*>").findAll(opf).forEach { m ->
        val id = attrIn(m.value, "id")
        val href = attrIn(m.value, "href")
        if (id != null && href != null) manifest[id] = href
    }
    Regex("<itemref\\b[^>]*>").findAll(opf)
        .mapNotNull { m -> attrIn(m.value, "idref")?.let { manifest[it] } }
        .mapNotNull { href ->
            val path = if (base.isEmpty()) href else "$base/$href"
            xZipText(zip, path)?.let { DocSection(htmlBlocks(it)) }
        }
        .toList()
}

private fun attrIn(tag: String, name: String): String? =
    Regex("\\s$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

private fun htmlBlocks(html: String): List<DocBlock> {
    val body = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.get(1) ?: html
    val marked = body
        .replace(Regex("<(script|style)\\b[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<h[1-6]\\b[^>]*>", RegexOption.IGNORE_CASE), "\n\n\u0001")
        .replace(Regex("</h[1-6]>", RegexOption.IGNORE_CASE), "\u0002\n\n")
        .replace(
            Regex(
                "</?(p|div|li|tr|blockquote|pre|ul|ol|table|section|article|header|footer)\\b[^>]*>",
                RegexOption.IGNORE_CASE
            ),
            "\n\n"
        )
        .replace(Regex("<[^>]+>"), "")
    return unescapeHtml(marked).split(Regex("\\n\\s*\\n")).mapNotNull { chunk ->
        val heading = chunk.contains('\u0001')
        val text = chunk.replace("\u0001", "").replace("\u0002", "")
            .replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) null else docPara(text, if (heading) 16f else 11f, heading)
    }
}

private fun unescapeHtml(s: String): String = s
    .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
        runCatching { String(Character.toChars(m.groupValues[1].toInt(16))) }.getOrDefault("")
    }
    .replace(Regex("&#(\\d+);")) { m ->
        runCatching { String(Character.toChars(m.groupValues[1].toInt())) }.getOrDefault("")
    }
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&#39;", "'")
    .replace("&amp;", "&")