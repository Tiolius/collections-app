package app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A simple Excel file (.xlsx) without libraries: .xlsx is a ZIP with several XML files.
 * Writing: each sheet — a header (bold, frozen) and rows; cells — text or number, null — empty.
 * Reading: sheets in order, cells — text (String), number (Double) or null; formulas — by their saved value.
 */
public final class Xlsx {

    /** Sheet: name (cut to 31 characters and made unique), headers, rows, column widths in characters. */
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
        // Styles: 0 — normal, 1 — bold (header)
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
                // The header is frozen when scrolling
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

    /** Column letters: 0 → A, 25 → Z, 26 → AA. */
    private static String column(int c) {
        StringBuilder s = new StringBuilder();
        for (c++; c > 0; c = (c - 1) / 26) s.insert(0, (char) ('A' + (c - 1) % 26));
        return s.toString();
    }

    /** Sheet name by Excel rules: without []:*?/\, up to 31 characters, not empty and unique ignoring case. */
    static String sheetName(String name, Set<String> used) {
        String base = name.replaceAll("[\\[\\]:*?/\\\\]", " ").replaceAll("\\s+", " ").replaceAll("^'+|'+$", "").trim();
        if (base.isEmpty()) base = I18n.t("export.sheet");
        if (base.length() > 31) base = base.substring(0, 31).trim();
        String n = base;
        for (int i = 2; used.contains(n.toLowerCase(Locale.ROOT)); i++) {
            String suffix = " (" + i + ")";
            n = (base.length() + suffix.length() > 31 ? base.substring(0, 31 - suffix.length()).trim() : base) + suffix;
        }
        used.add(n.toLowerCase(Locale.ROOT));
        return n;
    }

    /** Escaping for XML; control characters (except tab and line breaks) are dropped — XML does not allow them. */
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

    // ---------- reading ----------

    /** Largest size of one file inside .xlsx after unpacking (protection against a "ZIP bomb"). */
    static final int MAX_PART_BYTES = 64 * 1024 * 1024;
    /** Largest number of rows on a sheet and columns in a row. */
    static final int MAX_ROWS = 100_000, MAX_COLS = 200;

    /** The file does not look like .xlsx or is damaged. */
    public static class BadFile extends IOException {
        BadFile(String message) {
            super(message);
        }
    }

