package app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collection type: name, profile (Repo.PROFILES), roles of built-in fields in order (fields — keys from Repo.FIELDS),
 * how many collections have this type and definitions of all fields in order (defs: built-in and custom — label, kind, list values).
 */
public record Kind(long id, String name, String profile, Set<String> fields, int used, List<Field> defs) {

    /**
     * Type field: role (a Repo.FIELDS key; null — custom field), kind (Repo.TYPES), label (built-in — own or null:
     * the role label; custom — always), list values.
     */
    public record Field(long id, String role, String type, String label, List<Option> options) {
        /** Field key in forms, addresses and markup: the role for built-in, "c<id>" for custom. */
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

    /** A list field value and the colour of an item's row with this value (#rrggbb or null). */
    public record Option(String value, String color) {}

    /** Field with the role or key "c<id>" (custom field), or null. */
    public Field field(String key) {
        return defs.stream().filter(f -> key.equals(f.key())).findFirst().orElse(null);
    }

    /** The type's custom fields in order. */
    public List<Field> customs() {
        return defs.stream().filter(Field::custom).toList();
    }

    /** Keys of all fields in order: roles of built-in and "c<id>" of custom ones. */
    public List<String> keys() {
        return defs.stream().filter(f -> f.custom() || Repo.FIELDS.containsKey(f.role())).map(Field::key).toList();
    }

    /** Field label for display: its own or the role label in the interface language. */
    public String label(String key) {
        Field f = field(key);
        if (f != null && f.label() != null) return f.label();
        return Repo.FIELDS.containsKey(key) ? I18n.t(Repo.FIELDS.get(key)) : key;
    }

    /** Labels of all fields of the type in order: key → label. */
    public Map<String, String> labels() {
        Map<String, String> res = new LinkedHashMap<>();
        for (String k : keys()) res.put(k, label(k));
        return res;
    }

    /** Values of the "Status" field in order (empty — no such field or the list is empty). */
    public List<String> statuses() {
        Field f = field("status");
        return f == null ? List.of() : f.values();
    }

    /** Row colours by status: status → colour (only statuses with a colour), in list order. */
    public Map<String, String> statusColors() {
        Map<String, String> res = new LinkedHashMap<>();
        Field f = field("status");
        if (f != null)
            for (Option o : f.options())
                if (o.color() != null) res.put(o.value(), o.color());
        return res;
    }
}
