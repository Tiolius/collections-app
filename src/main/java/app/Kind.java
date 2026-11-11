package app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Тип коллекции: название, профиль (Repo.PROFILES), роли полей объектов по порядку (fields — ключи из Repo.FIELDS),
 * сколько коллекций этого типа и описания полей (defs: подпись, вид, значения списка с цветами).
 */
public record Kind(long id, String name, String profile, Set<String> fields, int used, List<Field> defs) {

    /** Поле типа: роль (ключ Repo.FIELDS), вид (Repo.TYPES), своя подпись (null — подпись роли), значения списка. */
    public record Field(long id, String role, String type, String label, List<Option> options) {}

    /** Значение поля-списка и цвет строки объекта с этим значением (#rrggbb или null). */
    public record Option(String value, String color) {}

    /** Поле с ролью или null. */
    public Field field(String role) {
        return defs.stream().filter(f -> role.equals(f.role())).findFirst().orElse(null);
    }

    /** Подпись поля для показа: своя или подпись роли на языке интерфейса. */
    public String label(String role) {
        Field f = field(role);
        return f != null && f.label() != null ? f.label() : I18n.t(Repo.FIELDS.get(role));
    }

    /** Подписи всех полей типа: роль → подпись. */
    public Map<String, String> labels() {
        Map<String, String> res = new LinkedHashMap<>();
        for (String r : fields) res.put(r, label(r));
        return res;
    }

    /** Значения поля «Статус» по порядку (пусто — поля нет или список пуст). */
    public List<String> statuses() {
        Field f = field("status");
        return f == null ? List.of() : f.options().stream().map(Option::value).toList();
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
