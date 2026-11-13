package app;

/**
 * A collection or an item.
 * hasImage — whether there is a picture (for a collection and an item).
 * Items only: description — description (all items have it, regardless of the collection type).
 * Collections only: kind (collection type name, kinds.name), count (number of items in the list).
 * Items only: collectionId, platform, format, status, price, available (whether it is in stock),
 * imageCount (how many pictures, up to ImageFiles.MAX_IMAGES), rating (rating in half stars: 1…10, null — none),
 * oldGames (link to the game page on Old-Games.ru or null), purchased (purchase date YYYY-MM-DD), tags (tags separated by ", "),
 * edition (region / edition), notes (notes) — null if not filled in.
 * pics — picture hashes separated by commas (item — in order, collection — the cover) for /img/<hash> addresses; null — none.
 */
public record Entry(long id, String name, boolean hasImage, Long collectionId, int count,
                    String kind, String platform, String format, String status, Integer price,
                    String description, Boolean available, int imageCount, Integer rating, String oldGames,
                    String purchased, String tags, String edition, String notes, String pics) {

    /** Hash of the first picture (collection cover) or null. */
    public String pic() {
        return pics == null || pics.isEmpty() ? null : pics.split(",")[0];
    }

    /** Game number on Old-Games.ru (from the saved page address) — shown by the field; null — no link. */
    public String oldGamesId() {
        return OldGames.gameId(oldGames);
    }

    /** Purchase date for display: "01.05.2024" in Russian, "May 1, 2024" in English; null — not given. */
    public String purchasedText() {
        return purchased == null ? null : dateText(purchased);
    }

    /** Date YYYY-MM-DD for display in the interface language; not a date — as is. */
    public static String dateText(String date) {
        java.util.Locale l = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        java.time.format.DateTimeFormatter f = "en".equals(l.getLanguage())
                ? java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH)
                : java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
        try {
            return java.time.LocalDate.parse(date).format(f);
        } catch (java.time.format.DateTimeParseException e) {
            return date;
        }
    }

    /** Tags as a list (without empty ones). */
    public java.util.List<String> tagList() {
        return Repo.splitTags(tags);
    }
}
