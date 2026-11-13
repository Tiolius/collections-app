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
 * Вся работа с базой. table — "collections" или "items".
 * Почти все методы принимают uid — id текущего пользователя: чужие коллекции, объекты и типы не видны (404).
 */
@Repository
public class Repo {

    // в запросах таблица collections — с псевдонимом c; kind — название типа (для показа и поиска типа по названию)
    private static final String COLLECTION_COLS =
            "id, name, EXISTS (SELECT 1 FROM collection_pictures cp WHERE cp.collection_id = c.id) AS has_image, "
            + "(SELECT k.name FROM kinds k WHERE k.id = c.kind_id) AS kind, "
            + "(SELECT cp.hash FROM collection_pictures cp WHERE cp.collection_id = c.id) AS pics";

    /**
     * Значение поля объекта с ролью role (ключ FIELDS) — подзапрос к item_values: поле с этой ролью у типа коллекции
     * объекта; item — псевдоним таблицы items в запросе. role — ключ из FIELDS (в SQL не подставляется ввод пользователя;
     * проверка — по виду ключа: FIELDS при создании ITEM_COLS ещё не заполнен).
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
            // хеши картинок по порядку — адреса /img/<хеш> (кеш браузера по содержимому)
            + "(SELECT group_concat(ip.hash, ',' ORDER BY ip.pos) FROM item_pictures ip WHERE ip.item_id = items.id) AS pics";

    /** Сравнение по алфавиту для русского языка: без учёта регистра, «Ё» рядом с «Е», латиница перед кириллицей. */
    public static Comparator<String> alphabetical() {
        Collator c = Collator.getInstance(Locale.forLanguageTag("ru"));
        c.setStrength(Collator.SECONDARY);
        return c::compare;
    }

    /**
     * Готовый набор полей объекта (кроме названия, оно есть всегда): ключ → подпись.
     * Ключ = столбец в items. Тип коллекции выбирает поля из этого набора.
     */
    public static final Map<String, String> FIELDS = new LinkedHashMap<>();
    static {
        // подпись — ключ текста (messages*.properties)
        FIELDS.put("platform", "field.platform");
        FIELDS.put("format", "field.format");
        FIELDS.put("edition", "field.edition"); // не столбец items: регион / издание (таблица item_details)
        FIELDS.put("status", "field.status");
        FIELDS.put("rating", "field.rating"); // не столбец items: таблица item_ratings, 1…10 половинок звезды (5 звёзд)
        FIELDS.put("price", "field.price");
        FIELDS.put("purchased", "field.purchased"); // не столбец items: дата покупки ГГГГ-ММ-ДД (item_details)
        FIELDS.put("available", "field.available"); // не столбец items: отметки «есть» — в таблице item_present, по умолчанию «нет»
        FIELDS.put("tags", "field.tags"); // не столбец items: теги через «, » (item_details)
        FIELDS.put("notes", "field.notes"); // не столбец items: заметки (item_details), в таблице объектов не показываются
        FIELDS.put("oldgames", "field.oldgames"); // не столбец items: ссылка на страницу игры (таблица item_oldgames)
    }

