package app;

/**
 * Коллекция или объект.
 * hasImage — есть ли картинка (у коллекции и у объекта).
 * Только у коллекций: kind (название типа коллекции, kinds.name), count (число объектов в списке).
 * Только у объектов: collectionId, platform, format, status, price.
 */
public record Entry(long id, String name, boolean hasImage, Long collectionId, int count,
                    String kind, String platform, String format, String status, Integer price) {
}
