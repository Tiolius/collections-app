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
 * Загрузка коллекций из файла Excel («Настройки» → «Импорт» → «Загрузка из Excel»): каждый лист — коллекция с названием
 * листа. Если коллекция с таким названием уже есть (без учёта регистра), данные пишутся в неё: объекты с тем же названием
 * обновляются (только заполненными ячейками), остальные добавляются; тип у неё не меняется. Столбцы узнаются по заголовкам — тем же, что пишет выгрузка в Excel (по-русски или по-английски),
 * обязателен «Название»; прочие столбцы и поля, которых нет у типа коллекции, пропускаются. Непонятные значения
 * (статус не из списка, рейтинг вне 0,5…5, дата не дата) не загружаются, объект — загружается.
 * Всё в одной транзакции: при ошибке не создаётся ничего.
 */
@Component
public class XlsxImport {

    /**
     * Итог: сколько коллекций создано и дополнено, объектов добавлено и обновлено, первая коллекция (для ссылки),
     * пропущенные столбцы (заголовки).
     */
    public record Result(int created, int updated, int added, int changed, long firstCollection, List<String> skipped) {
        public int collections() {
            return created + updated;
        }
    }

    /** Столбец «Описание» — не поле типа: описание есть у всех объектов. */
    static final String NAME = "name", DESCRIPTION = "description";

    private final Repo repo;
    private final TransactionTemplate tx;

    public XlsxImport(Repo repo, TransactionTemplate tx) {
        this.repo = repo;
        this.tx = tx;
    }

    /**
     * Загружает листы файла как новые коллекции пользователя. kind — тип для всех коллекций; пусто — для каждого листа
     * подбирается тип, у которого больше всего полей из столбцов листа (при равенстве — с меньшим числом лишних полей).
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
        // листы со столбцом «Название» и хотя бы одной строкой под заголовком
        List<Object[]> todo = new ArrayList<>(); // [лист, номер строки заголовков, столбцы: номер → поле]
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
                // своя коллекция с тем же названием — данные в неё, с её типом
                Entry existing = repo.collections(uid).stream().filter(c -> same(c.name(), name)).findFirst().orElse(null);
                String k = existing != null && kinds.stream().anyMatch(x -> x.name().equals(existing.kind())) ? existing.kind()
                        : chosen.isEmpty() ? bestKind(kinds, cols.values()) : chosen;
                Kind target = kinds.stream().filter(x -> x.name().equals(k)).findFirst().orElseThrow();
                Set<String> fields = target.fields();
                List<Object> header = sh.rows().get(headerRow);
                for (int c = 0; c < header.size(); c++) {
                    String h = text(header.get(c));
                    if (h == null) continue;
                    String f = cols.get(c);
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
                // объекты коллекции по названию; несколько с одним названием — по порядку: n-я строка файла
                // с этим названием обновляет n-й объект (повторная загрузка того же файла не создаёт дублей)
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
                    if (old != null) {
                        repo.saveItem(uid, old.id(), k, keepOld(form, old), Map.of(), Set.of());
                        changed++;
                    } else {
                        repo.saveItem(uid, repo.createItem(cid), k, form, Map.of(), Set.of());
                        added++;
                    }
                }
            }
            return new Result(created, updated, added, changed, first, List.copyOf(skipped));
        });
    }

    /** Одно и то же название коллекции или объекта: без пробелов по краям и без учёта регистра. */
    static boolean same(String a, String b) {
        return nameKey(a).equals(nameKey(b));
    }

    private static String nameKey(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(I18n.RU);
    }

    /**
     * Форма для обновления объекта: пустые и неверные ячейки не меняют значение. Платформу, формат, статус и цену
     * saveItem пишет всегда (пустое — очищает), поэтому для них берутся прежние значения; название — прежнее.
     */
    private static Repo.ItemForm keepOld(Repo.ItemForm f, Entry old) {
        return new Repo.ItemForm(old.name(), f.platform() != null ? f.platform() : old.platform(),
                f.format() != null ? f.format() : old.format(), f.status() != null ? f.status() : old.status(),
                f.price() != null ? f.price() : old.price(), f.available(), f.rating(), f.oldgames(), f.purchased(),
                f.tags(), f.edition(), f.notes(), f.description());
    }

