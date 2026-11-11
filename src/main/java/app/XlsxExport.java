package app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Выгрузка коллекций в Excel («Настройки» → «Экспорт»): каждая коллекция — свой лист, столбцы — название,
 * поля типа коллекции и описание. Подписи столбцов те же, что понимает загрузка из Excel (XlsxImport),
 * поэтому выгруженный файл загружается обратно без потерь (кроме картинок).
 */
@Component
public class XlsxExport {

    private final Repo repo;

    public XlsxExport(Repo repo) {
        this.repo = repo;
    }

    /** Листы для выбранных коллекций пользователя (по порядку). */
    public List<Xlsx.Sheet> sheets(long uid, List<Entry> collections) {
        List<Xlsx.Sheet> sheets = new ArrayList<>();
        for (Entry c : collections) {
            Kind kind = repo.kindByName(uid, c.kind());
            Set<String> fields = new LinkedHashSet<>(repo.itemFields(uid, c.kind()));
            List<String> headers = new ArrayList<>(List.of(I18n.t("field.name")));
            List<Integer> widths = new ArrayList<>(List.of(60));
            for (String f : fields) {
                // своя подпись поля в типе (её понимает загрузка из Excel) или стандартная; у цены — «Цена (₽)»
                Kind.Field def = kind == null ? null : kind.field(f);
                headers.add(def != null && def.label() != null ? def.label()
                        : I18n.t(f.equals("price") ? "field.priceRub" : Repo.FIELDS.get(f)));
                widths.add(switch (f) {
                    case "price", "available", "rating", "purchased", "oldgames" -> 12;
                    case "tags", "notes" -> 30;
                    default -> 22;
                });
            }
            // описание есть у всех объектов, независимо от типа — последним столбцом
            headers.add(I18n.t("field.description"));
            widths.add(60);
            List<List<Object>> rows = new ArrayList<>();
            for (Entry it : repo.items(c.id(), "name", false, Map.of())) {
                List<Object> row = new ArrayList<>();
                row.add(it.name());
                for (String f : fields)
                    row.add(switch (f) {
                        case "platform" -> it.platform();
                        case "format" -> it.format();
                        case "status" -> it.status();
                        case "price" -> it.price();
                        case "available" -> I18n.t(it.available() ? "available.yes" : "available.no");
                        case "rating" -> it.rating() == null ? null : it.rating() / 2.0; // звёзды: 3.5
                        case "purchased" -> it.purchased(); // ГГГГ-ММ-ДД
                        case "tags" -> it.tags();
                        case "edition" -> it.edition();
                        case "notes" -> it.notes();
                        case "oldgames" -> it.oldGamesId(); // номер игры в каталоге: «11»
                        default -> null;
                    });
                row.add(it.description());
                rows.add(row);
            }
            sheets.add(new Xlsx.Sheet(c.name(), headers, rows, widths));
        }
        return sheets;
    }
}
