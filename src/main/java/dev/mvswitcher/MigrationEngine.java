package dev.mvswitcher;

import com.fasterxml.jackson.databind.*;
import com.github.javaparser.*;
import com.github.javaparser.ast.CompilationUnit;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public final class MigrationEngine {
    private MigrationEngine() {}
    public static MigrationReport migrate(MigrationOptions o) throws IOException {
        ProjectInfo info = ProjectAnalyzer.analyze(o.input());
        if (!"NeoForge".equals(info.loader())) throw new IOException("Only NeoForge projects are supported in this first release.");
        String from = o.from() == null || o.from().isBlank() ? info.minecraftVersion() : o.from();
        if (from == null) throw new IOException("Could not detect source Minecraft version. Supply --from.");
        if (o.to() == null || o.to().isBlank()) throw new IOException("Target version is required (--to).");
        VersionProfile target = VersionCatalog.find(o.to());
        String neo = o.neoForgeVersion() != null ? o.neoForgeVersion() : target == null ? null : target.neoForge();
        int java = o.javaVersion() != null ? o.javaVersion() : target == null ? 21 : target.javaMajor();
        if (neo == null) throw new IOException("Unknown target profile. Supply --neoforge-version explicitly.");
        MigrationReport report = new MigrationReport();
        report.versions(from, o.to());
        Path out = o.output() == null
                ? info.root().resolveSibling("converted").resolve(info.projectName() + "-" + safe(o.to()))
                : o.output().toAbsolutePath().normalize();
        if (o.dryRun()) {
            report.warning("Dry run: no files were written.");
            report.warning("Version profile: Minecraft " + o.to() + ", NeoForge " + neo + ", Java " + java);
            report.warning("Rules are applied only when explicitly defined in the bundled/custom rule pack.");
            return report;
        }
        if (Files.exists(out)) {
            if (!o.force()) throw new IOException("Output already exists: " + out + " (use --force to replace it)");
            deleteTree(out);
        }
        Files.createDirectories(out);
        ProjectFiles.copyProject(info.root(), out);
        report.output(out);
        RulePack pack = loadRules(o.rules());
        applyRules(out, from, o.to(), pack, report);
        updateBuildFiles(out, o.to(), neo, java, report);
        validateJava(out, report);
        report.warning("Run the generated project's Gradle build and inspect all warnings; unknown API changes require manual migration rules.");
        return report;
    }
    private static String safe(String v) { return v.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static void deleteTree(Path root) throws IOException {
        try (var s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
    private static RulePack loadRules(Path custom) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        if (custom != null) return mapper.readValue(custom.toFile(), RulePack.class);
        try (InputStream in = MigrationEngine.class.getResourceAsStream("/rules/neoforge-rules.json")) {
            if (in == null) return new RulePack();
            return mapper.readValue(in, RulePack.class);
        }
    }
    private static void applyRules(Path root, String from, String to, RulePack pack, MigrationReport report) throws IOException {
        for (MigrationRule rule : pack.rules) {
            if (!rule.from.equals(from) || !rule.to.equals(to)) continue;
            try (var paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (!matches(rule.includes, rel)) continue;
                    String before = Files.readString(file);
                    String after = before.replace(rule.find, rule.replace);
                    if (!before.equals(after)) {
                        Files.writeString(file, after);
                        report.changed(rel + " (" + rule.id + ")");
                    }
                }
            }
        }
    }
    private static boolean matches(List<String> patterns, String path) {
        if (patterns == null || patterns.isEmpty()) return true;
        return patterns.stream().anyMatch(p -> path.matches(p.replace(".", "\\.").replace("**", ".*").replace("*", "[^/]*")));
    }
    private static void updateBuildFiles(Path root, String mc, String neo, int java, MigrationReport report) throws IOException {
        Path props = root.resolve("gradle.properties");
        if (Files.isRegularFile(props)) {
            String s = Files.readString(props);
            s = setProperty(s, "minecraft_version", mc);
            s = setProperty(s, "neo_version", neo);
            s = setProperty(s, "java_version", Integer.toString(java));
            Files.writeString(props, s);
            report.changed("gradle.properties (target versions)");
        } else report.warning("gradle.properties not found; target versions were not written there.");
    }
    private static String setProperty(String source, String key, String value) {
        Pattern p = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=.*$");
        if (p.matcher(source).find()) return p.matcher(source).replaceAll(Matcher.quoteReplacement(key + "=" + value));
        return source + (source.endsWith("\n") || source.isEmpty() ? "" : "\n") + key + "=" + value + "\n";
    }
    private static void validateJava(Path root, MigrationReport report) throws IOException {
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                ParseResult<CompilationUnit> parsed = parser.parse(file);
                if (!parsed.isSuccessful()) {
                    report.warning("JavaParser could not fully parse " + root.relativize(file) + ": " +
                            parsed.getProblems().stream().limit(2).map(Object::toString).toList());
                }
            }
        }
    }
    public static class RulePack { public List<MigrationRule> rules = new ArrayList<>(); }
    public static class MigrationRule {
        public String id, from, to, find, replace;
        public List<String> includes = new ArrayList<>();
    }
}
