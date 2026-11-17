package export;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a simple paginated, colored table to a .pdf file using only the
 * JDK — hand-rolled low-level PDF (objects, xref table, trailer), no
 * PDFBox/iText/etc. Uses the standard (non-embedded) Helvetica / Helvetica-
 * Bold base-14 fonts, so every PDF viewer can render it without any font
 * files being bundled.
 *
 * <p>Not a general-purpose PDF API — one table, one page size (US Letter,
 * landscape), automatic pagination and per-column word wrapping, and a
 * solid background rectangle per row so status coloring matches the
 * on-screen Logs table. Good enough for a few thousand exported rows.
 */
public final class PdfTableWriter {

    private PdfTableWriter() {}

    private static final float PAGE_W = 792f; // Letter landscape
    private static final float PAGE_H = 612f;
    private static final float MARGIN = 30f;
    private static final float HEADER_FONT_SIZE = 9f;
    private static final float BODY_FONT_SIZE = 8f;
    private static final float LINE_HEIGHT = 10.5f;
    private static final float ROW_PADDING = 3f;

    public static void write(File outFile, String title, String[] headers, float[] columnWidthPts,
            List<String[]> rows, List<String> rowFillHex) throws IOException {

        float tableWidth = 0;
        for (float w : columnWidthPts) tableWidth += w;
        float startX = MARGIN;
        float usableHeight = PAGE_H - 2 * MARGIN;

        List<String> pageContentStreams = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        float y = PAGE_H - MARGIN;

        y -= 14;
        content.append(textOp(title, startX, y, "F2", 12));
        y -= 18;

        // Header row
        y = drawRow(content, headers, columnWidthPts, startX, y, "D9D9D9", "F2", HEADER_FONT_SIZE, true);

        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            String fill = r < rowFillHex.size() ? rowFillHex.get(r) : null;

            List<List<String>> wrapped = new ArrayList<>();
            int maxLines = 1;
            for (int c = 0; c < headers.length; c++) {
                String val = c < row.length ? row[c] : "";
                List<String> lines = wrap(val, columnWidthPts[c], BODY_FONT_SIZE);
                wrapped.add(lines);
                maxLines = Math.max(maxLines, lines.size());
            }
            float rowHeight = maxLines * LINE_HEIGHT + ROW_PADDING * 2;

            if (y - rowHeight < MARGIN) {
                // Start a new page
                pageContentStreams.add(content.toString());
                content = new StringBuilder();
                y = PAGE_H - MARGIN;
                y = drawRow(content, headers, columnWidthPts, startX, y, "D9D9D9", "F2", HEADER_FONT_SIZE, true);
            }

            y = drawWrappedRow(content, wrapped, columnWidthPts, startX, y, fill, maxLines);
        }
        pageContentStreams.add(content.toString());

