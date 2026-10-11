package com.example.everyreader

import androidx.compose.ui.graphics.Color
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

internal fun xParse(input: InputStream): Document =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)

internal fun xZipDom(zip: ZipFile, path: String): Element? {
    val e = zip.getEntry(path) ?: return null
    return zip.getInputStream(e).use { xParse(it).documentElement }
}

internal fun xZipText(zip: ZipFile, path: String): String? {
    val e = zip.getEntry(path) ?: return null
    return zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
}

internal fun Element.xElements(): List<Element> {
    val out = ArrayList<Element>()
    for (i in 0 until childNodes.length) {
        (childNodes.item(i) as? Element)?.let { out.add(it) }
    }
    return out
}

internal fun Element.xChildren(tag: String): List<Element> =
    xElements().filter { it.tagName == tag }

internal fun Element.xChild(tag: String): Element? =
    xElements().firstOrNull { it.tagName == tag }

internal fun Element.xAll(tag: String): List<Element> {
    val nl = getElementsByTagName(tag)
    return List(nl.length) { nl.item(it) as Element }
}

internal fun xHex(v: String): Color? =
    if (v.length == 6) v.toLongOrNull(16)?.let { Color(0xFF000000L or it) } else null