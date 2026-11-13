package app;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loading collections from an Excel file ("Settings" → "Import" → "Load from Excel"): each sheet is a collection named after
 * the sheet. If a collection with this name already exists (ignoring case), data goes into it: items with the same name
 * are updated (only with filled cells), the rest are added; its type does not change. Columns are recognised by headers — the same that Excel export writes (in Russian or English),
 * "Название" is required; other columns and fields the collection type does not have are skipped. Values that make no sense
 * (a status not in the list, a rating outside 0.5…5, a date that is not a date) are not loaded, the item is.
 * All in one transaction: on an error nothing is created.
 */
@Component
public class XlsxImport {

    /**
     * Result: how many collections were created and extended, items added and updated, the first collection (for a link),
     * skipped columns (headers).
     */
    public record Result(int created, int updated, int added, int changed, long firstCollection, List<String> skipped) {
        public int collections() {
            return created + updated;
        }
    }

    /** The "Описание" column is not a type field: every item has a description. */
    static final String NAME = "name", DESCRIPTION = "description";

    private final Repo repo;
    private final TransactionTemplate tx;

    public XlsxImport(Repo repo, TransactionTemplate tx) {
        this.repo = repo;
        this.tx = tx;
    }

    /**
     * Loads the file's sheets as new collections of the user. kind — the type for all collections; empty — for each sheet
     * the type with the most fields from the sheet's columns is chosen (on a tie — the one with fewer extra fields).
     */
    public Result load(long uid, InputStream in, String kind) throws IOException {
        List<Xlsx.Sheet> sheets;
        try {
            sheets = Xlsx.read(in);
        } catch (Xlsx.BadFile e) {
            throw Fail.badRequest("excel.bad");
        }
        List<Kind> kinds = repo.kinds(uid);
        String chosen = kind == null ? "" : kind.trim();
        if (!chosen.isEmpty() && kinds.stream().noneMatch(k -> k.name().equals(chosen)))
            throw Fail.badRequest("coll.kindRequired");
        if (kinds.isEmpty()) throw Fail.badRequest("coll.kindRequired");
        Map<String, String> labels = labels(kinds);
        // sheets with a "Название" column and at least one row under the header
        List<Object[]> todo = new ArrayList<>(); // [sheet, header row number, columns: number → field]
        for (Xlsx.Sheet sh : sheets) {
            List<List<Object>> rows = sh.rows();
            for (int r = 0; r < rows.size() && r < 20; r++) {
                Map<Integer, String> cols = columns(rows.get(r), labels);
                if (cols.containsValue(NAME)) {
                    if (r + 1 < rows.size()) todo.add(new Object[] {sh, r, cols});
                    break;
                }
            }
        }
        if (todo.isEmpty()) throw Fail.badRequest("excel.noName");
        return tx.execute(status -> {
            int created = 0, updated = 0, added = 0, changed = 0;
            long first = 0;
            Set<String> skipped = new LinkedHashSet<>();
            for (Object[] t : todo) {
                Xlsx.Sheet sh = (Xlsx.Sheet) t[0];
                int headerRow = (Integer) t[1];
                @SuppressWarnings("unchecked")
                Map<Integer, String> cols = (Map<Integer, String>) t[2];
                String name = sh.name().isBlank() ? "Excel" : sh.name().trim();
                // own collection with the same name — data goes into it, with its type
                Entry existing = repo.collections(uid).stream().filter(c -> same(c.name(), name)).findFirst().orElse(null);
                String k = existing != null && kinds.stream().anyMatch(x -> x.name().equals(existing.kind())) ? existing.kind()
                        : chosen.isEmpty() ? bestKind(kinds, cols.values()) : chosen;
                Kind target = kinds.stream().filter(x -> x.name().equals(k)).findFirst().orElseThrow();
                Set<String> fields = target.fields();
                List<Object> header = sh.rows().get(headerRow);
                // custom fields of the type — by field name (as in the export): column number → field
                Map<Integer, Kind.Field> customCols = new HashMap<>();
                for (int c = 0; c < header.size(); c++) {
                    String h = text(header.get(c));
                    if (h == null) continue;
                    String f = cols.get(c);
                    if (f == null)
                        for (Kind.Field cf : target.customs())
                            if (key(cf.label()).equals(key(h)) && !customCols.containsValue(cf)) {
                                customCols.put(c, cf);
                                break;
                            }
                    if (customCols.containsKey(c)) continue;
                    if (f == null || !(f.equals(NAME) || f.equals(DESCRIPTION) || fields.contains(f))) skipped.add(h);
                }
                long cid;
                if (existing != null) {
                    cid = existing.id();
                    updated++;
                } else {
                    cid = repo.createCollection(uid);
                    repo.saveCollection(uid, cid, name, k, null, false);
                    created++;
                }
                if (first == 0) first = cid;
                // collection items by name; several with one name — in order: the n-th row of the file
                // with this name updates the n-th item (loading the same file again creates no duplicates)
                Map<String, java.util.ArrayDeque<Entry>> byName = new HashMap<>();
                if (existing != null)
                    for (Entry it : repo.items(cid, "name", false, Map.of()))
                        byName.computeIfAbsent(nameKey(it.name()), x -> new java.util.ArrayDeque<>()).add(it);
                for (List<Object> row : sh.rows().subList(headerRow + 1, sh.rows().size())) {
                    Repo.ItemForm form = form(row, cols);
                    if (form == null) continue;
                    form = statusOf(form, target);
                    java.util.ArrayDeque<Entry> same = byName.get(nameKey(form.name()));
                    Entry old = same == null ? null : same.poll();
                    long itemId;
                    if (old != null) {
                        itemId = old.id();
                        repo.saveItem(uid, itemId, k, keepOld(form, old), Map.of(), Set.of());
                        changed++;
                    } else {
                        itemId = repo.createItem(cid);
                        repo.saveItem(uid, itemId, k, form, Map.of(), Set.of());
                        added++;
                    }
                    // custom fields: empty cells do not change the value, invalid values are skipped
                    for (Map.Entry<Integer, Kind.Field> cc : customCols.entrySet()) {
                        Object cell = cc.getKey() < row.size() ? row.get(cc.getKey()) : null;
                        String v = cc.getValue().type().equals("bool") ? bool(cell) : text(cell);
                        if (v == null) continue;
                        try {
                            repo.setCustom(itemId, cc.getValue().id(), v);
                        } catch (org.springframework.web.server.ResponseStatusException e) {
                            // invalid value — skipped
                        }
                    }
                }
            }
            return new Result(created, updated, added, changed, first, List.copyOf(skipped));
        });
    }

