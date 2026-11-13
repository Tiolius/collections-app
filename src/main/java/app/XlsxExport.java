package app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Export of collections to Excel ("Settings" → "Export"): each collection is its own sheet, columns — name,
 * the fields of the collection type and description. Column titles are the ones Excel loading (XlsxImport) understands,
 * so the exported file loads back without losses (except pictures).
 */
@Component
public class XlsxExport {

    private final Repo repo;

    public XlsxExport(Repo repo) {
        this.repo = repo;
    }

    /** Sheets for the user's selected collections (in order). */
    public List<Xlsx.Sheet> sheets(long uid, List<Entry> collections) {
        List<Xlsx.Sheet> sheets = new ArrayList<>();
        for (Entry c : collections) {
            Kind kind = repo.kindByName(uid, c.kind());
            // all fields of the type in order: roles of built-in fields and "c<id>" of custom ones
            Set<String> fields = new LinkedHashSet<>(kind != null ? kind.keys() : repo.itemFields(uid, c.kind()));
            List<String> headers = new ArrayList<>(List.of(I18n.t("field.name")));
            List<Integer> widths = new ArrayList<>(List.of(60));
            for (String f : fields) {
                // the field's own label in the type (Excel loading understands it) or the standard one; price — "Цена (₽)"
                Kind.Field def = kind == null ? null : kind.field(f);
                headers.add(def != null && def.label() != null ? def.label()
                        : I18n.t(f.equals("price") ? "field.priceRub" : Repo.FIELDS.get(f)));
                if (def != null && def.custom()) {
                    widths.add(def.type().equals("longtext") ? 40 : 18);
                    continue;
                }
                widths.add(switch (f) {
                    case "price", "available", "rating", "purchased", "oldgames" -> 12;
                    case "tags", "notes" -> 30;
                    default -> 22;
                });
            }
            // every item has a description regardless of type — the last column
            headers.add(I18n.t("field.description"));
            widths.add(60);
            List<List<Object>> rows = new ArrayList<>();
            List<Entry> items = repo.items(c.id(), "name", false, Map.of());
            Map<Long, Map<Long, Object>> custom = repo.customValues(items.stream().map(Entry::id).toList());
            for (Entry it : items) {
                List<Object> row = new ArrayList<>();
                row.add(it.name());
                for (String f : fields) {
                    Kind.Field def = kind == null ? null : kind.field(f);
                    if (def != null && def.custom()) {
                        // custom field: number — as a number, yes/no — "Есть"/"Нет", the rest — as text
                        Object v = custom.getOrDefault(it.id(), Map.of()).get(def.id());
                        row.add(def.type().equals("bool") ? I18n.t(v != null ? "available.yes" : "available.no")
                                : v instanceof Number ? v : v == null ? null : String.valueOf(v));
                        continue;
                    }
                    row.add(switch (f) {
                        case "platform" -> it.platform();
                        case "format" -> it.format();
                        case "status" -> it.status();
                        case "price" -> it.price();
                        case "available" -> I18n.t(it.available() ? "available.yes" : "available.no");
                        case "rating" -> it.rating() == null ? null : it.rating() / 2.0; // stars: 3.5
                        case "purchased" -> it.purchased(); // YYYY-MM-DD
                        case "tags" -> it.tags();
                        case "edition" -> it.edition();
                        case "notes" -> it.notes();
                        case "oldgames" -> it.oldGamesId(); // game number in the catalogue: "11"
                        default -> null;
                    });
                }
                row.add(it.description());
                rows.add(row);
            }
            sheets.add(new Xlsx.Sheet(c.name(), headers, rows, widths));
        }
        return sheets;
    }
}
