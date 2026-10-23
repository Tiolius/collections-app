package app;

/**
 * Коллекция или объект.
 * hasImage — есть ли картинка (у коллекции и у объекта).
 * Только у объектов: description — описание (у всех объектов, независимо от типа коллекции).
 * Только у коллекций: kind (название типа коллекции, kinds.name), count (число объектов в списке).
 * Только у объектов: collectionId, platform, format, status, price, available (есть ли в наличии),
 * imageCount (сколько картинок, до ImageFiles.MAX_IMAGES), rating (рейтинг в половинках звезды: 1…10, null — нет),
 * oldGames (ссылка на страницу игры на Old-Games.ru или null).
 */
public record Entry(long id, String name, boolean hasImage, Long collectionId, int count,
                    String kind, String platform, String format, String status, Integer price,
                    String description, Boolean available, int imageCount, Integer rating, String oldGames) {

    /** Номер игры на Old-Games.ru (из сохранённого адреса страницы) — его показывает поле; null — ссылки нет. */
    public String oldGamesId() {
        return OldGames.gameId(oldGames);
    }
}
