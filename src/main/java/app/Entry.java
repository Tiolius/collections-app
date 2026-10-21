package app;

/**
 * Коллекция или объект: название, тип, есть ли картинка. collectionId только у объектов.
 * kind ("Физическая" / "Цифровая") — только у коллекций (у объектов null).
 * platform, format, status, price — только у объектов (у коллекций null).
 */
public record Entry(long id, String name, String type, boolean hasImage, Long collectionId, int count,
                    String kind, String platform, String format, String status, Integer price) {
}
