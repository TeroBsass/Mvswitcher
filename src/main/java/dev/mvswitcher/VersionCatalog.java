package dev.mvswitcher;

import java.util.*;

public final class VersionCatalog {
    private static final List<VersionProfile> PROFILES = List.of(
            new VersionProfile("1.21.11", "1.21.11", "21.11.48", 21),
            new VersionProfile("26.3", "26.3", "26.3.0.99", 25)
    );
    private VersionCatalog() {}
    public static List<VersionProfile> all() { return PROFILES; }
    public static VersionProfile find(String version) {
        return PROFILES.stream().filter(p -> p.minecraft().equals(version) || p.id().equals(version)).findFirst().orElse(null);
    }
}
