package app;

/**
 * Коллекция или объект: название, тип, есть ли картинка. collectionId только у объектов.
 * platform, format, publisher, status, price — только у объектов (у коллекций null).
 */
public record Entry(long id, String name, String type, boolean hasImage, Long collectionId, int count,
                    String platform, String format, String publisher, String status, Integer price) {
}
