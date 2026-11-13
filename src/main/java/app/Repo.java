package app;

import java.io.IOException;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;

/**
 * All database work. table — "collections" or "items".
 * Almost all methods take uid — the id of the current user: other users' collections, items and types are not visible (404).
 */
@Repository
public class Repo {

    // in queries the collections table has the alias c; kind — the type name (for display and finding a type by name)
    private static final String COLLECTION_COLS =
            "id, name, EXISTS (SELECT 1 FROM collection_pictures cp WHERE cp.collection_id = c.id) AS has_image, "
            + "(SELECT k.name FROM kinds k WHERE k.id = c.kind_id) AS kind, "
            + "(SELECT cp.hash FROM collection_pictures cp WHERE cp.collection_id = c.id) AS pics";

    /**
     * Value of the item field with role role (a FIELDS key) — a subquery to item_values: the field with this role in the item's
     * collection type; item — the alias of the items table in the query. role — a key from FIELDS (no user input goes into SQL;
     * checked by the key form: FIELDS is not filled yet when ITEM_COLS is created).
     */
    static String val(String role, String item) {
        if (!role.matches("[a-z]+") || !item.matches("[a-z]+")) throw new IllegalArgumentException(role);
        return "(SELECT v.value FROM item_values v JOIN kind_fields f ON f.id = v.field_id JOIN collections vc ON vc.id = "
                + item + ".collection_id WHERE v.item_id = " + item + ".id AND f.kind_id = vc.kind_id AND f.role = '" + role + "')";
    }

    private static final String ITEM_COLS = "id, name, collection_id, "
            + val("platform", "items") + " AS platform, " + val("format", "items") + " AS format, "
            + val("status", "items") + " AS status, " + val("price", "items") + " AS price, "
            + "EXISTS (SELECT 1 FROM item_pictures ip WHERE ip.item_id = items.id) AS has_image, "
            + "(SELECT COUNT(*) FROM item_pictures ip WHERE ip.item_id = items.id) AS image_count, "
            + "(SELECT d.text FROM item_descriptions d WHERE d.item_id = items.id) AS description, "
            + val("available", "items") + " IS NOT NULL AS available, "
            + val("rating", "items") + " AS rating, " + val("oldgames", "items") + " AS oldgames, "
            + val("purchased", "items") + " AS purchased, " + val("tags", "items") + " AS tags, "
            + val("edition", "items") + " AS edition, " + val("notes", "items") + " AS notes, "
            // picture hashes in order — /img/<hash> addresses (browser cache by content)
            + "(SELECT group_concat(ip.hash, ',' ORDER BY ip.pos) FROM item_pictures ip WHERE ip.item_id = items.id) AS pics";

    /** Alphabetical comparison for Russian: ignoring case, "Ё" next to "Е", Latin before Cyrillic. */
    public static Comparator<String> alphabetical() {
        Collator c = Collator.getInstance(Locale.forLanguageTag("ru"));
        c.setStrength(Collator.SECONDARY);
        return c::compare;
    }

    /**
     * The built-in set of item fields (except the name, which always exists): key → label.
     * A collection type chooses fields from this set.
     */
    public static final Map<String, String> FIELDS = new LinkedHashMap<>();
    static {
        // label — a text key (messages*.properties)
        FIELDS.put("platform", "field.platform");
        FIELDS.put("format", "field.format");
        FIELDS.put("edition", "field.edition"); // region / edition
        FIELDS.put("status", "field.status");
        FIELDS.put("rating", "field.rating"); // 1…10 half stars (5 stars)
        FIELDS.put("price", "field.price");
        FIELDS.put("purchased", "field.purchased"); // purchase date YYYY-MM-DD
        FIELDS.put("available", "field.available"); // "in stock" marks; by default — not in stock
        FIELDS.put("tags", "field.tags"); // tags separated by ", "
        FIELDS.put("notes", "field.notes"); // notes, not shown in the item table
        FIELDS.put("oldgames", "field.oldgames"); // link to the game page on Old-Games.ru
    }

