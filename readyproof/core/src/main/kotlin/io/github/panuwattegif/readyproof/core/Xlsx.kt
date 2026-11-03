package io.github.panuwattegif.readyproof.core

import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A plain .xlsx workbook (text and numbers, a bold style, column widths, a frozen header), enough
 * for Google Drive to turn it into the shop's Google Sheet. No library: only what is written here.
 */
object Xlsx {
    class Cell(val text: String? = null, val number: Double? = null, val bold: Boolean = false)

    class Sheet(
        val name: String,
        val rows: List<List<Cell?>>,
        /** Column widths in characters; missing ones keep the default. */
        val widths: List<Double> = emptyList(),
        /** Rows kept on screen while scrolling (0 = none). */
        val frozenRows: Int = 0,
    )

    fun text(s: String?, bold: Boolean = false): Cell? = s?.let { Cell(text = it, bold = bold) }

    fun number(v: Double?): Cell? = v?.let { Cell(number = it) }

    fun write(sheets: List<Sheet>): ByteArray {
        require(sheets.isNotEmpty())
        val names = sheets.map { safeName(it.name) }
        require(names.toSet().size == names.size) { "sheet names must differ" }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // Written straight into the zip: a sheet of a year's orders is never one big string.
            val w = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8), 64 * 1024)
            fun put(path: String, write: (Appendable) -> Unit) {
                zip.putNextEntry(ZipEntry(path))
                write(w)
                w.flush()
                zip.closeEntry()
            }
            put("[Content_Types].xml") { it.append(contentTypes(sheets.size)) }
            put("_rels/.rels") { it.append(ROOT_RELS) }
            put("xl/workbook.xml") { it.append(workbook(names)) }
            put("xl/_rels/workbook.xml.rels") { it.append(workbookRels(sheets.size)) }
            put("xl/styles.xml") { it.append(STYLES) }
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml") { sheet(s, it) } }
        }
        return out.toByteArray()
    }

    /** "A", "B", ... "Z", "AA" for column [i] (0-based). */
    fun column(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.append('A' + r)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** Sheet names: at most 31 characters, none of []:*?/\ . */
    fun safeName(name: String): String = name.replace(Regex("""[\[\]:*?/\\]"""), " ").trim().take(31).ifEmpty { "Sheet" }

    private fun esc(s: String): String = StringBuilder(s.length).also { esc(s, it) }.toString()

    private fun esc(s: String, out: Appendable) = with(out) {
        for (ch in s) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                // characters XML 1.0 does not allow are dropped
                ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> {}
                ch == '￾' || ch == '￿' -> {}
                else -> append(ch)
            }
        }
    }

    private fun sheet(s: Sheet, out: Appendable) = with(out) {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        if (s.frozenRows > 0) {
            append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="${s.frozenRows}" topLeftCell="A${s.frozenRows + 1}" """)
            append("""activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        }
        if (s.widths.isNotEmpty()) {
            append("<cols>")
            s.widths.forEachIndexed { i, w ->
                append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
            }
            append("</cols>")
        }
        append("<sheetData>")
        s.rows.forEachIndexed { ri, row ->
            val r = ri + 1
            append("""<row r="$r">""")
            row.forEachIndexed { ci, cell ->
                if (cell == null) return@forEachIndexed
                val ref = column(ci) + r
                val style = if (cell.bold) """ s="1"""" else ""
                val n = cell.number
                if (n != null && n.isFinite()) {
                    append("""<c r="$ref"$style><v>$n</v></c>""")
                } else if (cell.text != null) {
                    append("""<c r="$ref" t="inlineStr"$style><is><t xml:space="preserve">""")
                    esc(cell.text, this)
                    append("</t></is></c>")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun contentTypes(n: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..n) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun workbook(names: List<String>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        names.forEachIndexed { i, name ->
            append("""<sheet name="${esc(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(n: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..n) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("""<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
        """</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
        """<fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><sz val="11"/><name val="Arial"/></font></fonts>""" +
        """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
        """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
        """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>""" +
        """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
        """</styleSheet>"""
}
