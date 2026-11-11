package app;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Платформы игр для импорта (Nintendo eShop, LaunchBox): «PS2», «PlayStation 2» и «Sony Playstation 2» — одна платформа. */
final class Platforms {

    private Platforms() {}

    /** Названия платформ (после norm) → код. */
    private static final Map<String, String> CODES = new HashMap<>();
    static {
        add("ps1", "playstation", "playstation 1", "ps1", "psx", "ps one", "psone", "ps");
        add("ps2", "playstation 2", "ps2");
        add("ps3", "playstation 3", "ps3");
        add("ps4", "playstation 4", "ps4");
        add("ps5", "playstation 5", "ps5");
        add("psp", "psp", "playstation portable");
        add("vita", "playstation vita", "ps vita", "psvita", "psv", "vita");
        add("nes", "nes", "nintendo entertainment system", "famicom", "dendy", "денди");
        add("snes", "snes", "super nes", "super nintendo", "super nintendo entertainment system", "super famicom");
        add("n64", "n64", "nintendo 64");
        add("gc", "gamecube", "game cube", "gc", "ngc");
        add("wii", "wii");
        add("wiiu", "wii u", "wiiu");
        add("switch", "switch", "ns");
        add("switch2", "switch 2");
        add("gb", "game boy", "gameboy", "gb");
        add("gbc", "game boy color", "gameboy color", "gbc");
        add("gba", "game boy advance", "gameboy advance", "gba");
        add("ds", "ds", "nds");
        add("3ds", "3ds", "n3ds", "new 3ds", "new nintendo 3ds");
        add("pc", "pc", "windows", "ms dos", "dos");
        add("xbox", "xbox");
        add("x360", "xbox 360", "x360");
        add("xone", "xbox one");
        add("xsx", "xbox series x s", "xbox series x", "xbox series s");
        add("md", "genesis", "mega drive", "megadrive", "md");
        add("saturn", "saturn");
        add("dc", "dreamcast");
    }

    private static void add(String code, String... names) {
        for (String n : names) CODES.put(Names.norm(n), code);
    }

    /** Код платформы по названию; без известной приписки производителя («Sony», «Nintendo»…) тоже. null — неизвестная. */
    static String code(String name) {
        String n = Names.norm(name);
        String code = CODES.get(n);
        return code != null ? code : CODES.get(n.replaceFirst("^(sony|nintendo|microsoft|sega) ", ""));
    }

    /** Известные платформы из поля «Платформа» (можно несколько через «,», «/», «;», «+»: «PS4 / PS5»). */
    static Set<String> codes(String field) {
        Set<String> res = new HashSet<>();
        if (field != null)
            for (String p : field.split("[,/;+|]")) {
                String code = code(p);
                if (code != null) res.add(code);
            }
        return res;
    }

    /** Подходит ли запись источника (её платформы) объекту: у объекта нет известной платформы — подходит любая. */
    static boolean same(Set<String> wanted, List<String> sourcePlatforms) {
        if (wanted.isEmpty()) return true;
        for (String p : sourcePlatforms)
            if (wanted.contains(code(p))) return true;
        return false;
    }
}