    /** Field kind by role (kind_fields.type): how the field is entered, shown and stored. */
    public static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("platform", "text"), Map.entry("format", "text"), Map.entry("edition", "text"),
            Map.entry("status", "select"), Map.entry("rating", "rating"), Map.entry("price", "money"),
            Map.entry("purchased", "date"), Map.entry("available", "bool"), Map.entry("tags", "tags"),
            Map.entry("notes", "longtext"), Map.entry("oldgames", "link"));

    /** Kinds of custom fields (defined by the user in a type): key → label (a text key). */
    public static final Map<String, String> CUSTOM_TYPES = new LinkedHashMap<>();
    static {
        CUSTOM_TYPES.put("text", "ctype.text");
        CUSTOM_TYPES.put("longtext", "ctype.longtext");
        CUSTOM_TYPES.put("number", "ctype.number");
        CUSTOM_TYPES.put("date", "ctype.date");
        CUSTOM_TYPES.put("bool", "ctype.bool");
        CUSTOM_TYPES.put("select", "ctype.select");
    }

    /** Largest number of custom fields per type; value lengths of custom fields: text and long text. */
    public static final int MAX_CUSTOM = 30, MAX_TEXT = 500, MAX_LONGTEXT = 2000;

    /** Collection type profiles: key → label (a text key). The profile sets the fitting import sources. */
    public static final Map<String, String> PROFILES = new LinkedHashMap<>();
    static {
        PROFILES.put("games", "profile.games");
        PROFILES.put("music", "profile.music");
        PROFILES.put("books", "profile.books");
        PROFILES.put("other", "profile.other");
    }

    /** Play statuses of the initial game types (each type has its own list, configured in the type). */
    public static final List<String> GAME_STATUSES = List.of("Completed", "Unfinished");

    private final JdbcTemplate jdbc;
    /** Item and collection pictures — content-addressed storage (ImageFiles). */
    private final ImageFiles files;

    public Repo(JdbcTemplate jdbc, ImageFiles files) {
        this.jdbc = jdbc;
        this.files = files;
    }

    private static RowMapper<Entry> mapper(boolean isItem, boolean withCount) {
        return (rs, i) -> new Entry(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getBoolean("has_image"),
                isItem ? rs.getLong("collection_id") : null,
                withCount ? rs.getInt("cnt") : 0,
                isItem ? null : rs.getString("kind"),
                isItem ? rs.getString("platform") : null,
                isItem ? rs.getString("format") : null,
                isItem ? rs.getString("status") : null,
                isItem ? price(rs) : null,
                isItem ? rs.getString("description") : null,
                isItem ? rs.getBoolean("available") : null,
                isItem ? rs.getInt("image_count") : 0,
                isItem ? rating(rs) : null,
                isItem ? rs.getString("oldgames") : null,
                isItem ? rs.getString("purchased") : null,
                isItem ? rs.getString("tags") : null,
                isItem ? rs.getString("edition") : null,
                isItem ? rs.getString("notes") : null,
                rs.getString("pics"));
    }

    /** Rating (1…10 half stars) or null. */
    private static Integer rating(java.sql.ResultSet rs) throws java.sql.SQLException {
        int v = rs.getInt("rating");
        return rs.wasNull() ? null : v;
    }

    /** Price or null (the SQLite driver cannot getObject(.., Integer.class) for NULL). */
    private static Integer price(java.sql.ResultSet rs) throws java.sql.SQLException {
        int v = rs.getInt("price");
        return rs.wasNull() ? null : v;
    }

    // ---------- collection types ----------

    /** All the user's types alphabetically (with fields in order and list values). */
    public List<Kind> kinds(long uid) {
        return loadKinds("k.user_id = ?", uid).stream().sorted(Comparator.comparing(Kind::name, alphabetical())).toList();
    }

    public Kind kind(long uid, long id) {
        List<Kind> list = loadKinds("k.user_id = ? AND k.id = ?", uid, id);
        if (list.isEmpty()) throw Fail.notFound();
        return list.get(0);
    }

    /** The user's type by name or null. */
    public Kind kindByName(long uid, String name) {
        if (name == null) return null;
        List<Kind> list = loadKinds("k.user_id = ? AND k.name = ?", uid, name);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Types with fields and list values: three queries for everything. where — a condition on kinds k (from code, not user input). */
    private List<Kind> loadKinds(String where, Object... args) {
        Map<Long, List<Kind.Option>> options = new HashMap<>();
        jdbc.query("SELECT o.field_id, o.value, o.color FROM field_options o JOIN kind_fields f ON f.id = o.field_id "
                + "JOIN kinds k ON k.id = f.kind_id WHERE " + where + " ORDER BY o.field_id, o.pos", rs -> {
            options.computeIfAbsent(rs.getLong(1), x -> new java.util.ArrayList<>())
                    .add(new Kind.Option(rs.getString(2), rs.getString(3)));
        }, args);
        Map<Long, List<Kind.Field>> fields = new HashMap<>();
        jdbc.query("SELECT f.id, f.kind_id, f.role, f.type, f.label FROM kind_fields f JOIN kinds k ON k.id = f.kind_id "
                + "WHERE " + where + " ORDER BY f.kind_id, f.pos, f.id", rs -> {
            long id = rs.getLong(1);
            fields.computeIfAbsent(rs.getLong(2), x -> new java.util.ArrayList<>()).add(new Kind.Field(id, rs.getString(3),
                    rs.getString(4), rs.getString(5), List.copyOf(options.getOrDefault(id, List.of()))));
        }, args);
        return jdbc.query("SELECT k.id, k.name, k.profile, (SELECT COUNT(*) FROM collections c WHERE c.kind_id = k.id) AS used "
                + "FROM kinds k WHERE " + where, (rs, i) -> {
            List<Kind.Field> defs = List.copyOf(fields.getOrDefault(rs.getLong(1), List.of()));
            Set<String> roles = defs.stream().map(Kind.Field::role).filter(r -> r != null && FIELDS.containsKey(r))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return new Kind(rs.getLong(1), rs.getString(2), rs.getString(3), roles, rs.getInt(4), defs);
        }, args);
    }

    /** Row colours by status for a type: status in lower case → colour. */
    public Map<String, String> statusColors(long uid, String kindName) {
        Kind k = kindByName(uid, kindName);
        Map<String, String> res = new HashMap<>();
        if (k != null) k.statusColors().forEach((st, color) -> res.put(st.trim().toLowerCase(Locale.ROOT), color));
        return res;
    }

    /** Roles of item fields of a collection of this type in order. If the type is not found — all fields, so data is not hidden. */
    public Set<String> itemFields(long uid, String kindName) {
        Kind k = kindByName(uid, kindName);
        return k == null ? FIELDS.keySet() : k.fields();
    }

    /**
     * Creates (id == null) or changes a type: name, profile, fields (roles — all roles in form order, enabled —
     * ticked ones, labels — own labels in the same order, empty — the role label), statuses and their colours (empty colour — none).
     * Kept fields keep their ids — and item values; removed fields are deleted with their values. Returns the type id.
     */
    public long saveKind(long uid, Long id, String name, String profile, List<String> roles, List<String> enabled,
                         List<String> labels, List<String> statuses, List<String> colors) {
        return saveKind(uid, id, name, profile, roles, enabled, labels, List.of(), List.of(), statuses, colors);
    }

    /**
     * The same with custom fields. Form rows of fields (in order): roles — a built-in field role, "c<id>" — a custom field,
     * "n<number>" — a new custom field; enabled — ticked rows; labels — labels (required for custom ones);
     * types — the kind of a new custom field (CUSTOM_TYPES; does not change for an existing one); opts — list values
     * ("list" kind), one per line. A removed custom field is deleted with its values.
     */
    @org.springframework.transaction.annotation.Transactional // an error in the form — the type does not change at all
    public long saveKind(long uid, Long id, String name, String profile, List<String> roles, List<String> enabled,
                         List<String> labels, List<String> types, List<String> opts, List<String> statuses, List<String> colors) {
        requireName(name);
        String n = name.trim();
        for (Kind k : kinds(uid))
            if (alphabetical().compare(k.name(), n) == 0 && (id == null || k.id() != id))
                throw Fail.badRequest("kind.exists", k.name());
        if (profile == null || !PROFILES.containsKey(profile)) profile = "other";
        List<Kind.Option> options = parseStatuses(statuses, colors);
        long kindId;
        if (id == null) {
            kindId = jdbc.queryForObject("INSERT INTO kinds (user_id, name, profile) VALUES (?, ?, ?) RETURNING id",
                    Long.class, uid, n, profile);
        } else {
            kind(uid, id); // someone else's — 404
            jdbc.update("UPDATE kinds SET name = ?, profile = ? WHERE id = ?", n, profile, id);
            kindId = id;
        }
        // fields in form order: built-in roles (each once) and custom fields
        Set<Long> ownCustoms = new java.util.HashSet<>(jdbc.queryForList(
                "SELECT id FROM kind_fields WHERE kind_id = ? AND role IS NULL", Long.class, kindId));
        List<FieldSpec> specs = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; roles != null && i < roles.size(); i++) {
            String r = roles.get(i);
            if (r == null || !seen.add(r) || enabled == null || !enabled.contains(r)) continue;
            String l = at(labels, i);
            if (l.length() > MAX_LABEL) throw Fail.badRequest("item.tooLong", MAX_LABEL);
            if (FIELDS.containsKey(r)) {
                // own label if it differs from the standard one
                specs.add(new FieldSpec(r, null, null, !l.isEmpty() && !l.equals(I18n.t(FIELDS.get(r))) ? l : null, null));
            } else if (r.matches("c\\d{1,18}") || r.matches("n\\d{1,9}")) {
                Long fid = r.startsWith("c") ? Long.parseLong(r.substring(1)) : null;
                if (fid != null && !ownCustoms.contains(fid)) throw Fail.notFound(); // someone else's field
                String type = fid != null ? jdbc.queryForObject("SELECT type FROM kind_fields WHERE id = ?", String.class, fid)
                        : at(types, i);
                if (!CUSTOM_TYPES.containsKey(type)) throw Fail.badRequest("kind.badType");
                if (l.isEmpty()) throw Fail.badRequest("kind.labelRequired");
                List<Kind.Option> values = type.equals("select") ? parseOptions(at(opts, i), l) : List.of();
                specs.add(new FieldSpec(null, fid, type, l, values));
            }
        }
        if (specs.stream().filter(sp -> sp.role() == null).count() > MAX_CUSTOM)
            throw Fail.badRequest("kind.tooManyFields", MAX_CUSTOM);
        setFields(kindId, specs);
        Long statusField = fieldIdOfKind(kindId, "status");
        if (statusField != null) setOptions(statusField, options);
        return kindId;
    }

    /** Largest length of an own field label. */
    public static final int MAX_LABEL = 60;

    /** A field in the type form: built-in (role) or custom (id — existing, null — new; kind, label, list values). */
    private record FieldSpec(String role, Long id, String type, String label, List<Kind.Option> options) {}

    private static String at(List<String> list, int i) {
        return list != null && i < list.size() ? trim(list.get(i)) : "";
    }

    /** List values from the form: one per line, empty ones skipped, a repeat (ignoring case) — an error. */
    private static List<Kind.Option> parseOptions(String text, String field) {
        List<Kind.Option> res = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String line : text.split("\\R")) {
            String v = line.trim();
            if (v.isEmpty()) continue;
            if (v.length() > MAX_OPTION) throw Fail.badRequest("item.tooLong", MAX_OPTION);
            if (!seen.add(v.toLowerCase(Locale.ROOT))) throw Fail.badRequest("kind.optionTwice", v, field);
            res.add(new Kind.Option(v, null));
        }
        if (res.isEmpty()) throw Fail.badRequest("kind.optionsRequired", field);
        if (res.size() > MAX_OPTIONS) throw Fail.badRequest("kind.tooManyOptions", field, MAX_OPTIONS);
        return res;
    }

    /** Type fields in order; previous fields (built-in — by role, custom — by id) stay with their values, the rest are deleted. */
    private void setFields(long kindId, List<FieldSpec> specs) {
        Map<String, Long> roles = new HashMap<>();
        jdbc.query("SELECT role, id FROM kind_fields WHERE kind_id = ? AND role IS NOT NULL",
                rs -> { roles.put(rs.getString(1), rs.getLong(2)); }, kindId);
        Set<Long> keep = new java.util.HashSet<>();
        for (FieldSpec sp : specs) {
            if (sp.role() != null && roles.containsKey(sp.role())) keep.add(roles.get(sp.role()));
            if (sp.id() != null) keep.add(sp.id());
        }
        for (Long fid : jdbc.queryForList("SELECT id FROM kind_fields WHERE kind_id = ?", Long.class, kindId))
            if (!keep.contains(fid)) jdbc.update("DELETE FROM kind_fields WHERE id = ?", fid);
        for (int pos = 0; pos < specs.size(); pos++) {
            FieldSpec sp = specs.get(pos);
            if (sp.role() != null) {
                if (roles.containsKey(sp.role()))
                    jdbc.update("UPDATE kind_fields SET pos = ?, label = ? WHERE id = ?", pos, sp.label(), roles.get(sp.role()));
                else
                    jdbc.update("INSERT INTO kind_fields (kind_id, pos, role, type, label) VALUES (?, ?, ?, ?, ?)",
                            kindId, pos, sp.role(), TYPES.get(sp.role()), sp.label());
                continue;
            }
            long fid;
            if (sp.id() != null) {
                fid = sp.id();
                jdbc.update("UPDATE kind_fields SET pos = ?, label = ? WHERE id = ?", pos, sp.label(), fid);
            } else {
                fid = jdbc.queryForObject("INSERT INTO kind_fields (kind_id, pos, role, type, label) VALUES (?, ?, NULL, ?, ?) "
                        + "RETURNING id", Long.class, kindId, pos, sp.type(), sp.label());
            }
            if (sp.type().equals("select")) setOptions(fid, sp.options());
        }
    }

    private void setOptions(long fieldId, List<Kind.Option> options) {
        jdbc.update("DELETE FROM field_options WHERE field_id = ?", fieldId);
        for (int i = 0; i < options.size(); i++)
            jdbc.update("INSERT INTO field_options (field_id, pos, value, color) VALUES (?, ?, ?, ?)",
                    fieldId, i, options.get(i).value(), options.get(i).color());
    }

    private Long fieldIdOfKind(long kindId, String role) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM kind_fields WHERE kind_id = ? AND role = ?", Long.class, kindId, role);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Largest number of statuses per type and status length. */
    public static final int MAX_OPTIONS = 30, MAX_OPTION = 60;

    /** Statuses from the form in order: empty ones skipped, a repeat (ignoring case) — an error; colour #rrggbb or empty. */
    static List<Kind.Option> parseStatuses(List<String> statuses, List<String> colors) {
        List<Kind.Option> res = new java.util.ArrayList<>();
        if (statuses == null) return res;
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < statuses.size(); i++) {
            String st = trim(statuses.get(i));
            if (st.isEmpty()) continue;
            if (st.length() > MAX_OPTION) throw Fail.badRequest("item.tooLong", MAX_OPTION);
            String color = colors != null && i < colors.size() ? trim(colors.get(i)) : "";
            if (!color.isEmpty() && !color.matches("#[0-9a-fA-F]{6}"))
                throw Fail.badRequest("kind.badColor", st);
            if (!seen.add(st.toLowerCase(Locale.ROOT)))
                throw Fail.badRequest("kind.statusTwice", st);
            res.add(new Kind.Option(st, color.isEmpty() ? null : color.toLowerCase(Locale.ROOT)));
        }
        if (res.size() > MAX_OPTIONS) throw Fail.badRequest("kind.tooManyStatuses", MAX_OPTIONS);
        return res;
    }

    /** Deletes a type if no collection uses it. */
    public void deleteKind(long uid, long id) {
        Kind k = kind(uid, id);
        if (k.used() > 0)
            throw Fail.badRequest("kind.inUse", k.name(), k.used());
        jdbc.update("DELETE FROM kinds WHERE id = ?", id);
    }

    // ---------- users ----------

    /** User roles: key (users.role) → name. An administrator can add users. */
    public static final String ADMIN = "admin", USER = "user";
    public static final Map<String, String> ROLES = new LinkedHashMap<>();
    static {
        ROLES.put(ADMIN, "role.admin"); // label — a text key
        ROLES.put(USER, "role.user");
    }

    /** User: id, name, password hash (see Passwords), role (a key from ROLES). */
    public record User(long id, String name, String passwordHash, String role) {
        public boolean isAdmin() {
            return ADMIN.equals(role);
        }
    }

    private static final String USER_SELECT = "SELECT id, name, password_hash, role FROM users";

    private static final RowMapper<User> USER_MAPPER = (rs, i) ->
            new User(rs.getLong("id"), rs.getString("name"), rs.getString("password_hash"), rs.getString("role"));

    public int countUsers() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
    }

    /** All users alphabetically. */
    public List<User> users() {
        return jdbc.query(USER_SELECT, USER_MAPPER).stream()
                .sorted(Comparator.comparing(User::name, alphabetical())).toList();
    }

    /** User by name ignoring case (Cyrillic too) or null. */
    public User userByName(String name) {
        if (name == null) return null;
        Comparator<String> abc = alphabetical();
        return jdbc.query(USER_SELECT, USER_MAPPER).stream()
                .filter(u -> abc.compare(u.name(), name.trim()) == 0).findFirst().orElse(null);
    }

    public User user(long uid) {
        List<User> list = jdbc.query(USER_SELECT + " WHERE id = ?", USER_MAPPER, uid);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Whether to show prices openly ("Display" setting); by default — no, only in the tooltip. */
    public boolean showPrices(long uid) {
        List<Integer> v = jdbc.queryForList("SELECT show_prices FROM user_settings WHERE user_id = ?", Integer.class, uid);
        return !v.isEmpty() && v.get(0) == 1;
    }

    public void setShowPrices(long uid, boolean show) {
        jdbc.update("INSERT INTO user_settings (user_id, show_prices) VALUES (?, ?) "
                + "ON CONFLICT (user_id) DO UPDATE SET show_prices = excluded.show_prices", uid, show ? 1 : 0);
    }

    /**
     * Theme pairs: key (the data-pair attribute of the page, styles — app.css) → labels of the dark and light theme
     * (text keys). The switch in the header toggles dark and light within the pair.
     */
    public static final Map<String, List<String>> THEMES = new LinkedHashMap<>();
    static {
        THEMES.put("night", List.of("theme.night", "theme.paper"));
        THEMES.put("graphite", List.of("theme.graphite", "theme.mint"));
        THEMES.put("coal", List.of("theme.coal", "theme.sand"));
        THEMES.put("midnight", List.of("theme.midnight", "theme.sky"));
        THEMES.put("dusk", List.of("theme.dusk", "theme.lilac"));
        THEMES.put("forest", List.of("theme.forest", "theme.minimal"));
    }

    /** The user's theme pair (a THEMES key); not chosen — the first one. */
    public String themePair(long uid) {
        List<String> v = jdbc.queryForList("SELECT pair FROM user_theme WHERE user_id = ?", String.class, uid);
        return v.isEmpty() || !THEMES.containsKey(v.get(0)) ? THEMES.keySet().iterator().next() : v.get(0);
    }

    public void setThemePair(long uid, String pair) {
        if (!THEMES.containsKey(pair)) throw Fail.badRequest();
        jdbc.update("INSERT INTO user_theme (user_id, pair) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET pair = excluded.pair", uid, pair);
    }

    /**
     * How many to show per page ("Display" tab): collections on the home page and items in the collection table.
     * No row or value — default; values outside the allowed limits (MIN_…/MAX_…) are clamped to them.
     */
    public record PageSizes(int collections, int items) {
        public static final int DEFAULT_COLLECTIONS = 10, MIN_COLLECTIONS = 1, MAX_COLLECTIONS = 100;
        public static final int DEFAULT_ITEMS = 200, MIN_ITEMS = 10, MAX_ITEMS = 1000;

        static int clamp(Integer v, int def, int min, int max) {
            return v == null ? def : Math.min(Math.max(v, min), max);
        }
    }

    public PageSizes pageSizes(long uid) {
        List<PageSizes> v = jdbc.query("SELECT collections, items FROM user_page_sizes WHERE user_id = ?",
                (rs, i) -> {
                    int c = rs.getInt("collections");
                    Integer cv = rs.wasNull() ? null : c;
                    int it = rs.getInt("items");
                    Integer iv = rs.wasNull() ? null : it;
                    return new PageSizes(
                            PageSizes.clamp(cv, PageSizes.DEFAULT_COLLECTIONS, PageSizes.MIN_COLLECTIONS, PageSizes.MAX_COLLECTIONS),
                            PageSizes.clamp(iv, PageSizes.DEFAULT_ITEMS, PageSizes.MIN_ITEMS, PageSizes.MAX_ITEMS));
                }, uid);
        return v.isEmpty() ? new PageSizes(PageSizes.DEFAULT_COLLECTIONS, PageSizes.DEFAULT_ITEMS) : v.get(0);
    }

    public void setPageSizes(long uid, PageSizes p) {
        jdbc.update("INSERT INTO user_page_sizes (user_id, collections, items) VALUES (?, ?, ?) "
                + "ON CONFLICT (user_id) DO UPDATE SET collections = excluded.collections, items = excluded.items",
                uid, p.collections(), p.items());
    }

    /** Remembered view of a collection's item table (an address query string) or null. */
    public String collectionView(long uid, long collectionId) {
        List<String> v = jdbc.queryForList("SELECT query FROM collection_views WHERE user_id = ? AND collection_id = ?",
                String.class, uid, collectionId);
        return v.isEmpty() ? null : v.get(0);
    }

    public void setCollectionView(long uid, long collectionId, String query) {
        jdbc.update("INSERT INTO collection_views (user_id, collection_id, query) VALUES (?, ?, ?) "
                + "ON CONFLICT (user_id, collection_id) DO UPDATE SET query = excluded.query", uid, collectionId, query);
    }

    /** Personal token of an import service (for example, Discogs) or null. */
    public String token(long uid, String service) {
        List<String> v = jdbc.queryForList("SELECT token FROM user_tokens WHERE user_id = ? AND service = ?", String.class, uid, service);
        return v.isEmpty() ? null : v.get(0);
    }

    /** Saves the token; empty — deletes it. */
    public void setToken(long uid, String service, String token) {
        if (token == null || token.isBlank()) {
            jdbc.update("DELETE FROM user_tokens WHERE user_id = ? AND service = ?", uid, service);
            return;
        }
        jdbc.update("INSERT INTO user_tokens (user_id, service, token) VALUES (?, ?, ?) "
                + "ON CONFLICT (user_id, service) DO UPDATE SET token = excluded.token", uid, service, token);
    }

    /** Initial collection type of a new user: name (a text key), profile, fields in order, statuses. */
    private record DefaultKind(String nameKey, String profile, List<String> fields, List<String> statuses) {}

    // name — a text key: types are created in the interface language of whoever creates the user
    private static final List<DefaultKind> DEFAULT_KINDS = List.of(
            new DefaultKind("kind.default.gamesDiscs", "games",
                    List.of("status", "rating", "price", "available", "oldgames"), GAME_STATUSES),
            new DefaultKind("kind.default.gamesDigital", "games",
                    List.of("platform", "format", "status", "rating", "price", "available"), GAME_STATUSES),
            new DefaultKind("kind.default.books", "books", List.of("price", "available"), List.of()),
            new DefaultKind("kind.default.music", "music", List.of("price", "available"), List.of()));

    /** Creates a user with the initial collection types. Returns the id. */
    public long createUser(String name, String password, String role) {
        if (name == null || name.isBlank())
            throw Fail.badRequest("user.nameRequired");
        if (userByName(name) != null)
            throw Fail.badRequest("user.exists", name.trim());
        if (!ROLES.containsKey(role))
            throw Fail.badRequest("user.badRole");
        long uid = jdbc.queryForObject("INSERT INTO users (name, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class, name.trim(), Passwords.hash(password), role);
        for (DefaultKind d : DEFAULT_KINDS) {
            long kindId = jdbc.queryForObject("INSERT INTO kinds (user_id, name, profile) VALUES (?, ?, ?) RETURNING id",
                    Long.class, uid, I18n.t(d.nameKey()), d.profile());
            setFields(kindId, d.fields().stream().map(r -> new FieldSpec(r, null, null, null, null)).toList());
            Long statusField = fieldIdOfKind(kindId, "status");
            if (statusField != null)
                setOptions(statusField, d.statuses().stream().map(st -> new Kind.Option(st, null)).toList());
        }
        return uid;
    }

    /** New password; the user's remembered computers are forgotten (their sign-in keys are deleted). */
    public void setPassword(long uid, String password) {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", Passwords.hash(password), uid);
        jdbc.update("DELETE FROM remember_tokens WHERE user_id = ?", uid);
    }

    // ---------- "Remember me" (Remember): sign-in keys, the database has only their hash ----------

    public void saveRememberToken(String hash, long uid, long expires) {
        jdbc.update("DELETE FROM remember_tokens WHERE expires < ?", System.currentTimeMillis());
        jdbc.update("INSERT INTO remember_tokens (hash, user_id, expires) VALUES (?, ?, ?)", hash, uid, expires);
    }

    /** User by the hash of a valid key or null. */
    public Long rememberTokenUser(String hash, long now) {
        List<Long> ids = jdbc.queryForList("SELECT user_id FROM remember_tokens WHERE hash = ? AND expires > ?", Long.class, hash, now);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public void deleteRememberToken(String hash) {
        jdbc.update("DELETE FROM remember_tokens WHERE hash = ?", hash);
    }

    // ---------- reading ----------

    /**
     * All collections alphabetically. Sorted in Java: SQLite cannot compare Cyrillic ignoring case.
     * There are not many collections, so the home page cuts its page from this list.
     */
    public List<Entry> collections(long uid) {
        List<Entry> list = jdbc.query("SELECT " + COLLECTION_COLS + ", (SELECT COUNT(*) FROM items i "
                + "WHERE i.collection_id = c.id) AS cnt FROM collections c WHERE c.user_id = ?", mapper(false, true), uid);
        Comparator<String> abc = alphabetical();
        return list.stream().sorted(Comparator.comparing(Entry::name, abc).thenComparing(Entry::id)).toList();
    }

    public Entry collection(long uid, long id) {
        return one(jdbc.query("SELECT " + COLLECTION_COLS + " FROM collections c WHERE id = ? AND user_id = ?",
                mapper(false, false), id, uid));
    }

    /** Fields items can be sorted by: key from the URL → column. */
    public static final Map<String, String> ITEM_SORT = Map.of(
            "name", "name", "platform", "platform", "format", "format", "status", "status", "price", "price",
            "available", "available", "rating", "rating", "purchased", "purchased", "edition", "edition");

    /** Collection counters for the home page: how many items are in stock and the total value (sum of prices). */
    public record CollectionStats(int available, long price) {}

    /** Counters of all the user's collections (collections without items are not in the map). */
    public Map<Long, CollectionStats> collectionStats(long uid) {
        Map<Long, CollectionStats> res = new HashMap<>();
        jdbc.query("SELECT i.collection_id, SUM(" + val("available", "i") + " IS NOT NULL), "
                + "COALESCE(SUM(" + val("price", "i") + "), 0) FROM items i JOIN collections c ON c.id = i.collection_id "
                + "WHERE c.user_id = ? GROUP BY i.collection_id",
                rs -> { res.put(rs.getLong(1), new CollectionStats(rs.getInt(2), rs.getLong(3))); }, uid);
        return res;
    }

    /** Total value of a collection — the sum of item prices (items without a price are not counted). */
    public long totalPrice(long collectionId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(" + val("price", "items") + "), 0) FROM items WHERE collection_id = ?",
                Long.class, collectionId);
    }

    public int countItems(long collectionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM items WHERE collection_id = ?", Integer.class, collectionId);
    }

    /** Columns whose filter is a choice of a value from a list (exact match). */
    public static final List<String> SELECT_FILTERS = List.of("platform", "format", "status");

    /**
     * Collection items with filters and sorting (the caller cuts the page).
     * filters: "name" — part of the name ignoring case (Cyrillic too, so it is checked in Java),
     * keys from SELECT_FILTERS — an exact value. sort — a key from ITEM_SORT (otherwise by name);
     * empty values always at the end, on a tie — by name.
     */
    public List<Entry> items(long collectionId, String sort, boolean desc, Map<String, String> filters) {
        // a custom field of the collection type — "c<id>": sorting and filters by the value in item_values
        Map<Long, String> customs = new HashMap<>();
        jdbc.query("SELECT f.id, f.type FROM kind_fields f JOIN collections c ON c.kind_id = f.kind_id "
                + "WHERE c.id = ? AND f.role IS NULL", rs -> { customs.put(rs.getLong(1), rs.getString(2)); }, collectionId);
        Long sortCustom = customKey(sort, customs);
        String col = sortCustom != null ? cval(sortCustom) : ITEM_SORT.getOrDefault(sort, "name"); // otherwise — an ITEM_COLS column alias
        boolean numeric = col.equals("price") || col.equals("available") || col.equals("rating")
                || sortCustom != null && List.of("number", "bool").contains(customs.get(sortCustom));
        String order = "(" + col + " IS NULL OR " + col + " = ''), "
                + col + (numeric ? "" : " COLLATE NOCASE") + (desc ? " DESC" : "")
                + ", name COLLATE NOCASE, id";
        StringBuilder where = new StringBuilder("collection_id = ?");
        List<Object> args = new java.util.ArrayList<>(List.of(collectionId));
        for (Map.Entry<String, String> e : filters.entrySet()) {
            Long fid = customKey(e.getKey(), customs);
            if (fid == null) continue;
            if (customs.get(fid).equals("bool")) {
                where.append(" AND ").append(cval(fid)).append("1".equals(e.getValue()) ? " IS NOT NULL" : " IS NULL");
            } else {
                where.append(" AND ").append(cval(fid)).append(" = ?");
                args.add(e.getValue());
            }
        }
        for (String f : SELECT_FILTERS)
            if (filters.containsKey(f)) {
                where.append(" AND ").append(val(f, "items")).append(" = ?"); // f — only from SELECT_FILTERS
                args.add(filters.get(f));
            }
        // Price "from — to": values are already checked in the controller (whole numbers); items without a price do not match
        if (filters.containsKey("price_min")) {
            where.append(" AND ").append(val("price", "items")).append(" >= ?");
            args.add(Long.parseLong(filters.get("price_min")));
        }
        if (filters.containsKey("price_max")) {
            where.append(" AND ").append(val("price", "items")).append(" <= ?");
            args.add(Long.parseLong(filters.get("price_max")));
        }
        // Available: "1" — yes, "0" — no
        if (filters.containsKey("available"))
            where.append(" AND ").append(val("available", "items")).append("1".equals(filters.get("available")) ? " IS NOT NULL" : " IS NULL");
        // Rating: "0" — no rating, "1".."5" — at least this many stars
        String rating = filters.get("rating");
        if (rating != null) {
            String r = val("rating", "items");
            if (rating.equals("0")) where.append(" AND ").append(r).append(" IS NULL");
            else {
                where.append(" AND ").append(r).append(" >= ?");
                args.add(Integer.parseInt(rating) * 2);
            }
        }
        // Region / edition — an exact value; purchase year — the date starts with it
        if (filters.containsKey("edition")) {
            where.append(" AND ").append(val("edition", "items")).append(" = ?");
            args.add(filters.get("edition"));
        }
        if (filters.containsKey("purchased")) {
            where.append(" AND ").append(val("purchased", "items")).append(" LIKE ?");
            args.add(filters.get("purchased") + "-%");
        }
        // Pictures: "any" — at least one, "0" — none, "1".."5" — exactly this many
        String images = filters.get("images");
        if (images != null) {
            String count = "(SELECT COUNT(*) FROM item_pictures ip WHERE ip.item_id = items.id)";
            if (images.equals("any")) where.append(" AND ").append(count).append(" > 0");
            else {
                where.append(" AND ").append(count).append(" = ?");
                args.add(Integer.parseInt(images));
            }
        }
        List<Entry> list = jdbc.query("SELECT " + ITEM_COLS + " FROM items WHERE " + where + " ORDER BY " + order,
                mapper(true, false), args.toArray());
        // Tag — ignoring case (Cyrillic too), so in Java
        String tag = filters.get("tags");
        if (tag != null) {
            String t = tag.toLowerCase(RU);
            list = list.stream().filter(e -> e.tagList().stream().anyMatch(x -> x.toLowerCase(RU).equals(t))).toList();
        }
        String name = filters.get("name");
        if (name == null) return list;
        String needle = name.toLowerCase(RU);
        return list.stream().filter(e -> e.name().toLowerCase(RU).contains(needle)).toList();
    }

    private static final Locale RU = Locale.forLanguageTag("ru");

    /** Value of an item's custom field (a subquery; items — the table in the query). fieldId — a number, not user input. */
    private static String cval(long fieldId) {
        return "(SELECT v.value FROM item_values v WHERE v.item_id = items.id AND v.field_id = " + fieldId + ")";
    }

    /** Id of a custom field by the key "c<id>" if such a field is among customs; otherwise null. */
    private static Long customKey(String key, Map<Long, String> customs) {
        if (key == null || !key.matches("c\\d{1,18}")) return null;
        long id = Long.parseLong(key.substring(1));
        return customs.containsKey(id) ? id : null;
    }

    // ---------- custom fields: values ----------

    /** Values of items' custom fields: item id → (field id → value: text or number). Fields — of the item's collection type. */
    public Map<Long, Map<Long, Object>> customValues(java.util.Collection<Long> itemIds) {
        Map<Long, Map<Long, Object>> res = new HashMap<>();
        List<Long> ids = List.copyOf(itemIds);
        for (int i = 0; i < ids.size(); i += 500) { // in parts: SQLite limits the number of parameters
            List<Long> part = ids.subList(i, Math.min(ids.size(), i + 500));
            jdbc.query("SELECT v.item_id, v.field_id, v.value FROM item_values v JOIN kind_fields f ON f.id = v.field_id "
                    + "JOIN items i ON i.id = v.item_id JOIN collections c ON c.id = i.collection_id "
                    + "WHERE f.role IS NULL AND f.kind_id = c.kind_id AND v.item_id IN ("
                    + String.join(",", java.util.Collections.nCopies(part.size(), "?")) + ")", rs -> {
                Object v = rs.getObject(3);
                // whole numbers — Long (the driver returns small ones as Integer)
                res.computeIfAbsent(rs.getLong(1), x -> new HashMap<>()).put(rs.getLong(2), v instanceof Integer n ? (Object) n.longValue() : v);
            }, part.toArray());
        }
        return res;
    }

    /** Values of a custom field in collection items — for the filter dropdown (alphabetically, without empty ones). */
    public List<String> customDistinct(long collectionId, long fieldId) {
        return jdbc.queryForList("SELECT DISTINCT v.value FROM item_values v JOIN items i ON i.id = v.item_id "
                + "WHERE i.collection_id = ? AND v.field_id = ?", Object.class, collectionId, fieldId).stream()
                .map(String::valueOf).sorted(alphabetical()).toList();
    }

    /**
     * Writes the value of an item's custom field by the field kind (the field must be of the item's collection type, otherwise nothing): text and long
     * text — up to MAX_TEXT / MAX_LONGTEXT; number — whole or fractional (dot or comma); date — YYYY-MM-DD; yes/no —
     * "1" or empty; list — a value from the list (or the previous value). Empty — no value; invalid — 400.
     */
    public void setCustom(long itemId, long fieldId, String raw) {
        List<Map<String, Object>> f = jdbc.queryForList("SELECT f.type FROM kind_fields f JOIN collections c ON c.kind_id = f.kind_id "
                + "JOIN items i ON i.collection_id = c.id WHERE i.id = ? AND f.id = ? AND f.role IS NULL", itemId, fieldId);
        if (f.isEmpty()) return;
        String type = (String) f.get(0).get("type"), v = raw == null ? "" : raw.replace("\r\n", "\n").trim();
        Object value = v.isEmpty() ? null : switch (type) {
            case "text" -> limit(v, MAX_TEXT);
            case "longtext" -> limit(v, MAX_LONGTEXT);
            case "number" -> number(v);
            case "date" -> purchaseDate(v);
            case "bool" -> v.equals("1") || v.equalsIgnoreCase("true") ? 1 : null;
            case "select" -> {
                List<String> options = jdbc.queryForList("SELECT value FROM field_options WHERE field_id = ?", String.class, fieldId);
                Object old = jdbc.queryForList("SELECT value FROM item_values WHERE item_id = ? AND field_id = ?", Object.class,
                        itemId, fieldId).stream().findFirst().orElse(null);
                if (!options.contains(v) && !v.equals(String.valueOf(old))) throw Fail.badRequest("item.badOption", v);
                yield v;
            }
            default -> throw new IllegalStateException(type);
        };
        List<Object> args = new java.util.ArrayList<>();
        if (value == null) {
            jdbc.update("DELETE FROM item_values WHERE item_id = ? AND field_id = ?", itemId, fieldId);
            return;
        }
        args.add(itemId);
        args.add(fieldId);
        args.add(value);
        jdbc.update("INSERT INTO item_values (item_id, field_id, value) VALUES (?, ?, ?) "
                + "ON CONFLICT (item_id, field_id) DO UPDATE SET value = excluded.value", args.toArray());
    }

    /** Number from a field: whole — Long, fractional — Double (comma = dot); not a number — 400. */
    static Number number(String v) {
        String s = v.replace(',', '.').replace(" ", "").replace("\u00a0", "");
        try {
            java.math.BigDecimal d = new java.math.BigDecimal(s);
            if (d.abs().compareTo(new java.math.BigDecimal("1e15")) >= 0) throw Fail.badRequest("item.badNumber", v);
            return d.stripTrailingZeros().scale() <= 0 ? (Number) d.longValueExact() : (Number) d.doubleValue();
        } catch (NumberFormatException | ArithmeticException e) {
            throw Fail.badRequest("item.badNumber", v);
        }
    }

    /** How many items to show in the search across all collections. */
    public static final int SEARCH_LIMIT = 200;

    /**
     * Search across all the user's collections: part of a name, tag, region / edition or note ignoring case
     * (Cyrillic too — so in Java). Items — alphabetically, at most SEARCH_LIMIT; total — how many were found in all.
     */
    public record SearchResult(List<Entry> items, int total) {}

    public SearchResult search(long uid, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(RU);
        if (q.isEmpty()) return new SearchResult(List.of(), 0);
        List<Long> ids = new java.util.ArrayList<>();
        jdbc.query("SELECT i.id, i.name, " + val("tags", "i") + ", " + val("edition", "i") + ", " + val("notes", "i")
                + " FROM items i WHERE i.collection_id IN (SELECT id FROM collections WHERE user_id = ?)", rs -> {
            for (int col = 2; col <= 5; col++) {
                String s = rs.getString(col);
                if (s != null && s.toLowerCase(RU).contains(q)) {
                    ids.add(rs.getLong(1));
                    return;
                }
            }
        }, uid);
        // custom fields: text, long text, list
        Set<Long> already = new java.util.HashSet<>(ids);
        jdbc.query("SELECT v.item_id, v.value FROM item_values v JOIN kind_fields f ON f.id = v.field_id "
                + "JOIN items i ON i.id = v.item_id JOIN collections c ON c.id = i.collection_id "
                + "WHERE c.user_id = ? AND f.kind_id = c.kind_id AND f.role IS NULL AND f.type IN ('text', 'longtext', 'select')", rs -> {
            String v = rs.getString(2);
            if (v != null && v.toLowerCase(RU).contains(q) && already.add(rs.getLong(1))) ids.add(rs.getLong(1));
        }, uid);
        if (ids.isEmpty()) return new SearchResult(List.of(), 0);
        List<Entry> found = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i += 500) { // in parts: SQLite limits the number of parameters
            List<Long> part = ids.subList(i, Math.min(ids.size(), i + 500));
            found.addAll(jdbc.query("SELECT " + ITEM_COLS + " FROM items WHERE id IN ("
                    + String.join(",", java.util.Collections.nCopies(part.size(), "?")) + ")", mapper(true, false), part.toArray()));
        }
        Comparator<String> abc = alphabetical();
        return new SearchResult(found.stream().sorted(Comparator.comparing(Entry::name, abc).thenComparing(Entry::id))
                .limit(SEARCH_LIMIT).toList(), found.size());
    }

    // ---------- statistics ----------

    /**
     * Collection counters for the "Statistics" page: items, in stock, completed / not completed, rated and average
     * rating (in stars), value, with pictures, with a description.
     */
    public record Stats(long collectionId, int items, int available, int rated,
                        double avgRating, long price, int withImages, int withDescription) {}

    /** Counters of all the user's collections: collection id → counters (collections without items — zeros). */
    public Map<Long, Stats> stats(long uid) {
        Map<Long, Stats> res = new HashMap<>();
        String rating = val("rating", "i");
        jdbc.query("SELECT c.id, COUNT(i.id), "
                + "COALESCE(SUM(" + val("available", "i") + " IS NOT NULL), 0), "
                + "COUNT(" + rating + "), COALESCE(AVG(" + rating + "), 0), COALESCE(SUM(" + val("price", "i") + "), 0), "
                + "COALESCE(SUM(EXISTS (SELECT 1 FROM item_pictures p WHERE p.item_id = i.id)), 0), "
                + "COALESCE(SUM(EXISTS (SELECT 1 FROM item_descriptions d WHERE d.item_id = i.id)), 0) "
                + "FROM collections c LEFT JOIN items i ON i.collection_id = c.id "
                + "WHERE c.user_id = ? GROUP BY c.id", rs -> {
            res.put(rs.getLong(1), new Stats(rs.getLong(1), rs.getInt(2), rs.getInt(3),
                    rs.getInt(4), rs.getDouble(5) / 2, rs.getLong(6), rs.getInt(7), rs.getInt(8)));
        }, uid);
        return res;
    }

    /** How many items have each status: collection id → (status → number); items without a status are not counted. */
    public Map<Long, Map<String, Integer>> statusCounts(long uid) {
        Map<Long, Map<String, Integer>> res = new HashMap<>();
        jdbc.query("SELECT cid, st, COUNT(*) FROM (SELECT i.collection_id AS cid, " + val("status", "i") + " AS st FROM items i "
                + "JOIN collections c ON c.id = i.collection_id WHERE c.user_id = ?) WHERE st IS NOT NULL AND st <> '' GROUP BY 1, 2",
                rs -> { res.computeIfAbsent(rs.getLong(1), x -> new HashMap<>()).put(rs.getString(2), rs.getInt(3)); }, uid);
        return res;
    }

    /** How many items are rated in each whole star (rounded up: 3.5 → 4): index 0 — 1 star … 4 — 5 stars. */
    public int[] ratingHistogram(long uid) {
        int[] res = new int[5];
        jdbc.query("SELECT (r + 1) / 2, COUNT(*) FROM (SELECT " + val("rating", "i") + " AS r FROM items i "
                + "JOIN collections c ON c.id = i.collection_id WHERE c.user_id = ?) WHERE r IS NOT NULL GROUP BY 1",
                rs -> { int s = rs.getInt(1); if (s >= 1 && s <= 5) res[s - 1] = rs.getInt(2); }, uid);
        return res;
    }

    /** Purchases by year (purchase date): year → {items, sum of prices}; newer years first. */
    public Map<String, long[]> purchasesByYear(long uid) {
        Map<String, long[]> res = new java.util.TreeMap<>(Comparator.reverseOrder());
        jdbc.query("SELECT substr(p, 1, 4), COUNT(*), COALESCE(SUM(price), 0) FROM (SELECT " + val("purchased", "i") + " AS p, "
                + val("price", "i") + " AS price FROM items i JOIN collections c ON c.id = i.collection_id WHERE c.user_id = ?) "
                + "WHERE p IS NOT NULL GROUP BY 1",
                rs -> { res.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)}); }, uid);
        return res;
    }

    /** Most frequent tags: tag → how many items, at most limit, descending. */
    public List<Map.Entry<String, Integer>> topTags(long uid, int limit) {
        Map<String, Integer> count = new java.util.TreeMap<>(alphabetical());
        jdbc.query("SELECT t FROM (SELECT " + val("tags", "i") + " AS t FROM items i JOIN collections c ON c.id = i.collection_id "
                + "WHERE c.user_id = ?) WHERE t IS NOT NULL", rs -> {
            for (String t : splitTags(rs.getString(1))) count.merge(t, 1, Integer::sum);
        }, uid);
        return count.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(limit).toList();
    }

    /** Values of extra fields for filters: region / edition, tags, purchase years (newer first). */
    public List<String> detailValues(long collectionId, String field) {
        if (!List.of("edition", "tags", "purchased").contains(field)) throw new IllegalArgumentException(field);
        List<String> values = jdbc.queryForList("SELECT x FROM (SELECT " + val(field, "i") + " AS x FROM items i "
                + "WHERE i.collection_id = ?) WHERE x IS NOT NULL", String.class, collectionId);
        return switch (field) {
            case "edition" -> values.stream().distinct().sorted(alphabetical()).toList();
            case "tags" -> {
                Map<String, String> tags = new java.util.TreeMap<>(alphabetical());
                for (String s : values)
                    for (String t : splitTags(s)) tags.putIfAbsent(t, t);
                yield List.copyOf(tags.values());
            }
            case "purchased" -> values.stream().map(d -> d.substring(0, 4)).distinct().sorted(Comparator.reverseOrder()).toList();
            default -> throw new IllegalArgumentException(field);
        };
    }

    /** Values of a column in collection items — for the filter dropdown (alphabetically, without empty ones). */
    public List<String> distinctValues(long collectionId, String column) {
        if (!SELECT_FILTERS.contains(column)) throw new IllegalArgumentException(column);
        return jdbc.queryForList("SELECT DISTINCT x FROM (SELECT " + val(column, "items") + " AS x FROM items "
                + "WHERE collection_id = ?) WHERE x <> ''", String.class, collectionId).stream().sorted(alphabetical()).toList();
    }

    public Entry item(long uid, long id) {
        return one(jdbc.query("SELECT " + ITEM_COLS + " FROM items WHERE id = ? AND collection_id IN "
                + "(SELECT id FROM collections WHERE user_id = ?)", mapper(true, false), id, uid));
    }

    /**
     * Picture by content hash (the /img/<hash> address) if an item or collection of this user refers to it;
     * otherwise null (other users' pictures are not served, even if the hash is known).
     */
    public ImageFiles.Pic ownedPicture(long uid, String hash) {
        List<String> mime = jdbc.queryForList("SELECT p.mime FROM pictures p WHERE p.hash = ? AND ("
                + "EXISTS (SELECT 1 FROM item_pictures ip JOIN items i ON i.id = ip.item_id JOIN collections c ON c.id = i.collection_id "
                + "WHERE ip.hash = p.hash AND c.user_id = ?) OR "
                + "EXISTS (SELECT 1 FROM collection_pictures cp JOIN collections c ON c.id = cp.collection_id "
                + "WHERE cp.hash = p.hash AND c.user_id = ?))", String.class, hash, uid, uid);
        return mime.isEmpty() ? null : new ImageFiles.Pic(hash, mime.get(0));
    }

    /** Picture content (null — no file). */
    public byte[] pictureBytes(ImageFiles.Pic pic) {
        return files.read(pic);
    }

    /** Smaller copy of a picture or null — then the picture itself is served. */
    public ImageFiles.Thumb thumb(ImageFiles.Pic pic) {
        return files.thumb(pic);
    }

    // ---------- changes ----------

    public long createCollection(long uid) {
        return jdbc.queryForObject("INSERT INTO collections (user_id, name) VALUES (?, '') RETURNING id", Long.class, uid);
    }

    public long createItem(long collectionId) {
        return jdbc.queryForObject("INSERT INTO items (collection_id, name) VALUES (?, '') RETURNING id",
                Long.class, collectionId);
    }

    /** Saves a collection: name, type and (if uploaded) picture; removeImage deletes the picture. */
    public void saveCollection(long uid, long id, String name, String kind, MultipartFile image, boolean removeImage) {
        requireName(name);
        Kind k = kindByName(uid, kind);
        if (k == null) throw Fail.badRequest("coll.kindRequired");
        Long old = jdbc.queryForObject("SELECT kind_id FROM collections WHERE id = ?", Long.class, id);
        jdbc.update("UPDATE collections SET name = ?, kind_id = ? WHERE id = ?", name.trim(), k.id(), id);
        // another type — field values move to the fields of the new type with the same role
        if (old != null && old != k.id())
            remap("SELECT id FROM items WHERE collection_id = ?", id, k.id());

        if (image != null && !image.isEmpty()) {
            files.setCollection(id, readImage(image));
        } else if (removeImage) {
            files.setCollection(id, null);
        }
    }

    /** Largest length of an item description. */
    public static final int MAX_DESCRIPTION = 5000;

    /** Writes the item description (empty deletes it); the owner is checked by the caller. */
    public void setItemDescription(long itemId, String text) {
        String t = text == null ? "" : text.replace("\r\n", "\n").trim();
        if (t.length() > MAX_DESCRIPTION)
            throw Fail.badRequest("item.descriptionTooLong", MAX_DESCRIPTION);
        if (t.isEmpty()) jdbc.update("DELETE FROM item_descriptions WHERE item_id = ?", itemId);
        else jdbc.update("INSERT INTO item_descriptions (item_id, text) VALUES (?, ?) ON CONFLICT (item_id) "
                + "DO UPDATE SET text = excluded.text", itemId, t);
    }

    /**
     * Replaces all pictures of an item (up to ImageFiles.MAX_IMAGES, in order; an empty list deletes all).
     * Non-pictures are skipped. The owner is checked by the caller.
     */
    public void setItemImages(long itemId, List<byte[]> images) {
        files.setItem(itemId, images);
    }

    /** How many pictures the items of each of the user's collections have: collection id → number ("Cache" tab). */
    public Map<Long, Integer> collectionImageCounts(long uid) {
        Map<Long, Integer> counts = new HashMap<>();
        jdbc.query("SELECT i.collection_id, COUNT(*) FROM item_pictures ip JOIN items i ON i.id = ip.item_id "
                + "JOIN collections c ON c.id = i.collection_id WHERE c.user_id = ? GROUP BY i.collection_id",
                rs -> { counts.put(rs.getLong(1), rs.getInt(2)); }, uid);
        return counts;
    }

    /** Content of an uploaded picture; if it is not PNG, JPEG or GIF — error 400. */
    private static byte[] readImage(MultipartFile image) {
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw Fail.badRequest("image.readError");
        }
        // The type is determined by the file content, not by what the browser sent
        if (imageType(bytes) == null)
            throw Fail.badRequest("image.badFormat");
        return bytes;
    }

    /**
     * Picture type by the first bytes of the file: PNG, JPEG, GIF; otherwise null (SVG too — it can contain scripts).
     * WebP is not accepted (the user's request): Java cannot read it, and no smaller copy can be made for it.
     */
    static String imageType(byte[] b) {
        if (starts(b, 0, 0x89, 'P', 'N', 'G')) return "image/png";
        if (starts(b, 0, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (starts(b, 0, 'G', 'I', 'F', '8')) return "image/gif";
        return null;
    }

    private static boolean starts(byte[] b, int from, int... sig) {
        if (b.length < from + sig.length) return false;
        for (int i = 0; i < sig.length; i++)
            if ((b[from + i] & 0xFF) != sig[i]) return false;
        return true;
    }

    /**
     * Saves an item: the name and the fields the collection type has (see the type settings).
     * null in the form — the field is not in the form, the value does not change; empty — clear.
     */
    public void saveItem(long uid, long id, String kind, ItemForm form, Map<Integer, MultipartFile> newImages, Set<Integer> removed) {
        String name = form.name(), oldGames = form.oldgames();
        Integer price = form.price(), rating = form.rating();
        Boolean available = form.availableValue();
        requireName(name);
        if (price != null && price < 0)
            throw Fail.badRequest("item.negativePrice");
        Kind k = kindByName(uid, kind);
        Set<String> f = k == null ? FIELDS.keySet() : k.fields();
        jdbc.update("UPDATE items SET name = ? WHERE id = ?", name.trim(), id);
        if (f.contains("platform") && form.platform() != null) setValue(id, "platform", trim(form.platform()));
        if (f.contains("format") && form.format() != null) setValue(id, "format", trim(form.format()));
        if (f.contains("status") && form.status() != null) {
            // only values from the type list; a previous value not in the list can be kept as is
            String st = trim(form.status());
            if (!st.isEmpty() && (k == null || !k.statuses().contains(st)) && !st.equals(value(id, "status")))
                throw Fail.badRequest("item.badStatus");
            setValue(id, "status", st);
        }
        if (f.contains("price")) setValue(id, "price", price);
        // Rating: 0 — remove, 1…10 — half stars; null — the field is not in the form, does not change
        if (f.contains("rating") && rating != null) setRating(id, rating);
        // Old-Games.ru link: empty — remove; only a game page address; null — the field is not in the form, does not change
        if (f.contains("oldgames") && oldGames != null) {
            String url = oldGames.isBlank() ? null : OldGames.normalize(oldGames);
            if (!oldGames.isBlank() && url == null)
                throw Fail.badRequest("og.needIdField");
            setValue(id, "oldgames", url);
        }
        if (f.contains("available") && available != null) setAvailable(id, available);
        if (f.contains("purchased") && form.purchased() != null) setValue(id, "purchased", purchaseDate(form.purchased()));
        if (f.contains("tags") && form.tags() != null) setValue(id, "tags", limit(normalizeTags(form.tags()), MAX_TAGS));
        if (f.contains("edition") && form.edition() != null) setValue(id, "edition", limit(trim(form.edition()), MAX_EDITION));
        if (f.contains("notes") && form.notes() != null)
            setValue(id, "notes", limit(form.notes().replace("\r\n", "\n").trim(), MAX_NOTES));
        // Every item has a description and pictures, regardless of the collection type
        if (form.description() != null) setItemDescription(id, form.description());
        // Pictures: a new file at pos replaces the picture (or adds one), removed — delete; in order without gaps
        if (!newImages.isEmpty() || !removed.isEmpty()) {
            List<ImageFiles.Pic> pics = files.item(id);
            List<byte[]> result = new java.util.ArrayList<>();
            for (int pos = 0; pos < ImageFiles.MAX_IMAGES; pos++) {
                MultipartFile upload = newImages.get(pos);
                if (upload != null && !upload.isEmpty()) result.add(readImage(upload));
                else if (pos < pics.size() && !removed.contains(pos)) result.add(files.read(pics.get(pos)));
            }
            setItemImages(id, result);
        }
    }

    /** Value of the item field with role role (in its collection's type) or null. */
    public Object value(long itemId, String role) {
        List<Object> v = jdbc.queryForList("SELECT " + val(role, "items") + " FROM items WHERE id = ?", Object.class, itemId);
        return v.isEmpty() ? null : v.get(0);
    }

    /**
     * Writes the value of the item field with role role (a field of its collection's type); if the type has no such field — nothing.
     * null or an empty string — no value (the row is deleted). The owner is checked by the caller.
     */
    public void setValue(long itemId, String role, Object value) {
        List<Long> field = jdbc.queryForList("SELECT f.id FROM kind_fields f JOIN collections c ON c.kind_id = f.kind_id "
                + "JOIN items i ON i.collection_id = c.id WHERE i.id = ? AND f.role = ?", Long.class, itemId, role);
        if (field.isEmpty()) return;
        if (value == null || value instanceof String str && str.isEmpty())
            jdbc.update("DELETE FROM item_values WHERE item_id = ? AND field_id = ?", itemId, field.get(0));
        else
            jdbc.update("INSERT INTO item_values (item_id, field_id, value) VALUES (?, ?, ?) "
                    + "ON CONFLICT (item_id, field_id) DO UPDATE SET value = excluded.value", itemId, field.get(0), value);
    }

    /** Status: empty or a value from the list of the item's collection type (bulk change — without previous values not in the list). */
    public void setStatus(long uid, long itemId, String status) {
        setValue(itemId, "status", checkStatus(uid, itemId, status));
    }

    /** A status from the list of the item's collection type or empty (without spaces at the edges); otherwise — 400. */
    public String checkStatus(long uid, long itemId, String status) {
        String st = trim(status);
        if (st.isEmpty()) return st;
        Entry it = item(uid, itemId);
        Kind k = kindByName(uid, collection(uid, it.collectionId()).kind());
        if (k == null || !k.statuses().contains(st)) throw Fail.badRequest("item.badStatus");
        return st;
    }

    /**
     * Moves an item to another collection (the owner of both is checked by the caller); pictures do not change,
     * field values move to the fields of the new collection's type with the same role.
     */
    public void moveItem(long itemId, long collectionId) {
        jdbc.update("UPDATE items SET collection_id = ? WHERE id = ?", collectionId, itemId);
        Long kindId = jdbc.queryForObject("SELECT kind_id FROM collections WHERE id = ?", Long.class, collectionId);
        if (kindId != null) remap("SELECT ?", itemId, kindId);
    }

    /**
     * Values of items (items — a subquery of item ids with one parameter arg) whose fields are not of type kindId move
     * to the field of this type with the same role. If the item already has a value of the new field — it stays; values of fields
     * the new type does not have are kept hidden (they come back if the previous type is restored).
     */
    private void remap(String items, Object arg, long kindId) {
        jdbc.update("UPDATE OR IGNORE item_values SET field_id = (SELECT nf.id FROM kind_fields nf JOIN kind_fields of "
                + "ON of.id = item_values.field_id WHERE nf.kind_id = ? AND nf.role = of.role) "
                + "WHERE item_id IN (" + items + ") AND field_id NOT IN (SELECT id FROM kind_fields WHERE kind_id = ?) "
                + "AND EXISTS (SELECT 1 FROM kind_fields nf JOIN kind_fields of ON of.id = item_values.field_id "
                + "WHERE nf.kind_id = ? AND nf.role = of.role)", kindId, arg, kindId, kindId);
    }

    /**
     * Item fields from the item window (new and edit — one form). null — the field is not in the form: the value does not change.
     * available — values of the "Available" tick: before it a hidden 0, a ticked box adds 1.
     */
    public record ItemForm(String name, String platform, String format, String status, Integer price,
                           List<String> available, Integer rating, String oldgames, String purchased, String tags,
                           String edition, String notes, String description) {
        Boolean availableValue() {
            return available == null ? null : available.contains("1");
        }
    }

    /** Largest length of tags (all together), region / edition and notes. */
    public static final int MAX_TAGS = 500, MAX_EDITION = 200, MAX_NOTES = 2000;

    /** Item availability: yes — value 1, no — no value. */
    public void setAvailable(long itemId, boolean available) {
        setValue(itemId, "available", available ? 1 : null);
    }

    /** Item rating: 0 — remove, 1…10 — half stars. */
    public void setRating(long itemId, int rating) {
        if (rating < 0 || rating > 10) throw Fail.badRequest("item.badRating");
        setValue(itemId, "rating", rating == 0 ? null : rating);
    }

    /** Purchase date from a date field (YYYY-MM-DD) or empty; anything else — 400. */
    private static String purchaseDate(String value) {
        String v = trim(value);
        if (v.isEmpty()) return "";
        try {
            return java.time.LocalDate.parse(v).toString();
        } catch (java.time.format.DateTimeParseException e) {
            throw Fail.badRequest("item.badDate");
        }
    }

    /** Tags: separated by commas, without spaces at the edges and repeats (ignoring case), in input order. */
    public static List<String> splitTags(String tags) {
        if (tags == null) return List.of();
        Map<String, String> res = new LinkedHashMap<>();
        for (String t : tags.split(",")) {
            String s = t.trim().replaceAll("\\s+", " ");
            if (!s.isEmpty()) res.putIfAbsent(s.toLowerCase(RU), s);
        }
        return List.copyOf(res.values());
    }

    private static String normalizeTags(String tags) {
        return String.join(", ", splitTags(tags));
    }

    private static String limit(String s, int max) {
        if (s.length() > max) throw Fail.badRequest("item.tooLong", max);
        return s;
    }

    /** Deletes a record; the owner is checked by the caller (collection/item with uid). */
    public void delete(String table, long id) {
        // picture references are deleted in cascade with the record; files — if nobody refers to them any more
        List<String> pics = table.equals("items") ? files.itemHashes(id) : files.collectionHashes(id);
        jdbc.update("DELETE FROM " + table + " WHERE id = ?", id); // collection items are deleted in cascade
        files.release(pics);
    }

    // ---------- helpers ----------

    private static void requireName(String name) {
        if (name == null || name.isBlank())
            throw Fail.badRequest("item.nameRequired");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static Entry one(List<Entry> list) {
        if (list.isEmpty()) throw Fail.notFound();
        return list.get(0);
    }
}
