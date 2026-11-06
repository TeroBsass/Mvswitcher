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
        String loader = Files.exists(root.resolve("src/main/resources/META-INF/neoforge.mods.toml")) ||
                gradle.toLowerCase(Locale.ROOT).contains("neoforge") ? "NeoForge" : "unknown";
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
