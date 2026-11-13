package app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Collection types: fields in order, own labels, profile, statuses with colours; item values in item_values. */
class KindsTest extends DbTest {

    @Test
    void newUserGetsDefaultKindsWithProfilesAndStatuses() {
        long uid = newUser();
        List<Kind> kinds = repo.kinds(uid);
        assertThat(kinds).extracting(Kind::name).contains("Игры (диски)", "Игры (цифровые)", "Книги", "Музыка (диски)");
        Kind discs = repo.kindByName(uid, "Игры (диски)");
        assertThat(discs.profile()).isEqualTo("games");
        assertThat(discs.fields()).containsExactly("status", "rating", "price", "available", "oldgames");
        assertThat(discs.statuses()).containsExactly("Completed", "Unfinished");
        assertThat(repo.kindByName(uid, "Музыка (диски)").profile()).isEqualTo("music");
        assertThat(repo.kindByName(uid, "Книги").statuses()).isEmpty();
    }

    @Test
    void fieldsKeepFormOrderOwnLabelsAndStatusColors() {
        long uid = newUser();
        long id = repo.saveKind(uid, null, "Комиксы", "books",
                List.of("price", "status", "platform", "tags"), List.of("tags", "status", "price"),
                List.of("Стоимость", "Статус прохождения", "", " Метки "), List.of("Прочитано", "", "Читаю"), List.of("#AABBCC", "", ""));
        Kind k = repo.kind(uid, id);
        assertThat(k.profile()).isEqualTo("books");
        assertThat(k.fields()).containsExactly("price", "status", "tags");          // form order, ticked only
        assertThat(k.field("price").label()).isEqualTo("Стоимость");
        assertThat(k.field("status").label()).isNull();                          // same as standard — not own
        assertThat(k.label("tags")).isEqualTo("Метки");
        assertThat(k.label("status")).isEqualTo("Статус прохождения");
        assertThat(k.statuses()).containsExactly("Прочитано", "Читаю");
        assertThat(k.statusColors()).containsExactly(Map.entry("Прочитано", "#aabbcc"));
    }

