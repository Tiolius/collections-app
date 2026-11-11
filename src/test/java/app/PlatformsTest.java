package app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PlatformsTest {

    @Test
    void namesOfOnePlatformGiveOneCode() {
        assertThat(Platforms.code("PS2")).isEqualTo("ps2");
        assertThat(Platforms.code("PlayStation 2")).isEqualTo("ps2");
        assertThat(Platforms.code("Sony Playstation 2")).isEqualTo("ps2");
        assertThat(Platforms.code("Nintendo Switch")).isEqualTo("switch");
        assertThat(Platforms.code("Денди")).isEqualTo("nes");
        assertThat(Platforms.code("Amiga")).isNull();
    }

    @Test
    void fieldMayListSeveralPlatforms() {
        assertThat(Platforms.codes("PS4 / PS5")).containsExactlyInAnyOrder("ps4", "ps5");
        assertThat(Platforms.codes("Switch, 3DS; Amiga")).containsExactlyInAnyOrder("switch", "3ds");
        assertThat(Platforms.codes(null)).isEmpty();
    }

    @Test
    void unknownPlatformMatchesAnything() {
        assertThat(Platforms.same(Set.of(), List.of("Nintendo 3DS"))).isTrue();
        assertThat(Platforms.same(Set.of("switch"), List.of("Nintendo Switch"))).isTrue();
        assertThat(Platforms.same(Set.of("switch"), List.of("Nintendo 3DS", "Wii U"))).isFalse();
    }
}
