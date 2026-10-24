package app;

/**
 * Коллекция или объект.
 * hasImage — есть ли картинка (у коллекции и у объекта).
 * Только у объектов: description — описание (у всех объектов, независимо от типа коллекции).
 * Только у коллекций: kind (название типа коллекции, kinds.name), count (число объектов в списке).
 * Только у объектов: collectionId, platform, format, status, price, available (есть ли в наличии),
 * imageCount (сколько картинок, до ImageFiles.MAX_IMAGES), rating (рейтинг в половинках звезды: 1…10, null — нет),
 * oldGames (ссылка на страницу игры на Old-Games.ru или null), purchased (дата покупки ГГГГ-ММ-ДД), tags (теги через «, »),
 * edition (регион / издание), notes (заметки) — null, если не заполнено.
 * pics — хеши картинок через запятую (у объекта — по порядку, у коллекции — обложка) для адресов /img/<хеш>; null — нет.
 */
public record Entry(long id, String name, boolean hasImage, Long collectionId, int count,
                    String kind, String platform, String format, String status, Integer price,
                    String description, Boolean available, int imageCount, Integer rating, String oldGames,
                    String purchased, String tags, String edition, String notes, String pics) {

    /** Хеш первой картинки (обложки коллекции) или null. */
    public String pic() {
        return pics == null || pics.isEmpty() ? null : pics.split(",")[0];
    }

    /** Номер игры на Old-Games.ru (из сохранённого адреса страницы) — его показывает поле; null — ссылки нет. */
    public String oldGamesId() {
        return OldGames.gameId(oldGames);
    }

    /** Дата покупки для показа: «01.05.2024» по-русски, «May 1, 2024» по-английски; null — не указана. */
    public String purchasedText() {
        if (purchased == null) return null;
        java.util.Locale l = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        java.time.format.DateTimeFormatter f = "en".equals(l.getLanguage())
                ? java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH)
                : java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
        try {
            return java.time.LocalDate.parse(purchased).format(f);
        } catch (java.time.format.DateTimeParseException e) {
            return purchased;
        }
    }

    /** Теги списком (без пустых). */
    public java.util.List<String> tagList() {
        return Repo.splitTags(tags);
    }
}