    @Test
    void badKindInputIsRejected() {
        long uid = newUser();
        assertThatThrownBy(() -> repo.saveKind(uid, null, "Книги", "books", List.of(), List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(ResponseStatusException.class);                     // such a type already exists
        assertThatThrownBy(() -> repo.saveKind(uid, null, "X", "other", List.of("status"), List.of("status"), List.of(),
                List.of("a", "A"), List.of())).isInstanceOf(ResponseStatusException.class); // status twice
        assertThatThrownBy(() -> repo.saveKind(uid, null, "Y", "other", List.of("status"), List.of("status"), List.of(),
                List.of("a"), List.of("red"))).isInstanceOf(ResponseStatusException.class); // invalid colour
        // unknown profile — "other"
        long id = repo.saveKind(uid, null, "Z", "planes", List.of("bogus"), List.of("bogus"), List.of(), List.of(), List.of());
        assertThat(repo.kind(uid, id).profile()).isEqualTo("other");
        assertThat(repo.kind(uid, id).fields()).isEmpty();
    }

    @Test
    void valuesAreStoredPerFieldAndValidated() {
        long uid = newUser();
        long cid = newCollection(uid, "Диски", "Игры (диски)");
        long id = newItem(uid, cid, new Repo.ItemForm("Fallout 2", "PC", null, "Completed", 300, List.of("0", "1"), 9,
                "https://www.old-games.ru/game/11.html", null, null, null, null, "Описание"));
        Entry e = repo.item(uid, id);
        assertThat(e.platform()).isNull();                                       // the type has no "Platform" field
        assertThat(e.status()).isEqualTo("Completed");
        assertThat(e.price()).isEqualTo(300);
        assertThat(e.rating()).isEqualTo(9);
        assertThat(e.available()).isTrue();
        assertThat(e.oldGamesId()).isEqualTo("11");
        assertThat(e.description()).isEqualTo("Описание");
        // status not in the type's list — 400; empty — no status
        assertThatThrownBy(() -> repo.saveItem(uid, id, "Игры (диски)", statusForm("Fallout 2", "Пройдено"), Map.of(), java.util.Set.of()))
                .isInstanceOf(ResponseStatusException.class);
        repo.saveItem(uid, id, "Игры (диски)", statusForm("Fallout 2", ""), Map.of(), java.util.Set.of());
        assertThat(repo.item(uid, id).status()).isNull();
        // a form without a price clears the price (the type has the field, no value); price sorting — by number, no price — at the end
        assertThat(repo.item(uid, id).price()).isNull();
        newItem(uid, cid, new Repo.ItemForm("Arcanum", null, null, null, 1500, List.of("0"), null, null, null, null, null, null, null));
        newItem(uid, cid, new Repo.ItemForm("Baldur's Gate", null, null, null, 90, List.of("0"), null, null, null, null, null, null, null));
        assertThat(repo.items(cid, "price", false, Map.of())).extracting(Entry::name).containsExactly("Baldur's Gate", "Arcanum", "Fallout 2");
        assertThat(repo.items(cid, "name", false, Map.of("available", "1"))).extracting(Entry::name).containsExactly("Fallout 2");
        assertThat(repo.totalPrice(cid)).isEqualTo(1590);
    }

    @Test
    void changingCollectionKindKeepsValuesOfSameRoles() {
        long uid = newUser();
        long cid = newCollection(uid, "Диски", "Игры (диски)");
        long id = newItem(uid, cid, new Repo.ItemForm("Gothic", null, null, "Unfinished", 500, null, 6, null, null, null, null, null, null));
        // "Игры (цифровые)": has status, rating, price; no Old-Games — the item did not have it anyway
        repo.saveCollection(uid, cid, "Диски", "Игры (цифровые)", null, false);
        Entry e = repo.item(uid, id);
        assertThat(e.status()).isEqualTo("Unfinished");
        assertThat(e.rating()).isEqualTo(6);
        assertThat(e.price()).isEqualTo(500);
        // "Книги" has no status and rating: values are hidden, and when the previous type comes back — visible again
        repo.saveCollection(uid, cid, "Диски", "Книги", null, false);
        assertThat(repo.item(uid, id).status()).isNull();
        assertThat(repo.item(uid, id).price()).isEqualTo(500);
        repo.saveCollection(uid, cid, "Диски", "Игры (цифровые)", null, false);
        assertThat(repo.item(uid, id).status()).isEqualTo("Unfinished");
    }

    @Test
    void movingItemCarriesValuesAndUncheckedFieldLosesThem() {
        long uid = newUser();
        long a = newCollection(uid, "A", "Игры (диски)"), b = newCollection(uid, "B", "Игры (цифровые)");
        long id = newItem(uid, a, new Repo.ItemForm("Doom", null, null, "Completed", 100, null, null, null, null, null, null, null, null));
        repo.moveItem(id, b);
        assertThat(repo.item(uid, id).status()).isEqualTo("Completed");
        assertThat(repo.item(uid, id).price()).isEqualTo(100);
        // removing "Price" from the type deletes price values; adding the field back — empty
        Kind k = repo.kindByName(uid, "Игры (цифровые)");
        List<String> without = k.fields().stream().filter(f -> !f.equals("price")).toList();
        repo.saveKind(uid, k.id(), k.name(), k.profile(), without, without, List.of(), k.statuses(), List.of());
        List<String> with = new java.util.ArrayList<>(without);
        with.add("price");
        repo.saveKind(uid, k.id(), k.name(), k.profile(), with, with, List.of(), k.statuses(), List.of());
        assertThat(repo.item(uid, id).price()).isNull();
        assertThat(repo.item(uid, id).status()).isEqualTo("Completed");
    }

    @Test
    void kindInUseCannotBeDeleted() {
        long uid = newUser();
        newCollection(uid, "Полка", "Книги");
        long kid = repo.kindByName(uid, "Книги").id();
        assertThatThrownBy(() -> repo.deleteKind(uid, kid)).isInstanceOf(ResponseStatusException.class);
        long free = repo.kindByName(uid, "Музыка (диски)").id();
        repo.deleteKind(uid, free);
        assertThat(repo.kindByName(uid, "Музыка (диски)")).isNull();
    }

    @Test
    void countersStatsFiltersAndSearchReadValues() {
        long uid = newUser();
        List<String> all = List.of("platform", "edition", "status", "rating", "price", "purchased", "available", "tags", "notes");
        repo.saveKind(uid, null, "Все", "games", all, all, List.of(), List.of("Completed", "Unfinished"), List.of());
        long cid = newCollection(uid, "Полка", "Все");
        newItem(uid, cid, new Repo.ItemForm("Quake", "PC", null, "Completed", 100, List.of("0", "1"), 8, null,
                "2021-03-01", "шутер, id", "Europe", "редкое издание", null));
        newItem(uid, cid, new Repo.ItemForm("Hexen", "PS1", null, "Unfinished", 50, List.of("0"), 3, null,
                "2022-01-01", "Шутер", null, null, null));
        assertThat(repo.collectionStats(uid).get(cid)).isEqualTo(new Repo.CollectionStats(1, 150));
        Repo.Stats st = repo.stats(uid).get(cid);
        assertThat(st.items()).isEqualTo(2);
        assertThat(repo.statusCounts(uid).get(cid)).containsExactlyInAnyOrderEntriesOf(Map.of("Completed", 1, "Unfinished", 1));
        assertThat(st.rated()).isEqualTo(2);
        assertThat(st.avgRating()).isEqualTo(2.75);
        assertThat(st.price()).isEqualTo(150);
        assertThat(repo.ratingHistogram(uid)).containsExactly(0, 1, 0, 1, 0); // 1.5 → 2 stars, 4 → 4 stars
        assertThat(repo.purchasesByYear(uid).keySet()).containsExactly("2022", "2021");
        assertThat(repo.topTags(uid, 5)).extracting(Map.Entry::getKey).first().isEqualTo("шутер");
        assertThat(repo.distinctValues(cid, "platform")).containsExactly("PC", "PS1");
        assertThat(repo.detailValues(cid, "tags")).containsExactly("id", "шутер");
        assertThat(repo.detailValues(cid, "purchased")).containsExactly("2022", "2021");
        assertThat(repo.items(cid, "name", false, Map.of("platform", "PS1"))).extracting(Entry::name).containsExactly("Hexen");
        assertThat(repo.items(cid, "name", false, Map.of("rating", "4"))).extracting(Entry::name).containsExactly("Quake");
        assertThat(repo.items(cid, "name", false, Map.of("tags", "ШУТЕР"))).hasSize(2);
        assertThat(repo.items(cid, "name", false, Map.of("purchased", "2021", "edition", "Europe"))).extracting(Entry::name).containsExactly("Quake");
        assertThat(repo.search(uid, "редкое").items()).extracting(Entry::name).containsExactly("Quake");
    }

    private static Repo.ItemForm statusForm(String name, String status) {
        return new Repo.ItemForm(name, null, null, status, null, null, null, null, null, null, null, null, null);
    }
}
