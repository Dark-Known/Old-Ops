package export;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a simple single-sheet, single-table .xlsx file using only the JDK
 * (raw OOXML/zip — no Apache POI or other third-party library). Supports a
 * bold/shaded header row and a per-data-row background fill color, which is
 * all the Logs export needs to mirror the on-screen status coloring.
 *
 * <p>Not a general-purpose spreadsheet API — no formulas, no multiple
 * sheets, no shared-string table (cells are written as inline strings,
 * which is slightly larger on disk but far simpler to generate correctly).
 * Good enough for a few thousand exported rows.
 */
public final class XlsxWriter {

    private XlsxWriter() {}

    /**
     * @param outFile      destination .xlsx path
     * @param headers      column header labels, left to right
     * @param columnWidths approximate character width per column (same length as headers)
     * @param rows         each element is one row's cell values, same column count as headers
     * @param rowFillHex   parallel to rows; a 6-digit RRGGBB hex string for that row's
     *                     background, or null for no fill (e.g. the header uses its own fill)
     */
    public static void write(File outFile, String[] headers, int[] columnWidths,
            List<String[]> rows, List<String> rowFillHex) throws IOException {

        int colCount = headers.length;

        // ── styles.xml: one style per distinct fill color, plus header + default ──
        java.util.LinkedHashMap<String, Integer> fillStyleIndex = new java.util.LinkedHashMap<>();
        // style 0 = default (no fill), style 1 = header (bold + gray fill)
        int nextStyle = 2;
        for (String hex : rowFillHex) {
            if (hex == null) continue;
            fillStyleIndex.computeIfAbsent(hex, k -> 0); // placeholder, assign below in order
        }
        int idx = 2;
        java.util.LinkedHashMap<String, Integer> assigned = new java.util.LinkedHashMap<>();
        for (String hex : fillStyleIndex.keySet()) {
            assigned.put(hex, idx++);
        }

        String stylesXml = buildStylesXml(assigned);
        String sheetXml = buildSheetXml(headers, columnWidths, rows, rowFillHex, assigned);
        String workbookXml = buildWorkbookXml();

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)))) {
            writeEntry(zos, "[Content_Types].xml", CONTENT_TYPES);
            writeEntry(zos, "_rels/.rels", RELS);
            writeEntry(zos, "xl/workbook.xml", workbookXml);
            writeEntry(zos, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            writeEntry(zos, "xl/styles.xml", stylesXml);
            writeEntry(zos, "xl/worksheets/sheet1.xml", sheetXml);
        }
    }

    private static void writeEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    // Strip control chars invalid in XML 1.0 (other than tab/lf/cr)
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String colLetter(int zeroBasedIndex) {
        StringBuilder sb = new StringBuilder();
        int n = zeroBasedIndex;
        do {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    private static String buildWorkbookXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Logs\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
    }

    private static String buildStylesXml(java.util.LinkedHashMap<String, Integer> assignedFills) {
        StringBuilder fills = new StringBuilder();
        fills.append("<fill><patternFill patternType=\"none\"/></fill>"); // 0
        fills.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD9D9D9\"/><bgColor indexed=\"64\"/></patternFill></fill>"); // 1 = header gray
        for (String hex : assignedFills.keySet()) {
            fills.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF").append(hex)
                    .append("\"/><bgColor indexed=\"64\"/></patternFill></fill>");
        }

        StringBuilder xfs = new StringBuilder();
        // xf 0: default
        xfs.append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>");
        // xf 1: header — bold font(id1), gray fill(id1)
        xfs.append("<xf numFmtId=\"0\" fontId=\"1\" fillId=\"1\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>");
        // xf 2..N: one per distinct row fill color, using default font(id0)
        int fillId = 2;
        for (int i = 0; i < assignedFills.size(); i++) {
            xfs.append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"").append(fillId++)
                    .append("\" borderId=\"0\" xfId=\"0\" applyFill=\"1\"/>");
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<fonts count=\"2\">" +
                "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
                "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
                "</fonts>" +
                "<fills count=\"" + (2 + assignedFills.size()) + "\">" + fills + "</fills>" +
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"" + (2 + assignedFills.size()) + "\">" + xfs + "</cellXfs>" +
                "</styleSheet>";
    }

    private static String buildSheetXml(String[] headers, int[] columnWidths, List<String[]> rows,
            List<String> rowFillHex, java.util.LinkedHashMap<String, Integer> assignedFills) {
        StringBuilder cols = new StringBuilder("<cols>");
        for (int i = 0; i < headers.length; i++) {
            double w = columnWidths != null && i < columnWidths.length ? columnWidths[i] : 20;
            cols.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                    .append("\" width=\"").append(w).append("\" customWidth=\"1\"/>");
        }
        cols.append("</cols>");

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
          .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
          .append(cols)
          .append("<sheetData>");

        // Header row (row 1)
        sb.append("<row r=\"1\">");
        for (int c = 0; c < headers.length; c++) {
            sb.append("<c r=\"").append(colLetter(c)).append("1\" t=\"inlineStr\" s=\"1\"><is><t>")
              .append(esc(headers[c])).append("</t></is></c>");
        }
        sb.append("</row>");

        // Data rows (row 2+)
        for (int r = 0; r < rows.size(); r++) {
            String[] rowVals = rows.get(r);
            String fillHex = r < rowFillHex.size() ? rowFillHex.get(r) : null;
            int style = fillHex != null ? assignedFills.getOrDefault(fillHex, 0) : 0;
            int excelRow = r + 2;
            sb.append("<row r=\"").append(excelRow).append("\">");
            for (int c = 0; c < headers.length; c++) {
                String val = c < rowVals.length ? rowVals[c] : "";
                sb.append("<c r=\"").append(colLetter(c)).append(excelRow).append("\" t=\"inlineStr\"");
                if (style != 0) sb.append(" s=\"").append(style).append("\"");
                sb.append("><is><t xml:space=\"preserve\">").append(esc(val)).append("</t></is></c>");
            }
            sb.append("</row>");
        }

        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    private static final String CONTENT_TYPES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "</Types>";

    private static final String RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>";

    private static final String WORKBOOK_RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>";
}