    /**
     * Заголовки столбцов (без регистра и лишних пробелов) → поле: свои подписи полей в типах пользователя
     * (так их выгружает экспорт), стандартные подписи на обоих языках и ключи полей.
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
        // стандартные подписи — главнее своих: свою подпись «Цена» у одного типа не спутать со столбцом другого поля
        res.put(NAME, NAME);
        res.put(DESCRIPTION, DESCRIPTION);
        return res;
    }

    private static String key(String header) {
        return header.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Столбцы строки заголовков: номер → поле (повтор заголовка — берётся первый столбец). */
    private static Map<Integer, String> columns(List<Object> header, Map<String, String> labels) {
        Map<Integer, String> res = new LinkedHashMap<>();
        for (int c = 0; c < header.size(); c++) {
            String h = text(header.get(c));
            String f = h == null ? null : labels.get(key(h));
            if (f != null && !res.containsValue(f)) res.put(c, f);
        }
        return res;
    }

    /** Тип, у которого больше всего полей из столбцов; при равенстве — меньше лишних полей; затем — по алфавиту. */
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

    /** Поля объекта из строки; без названия — null (строка пропускается). Нет столбца — null: поле не заполняется. */
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

    /** Текст ячейки без пробелов по краям; число — без «.0» (12 → «12»); пусто — null. */
    static String text(Object v) {
        if (v == null) return null;
        String s = v instanceof Double d ? BigDecimal.valueOf(d).stripTrailingZeros().toPlainString() : v.toString();
        s = s.replace("\r\n", "\n").trim();
        return s.isEmpty() ? null : s;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max).trim();
    }

    /** Статус как есть; проверяется по списку статусов типа коллекции при загрузке (statusOf). */
    static String status(Object v) {
        return text(v);
    }

    /** Форма со статусом из списка типа (без учёта регистра — как в списке); статуса нет в списке — не загружается. */
    static Repo.ItemForm statusOf(Repo.ItemForm f, Kind kind) {
        if (f.status() == null) return f;
        String st = kind == null ? null
                : kind.statuses().stream().filter(x -> x.equalsIgnoreCase(f.status())).findFirst().orElse(null);
        return new Repo.ItemForm(f.name(), f.platform(), f.format(), st, f.price(), f.available(), f.rating(), f.oldgames(),
                f.purchased(), f.tags(), f.edition(), f.notes(), f.description());
    }

    /** Рейтинг в звёздах (0,5…5, как в выгрузке) → 1…10 половинок; другое — не загружается. */
    static Integer rating(Object v) {
        Double d = number(v);
        if (d == null) return null;
        long r = Math.round(d * 2);
        return r >= 1 && r <= 10 ? (int) r : null;
    }

    /** Цена — целое число рублей, не меньше 0 («1 500 ₽» тоже); другое — не загружается. */
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

    /** Наличие: «Есть», «Yes», «да», «1», «+» — есть; другое — нет (как в окне объекта: «0», «1»); пусто — null (не менять). */
    static List<String> available(Object v) {
        String s = text(v);
        if (s == null) return null;
        boolean yes = s != null && List.of("есть", "yes", "да", "true", "1", "+", "✓", "✔").contains(s.toLowerCase(Locale.ROOT));
        return yes ? List.of("0", "1") : List.of("0");
    }

    /** Дата покупки: ГГГГ-ММ-ДД, ДД.ММ.ГГГГ или дата Excel (число дней); пусто или не дата — null (не менять). */
    static String date(Object v) {
        if (v instanceof Double d) {
            // даты Excel — дни от 30.12.1899
            return d >= 1 && d < 2958466 ? LocalDate.of(1899, 12, 30).plusDays(d.longValue()).toString() : null;
        }
        String s = text(v);
        if (s == null) return null;
        for (DateTimeFormatter f : List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d.M.uuuu")))
            try {
                return LocalDate.parse(s.length() > 10 && f == DateTimeFormatter.ISO_LOCAL_DATE ? s.substring(0, 10) : s, f).toString();
            } catch (java.time.format.DateTimeParseException e) {
                // следующий формат
            }
        return null;
    }

    /** Номер или адрес игры Old-Games.ru; другое — не загружается. */
    static String oldGames(Object v) {
        String s = text(v);
        return s != null && OldGames.normalize(s) != null ? s : null;
    }
}
