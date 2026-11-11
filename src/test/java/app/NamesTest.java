package app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Сравнение названий при импорте: от него зависит, найдётся ли объект в сервисе. */
class NamesTest {

    @Test
    void normIgnoresCasePunctuationAndMarks() {
        assertThat(Names.norm("F.E.A.R.™")).isEqualTo("f e a r");
        assertThat(Names.norm("  Batman:  Arkham   Asylum ")).isEqualTo("batman arkham asylum");
        assertThat(Names.norm("Ratchet & Clank")).isEqualTo("ratchet and clank");
        assertThat(Names.norm("Бельтион: Свод Равновесия")).isEqualTo("бельтион свод равновесия");
    }

    @Test
    void normTurnsRomanNumeralsIntoArabic() {
        assertThat(Names.norm("Gothic II")).isEqualTo(Names.norm("Gothic 2"));
        assertThat(Names.norm("Final Fantasy IX")).isEqualTo("final fantasy 9");
        assertThat(Names.norm("Civilization VI")).isEqualTo("civilization 6");
        assertThat(Names.roman("xxxix")).isEqualTo(39);
        assertThat(Names.roman("iv")).isEqualTo(4);
    }

    @Test
    void editionSuffixesAreIgnoredInLooseKey() {
        assertThat(Names.editionBase("atelier ryza ever darkness and the secret hideout dx"))
                .isEqualTo("atelier ryza ever darkness and the secret hideout");
        assertThat(Names.looseKey("the banner saga 3")).isEqualTo(Names.looseKey("banner saga 3"));
        assertThat(Names.looseKey(Names.norm("Mafia: Definitive Edition"))).isEqualTo("mafia");
        assertThat(Names.looseKey(Names.norm("Disgaea 4 Complete+"))).isEqualTo("disgaea 4");
        // только приписка — не стирается в пустую строку
        assertThat(Names.editionBase("hd")).isEqualTo("hd");
    }

    @Test
    void plainDropsPunctuation() {
        assertThat(Names.plain("F.E.A.R.")).isEqualTo("FEAR");
        assertThat(Names.plain("Batman: Arkham Asylum - Game of the Year Edition"))
                .isEqualTo("Batman Arkham Asylum Game of the Year Edition");
    }

    @Test
    void artistAlbumSplitsByFirstDash() {
        assertThat(Names.artistAlbum("Pink Floyd - The Wall")).containsExactly("Pink Floyd", "The Wall");
        assertThat(Names.artistAlbum("Кино — Группа крови")).containsExactly("Кино", "Группа крови");
        assertThat(Names.artistAlbum("AC/DC – Back in Black - Remaster")).containsExactly("AC/DC", "Back in Black - Remaster");
        assertThat(Names.artistAlbum("Spider-Man")).isNull();
        assertThat(Names.artistAlbum(" - Album")).isNull();
    }

    @Test
    void sameArtistIgnoresTheAndDiscogsNumbers() {
        assertThat(Names.sameArtist("The Beatles", "Beatles")).isTrue();
        assertThat(Names.sameArtist("Kino (2)", "Kino")).isTrue();
        assertThat(Names.sameArtist("Prince*", "prince")).isTrue();
        assertThat(Names.sameArtist("Queen", "Queensryche")).isFalse();
    }

    @Test
    void cleanDescriptionStripsHtml() {
        assertThat(Names.cleanDescription("<b>Hello</b><br>world &amp; &quot;you&quot; &#39;all&#39; &#x2014; ok"))
                .isEqualTo("Hello\nworld & \"you\" 'all' — ok");
        assertThat(Names.cleanDescription("  <p> </p> ")).isNull();
        assertThat(Names.cleanDescription("x".repeat(Repo.MAX_DESCRIPTION + 10))).hasSize(Repo.MAX_DESCRIPTION);
    }
}
