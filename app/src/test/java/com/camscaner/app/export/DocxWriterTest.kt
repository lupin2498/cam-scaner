package com.camscaner.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class DocxWriterTest {

    private fun entries(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = String(zip.readBytes(), Charsets.UTF_8)
            }
        }
        return out
    }

    @Test
    fun textPagesBecomeParagraphsWithPageBreaks() {
        val bytes = ByteArrayOutputStream()
        DocxWriter.write(listOf(DocxPage("Dòng 1\nDòng 2 & <ký hiệu>"), DocxPage("Trang hai")), bytes)
        val parts = entries(bytes.toByteArray())
        assertTrue(parts.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/_rels/document.xml.rels")))
        val doc = parts.getValue("word/document.xml")
        assertTrue(doc.contains("<w:t xml:space=\"preserve\">Dòng 1</w:t>"))
        assertTrue(doc.contains("Dòng 2 &amp; &lt;ký hiệu&gt;"))
        assertEquals(1, Regex("<w:br w:type=\"page\"/>").findAll(doc).count())
        assertTrue(doc.indexOf("Dòng 1") < doc.indexOf("w:type=\"page\"") && doc.indexOf("w:type=\"page\"") < doc.indexOf("Trang hai"))
        assertTrue(!parts.getValue("[Content_Types].xml").contains("jpg"))
    }

    @Test
    fun imagesAreEmbeddedAndReferenced() {
        val image = File.createTempFile("page", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val bytes = ByteArrayOutputStream()
        DocxWriter.write(listOf(DocxPage("Chữ", image, 1000, 1414)), bytes)
        val parts = entries(bytes.toByteArray())
        assertTrue(parts.containsKey("word/media/image0.jpg"))
        assertTrue(parts.getValue("word/_rels/document.xml.rels").contains("Target=\"media/image0.jpg\""))
        val doc = parts.getValue("word/document.xml")
        assertTrue(doc.contains("r:embed=\"rIdImg0\""))
        // the picture keeps the page's proportions inside the content width
        val cx = Regex("<wp:extent cx=\"(\\d+)\" cy=\"(\\d+)\"").find(doc)!!
        val ratio = cx.groupValues[2].toDouble() / cx.groupValues[1].toDouble()
        assertEquals(1.414, ratio, 0.01)
        assertTrue(parts.getValue("[Content_Types].xml").contains("Extension=\"jpg\""))
    }

    @Test
    fun escapeDropsControlCharacters() {
        assertEquals("a\tb &amp; &quot;c&quot;", DocxWriter.escape("a\tb\u0001 & \"c\""))
    }
}