    /** Вид поля по роли (kind_fields.type): как поле вводится, показывается и хранится. */
    public static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("platform", "text"), Map.entry("format", "text"), Map.entry("edition", "text"),
            Map.entry("status", "select"), Map.entry("rating", "rating"), Map.entry("price", "money"),
            Map.entry("purchased", "date"), Map.entry("available", "bool"), Map.entry("tags", "tags"),
            Map.entry("notes", "longtext"), Map.entry("oldgames", "link"));

    /** Виды своих полей (заданных пользователем в типе): ключ → подпись (ключ текста). */
    public static final Map<String, String> CUSTOM_TYPES = new LinkedHashMap<>();
    static {
        CUSTOM_TYPES.put("text", "ctype.text");
        CUSTOM_TYPES.put("longtext", "ctype.longtext");
        CUSTOM_TYPES.put("number", "ctype.number");
        CUSTOM_TYPES.put("date", "ctype.date");
        CUSTOM_TYPES.put("bool", "ctype.bool");
        CUSTOM_TYPES.put("select", "ctype.select");
    }

    /** Наибольшее число своих полей у типа; длины значений своих полей: текст и длинный текст. */
    public static final int MAX_CUSTOM = 30, MAX_TEXT = 500, MAX_LONGTEXT = 2000;

    /** Профили типов коллекций: ключ → подпись (ключ текста). Профиль задаёт подходящие источники импорта. */
    public static final Map<String, String> PROFILES = new LinkedHashMap<>();
    static {
        PROFILES.put("games", "profile.games");
        PROFILES.put("music", "profile.music");
        PROFILES.put("books", "profile.books");
        PROFILES.put("other", "profile.other");
    }

    /** Статусы прохождения начальных типов игр (у каждого типа свой список, настраивается в типе). */
    public static final List<String> GAME_STATUSES = List.of("Completed", "Unfinished");

    private final JdbcTemplate jdbc;
    /** Картинки объектов и коллекций — хранилище по содержимому (ImageFiles). */
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

    /** Рейтинг (1…10 половинок звезды) или null. */
    private static Integer rating(java.sql.ResultSet rs) throws java.sql.SQLException {
        int v = rs.getInt("rating");
        return rs.wasNull() ? null : v;
    }

    /** Цена или null (getObject(.., Integer.class) драйвер SQLite не умеет для NULL). */
    private static Integer price(java.sql.ResultSet rs) throws java.sql.SQLException {
        int v = rs.getInt("price");
        return rs.wasNull() ? null : v;
    }

    // ---------- типы коллекций ----------

    /** Все типы пользователя по алфавиту (с полями по порядку и значениями списков). */
    public List<Kind> kinds(long uid) {
        return loadKinds("k.user_id = ?", uid).stream().sorted(Comparator.comparing(Kind::name, alphabetical())).toList();
    }

    public Kind kind(long uid, long id) {
        List<Kind> list = loadKinds("k.user_id = ? AND k.id = ?", uid, id);
        if (list.isEmpty()) throw Fail.notFound();
        return list.get(0);
    }

    /** Тип пользователя по названию или null. */
    public Kind kindByName(long uid, String name) {
        if (name == null) return null;
        List<Kind> list = loadKinds("k.user_id = ? AND k.name = ?", uid, name);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Типы с полями и значениями списков: три запроса на всё. where — условие на kinds k (из кода, не ввод пользователя). */
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

    /** Цвета строк по статусу для типа: статус в нижнем регистре → цвет. */
    public Map<String, String> statusColors(long uid, String kindName) {
        Kind k = kindByName(uid, kindName);
        Map<String, String> res = new HashMap<>();
        if (k != null) k.statusColors().forEach((st, color) -> res.put(st.trim().toLowerCase(Locale.ROOT), color));
        return res;
    }

    /** Роли полей объектов коллекции этого типа по порядку. Если тип не найден — все поля, чтобы данные не прятались. */
    public Set<String> itemFields(long uid, String kindName) {
        Kind k = kindByName(uid, kindName);
        return k == null ? FIELDS.keySet() : k.fields();
    }

    /**
     * Создаёт (id == null) или изменяет тип: название, профиль, поля (roles — все роли в порядке формы, enabled —
     * отмеченные, labels — свои подписи в том же порядке, пусто — подпись роли), статусы и их цвета (пустой цвет — без цвета).
     * У оставленных полей сохраняются id — и значения объектов; снятые поля удаляются вместе со значениями. Возвращает id типа.
     */
    public long saveKind(long uid, Long id, String name, String profile, List<String> roles, List<String> enabled,
                         List<String> labels, List<String> statuses, List<String> colors) {
        return saveKind(uid, id, name, profile, roles, enabled, labels, List.of(), List.of(), statuses, colors);
    }

    /**
     * То же со своими полями. Строки формы полей (по порядку): roles — роль встроенного поля, «c<id>» — своё поле,
     * «n<номер>» — новое своё поле; enabled — отмеченные строки; labels — подписи (у своего — обязательна);
     * types — вид нового своего поля (CUSTOM_TYPES; у существующего не меняется); opts — значения списка
     * (вид «список»), по одному в строке. Снятое своё поле удаляется со значениями.
     */
    @org.springframework.transaction.annotation.Transactional // ошибка в форме — тип не меняется вовсе
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
            kind(uid, id); // чужой — 404
            jdbc.update("UPDATE kinds SET name = ?, profile = ? WHERE id = ?", n, profile, id);
            kindId = id;
        }
        // поля по порядку формы: встроенные роли (каждая один раз) и свои поля
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
                // своя подпись, если отличается от стандартной
                specs.add(new FieldSpec(r, null, null, !l.isEmpty() && !l.equals(I18n.t(FIELDS.get(r))) ? l : null, null));
            } else if (r.matches("c\\d{1,18}") || r.matches("n\\d{1,9}")) {
                Long fid = r.startsWith("c") ? Long.parseLong(r.substring(1)) : null;
                if (fid != null && !ownCustoms.contains(fid)) throw Fail.notFound(); // чужое поле
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

    /** Наибольшая длина своей подписи поля. */
    public static final int MAX_LABEL = 60;

    /** Поле в форме типа: встроенное (role) или своё (id — существующее, null — новое; вид, подпись, значения списка). */
    private record FieldSpec(String role, Long id, String type, String label, List<Kind.Option> options) {}

    private static String at(List<String> list, int i) {
        return list != null && i < list.size() ? trim(list.get(i)) : "";
    }

    /** Значения списка из формы: по одному в строке, пустые пропускаются, повтор (без учёта регистра) — ошибка. */
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

    /** Поля типа по порядку; прежние поля (встроенные — по роли, свои — по id) остаются со значениями, прочие удаляются. */
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

    /** Наибольшее число статусов у типа и длина статуса. */
    public static final int MAX_OPTIONS = 30, MAX_OPTION = 60;

    /** Статусы из формы по порядку: пустые пропускаются, повтор (без учёта регистра) — ошибка; цвет #rrggbb или пусто. */
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

    /** Удаляет тип, если ни одна коллекция его не использует. */
    public void deleteKind(long uid, long id) {
        Kind k = kind(uid, id);
        if (k.used() > 0)
            throw Fail.badRequest("kind.inUse", k.name(), k.used());
        jdbc.update("DELETE FROM kinds WHERE id = ?", id);
    }

    // ---------- пользователи ----------

    /** Роли пользователей: ключ (users.role) → название. Администратор может добавлять пользователей. */
    public static final String ADMIN = "admin", USER = "user";
    public static final Map<String, String> ROLES = new LinkedHashMap<>();
    static {
        ROLES.put(ADMIN, "role.admin"); // подпись — ключ текста
        ROLES.put(USER, "role.user");
    }

    /** Пользователь: id, имя, хеш пароля (см. Passwords), роль (ключ из ROLES). */
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

    /** Все пользователи по алфавиту. */
    public List<User> users() {
        return jdbc.query(USER_SELECT, USER_MAPPER).stream()
                .sorted(Comparator.comparing(User::name, alphabetical())).toList();
    }

    /** Пользователь по имени без учёта регистра (в т.ч. кириллицы) или null. */
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

    /** Показывать ли цены открыто (настройка «Отображение»); по умолчанию — нет, только в подсказке. */
    public boolean showPrices(long uid) {
        List<Integer> v = jdbc.queryForList("SELECT show_prices FROM user_settings WHERE user_id = ?", Integer.class, uid);
        return !v.isEmpty() && v.get(0) == 1;
    }

    public void setShowPrices(long uid, boolean show) {
        jdbc.update("INSERT INTO user_settings (user_id, show_prices) VALUES (?, ?) "
                + "ON CONFLICT (user_id) DO UPDATE SET show_prices = excluded.show_prices", uid, show ? 1 : 0);
    }

    /**
     * Пары тем оформления: ключ (атрибут data-pair у страницы, стили — app.css) → подписи тёмной и светлой темы
     * (ключи текстов). Переключатель в шапке меняет тёмную и светлую тему пары.
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

    /** Пара тем пользователя (ключ THEMES); не выбрана — первая. */
    public String themePair(long uid) {
        List<String> v = jdbc.queryForList("SELECT pair FROM user_theme WHERE user_id = ?", String.class, uid);
        return v.isEmpty() || !THEMES.containsKey(v.get(0)) ? THEMES.keySet().iterator().next() : v.get(0);
    }

    public void setThemePair(long uid, String pair) {
        if (!THEMES.containsKey(pair)) throw Fail.badRequest();
        jdbc.update("INSERT INTO user_theme (user_id, pair) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET pair = excluded.pair", uid, pair);
    }

    /**
     * Сколько показывать на странице (вкладка «Отображение»): коллекций на главной и объектов в таблице коллекции.
     * Нет строки или значения — по умолчанию; значения вне допустимых пределов (MIN_…/MAX_…) приводятся к ним.
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

    /** Запомненный вид таблицы объектов коллекции (строка параметров адреса) или null. */
    public String collectionView(long uid, long collectionId) {
        List<String> v = jdbc.queryForList("SELECT query FROM collection_views WHERE user_id = ? AND collection_id = ?",
                String.class, uid, collectionId);
        return v.isEmpty() ? null : v.get(0);
    }

    public void setCollectionView(long uid, long collectionId, String query) {
        jdbc.update("INSERT INTO collection_views (user_id, collection_id, query) VALUES (?, ?, ?) "
                + "ON CONFLICT (user_id, collection_id) DO UPDATE SET query = excluded.query", uid, collectionId, query);
    }

    /** Личный токен сервиса импорта (например, Discogs) или null. */
    public String token(long uid, String service) {
        List<String> v = jdbc.queryForList("SELECT token FROM user_tokens WHERE user_id = ? AND service = ?", String.class, uid, service);
        return v.isEmpty() ? null : v.get(0);
    }

    /** Сохраняет токен; пустой — удаляет. */
    public void setToken(long uid, String service, String token) {
        if (token == null || token.isBlank()) {
            jdbc.update("DELETE FROM user_tokens WHERE user_id = ? AND service = ?", uid, service);
            return;
        }
        jdbc.update("INSERT INTO user_tokens (user_id, service, token) VALUES (?, ?, ?) "
                + "ON CONFLICT (user_id, service) DO UPDATE SET token = excluded.token", uid, service, token);
    }

    /** Начальный тип коллекций нового пользователя: название (ключ текста), профиль, поля по порядку, статусы. */
    private record DefaultKind(String nameKey, String profile, List<String> fields, List<String> statuses) {}

    // название — ключ текста: типы создаются на языке интерфейса того, кто создаёт пользователя
    private static final List<DefaultKind> DEFAULT_KINDS = List.of(
            new DefaultKind("kind.default.gamesDiscs", "games",
                    List.of("status", "rating", "price", "available", "oldgames"), GAME_STATUSES),
            new DefaultKind("kind.default.gamesDigital", "games",
                    List.of("platform", "format", "status", "rating", "price", "available"), GAME_STATUSES),
            new DefaultKind("kind.default.books", "books", List.of("price", "available"), List.of()),
            new DefaultKind("kind.default.music", "music", List.of("price", "available"), List.of()));

    /** Создаёт пользователя с начальными типами коллекций. Возвращает id. */
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

    /** Новый пароль; запомненные компьютеры пользователя забываются (их ключи входа удаляются). */
    public void setPassword(long uid, String password) {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", Passwords.hash(password), uid);
        jdbc.update("DELETE FROM remember_tokens WHERE user_id = ?", uid);
    }

    // ---------- «Запомнить меня» (Remember): ключи входа, в базе — только их хеш ----------

    public void saveRememberToken(String hash, long uid, long expires) {
        jdbc.update("DELETE FROM remember_tokens WHERE expires < ?", System.currentTimeMillis());
        jdbc.update("INSERT INTO remember_tokens (hash, user_id, expires) VALUES (?, ?, ?)", hash, uid, expires);
    }

    /** Пользователь по хешу действующего ключа или null. */
    public Long rememberTokenUser(String hash, long now) {
        List<Long> ids = jdbc.queryForList("SELECT user_id FROM remember_tokens WHERE hash = ? AND expires > ?", Long.class, hash, now);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public void deleteRememberToken(String hash) {
        jdbc.update("DELETE FROM remember_tokens WHERE hash = ?", hash);
    }

    // ---------- чтение ----------

    /**
     * Все коллекции по алфавиту. Сортируем в Java: SQLite не умеет сравнивать кириллицу без учёта регистра.
     * Коллекций немного, поэтому страницу на главной вырезаем из этого списка.
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

    /** Поля, по которым можно сортировать объекты: ключ из URL → столбец. */
    public static final Map<String, String> ITEM_SORT = Map.of(
            "name", "name", "platform", "platform", "format", "format", "status", "status", "price", "price",
            "available", "available", "rating", "rating", "purchased", "purchased", "edition", "edition");

    /** Счётчики коллекции для главной: сколько объектов в наличии и общая стоимость (сумма цен). */
    public record CollectionStats(int available, long price) {}

    /** Счётчики всех коллекций пользователя (коллекции без объектов в карте нет). */
    public Map<Long, CollectionStats> collectionStats(long uid) {
        Map<Long, CollectionStats> res = new HashMap<>();
        jdbc.query("SELECT i.collection_id, SUM(" + val("available", "i") + " IS NOT NULL), "
                + "COALESCE(SUM(" + val("price", "i") + "), 0) FROM items i JOIN collections c ON c.id = i.collection_id "
                + "WHERE c.user_id = ? GROUP BY i.collection_id",
                rs -> { res.put(rs.getLong(1), new CollectionStats(rs.getInt(2), rs.getLong(3))); }, uid);
        return res;
    }

    /** Общая стоимость коллекции — сумма цен объектов (объекты без цены не считаются). */
    public long totalPrice(long collectionId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(" + val("price", "items") + "), 0) FROM items WHERE collection_id = ?",
                Long.class, collectionId);
    }

    public int countItems(long collectionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM items WHERE collection_id = ?", Integer.class, collectionId);
    }

    /** Столбцы, по которым фильтр — выбор значения из списка (точное совпадение). */
    public static final List<String> SELECT_FILTERS = List.of("platform", "format", "status");

    /**
     * Объекты коллекции с фильтрами и сортировкой (страницу вырезает вызывающий код).
     * filters: "name" — часть названия без учёта регистра (в т.ч. кириллицы, поэтому проверяется в Java),
     * ключи из SELECT_FILTERS — точное значение. sort — ключ из ITEM_SORT (иначе по названию);
     * пустые значения всегда в конце, при равенстве — по названию.
     */
    public List<Entry> items(long collectionId, String sort, boolean desc, Map<String, String> filters) {
        // своё поле типа коллекции — «c<id>»: сортировка и фильтры по значению в item_values
        Map<Long, String> customs = new HashMap<>();
        jdbc.query("SELECT f.id, f.type FROM kind_fields f JOIN collections c ON c.kind_id = f.kind_id "
                + "WHERE c.id = ? AND f.role IS NULL", rs -> { customs.put(rs.getLong(1), rs.getString(2)); }, collectionId);
        Long sortCustom = customKey(sort, customs);
        String col = sortCustom != null ? cval(sortCustom) : ITEM_SORT.getOrDefault(sort, "name"); // иначе — псевдоним столбца ITEM_COLS
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
                where.append(" AND ").append(val(f, "items")).append(" = ?"); // f — только из SELECT_FILTERS
                args.add(filters.get(f));
            }
        // Цена «от — до»: значения уже проверены в контроллере (целые числа); объекты без цены не подходят
        if (filters.containsKey("price_min")) {
            where.append(" AND ").append(val("price", "items")).append(" >= ?");
            args.add(Long.parseLong(filters.get("price_min")));
        }
        if (filters.containsKey("price_max")) {
            where.append(" AND ").append(val("price", "items")).append(" <= ?");
            args.add(Long.parseLong(filters.get("price_max")));
        }
        // Наличие: "1" — есть, "0" — нет
        if (filters.containsKey("available"))
            where.append(" AND ").append(val("available", "items")).append("1".equals(filters.get("available")) ? " IS NOT NULL" : " IS NULL");
        // Рейтинг: "0" — без рейтинга, "1".."5" — не меньше стольких звёзд
        String rating = filters.get("rating");
        if (rating != null) {
            String r = val("rating", "items");
            if (rating.equals("0")) where.append(" AND ").append(r).append(" IS NULL");
            else {
                where.append(" AND ").append(r).append(" >= ?");
                args.add(Integer.parseInt(rating) * 2);
            }
        }
        // Регион / издание — точное значение; год покупки — дата начинается с него
        if (filters.containsKey("edition")) {
            where.append(" AND ").append(val("edition", "items")).append(" = ?");
            args.add(filters.get("edition"));
        }
        if (filters.containsKey("purchased")) {
            where.append(" AND ").append(val("purchased", "items")).append(" LIKE ?");
            args.add(filters.get("purchased") + "-%");
        }
        // Картинки: "any" — есть хотя бы одна, "0" — нет ни одной, "1".."5" — ровно столько
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
        // Тег — без учёта регистра (в т.ч. кириллицы), поэтому в Java
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

    /** Значение своего поля объекта (подзапрос; items — таблица в запросе). fieldId — число, не ввод пользователя. */
    private static String cval(long fieldId) {
        return "(SELECT v.value FROM item_values v WHERE v.item_id = items.id AND v.field_id = " + fieldId + ")";
    }

    /** id своего поля по ключу «c<id>», если такое поле есть среди customs; иначе null. */
    private static Long customKey(String key, Map<Long, String> customs) {
        if (key == null || !key.matches("c\\d{1,18}")) return null;
        long id = Long.parseLong(key.substring(1));
        return customs.containsKey(id) ? id : null;
    }

    // ---------- свои поля: значения ----------

    /** Значения своих полей объектов: id объекта → (id поля → значение: текст или число). Поля — типа коллекции объекта. */
    public Map<Long, Map<Long, Object>> customValues(java.util.Collection<Long> itemIds) {
        Map<Long, Map<Long, Object>> res = new HashMap<>();
        List<Long> ids = List.copyOf(itemIds);
        for (int i = 0; i < ids.size(); i += 500) { // по частям: у SQLite ограничено число параметров
            List<Long> part = ids.subList(i, Math.min(ids.size(), i + 500));
            jdbc.query("SELECT v.item_id, v.field_id, v.value FROM item_values v JOIN kind_fields f ON f.id = v.field_id "
                    + "JOIN items i ON i.id = v.item_id JOIN collections c ON c.id = i.collection_id "
                    + "WHERE f.role IS NULL AND f.kind_id = c.kind_id AND v.item_id IN ("
                    + String.join(",", java.util.Collections.nCopies(part.size(), "?")) + ")", rs -> {
                Object v = rs.getObject(3);
                // целые числа — Long (драйвер отдаёт небольшие как Integer)
                res.computeIfAbsent(rs.getLong(1), x -> new HashMap<>()).put(rs.getLong(2), v instanceof Integer n ? (Object) n.longValue() : v);
            }, part.toArray());
        }
        return res;
    }

    /** Значения своего поля у объектов коллекции — для выпадающего списка фильтра (по алфавиту, без пустых). */
    public List<String> customDistinct(long collectionId, long fieldId) {
        return jdbc.queryForList("SELECT DISTINCT v.value FROM item_values v JOIN items i ON i.id = v.item_id "
                + "WHERE i.collection_id = ? AND v.field_id = ?", Object.class, collectionId, fieldId).stream()
                .map(String::valueOf).sorted(alphabetical()).toList();
    }

    /**
     * Записывает значение своего поля объекта по виду поля (поле — типа коллекции объекта, иначе ничего): текст и длинный
     * текст — до MAX_TEXT / MAX_LONGTEXT; число — целое или дробное (точка или запятая); дата — ГГГГ-ММ-ДД; да/нет —
     * «1» или пусто; список — значение из списка (или прежнее значение). Пусто — значения нет; неверное — 400.
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

    /** Число из поля: целое — Long, дробное — Double (запятая = точка); не число — 400. */
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

    /** Сколько объектов показывать в поиске по всем коллекциям. */
    public static final int SEARCH_LIMIT = 200;

    /**
     * Поиск по всем коллекциям пользователя: часть названия, тега, региона / издания или заметки без учёта регистра
     * (в т.ч. кириллицы — поэтому в Java). Объекты — по алфавиту, не больше SEARCH_LIMIT; total — сколько найдено всего.
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
        // свои поля: текст, длинный текст, список
        Set<Long> already = new java.util.HashSet<>(ids);
        jdbc.query("SELECT v.item_id, v.value FROM item_values v JOIN kind_fields f ON f.id = v.field_id "
                + "JOIN items i ON i.id = v.item_id JOIN collections c ON c.id = i.collection_id "
                + "WHERE c.user_id = ? AND f.kind_id = c.kind_id AND f.role IS NULL AND f.type IN ('text', 'longtext', 'select')", rs -> {
            String v = rs.getString(2);
            if (v != null && v.toLowerCase(RU).contains(q) && already.add(rs.getLong(1))) ids.add(rs.getLong(1));
        }, uid);
        if (ids.isEmpty()) return new SearchResult(List.of(), 0);
        List<Entry> found = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i += 500) { // по частям: у SQLite ограничено число параметров
            List<Long> part = ids.subList(i, Math.min(ids.size(), i + 500));
            found.addAll(jdbc.query("SELECT " + ITEM_COLS + " FROM items WHERE id IN ("
                    + String.join(",", java.util.Collections.nCopies(part.size(), "?")) + ")", mapper(true, false), part.toArray()));
        }
        Comparator<String> abc = alphabetical();
        return new SearchResult(found.stream().sorted(Comparator.comparing(Entry::name, abc).thenComparing(Entry::id))
                .limit(SEARCH_LIMIT).toList(), found.size());
    }

    // ---------- статистика ----------

    /**
     * Счётчики коллекции для страницы «Статистика»: объектов, в наличии, пройдено / не пройдено, с рейтингом и средний
     * рейтинг (в звёздах), стоимость, с картинками, с описанием.
     */
    public record Stats(long collectionId, int items, int available, int rated,
                        double avgRating, long price, int withImages, int withDescription) {}

    /** Счётчики всех коллекций пользователя: id коллекции → счётчики (коллекции без объектов — нули). */
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

    /** Сколько объектов с каждым статусом: id коллекции → (статус → число); объекты без статуса не считаются. */
    public Map<Long, Map<String, Integer>> statusCounts(long uid) {
        Map<Long, Map<String, Integer>> res = new HashMap<>();
        jdbc.query("SELECT cid, st, COUNT(*) FROM (SELECT i.collection_id AS cid, " + val("status", "i") + " AS st FROM items i "
                + "JOIN collections c ON c.id = i.collection_id WHERE c.user_id = ?) WHERE st IS NOT NULL AND st <> '' GROUP BY 1, 2",
                rs -> { res.computeIfAbsent(rs.getLong(1), x -> new HashMap<>()).put(rs.getString(2), rs.getInt(3)); }, uid);
        return res;
    }

    /** Сколько объектов с рейтингом в каждой целой звезде (округление вверх: 3,5 → 4): индекс 0 — 1 звезда … 4 — 5 звёзд. */
    public int[] ratingHistogram(long uid) {
        int[] res = new int[5];
        jdbc.query("SELECT (r + 1) / 2, COUNT(*) FROM (SELECT " + val("rating", "i") + " AS r FROM items i "
                + "JOIN collections c ON c.id = i.collection_id WHERE c.user_id = ?) WHERE r IS NOT NULL GROUP BY 1",
                rs -> { int s = rs.getInt(1); if (s >= 1 && s <= 5) res[s - 1] = rs.getInt(2); }, uid);
        return res;
    }

    /** Покупки по годам (дата покупки): год → {объектов, сумма цен}; новые годы сверху. */
    public Map<String, long[]> purchasesByYear(long uid) {
        Map<String, long[]> res = new java.util.TreeMap<>(Comparator.reverseOrder());
        jdbc.query("SELECT substr(p, 1, 4), COUNT(*), COALESCE(SUM(price), 0) FROM (SELECT " + val("purchased", "i") + " AS p, "
                + val("price", "i") + " AS price FROM items i JOIN collections c ON c.id = i.collection_id WHERE c.user_id = ?) "
                + "WHERE p IS NOT NULL GROUP BY 1",
                rs -> { res.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)}); }, uid);
        return res;
    }

    /** Самые частые теги: тег → сколько объектов, не больше limit, по убыванию. */
    public List<Map.Entry<String, Integer>> topTags(long uid, int limit) {
        Map<String, Integer> count = new java.util.TreeMap<>(alphabetical());
        jdbc.query("SELECT t FROM (SELECT " + val("tags", "i") + " AS t FROM items i JOIN collections c ON c.id = i.collection_id "
                + "WHERE c.user_id = ?) WHERE t IS NOT NULL", rs -> {
            for (String t : splitTags(rs.getString(1))) count.merge(t, 1, Integer::sum);
        }, uid);
        return count.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(limit).toList();
    }

    /** Значения дополнительных полей для фильтров: регион / издание, теги, годы покупки (новые сверху). */
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

    /** Значения столбца у объектов коллекции — для выпадающего списка фильтра (по алфавиту, без пустых). */
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
     * Картинка по хешу содержимого (адрес /img/<хеш>), если на неё ссылается объект или коллекция этого пользователя;
     * иначе null (чужие картинки не отдаются, даже если знать хеш).
     */
    public ImageFiles.Pic ownedPicture(long uid, String hash) {
        List<String> mime = jdbc.queryForList("SELECT p.mime FROM pictures p WHERE p.hash = ? AND ("
                + "EXISTS (SELECT 1 FROM item_pictures ip JOIN items i ON i.id = ip.item_id JOIN collections c ON c.id = i.collection_id "
                + "WHERE ip.hash = p.hash AND c.user_id = ?) OR "
                + "EXISTS (SELECT 1 FROM collection_pictures cp JOIN collections c ON c.id = cp.collection_id "
                + "WHERE cp.hash = p.hash AND c.user_id = ?))", String.class, hash, uid, uid);
        return mime.isEmpty() ? null : new ImageFiles.Pic(hash, mime.get(0));
    }

    /** Содержимое картинки (null — файла нет). */
    public byte[] pictureBytes(ImageFiles.Pic pic) {
        return files.read(pic);
    }

    /** Уменьшенная копия картинки или null — тогда отдаётся сама картинка. */
    public ImageFiles.Thumb thumb(ImageFiles.Pic pic) {
        return files.thumb(pic);
    }

    // ---------- изменение ----------

    public long createCollection(long uid) {
        return jdbc.queryForObject("INSERT INTO collections (user_id, name) VALUES (?, '') RETURNING id", Long.class, uid);
    }

    public long createItem(long collectionId) {
        return jdbc.queryForObject("INSERT INTO items (collection_id, name) VALUES (?, '') RETURNING id",
                Long.class, collectionId);
    }

    /** Сохраняет коллекцию: название, тип и (если загружена) картинку; removeImage удаляет картинку. */
    public void saveCollection(long uid, long id, String name, String kind, MultipartFile image, boolean removeImage) {
        requireName(name);
        Kind k = kindByName(uid, kind);
        if (k == null) throw Fail.badRequest("coll.kindRequired");
        Long old = jdbc.queryForObject("SELECT kind_id FROM collections WHERE id = ?", Long.class, id);
        jdbc.update("UPDATE collections SET name = ?, kind_id = ? WHERE id = ?", name.trim(), k.id(), id);
        // другой тип — значения полей переходят к полям нового типа с той же ролью
        if (old != null && old != k.id())
            remap("SELECT id FROM items WHERE collection_id = ?", id, k.id());

        if (image != null && !image.isEmpty()) {
            files.setCollection(id, readImage(image));
        } else if (removeImage) {
            files.setCollection(id, null);
        }
    }

    /** Наибольшая длина описания объекта. */
    public static final int MAX_DESCRIPTION = 5000;

    /** Записывает описание объекта (пустое — удаляет); владельца проверяет вызывающий код. */
    public void setItemDescription(long itemId, String text) {
        String t = text == null ? "" : text.replace("\r\n", "\n").trim();
        if (t.length() > MAX_DESCRIPTION)
            throw Fail.badRequest("item.descriptionTooLong", MAX_DESCRIPTION);
        if (t.isEmpty()) jdbc.update("DELETE FROM item_descriptions WHERE item_id = ?", itemId);
        else jdbc.update("INSERT INTO item_descriptions (item_id, text) VALUES (?, ?) ON CONFLICT (item_id) "
                + "DO UPDATE SET text = excluded.text", itemId, t);
    }

    /**
     * Заменяет все картинки объекта (до ImageFiles.MAX_IMAGES, по порядку; пустой список — удаляет все).
     * Не картинки пропускаются. Владельца проверяет вызывающий код.
     */
    public void setItemImages(long itemId, List<byte[]> images) {
        files.setItem(itemId, images);
    }

    /** Сколько картинок у объектов каждой коллекции пользователя: id коллекции → число (вкладка «Кэш»). */
    public Map<Long, Integer> collectionImageCounts(long uid) {
        Map<Long, Integer> counts = new HashMap<>();
        jdbc.query("SELECT i.collection_id, COUNT(*) FROM item_pictures ip JOIN items i ON i.id = ip.item_id "
                + "JOIN collections c ON c.id = i.collection_id WHERE c.user_id = ? GROUP BY i.collection_id",
                rs -> { counts.put(rs.getLong(1), rs.getInt(2)); }, uid);
        return counts;
    }

    /** Содержимое загруженной картинки; если это не PNG, JPEG или GIF — ошибка 400. */
    private static byte[] readImage(MultipartFile image) {
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw Fail.badRequest("image.readError");
        }
        // Тип определяется по содержимому файла, а не по тому, что прислал браузер
        if (imageType(bytes) == null)
            throw Fail.badRequest("image.badFormat");
        return bytes;
    }

    /**
     * Тип картинки по первым байтам файла: PNG, JPEG, GIF; иначе null (в т.ч. SVG — в нём бывают скрипты).
     * WebP не принимается (по просьбе пользователя): Java не умеет его читать, и для него не сделать уменьшенную копию.
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
     * Сохраняет объект: название и те поля, которые есть у типа коллекции (см. настройки типа).
     * null в форме — поля нет в форме, значение не меняется; пусто — очистить.
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
            // только значения из списка типа; прежнее значение не из списка можно оставить как есть
            String st = trim(form.status());
            if (!st.isEmpty() && (k == null || !k.statuses().contains(st)) && !st.equals(value(id, "status")))
                throw Fail.badRequest("item.badStatus");
            setValue(id, "status", st);
        }
        if (f.contains("price")) setValue(id, "price", price);
        // Рейтинг: 0 — убрать, 1…10 — половинки звезды; null — поля нет в форме, не меняется
        if (f.contains("rating") && rating != null) setRating(id, rating);
        // Ссылка на Old-Games.ru: пусто — убрать; только адрес страницы игры; null — поля нет в форме, не меняется
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
        // Описание и картинка есть у всех объектов, независимо от типа коллекции
        if (form.description() != null) setItemDescription(id, form.description());
        // Картинки: новый файл на месте pos заменяет картинку (или добавляет), removed — удалить; без пропусков по порядку
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

    /** Значение поля объекта с ролью role (у типа его коллекции) или null. */
    public Object value(long itemId, String role) {
        List<Object> v = jdbc.queryForList("SELECT " + val(role, "items") + " FROM items WHERE id = ?", Object.class, itemId);
        return v.isEmpty() ? null : v.get(0);
    }

    /**
     * Записывает значение поля объекта с ролью role (поле типа его коллекции); у типа нет такого поля — ничего.
     * null или пустая строка — значения нет (строка удаляется). Владельца проверяет вызывающий код.
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

    /** Статус: пусто или значение из списка типа коллекции объекта (массовое изменение — без прежних значений не из списка). */
    public void setStatus(long uid, long itemId, String status) {
        setValue(itemId, "status", checkStatus(uid, itemId, status));
    }

    /** Статус из списка типа коллекции объекта или пусто (без пробелов по краям); иначе — 400. */
    public String checkStatus(long uid, long itemId, String status) {
        String st = trim(status);
        if (st.isEmpty()) return st;
        Entry it = item(uid, itemId);
        Kind k = kindByName(uid, collection(uid, it.collectionId()).kind());
        if (k == null || !k.statuses().contains(st)) throw Fail.badRequest("item.badStatus");
        return st;
    }

    /**
     * Переносит объект в другую коллекцию (владельца обеих проверяет вызывающий код); картинки не меняются,
     * значения полей переходят к полям типа новой коллекции с той же ролью.
     */
    public void moveItem(long itemId, long collectionId) {
        jdbc.update("UPDATE items SET collection_id = ? WHERE id = ?", collectionId, itemId);
        Long kindId = jdbc.queryForObject("SELECT kind_id FROM collections WHERE id = ?", Long.class, collectionId);
        if (kindId != null) remap("SELECT ?", itemId, kindId);
    }

    /**
     * Значения объектов (items — подзапрос id объектов с одним параметром arg), чьи поля не из типа kindId, переходят
     * к полю этого типа с той же ролью. Если у объекта уже есть значение нового поля — оно остаётся; значения полей,
     * которых у нового типа нет, сохраняются скрытыми (вернутся, если вернуть прежний тип).
     */
    private void remap(String items, Object arg, long kindId) {
        jdbc.update("UPDATE OR IGNORE item_values SET field_id = (SELECT nf.id FROM kind_fields nf JOIN kind_fields of "
                + "ON of.id = item_values.field_id WHERE nf.kind_id = ? AND nf.role = of.role) "
                + "WHERE item_id IN (" + items + ") AND field_id NOT IN (SELECT id FROM kind_fields WHERE kind_id = ?) "
                + "AND EXISTS (SELECT 1 FROM kind_fields nf JOIN kind_fields of ON of.id = item_values.field_id "
                + "WHERE nf.kind_id = ? AND nf.role = of.role)", kindId, arg, kindId, kindId);
    }

    /**
     * Поля объекта из окна объекта (новый и изменение — одна форма). null — поля нет в форме: значение не меняется.
     * available — значения галочки «Наличие»: перед ней скрытое 0, отмеченная галочка добавляет 1.
     */
    public record ItemForm(String name, String platform, String format, String status, Integer price,
                           List<String> available, Integer rating, String oldgames, String purchased, String tags,
                           String edition, String notes, String description) {
        Boolean availableValue() {
            return available == null ? null : available.contains("1");
        }
    }

    /** Наибольшая длина тегов (всех вместе), региона / издания и заметок. */
    public static final int MAX_TAGS = 500, MAX_EDITION = 200, MAX_NOTES = 2000;

    /** Наличие объекта: есть — значение 1, нет — значения нет. */
    public void setAvailable(long itemId, boolean available) {
        setValue(itemId, "available", available ? 1 : null);
    }

    /** Рейтинг объекта: 0 — убрать, 1…10 — половинки звезды. */
    public void setRating(long itemId, int rating) {
        if (rating < 0 || rating > 10) throw Fail.badRequest("item.badRating");
        setValue(itemId, "rating", rating == 0 ? null : rating);
    }

    /** Дата покупки из поля даты (ГГГГ-ММ-ДД) или пусто; другое — 400. */
    private static String purchaseDate(String value) {
        String v = trim(value);
        if (v.isEmpty()) return "";
        try {
            return java.time.LocalDate.parse(v).toString();
        } catch (java.time.format.DateTimeParseException e) {
            throw Fail.badRequest("item.badDate");
        }
    }

    /** Теги: через запятую, без пробелов по краям и повторов (без учёта регистра), в порядке ввода. */
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

    /** Удаляет запись; владельца проверяет вызывающий код (collection/item с uid). */
    public void delete(String table, long id) {
        // ссылки на картинки удаляются каскадно вместе с записью; файлы — если на них больше никто не ссылается
        List<String> pics = table.equals("items") ? files.itemHashes(id) : files.collectionHashes(id);
        jdbc.update("DELETE FROM " + table + " WHERE id = ?", id); // объекты коллекции удаляются каскадно
        files.release(pics);
    }

    // ---------- вспомогательное ----------

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