    /** Sheets of the file in order: name and all rows (the first as is, the caller chooses the headers). */
    public static List<Sheet> read(InputStream in) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String n = e.getName();
                // only the workbook, relationships, shared strings and sheets are needed
                if (!n.equals("xl/workbook.xml") && !n.equals("xl/_rels/workbook.xml.rels") && !n.equals("xl/sharedStrings.xml")
                        && !n.startsWith("xl/worksheets/")) continue;
                parts.put(n, readPart(zip));
            }
        } catch (java.util.zip.ZipException e) {
            throw new BadFile("not a zip");
        }
        if (!parts.containsKey("xl/workbook.xml")) throw new BadFile("no workbook");
        try {
            List<String> shared = sharedStrings(parts.get("xl/sharedStrings.xml"));
            Map<String, String> targets = relationships(parts.get("xl/_rels/workbook.xml.rels"));
            List<Sheet> res = new ArrayList<>();
            XMLStreamReader x = xml(parts.get("xl/workbook.xml"));
            while (x.hasNext())
                if (x.next() == XMLStreamReader.START_ELEMENT && x.getLocalName().equals("sheet")) {
                    String name = x.getAttributeValue(null, "name");
                    String rid = null;
                    for (int a = 0; a < x.getAttributeCount(); a++)
                        if (x.getAttributeLocalName(a).equals("id")) rid = x.getAttributeValue(a);
                    String target = rid == null ? null : targets.get(rid);
                    if (target == null) continue;
                    // sheet path — from the xl/ folder or from the archive root ("/xl/worksheets/sheet1.xml")
                    String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                    byte[] sheet = parts.get(path);
                    if (sheet != null) res.add(new Sheet(name == null ? "" : name, List.of(), rows(sheet, shared), List.of()));
                }
            return res;
        } catch (XMLStreamException | RuntimeException e) {
            throw new BadFile("bad xml");
        }
    }

    private static byte[] readPart(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > MAX_PART_BYTES) throw new BadFile("too large");
        }
        return out.toByteArray();
    }

    /** XML parsing without DTD and external entities (the file comes from the user). */
    private static XMLStreamReader xml(byte[] data) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f.createXMLStreamReader(new ByteArrayInputStream(data));
    }

    /** Workbook relationships: Id → sheet path. */
    private static Map<String, String> relationships(byte[] data) throws XMLStreamException {
        Map<String, String> res = new HashMap<>();
        if (data == null) return res;
        XMLStreamReader x = xml(data);
        while (x.hasNext())
            if (x.next() == XMLStreamReader.START_ELEMENT && x.getLocalName().equals("Relationship"))
                res.put(x.getAttributeValue(null, "Id"), x.getAttributeValue(null, "Target"));
        return res;
    }

    /** Shared strings of the workbook by number: text of all <t> of the string, except phonetic hints (<rPh>). */
    private static List<String> sharedStrings(byte[] data) throws XMLStreamException {
        List<String> res = new ArrayList<>();
        if (data == null) return res;
        XMLStreamReader x = xml(data);
        StringBuilder b = null;
        int phonetic = 0;
        while (x.hasNext()) {
            int ev = x.next();
            if (ev == XMLStreamReader.START_ELEMENT) {
                switch (x.getLocalName()) {
                    case "si" -> b = new StringBuilder();
                    case "rPh" -> phonetic++;
                    case "t" -> { if (b != null && phonetic == 0) b.append(x.getElementText()); }
                    default -> { }
                }
            } else if (ev == XMLStreamReader.END_ELEMENT) {
                if (x.getLocalName().equals("rPh")) phonetic--;
                else if (x.getLocalName().equals("si") && b != null) res.add(b.toString());
            }
        }
        return res;
    }

    /** Sheet rows: cells by column number (from the "B7" address), gaps — null; empty rows at the end are not included. */
    private static List<List<Object>> rows(byte[] data, List<String> shared) throws XMLStreamException {
        List<List<Object>> res = new ArrayList<>();
        XMLStreamReader x = xml(data);
        List<Object> row = null;
        String type = null, ref = null, value = null;
        StringBuilder inline = null;
        int next = 0; // column of the next cell without an address
        while (x.hasNext()) {
            int ev = x.next();
            if (ev == XMLStreamReader.START_ELEMENT) {
                switch (x.getLocalName()) {
                    case "row" -> {
                        String r = x.getAttributeValue(null, "r");
                        int n = r == null ? res.size() + 1 : Integer.parseInt(r);
                        if (n > MAX_ROWS) return res;
                        while (res.size() < n - 1) res.add(new ArrayList<>()); // skipped empty rows
                        row = new ArrayList<>();
                        next = 0;
                    }
                    case "c" -> {
                        type = x.getAttributeValue(null, "t");
                        ref = x.getAttributeValue(null, "r");
                        value = null;
                        inline = null;
                    }
                    case "v" -> value = x.getElementText();
                    case "is" -> inline = new StringBuilder();
                    case "t" -> { if (inline != null) inline.append(x.getElementText()); }
                    default -> { }
                }
            } else if (ev == XMLStreamReader.END_ELEMENT) {
                switch (x.getLocalName()) {
                    case "c" -> {
                        int col = ref == null ? next : column(ref);
                        next = col + 1;
                        if (row != null && col < MAX_COLS) {
                            while (row.size() <= col) row.add(null);
                            row.set(col, cellValue(type, value, inline, shared));
                        }
                    }
                    case "row" -> {
                        if (row != null) res.add(row);
                        row = null;
                    }
                    default -> { }
                }
            }
        }
        while (!res.isEmpty() && res.get(res.size() - 1).stream().allMatch(v -> v == null)) res.remove(res.size() - 1);
        return res;
    }

    /** Column number from a cell address: "A1" → 0, "AB12" → 27. */
    static int column(String ref) {
        int n = 0;
        for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++)
            n = n * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
        return n - 1;
    }

    /** Cell value: shared string (s), inline string (inlineStr, str), boolean (b — "1"/"0"), number; error — null. */
    private static Object cellValue(String type, String value, StringBuilder inline, List<String> shared) {
        if ("inlineStr".equals(type)) return inline == null ? null : inline.toString();
        if (value == null) return null;
        if ("s".equals(type)) {
            int i = Integer.parseInt(value.trim());
            return i >= 0 && i < shared.size() ? shared.get(i) : null;
        }
        if ("str".equals(type) || "b".equals(type)) return value;
        if ("e".equals(type)) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