    /** The same collection or item name: without spaces at the edges and ignoring case. */
    static boolean same(String a, String b) {
        return nameKey(a).equals(nameKey(b));
    }

    private static String nameKey(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(I18n.RU);
    }

    /**
     * Form for updating an item: empty and invalid cells do not change the value. Platform, format, status and price
     * are always written by saveItem (empty clears), so the previous values are taken for them; name — the previous one.
     */
    private static Repo.ItemForm keepOld(Repo.ItemForm f, Entry old) {
        return new Repo.ItemForm(old.name(), f.platform() != null ? f.platform() : old.platform(),
                f.format() != null ? f.format() : old.format(), f.status() != null ? f.status() : old.status(),
                f.price() != null ? f.price() : old.price(), f.available(), f.rating(), f.oldgames(), f.purchased(),
                f.tags(), f.edition(), f.notes(), f.description());
    }

    /**
     * Column headers (ignoring case and extra spaces) → field: the user's own field labels in types
     * (that is how the export writes them), standard labels in both languages and field keys.
     */
    private static Map<String, String> labels(List<Kind> kinds) {
        Map<String, String> res = new HashMap<>();
        for (Kind k : kinds)
            for (Kind.Field f : k.defs())
                if (f.role() != null && f.label() != null) res.putIfAbsent(key(f.label()), f.role());
        for (Locale loc : List.of(I18n.RU, Locale.ENGLISH)) {
            res.put(key(I18n.t(loc, "field.name")), NAME);
            res.put(key(I18n.t(loc, "field.description")), DESCRIPTION);
            res.put(key(I18n.t(loc, "field.priceRub")), "price");
            res.put(key(I18n.t(loc, "field.statusShort")), "status");
            Repo.FIELDS.forEach((f, label) -> res.put(key(I18n.t(loc, label)), f));
        }
        Repo.FIELDS.keySet().forEach(f -> res.put(f, f));
        // standard labels take priority over own ones: an own "Цена" label of one type must not be confused with a column of another field
        res.put(NAME, NAME);
        res.put(DESCRIPTION, DESCRIPTION);
        return res;
    }

