package app;

/**
 * Коллекция или объект.
 * hasImage — есть ли картинка (у коллекции и у объекта).
 * Только у объектов: description — описание (у всех объектов, независимо от типа коллекции).
 * Только у коллекций: kind (название типа коллекции, kinds.name), count (число объектов в списке).
 * Только у объектов: collectionId, platform, format, status, price, available (есть ли в наличии).
 */
public record Entry(long id, String name, boolean hasImage, Long collectionId, int count,
                    String kind, String platform, String format, String status, Integer price,
                    String description, Boolean available) {
}
