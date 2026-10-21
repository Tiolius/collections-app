package app;

/**
 * Коллекция или объект.
 * Только у коллекций: type, hasImage, kind ("Физическая" / "Цифровая"), count (число объектов в списке).
 * Только у объектов: collectionId, platform, format, status, price.
 */
public record Entry(long id, String name, String type, boolean hasImage, Long collectionId, int count,
                    String kind, String platform, String format, String status, Integer price) {
}
