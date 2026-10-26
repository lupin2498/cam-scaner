package com.camscaner.app.export

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One page of a Word export: its text and, optionally, its image with pixel size. */
data class DocxPage(val text: String, val image: File? = null, val imageWidth: Int = 0, val imageHeight: Int = 0)

/**
 * Writes a minimal Office Open XML document (.docx): one section per page with the recognised
 * text as paragraphs and, when given, the page image above it; pages are separated by page
 * breaks. Pure JVM code, so it can be unit tested.
 */
object DocxWriter {

    /** Usable width of the page in EMU (A4 with 2 cm margins). */
    private const val CONTENT_WIDTH_EMU = 6_120_000L
    private const val CONTENT_HEIGHT_EMU = 9_000_000L

    fun write(pages: List<DocxPage>, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            val images = pages.mapIndexedNotNull { i, p -> p.image?.takeIf { it.exists() }?.let { i to it } }
            zip.entry("[Content_Types].xml", contentTypes(images.isNotEmpty()))
            zip.entry("_rels/.rels", ROOT_RELS)
            zip.entry("word/_rels/document.xml.rels", documentRels(images.map { it.first }))
            zip.entry("word/document.xml", document(pages))
            for ((index, file) in images) {
                zip.putNextEntry(ZipEntry("word/media/image$index.jpg"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(withImages: Boolean) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        if (withImages) append("<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>")
        append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
        append("</Types>")
    }

    private const val ROOT_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "</Relationships>"

    private fun documentRels(imageIndices: List<Int>) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in imageIndices) {
            append("<Relationship Id=\"rIdImg$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image$i.jpg\"/>")
        }
        append("</Relationships>")
    }

    private fun document(pages: List<DocxPage>) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" ")
        append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ")
        append("xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" ")
        append("xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ")
        append("xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
        append("<w:body>")
        pages.forEachIndexed { index, page ->
            if (index > 0) append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            if (page.image != null && page.image.exists()) append(imageParagraph(index, page.imageWidth, page.imageHeight))
            val lines = page.text.trim().lines()
            if (lines.all { it.isBlank() }) {
                append("<w:p/>")
            } else {
                for (line in lines) {
                    append("<w:p><w:r><w:t xml:space=\"preserve\">").append(escape(line)).append("</w:t></w:r></w:p>")
                }
            }
        }
        append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
        append("<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>")
        append("</w:body></w:document>")
    }

    /** The page image scaled to fit the content area, as an inline drawing. */
    private fun imageParagraph(index: Int, width: Int, height: Int): String {
        val w = width.coerceAtLeast(1).toDouble()
        val h = height.coerceAtLeast(1).toDouble()
        val scale = minOf(CONTENT_WIDTH_EMU / w, CONTENT_HEIGHT_EMU / h)
        val cx = (w * scale).toLong()
        val cy = (h * scale).toLong()
        val id = index + 1
        return "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"Trang $id\"/>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"image$index.jpg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"rIdImg$index\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
    }

    /** XML-escapes [s] and drops control characters Word rejects. */
    fun escape(s: String): String = buildString(s.length + 8) {
        for (ch in s) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch.code < 0x20 && ch != '\t' -> Unit
                else -> append(ch)
            }
        }
    }
}