    private static String key(String header) {
        return header.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Columns of the header row: number → field (a repeated header — the first column is taken). */
    private static Map<Integer, String> columns(List<Object> header, Map<String, String> labels) {
        Map<Integer, String> res = new LinkedHashMap<>();
        for (int c = 0; c < header.size(); c++) {
            String h = text(header.get(c));
            String f = h == null ? null : labels.get(key(h));
            if (f != null && !res.containsValue(f)) res.put(c, f);
        }
        return res;
    }

    /** The type with the most fields from the columns; on a tie — fewer extra fields; then — alphabetically. */
    static String bestKind(List<Kind> kinds, java.util.Collection<String> columns) {
        Kind best = null;
        int bestHit = -1, bestExtra = Integer.MAX_VALUE;
        for (Kind k : kinds) {
            int hit = 0;
            for (String f : k.fields()) if (columns.contains(f)) hit++;
            int extra = k.fields().size() - hit;
            if (hit > bestHit || (hit == bestHit && extra < bestExtra)) {
                best = k;
                bestHit = hit;
                bestExtra = extra;
            }
        }
        return best.name();
    }

    /** Item fields from a row; no name — null (the row is skipped). No column — null: the field is not filled in. */
    private static Repo.ItemForm form(List<Object> row, Map<Integer, String> cols) {
        Map<String, Object> v = new HashMap<>();
        cols.forEach((c, f) -> v.put(f, c < row.size() ? row.get(c) : null));
        String name = text(v.get(NAME));
        if (name == null) return null;
        List<String> available = available(v.get("available"));
        return new Repo.ItemForm(name, text(v.get("platform")), text(v.get("format")), status(v.get("status")),
                price(v.get("price")), available, rating(v.get("rating")), oldGames(v.get("oldgames")),
                date(v.get("purchased")),
                cut(text(v.get("tags")), Repo.MAX_TAGS), cut(text(v.get("edition")), Repo.MAX_EDITION),
                cut(text(v.get("notes")), Repo.MAX_NOTES), cut(text(v.get(DESCRIPTION)), Repo.MAX_DESCRIPTION));
    }

    /** Cell text without spaces at the edges; a number — without ".0" (12 → "12"); empty — null. */
    static String text(Object v) {
        if (v == null) return null;
        String s = v instanceof Double d ? BigDecimal.valueOf(d).stripTrailingZeros().toPlainString() : v.toString();
        s = s.replace("\r\n", "\n").trim();
        return s.isEmpty() ? null : s;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max).trim();
    }

    /** Yes/no from a cell: "Есть", "Да", "Yes", "1", "true" — "1"; "Нет", "No", "0", "false" — "0"; empty and anything else — null. */
    static String bool(Object v) {
        String s = text(v);
        if (s == null) return null;
        String l = s.toLowerCase(Locale.ROOT);
        if (List.of("1", "true", "да", "есть", "yes", "+").contains(l)) return "1";
        if (List.of("0", "false", "нет", "no", "-").contains(l)) return "0";
        return null;
    }

    /** Status as is; checked against the status list of the collection type on loading (statusOf). */
    static String status(Object v) {
        return text(v);
    }

    /** Form with a status from the type list (ignoring case — as in the list); a status not in the list is not loaded. */
    static Repo.ItemForm statusOf(Repo.ItemForm f, Kind kind) {
        if (f.status() == null) return f;
        String st = kind == null ? null
                : kind.statuses().stream().filter(x -> x.equalsIgnoreCase(f.status())).findFirst().orElse(null);
        return new Repo.ItemForm(f.name(), f.platform(), f.format(), st, f.price(), f.available(), f.rating(), f.oldgames(),
                f.purchased(), f.tags(), f.edition(), f.notes(), f.description());
    }

    /** Rating in stars (0.5…5, as in the export) → 1…10 halves; anything else is not loaded. */
    static Integer rating(Object v) {
        Double d = number(v);
        if (d == null) return null;
        long r = Math.round(d * 2);
        return r >= 1 && r <= 10 ? (int) r : null;
    }

    /** Price — a whole number of roubles, not less than 0 ("1 500 ₽" too); anything else is not loaded. */
    static Integer price(Object v) {
        Double d = v instanceof String s ? number(s.replaceAll("[\\s\\u00a0₽]|руб\\.?", "")) : number(v);
        return d == null || d < 0 || d > Integer.MAX_VALUE ? null : (int) Math.round(d);
    }

    private static Double number(Object v) {
        if (v instanceof Double d) return d;
        String s = text(v);
        if (s == null) return null;
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Available: "Есть", "Yes", "да", "1", "+" — yes; anything else — no (as in the item window: "0", "1"); empty — null (do not change). */
    static List<String> available(Object v) {
        String s = text(v);
        if (s == null) return null;
        boolean yes = s != null && List.of("есть", "yes", "да", "true", "1", "+", "✓", "✔").contains(s.toLowerCase(Locale.ROOT));
        return yes ? List.of("0", "1") : List.of("0");
    }

    /** Purchase date: YYYY-MM-DD, DD.MM.YYYY or an Excel date (number of days); empty or not a date — null (do not change). */
    static String date(Object v) {
        if (v instanceof Double d) {
            // Excel dates — days since 30.12.1899
            return d >= 1 && d < 2958466 ? LocalDate.of(1899, 12, 30).plusDays(d.longValue()).toString() : null;
        }
        String s = text(v);
        if (s == null) return null;
        for (DateTimeFormatter f : List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d.M.uuuu")))
            try {
                return LocalDate.parse(s.length() > 10 && f == DateTimeFormatter.ISO_LOCAL_DATE ? s.substring(0, 10) : s, f).toString();
            } catch (java.time.format.DateTimeParseException e) {
                // next format
            }
        return null;
    }

    /** Old-Games.ru game number or address; anything else is not loaded. */
    static String oldGames(Object v) {
        String s = text(v);
        return s != null && OldGames.normalize(s) != null ? s : null;
    }
}
