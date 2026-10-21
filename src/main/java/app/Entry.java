package app;

/** Коллекция или объект: название, тип, есть ли картинка. collectionId только у объектов. */
public record Entry(long id, String name, String type, boolean hasImage, Long collectionId, int count) {
}
