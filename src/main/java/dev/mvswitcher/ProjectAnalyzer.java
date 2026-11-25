package dev.mvswitcher;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public final class ProjectAnalyzer {
    private ProjectAnalyzer() {}

    public static ProjectInfo analyze(Path input) throws IOException {
        return analyze(input, MigrationLog.Listener.NONE);
    }

    public static ProjectInfo analyze(Path input, MigrationLog.Listener listener) throws IOException {
        MigrationLog.log(listener, MigrationLog.Level.INFO, "Resolving project input: " + input);
        Path root = ProjectFiles.resolveInput(input);
        MigrationLog.log(listener, MigrationLog.Level.INFO, "Project root: " + root);
        MigrationLog.log(listener, MigrationLog.Level.DEBUG, "Reading Gradle metadata and NeoForge markers.");
        String gradle = read(root.resolve("gradle.properties")) + "\n" + read(root.resolve("build.gradle")) + "\n" + read(root.resolve("build.gradle.kts"));
        String mc = first(gradle, "(?m)^\\s*minecraft_version\\s*=\\s*['\\\"]?([^'\\\"\\s]+)");
        if (mc == null) mc = first(gradle, "(?i)minecraft\\s*[:=]\\s*['\\\"]([0-9][^'\\\"]*)");
        String neo = first(gradle, "(?m)^\\s*neo_version\\s*=\\s*['\\\"]?([^'\\\"\\s]+)");
        if (neo == null) neo = first(gradle, "(?i)neoforge\\s*[:=]\\s*['\\\"]([0-9][^'\\\"]*)");
        String loader = detectLoader(root, gradle, listener);
        int java = 0;
        String j = first(gradle, "(?i)(?:JavaLanguageVersion\\.of\\(|java_version\\s*=\\s*)['\\\"]?(\\d+)");
        if (j != null) try { java = Integer.parseInt(j); } catch (NumberFormatException ignored) {}
        List<String> sources = findDirs(root, "src/main/java", "src/client/java");
        List<String> resources = findDirs(root, "src/main/resources", "src/client/resources");
        MigrationLog.log(listener, MigrationLog.Level.DEBUG, "Checking Git revision.");
        String revision = gitRevision(root);
        ProjectInfo result = new ProjectInfo(root, root.getFileName().toString(), mc, neo, loader, java,
                sources, resources, revision);
        MigrationLog.log(listener, MigrationLog.Level.SUCCESS, "Project analysis complete: loader=" + loader +
                ", Minecraft=" + value(mc) + ", NeoForge=" + value(neo) +
                ", Java=" + (java == 0 ? "unknown" : java) +
                ", source roots=" + sources.size() + ", resource roots=" + resources.size());
        return result;
    }
    /** Display names of the loaders this tool recognises; {@code unknown} is returned otherwise. */
    public static final List<String> LOADERS = List.of("NeoForge", "Forge", "Fabric", "Quilt");

    /**
     * Detects the mod loader. Metadata files are the most reliable signal, Gradle markers are the fallback.
     * A project that shows markers of several unrelated loaders (a multi-loader repository) is reported as
     * {@code unknown} instead of guessing; Quilt together with Fabric counts as Quilt, because Quilt projects
     * often keep Fabric metadata for compatibility.
     */
    static String detectLoader(Path root, String gradle, MigrationLog.Listener listener) {
        Set<String> byFiles = loadersIn(
                Files.exists(root.resolve("src/main/resources/META-INF/neoforge.mods.toml")),
                Files.exists(root.resolve("src/main/resources/META-INF/mods.toml")),
                Files.exists(root.resolve("src/main/resources/fabric.mod.json")),
                Files.exists(root.resolve("src/main/resources/quilt.mod.json")));
        String text = gradle.toLowerCase(Locale.ROOT);
        Set<String> byGradle = loadersIn(
                text.contains("neoforge") || text.contains("net.neoforged"),
                text.contains("net.minecraftforge") || text.contains("forgegradle"),
                text.contains("fabric-loom") || text.contains("net.fabricmc"),
                text.contains("org.quiltmc") || text.contains("quilt_loader") || text.contains("quilt-loader"));
        Set<String> found = byFiles.isEmpty() ? byGradle : byFiles;
        // NeoForge 1.20.1-1.20.4 still ships META-INF/mods.toml, exactly like Forge: Gradle breaks the tie.
        if (found.equals(Set.of("Forge")) && byGradle.equals(Set.of("NeoForge"))) found = byGradle;
        if (found.size() == 2 && found.contains("Quilt") && found.contains("Fabric")) return "Quilt";
        if (found.size() == 1) return found.iterator().next();
        if (found.size() > 1) {
            MigrationLog.log(listener, MigrationLog.Level.WARN,
                    "Several mod loaders were detected " + found + "; this looks like a multi-loader project.");
        }
        return "unknown";
    }

    private static Set<String> loadersIn(boolean neoforge, boolean forge, boolean fabric, boolean quilt) {
        Set<String> result = new LinkedHashSet<>();
        if (neoforge) result.add("NeoForge");
        if (forge) result.add("Forge");
        if (fabric) result.add("Fabric");
        if (quilt) result.add("Quilt");
        return result;
    }

    private static String read(Path p) throws IOException { return Files.isRegularFile(p) ? Files.readString(p) : ""; }
    private static String first(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String value(String value) { return value == null ? "unknown" : value; }
    private static List<String> findDirs(Path root, String... dirs) {
        return Arrays.stream(dirs).filter(d -> Files.isDirectory(root.resolve(d))).toList();
    }
    private static String gitRevision(Path root) {
        try (Repository r = new FileRepositoryBuilder().findGitDir(root.toFile()).build()) {
            return r.resolve("HEAD") == null ? null : r.resolve("HEAD").name().substring(0, 12);
        } catch (Exception ignored) { return null; }
    }
}