        writePdf(outFile, pageContentStreams);
    }

    private static float drawRow(StringBuilder content, String[] values, float[] widths, float startX, float y,
            String fillHex, String font, float fontSize, boolean bold) {
        float rowHeight = LINE_HEIGHT + ROW_PADDING * 2;
        if (fillHex != null) content.append(rectOp(startX, y - rowHeight, sum(widths), rowHeight, fillHex));
        float x = startX;
        for (int c = 0; c < values.length; c++) {
            content.append(textOp(clip(values[c], widths[c], fontSize), x + 3, y - ROW_PADDING - LINE_HEIGHT + 2, font, fontSize));
            x += widths[c];
        }
        return y - rowHeight;
    }

    private static float drawWrappedRow(StringBuilder content, List<List<String>> wrappedCols, float[] widths,
            float startX, float y, String fillHex, int maxLines) {
        float rowHeight = maxLines * LINE_HEIGHT + ROW_PADDING * 2;
        if (fillHex != null) content.append(rectOp(startX, y - rowHeight, sum(widths), rowHeight, fillHex));
        float x = startX;
        for (int c = 0; c < wrappedCols.size(); c++) {
            List<String> lines = wrappedCols.get(c);
            float ty = y - ROW_PADDING - LINE_HEIGHT + 2;
            for (String line : lines) {
                content.append(textOp(line, x + 3, ty, "F1", BODY_FONT_SIZE));
                ty -= LINE_HEIGHT;
            }
            x += widths[c];
        }
        return y - rowHeight;
    }

    private static float sum(float[] a) { float s = 0; for (float v : a) s += v; return s; }

    /** Very rough Helvetica average-char-width wrap (no AFM metrics table — good enough for pagination purposes). */
    private static List<String> wrap(String text, float widthPts, float fontSize) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) { lines.add(""); return lines; }
        float avgCharWidth = fontSize * 0.52f;
        int maxChars = Math.max(4, (int) ((widthPts - 6) / avgCharWidth));
        for (String raw : text.split("\n", -1)) {
            String remaining = raw;
            if (remaining.isEmpty()) { lines.add(""); continue; }
            while (remaining.length() > maxChars) {
                int breakAt = remaining.lastIndexOf(' ', maxChars);
                if (breakAt < maxChars / 2) breakAt = maxChars; // no good space — hard break
                lines.add(remaining.substring(0, breakAt).trim());
                remaining = remaining.substring(breakAt).trim();
                if (lines.size() > 20) { lines.add(remaining + " …(truncated)"); remaining = ""; break; }
            }
            if (!remaining.isEmpty() || lines.isEmpty()) lines.add(remaining);
        }
        return lines;
    }

    private static String clip(String text, float widthPts, float fontSize) {
        List<String> l = wrap(text, widthPts, fontSize);
        return l.isEmpty() ? "" : l.get(0);
    }

    private static String rectOp(float x, float y, float w, float h, String hex) {
        float[] rgb = hexToRgb(hex);
        return String.format(java.util.Locale.ROOT, "%.3f %.3f %.3f rg %.2f %.2f %.2f %.2f re f\n",
                rgb[0], rgb[1], rgb[2], x, y, w, h);
    }

    private static float[] hexToRgb(String hex) {
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);
        return new float[]{r / 255f, g / 255f, b / 255f};
    }

    private static String textOp(String text, float x, float y, String font, float size) {
        return String.format(java.util.Locale.ROOT, "0 g BT /%s %.1f Tf %.2f %.2f Td (%s) Tj ET\n",
                font, size, x, y, pdfEscape(text));
    }

    /** PDF literal-string escaping + best-effort ISO-8859-1 fallback for characters outside Latin-1 (no embedded Unicode font here). */
    private static String pdfEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 255) c = '?';
            if (c == '(' || c == ')' || c == '\\') sb.append('\\');
            if (c == '\n' || c == '\r') { sb.append(' '); continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    // ── Low-level PDF object/xref assembly ────────────────────────────────

    private static void writePdf(File outFile, List<String> pageContentStreams) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>(); // index = object number - 1
        int objCount = 0;

        write(buf, "%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n");

        int catalogObj = ++objCount;      // 1
        int pagesObj = ++objCount;        // 2
        int fontRegularObj = ++objCount;  // 3
        int fontBoldObj = ++objCount;     // 4

        int numPages = pageContentStreams.size();
        int firstPageObj = objCount + 1;
        // Each page uses 2 objects: the Page dict, then its Contents stream.
        List<Integer> pageObjIds = new ArrayList<>();
        List<Integer> contentObjIds = new ArrayList<>();
        for (int i = 0; i < numPages; i++) {
            pageObjIds.add(++objCount);
            contentObjIds.add(++objCount);
        }

        // Placeholder offsets array, filled as we write each object in order.
        int[] offsetByObjNum = new int[objCount + 1];

        offsetByObjNum[catalogObj] = buf.size();
        write(buf, obj(catalogObj, "<< /Type /Catalog /Pages " + pagesObj + " 0 R >>"));

        StringBuilder kids = new StringBuilder();
        for (int id : pageObjIds) kids.append(id).append(" 0 R ");
        offsetByObjNum[pagesObj] = buf.size();
        write(buf, obj(pagesObj, "<< /Type /Pages /Kids [ " + kids.toString().trim() + " ] /Count " + numPages + " >>"));

        offsetByObjNum[fontRegularObj] = buf.size();
        write(buf, obj(fontRegularObj, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"));

        offsetByObjNum[fontBoldObj] = buf.size();
        write(buf, obj(fontBoldObj, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>"));

        for (int i = 0; i < numPages; i++) {
            int pageObj = pageObjIds.get(i);
            int contentObj = contentObjIds.get(i);
            byte[] streamBytes = pageContentStreams.get(i).getBytes(StandardCharsets.ISO_8859_1);

            offsetByObjNum[pageObj] = buf.size();
            write(buf, obj(pageObj,
                    "<< /Type /Page /Parent " + pagesObj + " 0 R /MediaBox [0 0 " + (int) PAGE_W + " " + (int) PAGE_H + "] " +
                    "/Resources << /Font << /F1 " + fontRegularObj + " 0 R /F2 " + fontBoldObj + " 0 R >> >> " +
                    "/Contents " + contentObj + " 0 R >>"));

            offsetByObjNum[contentObj] = buf.size();
            write(buf, "" + contentObj + " 0 obj\n<< /Length " + streamBytes.length + " >>\nstream\n");
            buf.write(streamBytes);
            write(buf, "\nendstream\nendobj\n");
        }

        int xrefStart = buf.size();
        StringBuilder xref = new StringBuilder();
        xref.append("xref\n0 ").append(objCount + 1).append("\n");
        xref.append("0000000000 65535 f \n");
        for (int i = 1; i <= objCount; i++) {
            xref.append(String.format("%010d 00000 n \n", offsetByObjNum[i]));
        }
        write(buf, xref.toString());
        write(buf, "trailer\n<< /Size " + (objCount + 1) + " /Root " + catalogObj + " 0 R >>\nstartxref\n" + xrefStart + "\n%%EOF");

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            buf.writeTo(fos);
        }
    }

    private static String obj(int num, String dict) {
        return num + " 0 obj\n" + dict + "\nendobj\n";
    }

    private static void write(ByteArrayOutputStream buf, String s) throws IOException {
        buf.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
