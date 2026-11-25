package dev.mvswitcher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A known Minecraft + mod loader combination.
 *
 * @param id            profile identifier shown to the user (usually the Minecraft version)
 * @param minecraft     Minecraft version
 * @param loader        lower-case loader name, for example {@code neoforge}, {@code forge}, {@code fabric}, {@code quilt}
 * @param loaderVersion loader version for this Minecraft version, or null if it must be supplied explicitly
 * @param javaMajor     Java major version required to build for this profile
 * @param properties    extra {@code gradle.properties} entries to write when migrating to this profile, for example
 *                      {@code loader_version} or {@code fabric_version}; never null
 */
public record VersionProfile(String id, String minecraft, String loader, String loaderVersion, int javaMajor,
                             Map<String, String> properties) {
    public VersionProfile {
        properties = properties == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(properties));
    }

    public VersionProfile(String id, String minecraft, String loader, String loaderVersion, int javaMajor) {
        this(id, minecraft, loader, loaderVersion, javaMajor, Map.of());
    }

    /** Loader name as shown to people, for example {@code NeoForge}; falls back to the stored name. */
    public String loaderLabel() {
        return ProjectAnalyzer.LOADERS.stream().filter(name -> name.equalsIgnoreCase(loader)).findFirst().orElse(loader);
    }

    /** Loader version for display: the version itself, or a note that it has to be supplied. */
    public String loaderVersionText() {
        return loaderVersion == null ? "override required" : loaderVersion;
    }

    /** NeoForge version of this profile, or null when the profile belongs to another loader. */
    public String neoForge() {
        return "neoforge".equals(loader) ? loaderVersion : null;
    }
}
