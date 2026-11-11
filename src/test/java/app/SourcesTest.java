package app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/** Разбор ответов сервисов импорта и выбор подходящего результата (без сети). */
class SourcesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s.replace('\'', '"'));
    }

    private static ImageImport.Job job(boolean screenshot) {
        return new ImageImport.Job(1, "c", ImageImport.GOG, true, true, screenshot, 5, null);
    }

    // ---------- Discogs ----------

    @Test
    void discogsFormatFollowsItemFormat() {
        assertThat(DiscogsSource.format(null)).isEqualTo("CD");
        assertThat(DiscogsSource.format("CD")).isEqualTo("CD");
        assertThat(DiscogsSource.format("Винил")).isEqualTo("Vinyl");
        assertThat(DiscogsSource.format("LP")).isEqualTo("Vinyl");
        assertThat(DiscogsSource.format("Кассета")).isEqualTo("Cassette");
        assertThat(DiscogsSource.format("MC")).isEqualTo("Cassette");
    }

    @Test
    void discogsPicksMostPopularReleaseOfSameAlbumOnMedia() throws Exception {
        JsonNode results = json("""
                [{'id':1,'master_id':10,'title':'Pink Floyd - The Wall','format':['Vinyl'],'cover_image':'https://i.discogs.com/v.jpg','community':{'have':5000}},
                 {'id':2,'master_id':10,'title':'Pink Floyd - The Wall','format':['CD','Album'],'cover_image':'https://i.discogs.com/c.jpg','community':{'have':3000}},
                 {'id':3,'master_id':10,'title':'Pink Floyd - The Wall','format':['CD'],'cover_image':'https://i.discogs.com/c2.jpg','community':{'have':100}},
                 {'id':4,'master_id':99,'title':'Pink Floyd - The Wall','format':['CD'],'cover_image':'https://i.discogs.com/o.jpg','community':{'have':9000}},
                 {'id':5,'master_id':10,'title':'Pink Floyd - The Wall','format':['CD'],'cover_image':'https://s.discogs.com/spacer.gif','community':{'have':8000}}]""");
        assertThat(DiscogsSource.bestMedia(results, "Pink Floyd", "The Wall", "CD", 10).path("id").asInt()).isEqualTo(2);
        assertThat(DiscogsSource.bestMedia(results, "Pink Floyd", "The Wall", "Vinyl", 10).path("id").asInt()).isEqualTo(1);
        assertThat(DiscogsSource.bestMedia(results, "Pink Floyd", "The Wall", "Cassette", 10)).isNull();
        // без главной версии — по названию: подходят все издания с этим названием
        assertThat(DiscogsSource.bestMedia(results, "Pink Floyd", "The Wall", "CD", 0).path("id").asInt()).isEqualTo(4);
    }

    @Test
    void discogsNotesWithoutMarkup() {
        assertThat(DiscogsSource.cleanNotes("Recorded at [l=Abbey Road] by [a=Alan Parsons] [a45467] , see [url=http://x]site[/url]. [b]Bold[/b]"))
                .isEqualTo("Recorded at Abbey Road by Alan Parsons, see site. Bold");
        assertThat(DiscogsSource.cleanNotes("  [m=11329] ")).isNull();
    }

    // ---------- FantLab ----------

    @Test
    void fantlabAuthorMatchesByWordStems() {
        assertThat(FantlabSource.sameAuthor("Стругацкие", "Аркадий и Борис Стругацкие Arkady Strugatsky")).isTrue();
        assertThat(FantlabSource.sameAuthor("Стругацкий", "Аркадий и Борис Стругацкие")).isTrue();
        assertThat(FantlabSource.sameAuthor("Азимов", "Айзек Азимов Isaac Asimov")).isTrue();
        assertThat(FantlabSource.sameAuthor("Лем", "Айзек Азимов")).isFalse();
    }

    @Test
    void fantlabTitleMayBeAlternative() throws Exception {
        JsonNode w = json("{'rusname':'Академия','name':'Foundation','altname':'Основание;Фонд'}");
        assertThat(FantlabSource.sameTitle(w, "Основание")).isTrue();
        assertThat(FantlabSource.sameTitle(w, "Foundation")).isTrue();
        assertThat(FantlabSource.sameTitle(w, "Империя")).isFalse();
    }

    @Test
    void fantlabAnnotationWithoutMarkup() {
        assertThat(FantlabSource.cleanFantlab("[b]Роман[/b] о [url=https://fantlab.ru]будущем[/url].\r\n\r\n\r\n[spoiler]Конец[/spoiler]"))
                .isEqualTo("Роман о будущем.\n\nКонец");
    }

    // ---------- GOG, Nintendo, LaunchBox ----------

    @Test
    void gogPrefersExactTitleThenLoose() throws Exception {
        JsonNode products = json("[{'title':'The Witcher 2: Assassins of Kings Enhanced Edition'},{'title':'Witcher 2'},{'title':'Witcher'}]");
        assertThat(GogSource.bestMatch(products, "title", Names.norm("Witcher 2")).path("title").asText()).isEqualTo("Witcher 2");
        assertThat(GogSource.bestMatch(products, "title", Names.norm("The Witcher")).path("title").asText()).isEqualTo("Witcher");
        assertThat(GogSource.bestMatch(products, "title", Names.norm("Fallout"))).isNull();
    }

    @Test
    void coverAndShotsOrderFollowsMode() {
        var shots = java.util.List.of("s1", "s2");
        assertThat(ImportSource.coverAndShots(job(true), "c", shots)).containsExactly("s1", "s2");
        assertThat(ImportSource.coverAndShots(job(true), "c", java.util.List.of())).containsExactly("c");
        assertThat(ImportSource.coverAndShots(job(false), "c", shots)).containsExactly("c", "s1", "s2");
    }

    @Test
    void nintendoScreenshotNumber() {
        assertThat(NintendoSource.shotNumber("/x/NSwitch_Hades_03_enGB.png")).isEqualTo(3);
        assertThat(NintendoSource.shotNumber("/x/3DS_Game_12_TM_Standard.jpg")).isEqualTo(12);
        assertThat(NintendoSource.shotNumber("/x/cover.jpg")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void launchboxOverviewAndImages() {
        String html = """
                <div id="overview"><h3>Overview <svg><path d="M0"/></svg></h3><p>First &amp; best.</p><p class="x">Second<br>line</p></div><dl>tail</dl>
                <img src="https://images.launchbox-app.com//a.jpg" class="i" alt="Game - Box - Front (Japan) - 600x800">
                <img src="https://images.launchbox-app.com/b.jpg" alt="Game - Box - Front (Europe) - 600x800">
                <img src="https://images.launchbox-app.com/c.jpg" alt="Game - Fanart - Box - Front (Europe) - 600x800">
                <img src="https://images.launchbox-app.com/d.jpg" alt="Game - Screenshot - Gameplay (World) - 320x240">
                <img src="https://images.launchbox-app.com/e.jpg" alt="Game - Screenshot - Gameplay (World) - 640x480">
                <img src="https://images.launchbox-app.com/f.jpg" alt="Game - Screenshot - Game Title (World) - 640x480">
                """;
        assertThat(LaunchboxSource.overview(html)).isEqualTo("First & best.\n\nSecond\nline");
        assertThat(LaunchboxSource.images(job(false), html)).containsExactly(
                "https://images.launchbox-app.com/b.jpg", "https://images.launchbox-app.com/e.jpg",
                "https://images.launchbox-app.com/d.jpg", "https://images.launchbox-app.com/f.jpg");
        assertThat(LaunchboxSource.regionRank("X (Japan) - 1x1")).isGreaterThan(LaunchboxSource.regionRank("X (Brazil) - 1x1"));
        assertThat(LaunchboxSource.area("X - 1024x714")).isEqualTo(1024L * 714);
    }
}
