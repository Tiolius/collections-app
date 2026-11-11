package app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Тип коллекции: название, профиль (Repo.PROFILES), роли встроенных полей по порядку (fields — ключи из Repo.FIELDS),
 * сколько коллекций этого типа и описания всех полей по порядку (defs: встроенные и свои — подпись, вид, значения списка).
 */
public record Kind(long id, String name, String profile, Set<String> fields, int used, List<Field> defs) {

    /**
     * Поле типа: роль (ключ Repo.FIELDS; null — своё поле), вид (Repo.TYPES), подпись (у встроенного — своя или null:
     * подпись роли; у своего — всегда), значения списка.
     */
    public record Field(long id, String role, String type, String label, List<Option> options) {
        /** Ключ поля в формах, адресах и разметке: роль у встроенного, «c<id>» у своего. */
        public String key() {
            return role != null ? role : "c" + id;
        }

        public boolean custom() {
            return role == null;
        }

        public List<String> values() {
            return options.stream().map(Option::value).toList();
        }
    }

    /** Значение поля-списка и цвет строки объекта с этим значением (#rrggbb или null). */
    public record Option(String value, String color) {}

    /** Поле с ролью или ключом «c<id>» (своё поле) либо null. */
    public Field field(String key) {
        return defs.stream().filter(f -> key.equals(f.key())).findFirst().orElse(null);
    }

    /** Свои поля типа по порядку. */
    public List<Field> customs() {
        return defs.stream().filter(Field::custom).toList();
    }

    /** Ключи всех полей по порядку: роли встроенных и «c<id>» своих. */
    public List<String> keys() {
        return defs.stream().filter(f -> f.custom() || Repo.FIELDS.containsKey(f.role())).map(Field::key).toList();
    }

    /** Подпись поля для показа: своя или подпись роли на языке интерфейса. */
    public String label(String key) {
        Field f = field(key);
        if (f != null && f.label() != null) return f.label();
        return Repo.FIELDS.containsKey(key) ? I18n.t(Repo.FIELDS.get(key)) : key;
    }

    /** Подписи всех полей типа по порядку: ключ → подпись. */
    public Map<String, String> labels() {
        Map<String, String> res = new LinkedHashMap<>();
        for (String k : keys()) res.put(k, label(k));
        return res;
    }

    /** Значения поля «Статус» по порядку (пусто — поля нет или список пуст). */
    public List<String> statuses() {
        Field f = field("status");
        return f == null ? List.of() : f.values();
    }

    /** Цвета строк по статусу: статус → цвет (только статусы с цветом), в порядке списка. */
    public Map<String, String> statusColors() {
        Map<String, String> res = new LinkedHashMap<>();
        Field f = field("status");
        if (f != null)
            for (Option o : f.options())
                if (o.color() != null) res.put(o.value(), o.color());
        return res;
    }
}
