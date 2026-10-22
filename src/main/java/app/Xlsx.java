package app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Запись простого файла Excel (.xlsx) без библиотек: .xlsx — это ZIP с несколькими XML-файлами.
 * Каждый лист — заголовок (жирный, закреплён) и строки; ячейки — текст или число, null — пустая.
 */
public final class Xlsx {

    /** Лист: название (обрезается до 31 символа и делается уникальным), заголовки, строки, ширина столбцов в символах. */
    public record Sheet(String name, List<String> headers, List<List<Object>> rows, List<Integer> widths) {}

    private Xlsx() {
    }

    public static void write(OutputStream out, List<Sheet> sheets) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        StringBuilder types = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        StringBuilder book = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder bookRels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        Set<String> used = new HashSet<>();
        for (int i = 1; i <= sheets.size(); i++) {
            Sheet sh = sheets.get(i - 1);
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            book.append("<sheet name=\"").append(esc(sheetName(sh.name(), used))).append("\" sheetId=\"").append(i)
                    .append("\" r:id=\"rId").append(i).append("\"/>");
            bookRels.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i).append(".xml\"/>");
            put(zip, "xl/worksheets/sheet" + i + ".xml", sheetXml(sh));
        }
        types.append("</Types>");
        book.append("</sheets></workbook>");
        bookRels.append("<Relationship Id=\"rId").append(sheets.size() + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
                .append("</Relationships>");
        put(zip, "[Content_Types].xml", types.toString());
        put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
        put(zip, "xl/workbook.xml", book.toString());
        put(zip, "xl/_rels/workbook.xml.rels", bookRels.toString());
        // Стили: 0 — обычный, 1 — жирный (заголовок)
        put(zip, "xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>");
        zip.finish();
    }

    private static String sheetXml(Sheet sh) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                // Заголовок закреплён при прокрутке
                + "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"
                + "</sheetView></sheetViews><cols>");
        for (int c = 0; c < sh.widths().size(); c++)
            x.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                    .append(sh.widths().get(c)).append("\" customWidth=\"1\"/>");
        x.append("</cols><sheetData>");
        x.append("<row r=\"1\">");
        for (int c = 0; c < sh.headers().size(); c++) cell(x, c, 1, sh.headers().get(c), 1);
        x.append("</row>");
        int r = 2;
        for (List<Object> row : sh.rows()) {
            x.append("<row r=\"").append(r).append("\">");
            for (int c = 0; c < row.size(); c++) cell(x, c, r, row.get(c), 0);
            x.append("</row>");
            r++;
        }
        return x.append("</sheetData></worksheet>").toString();
    }

    private static void cell(StringBuilder x, int col, int row, Object v, int style) {
        if (v == null || (v instanceof String s && s.isEmpty())) return;
        String ref = column(col) + row;
        String st = style == 0 ? "" : " s=\"" + style + "\"";
        if (v instanceof Number n)
            x.append("<c r=\"").append(ref).append('"').append(st).append("><v>").append(n).append("</v></c>");
        else
            x.append("<c r=\"").append(ref).append('"').append(st).append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(esc(v.toString())).append("</t></is></c>");
    }

    /** Буквы столбца: 0 → A, 25 → Z, 26 → AA. */
    private static String column(int c) {
        StringBuilder s = new StringBuilder();
        for (c++; c > 0; c = (c - 1) / 26) s.insert(0, (char) ('A' + (c - 1) % 26));
        return s.toString();
    }

    /** Название листа по правилам Excel: без []:*?/\, до 31 символа, не пустое и уникальное без учёта регистра. */
    static String sheetName(String name, Set<String> used) {
        String base = name.replaceAll("[\\[\\]:*?/\\\\]", " ").replaceAll("\\s+", " ").replaceAll("^'+|'+$", "").trim();
        if (base.isEmpty()) base = "Лист";
        if (base.length() > 31) base = base.substring(0, 31).trim();
        String n = base;
        for (int i = 2; used.contains(n.toLowerCase(Locale.ROOT)); i++) {
            String suffix = " (" + i + ")";
            n = (base.length() + suffix.length() > 31 ? base.substring(0, 31 - suffix.length()).trim() : base) + suffix;
        }
        used.add(n.toLowerCase(Locale.ROOT));
        return n;
    }

    /** Экранирование для XML; управляющие символы (кроме таба и переводов строк) выбрасываются — XML их не допускает. */
    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> { if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') b.append(ch); }
            }
        }
        return b.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
