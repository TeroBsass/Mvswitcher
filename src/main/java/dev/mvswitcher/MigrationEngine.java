package dev.mvswitcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.*;
import com.github.javaparser.ast.CompilationUnit;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public final class MigrationEngine {
    private static final String RULE_INDEX = "/rules/index.json";

    private MigrationEngine() {}

    public static MigrationReport migrate(MigrationOptions o) throws IOException {
        ProjectInfo sourceInfo = ProjectAnalyzer.analyze(o.input());
        if (!"NeoForge".equals(sourceInfo.loader())) {
            throw new IOException("Only NeoForge projects are supported.");
        }
        String from = o.from() == null || o.from().isBlank() ? sourceInfo.minecraftVersion() : o.from();
        if (from == null) throw new IOException("Could not detect source Minecraft version. Supply --from.");
        if (o.to() == null || o.to().isBlank()) throw new IOException("Target version is required (--to).");

        VersionProfile target = VersionCatalog.find(o.to());
        String neo = o.neoForgeVersion() != null ? o.neoForgeVersion() : target == null ? null : target.neoForge();
        int java = o.javaVersion() != null ? o.javaVersion() : target == null ? 21 : target.javaMajor();
        if (neo == null) throw new IOException("Unknown target profile. Supply --neoforge-version explicitly.");

        Path templateRoot = null;
        if (o.template() != null) {
            ProjectInfo templateInfo = ProjectAnalyzer.analyze(o.template());
            if (!"NeoForge".equals(templateInfo.loader())) {
                throw new IOException("--template must be a NeoForge MDK project directory or ZIP.");
            }
            if (templateInfo.minecraftVersion() != null && !o.to().equals(templateInfo.minecraftVersion())) {
                throw new IOException("Template Minecraft version " + templateInfo.minecraftVersion() +
                        " does not match target " + o.to() + ".");
            }
            if (templateInfo.neoForgeVersion() != null && !neo.equals(templateInfo.neoForgeVersion())) {
                throw new IOException("Template NeoForge version " + templateInfo.neoForgeVersion() +
                        " does not match selected version " + neo + ".");
            }
            templateRoot = templateInfo.root();
        }

        RulePack pack = loadRules(o.rules());
        validateRules(pack);
        List<MigrationRule> route = findRoute(pack.rules, from, o.to());

        MigrationReport report = new MigrationReport();
        report.versions(from, o.to());
        if (!from.equals(o.to()) && route.isEmpty()) {
            report.warning("No migration rules are defined from " + from + " to " + o.to() +
                    ". Build metadata can still be updated, but API compatibility is not covered.");
        } else if (!route.isEmpty()) {
            report.info("Selected migration route: " + describeRoute(route, o.to()));
        }

        Path sourceArgument = o.input().toAbsolutePath().normalize();
        Path outputParent = Files.isRegularFile(sourceArgument) ? sourceArgument.getParent() : sourceInfo.root().getParent();
        Path out = o.output() == null
                ? outputParent.resolve("converted").resolve(sourceInfo.projectName() + "-" + safe(o.to()))
                : o.output().toAbsolutePath().normalize();
        if (overlaps(out, sourceInfo.root())) {
            throw new IOException("Output must be outside the source project directory.");
        }
        if (templateRoot != null && overlaps(out, templateRoot)) {
            throw new IOException("Output must be outside the NeoForge template directory.");
        }
        if (templateRoot != null && overlaps(sourceInfo.root(), templateRoot)) {
            throw new IOException("Source project and NeoForge template must be separate directories.");
        }

        if (o.dryRun()) {
            report.warning("Dry run: no files were written.");
            report.warning("Target profile: Minecraft " + o.to() + ", NeoForge " + neo + ", Java " + java);
            if (templateRoot != null) {
                report.warning("Pipeline: copy prepared NeoForge template -> build template -> transfer mod files -> apply rules -> build migrated project.");
            } else {
                report.warning("Pipeline: copy source project -> apply rules -> update build metadata -> build migrated project.");
            }
            return report;
        }
        if (Files.exists(out)) {
            if (!o.force()) throw new IOException("Output already exists: " + out + " (use --force to replace it)");
            deleteTree(out);
        }
        Files.createDirectories(out);
        report.output(out);

        if (templateRoot == null) {
            ProjectFiles.copyProject(sourceInfo.root(), out);
        } else {
            ProjectFiles.copyProject(templateRoot, out);
            updateBuildFiles(out, o.to(), neo, java, report);
            if (o.skipBuild()) {
                report.templateBuild("SKIPPED", List.of());
                report.warning("Gradle builds skipped by --skip-build (both template and migrated-project builds).");
            } else {
                BuildRunner.Result templateBuild = BuildRunner.run(out);
                report.templateBuild(templateBuild.success() ? "SUCCESS" : templateBuild.started() ? "FAILED" : "NOT RUN",
                        templateBuild.output());
                if (!templateBuild.success()) {
                    report.error("Target NeoForge template build failed before source files were transferred.");
                    return report;
                }
            }
            ProjectFiles.overlayProject(sourceInfo.root(), out);
        }

        applyRules(out, from, o.to(), pack, route, report);
        updateBuildFiles(out, o.to(), neo, java, report);
        validateJava(out, report);
        if (o.skipBuild()) {
            if (templateRoot == null) report.warning("Gradle build skipped by --skip-build.");
        } else {
            BuildRunner.Result build = BuildRunner.run(out);
            report.build(build.success() ? "SUCCESS" : build.started() ? "FAILED" : "NOT RUN", build.output());
            if (!build.success()) report.error("Target Gradle build did not pass. Review the build output and unresolved API changes.");
        }
        return report;
    }

    private static boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    static RulePack loadRules(Path custom) throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        RulePack bundled = loadBundledRules(mapper);
        if (custom == null) return bundled;
        if (Files.isDirectory(custom)) {
            List<MigrationRule> additions = readRuleDirectory(custom, mapper);
            if (additions.isEmpty()) throw new IOException("No rule JSON files found under " + custom);
            RulePack overlay = new RulePack();
            overlay.rules.addAll(additions);
            validateRules(overlay);
            return mergeRules(bundled, additions);
        }
        if (!Files.isRegularFile(custom)) throw new IOException("Rules path is not a file or directory: " + custom);
        try (InputStream input = Files.newInputStream(custom)) {
            return readRulePack(mapper, input, custom.toString());
        } catch (IOException e) {
            throw new IOException("Could not load rules from " + custom + ": " + e.getMessage(), e);
        }
    }

    static List<String> bundledRoutes() throws IOException {
        RulePack pack = loadRules(null);
        validateRules(pack);
        LinkedHashSet<String> edges = new LinkedHashSet<>();
        for (MigrationRule rule : pack.rules) edges.add(rule.from + " -> " + rule.to);
        return List.copyOf(edges);
    }

    private static RulePack loadBundledRules(ObjectMapper mapper) throws IOException {
        try (InputStream indexStream = MigrationEngine.class.getResourceAsStream(RULE_INDEX)) {
            if (indexStream == null) throw new IOException("Bundled migration rule index is missing: " + RULE_INDEX);
            JsonNode indexNode = mapper.readTree(indexStream);
            if (indexNode == null || !indexNode.isObject() || !indexNode.has("files") || !indexNode.get("files").isArray()) {
                throw new IOException("Bundled migration rule index must contain a 'files' array.");
            }
            RuleIndex index = mapper.treeToValue(indexNode, RuleIndex.class);
            RulePack pack = new RulePack();
            if (index.files == null) throw new IOException("Bundled migration rule index has no files list.");
            for (String file : index.files) {
                if (!safeRulePath(file)) throw new IOException("Invalid path in bundled migration rule index: " + file);
                String resourcePath = "/rules/" + file;
                try (InputStream ruleStream = MigrationEngine.class.getResourceAsStream(resourcePath)) {
                    if (ruleStream == null) throw new IOException("Bundled rule file is missing: " + resourcePath);
                    RulePack edgeRules = readRulePack(mapper, ruleStream, resourcePath);
                    verifyDirectoryEdge(edgeRules, edgeFromPath(file), edgeToPath(file), resourcePath);
                    pack.rules.addAll(edgeRules.rules);
                }
            }
            return pack;
        } catch (IOException e) {
            throw new IOException("Could not load bundled migration rules: " + e.getMessage(), e);
        }
    }

    private static List<MigrationRule> readRuleDirectory(Path root, ObjectMapper mapper) throws IOException {
        Path rulesRoot = root.toAbsolutePath().normalize();
        List<MigrationRule> rules = new ArrayList<>();
        try (var paths = Files.walk(rulesRoot)) {
            for (Path file : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().toList()) {
                Path relative = rulesRoot.relativize(file);
                if (relative.getNameCount() < 3) {
                    throw new IOException("Rule JSON must be inside <source-version>/<target-version>/: " + file);
                }
                String source = relative.getName(0).toString();
                String target = relative.getName(1).toString();
                try (InputStream input = Files.newInputStream(file)) {
                    RulePack edgeRules = readRulePack(mapper, input, file.toString());
                    verifyDirectoryEdge(edgeRules, source, target, file.toString());
                    rules.addAll(edgeRules.rules);
                } catch (IOException e) {
                    throw new IOException("Could not load rules from " + file + ": " + e.getMessage(), e);
                }
            }
        }
        return rules;
    }

    private static RulePack readRulePack(ObjectMapper mapper, InputStream input, String label) throws IOException {
        JsonNode node = mapper.readTree(input);
        if (node == null || !node.isObject() || !node.has("rules") || !node.get("rules").isArray()) {
            throw new IOException("Rule file must contain a 'rules' array: " + label);
        }
        return mapper.treeToValue(node, RulePack.class);
    }

    private static void verifyDirectoryEdge(RulePack pack, String from, String to, String file) throws IOException {
        if (pack == null || pack.rules == null) throw new IOException("Rule file must contain a 'rules' array: " + file);
        for (MigrationRule rule : pack.rules) {
            if (rule == null) throw new IOException("Rule file contains a null entry: " + file);
            if (!Objects.equals(from, rule.from) || !Objects.equals(to, rule.to)) {
                throw new IOException("Rule " + rule.id + " in " + file +
                        " must match its directed folder path " + from + "/" + to + ".");
            }
        }
    }

    private static String edgeFromPath(String path) {
        return path.substring(0, path.indexOf('/'));
    }

    private static String edgeToPath(String path) {
        int first = path.indexOf('/');
        int second = path.indexOf('/', first + 1);
        return path.substring(first + 1, second);
    }

    private static boolean safeRulePath(String path) {
        if (path == null || path.contains("\\") || path.startsWith("/") || path.contains("..")) return false;
        String[] parts = path.split("/");
        return parts.length >= 3 && validVersion(parts[0]) && validVersion(parts[1]);
    }

    private static RulePack mergeRules(RulePack base, List<MigrationRule> additions) {
        LinkedHashMap<String, MigrationRule> merged = new LinkedHashMap<>();
        for (MigrationRule rule : base.rules) merged.put(rule.id, rule);
        for (MigrationRule rule : additions) merged.put(rule.id, rule);
        RulePack result = new RulePack();
        result.rules.addAll(merged.values());
        return result;
    }

    private static void validateRules(RulePack pack) throws IOException {
        if (pack == null || pack.rules == null) throw new IOException("Rule pack must contain a 'rules' array.");
        Set<String> ids = new HashSet<>();
        for (MigrationRule rule : pack.rules) {
            if (rule == null) throw new IOException("Rule pack contains a null rule.");
            if (blank(rule.id)) throw new IOException("Every migration rule must have a non-empty id.");
            if (!ids.add(rule.id)) throw new IOException("Duplicate migration rule id: " + rule.id);
            if (!validVersion(rule.from) || !validVersion(rule.to)) {
                throw new IOException("Rule " + rule.id + " must have valid 'from' and 'to' versions.");
            }
            if (rule.from.equals(rule.to)) throw new IOException("Rule " + rule.id + " has the same source and target version.");
            boolean hasLiteralFind = rule.find != null && !rule.find.isEmpty();
            boolean hasRegexFind = rule.findRegex != null && !rule.findRegex.isEmpty();
            if (hasLiteralFind == hasRegexFind) {
                throw new IOException("Rule " + rule.id + " must set exactly one non-empty 'find' or 'findRegex' value.");
            }
            if (rule.replace == null) throw new IOException("Rule " + rule.id + " must have a 'replace' string (use an empty string to delete text).");
            if (hasLiteralFind && rule.find.equals(rule.replace)) throw new IOException("Rule " + rule.id + " is a no-op: 'find' and 'replace' are identical.");
            if (hasRegexFind) {
                try {
                    Pattern.compile(rule.findRegex);
                } catch (PatternSyntaxException e) {
                    throw new IOException("Rule " + rule.id + " has an invalid 'findRegex': " + e.getMessage(), e);
                }
            }
            if (rule.includes == null || rule.includes.isEmpty() || rule.includes.stream().anyMatch(MigrationEngine::blank)) {
                throw new IOException("Rule " + rule.id + " must provide at least one non-empty 'includes' glob.");
            }
            if (rule.skipIfContains != null && rule.skipIfContains.isEmpty()) {
                throw new IOException("Rule " + rule.id + " has an empty 'skipIfContains' guard.");
            }
            if (rule.maxReplacements != null && rule.maxReplacements < 1) {
                throw new IOException("Rule " + rule.id + " must set 'maxReplacements' to a positive number.");
            }
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static boolean validVersion(String version) {
        return version != null && version.matches("[A-Za-z0-9.+_-]+");
    }

    private static void applyRules(Path root, String from, String to, RulePack pack,
                                   List<MigrationRule> route, MigrationReport report) throws IOException {
        if (from.equals(to)) return;
        for (MigrationRule edge : route) {
            for (MigrationRule rule : pack.rules) {
                if (!Objects.equals(rule.from, edge.from) || !Objects.equals(rule.to, edge.to)) continue;
                int replacements = 0;
                int filesChanged = 0;
                try (var paths = Files.walk(root)) {
                    for (Path file : paths.filter(Files::isRegularFile).toList()) {
                        String relative = root.relativize(file).toString().replace('\\', '/');
                        if (!matches(rule.includes, relative)) continue;
                        String before;
                        try {
                            before = Files.readString(file);
                        } catch (IOException e) {
                            throw new IOException("Rule " + rule.id + " could not read " + relative + ": " + e.getMessage(), e);
                        }
                        if (rule.skipIfContains != null && before.contains(rule.skipIfContains)) continue;
                        Replacement result = rule.findRegex == null
                                ? replaceLiteral(before, rule.find, rule.replace, rule.maxReplacements)
                                : replaceRegex(before, rule.findRegex, rule.replace, rule.maxReplacements, rule.id);
                        if (result.count > 0) {
                            Files.writeString(file, result.text);
                            replacements += result.count;
                            filesChanged++;
                            report.changed(relative + " (" + rule.id + ", " + result.count + " replacement(s))");
                        }
                    }
                }
                if (rule.required && replacements == 0) {
                    throw new IOException("Required migration rule " + rule.id + " did not match any files.");
                }
                report.ruleResult(rule.id + ": " + replacements + " replacement(s) in " + filesChanged + " file(s)");
            }
        }
    }

    static List<MigrationRule> findRoute(List<MigrationRule> rules, String from, String to) {
        if (Objects.equals(from, to)) return List.of();
        record Step(String version, List<MigrationRule> path) {}
        Queue<Step> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new Step(from, List.of()));
        visited.add(from);
        while (!queue.isEmpty()) {
            Step current = queue.remove();
            Set<String> outgoing = new HashSet<>();
            for (MigrationRule edge : rules) {
                if (!Objects.equals(edge.from, current.version) || edge.to == null ||
                        visited.contains(edge.to) || !outgoing.add(edge.to)) continue;
                List<MigrationRule> path = new ArrayList<>(current.path);
                path.add(edge);
                if (edge.to.equals(to)) return path;
                visited.add(edge.to);
                queue.add(new Step(edge.to, path));
            }
        }
        return List.of();
    }

    private static String describeRoute(List<MigrationRule> route, String to) {
        List<String> versions = new ArrayList<>();
        versions.add(route.get(0).from);
        for (MigrationRule edge : route) versions.add(edge.to);
        if (!Objects.equals(versions.get(versions.size() - 1), to)) versions.add(to);
        return String.join(" -> ", versions);
    }

    private static boolean matches(List<String> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> path.matches(globRegex(pattern)));
    }

    private static String globRegex(String glob) {
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                    i++;
                    out.append("(?:.*/)?");
                } else out.append(".*");
            } else if (c == '*') out.append("[^/]*");
            else if (c == '?') out.append("[^/]");
            else {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) out.append('\\');
                out.append(c);
            }
        }
        return out.append("$").toString();
    }

    private static Replacement replaceLiteral(String source, String find, String replace, Integer max) {
        StringBuilder output = new StringBuilder();
        int cursor = 0;
        int count = 0;
        int match;
        while ((match = source.indexOf(find, cursor)) >= 0 && (max == null || count < max)) {
            output.append(source, cursor, match).append(replace);
            cursor = match + find.length();
            count++;
        }
        if (count == 0) return new Replacement(source, 0);
        output.append(source, cursor, source.length());
        return new Replacement(output.toString(), count);
    }

    private static Replacement replaceRegex(String source, String regex, String replace, Integer max, String ruleId)
            throws IOException {
        Matcher matcher = Pattern.compile(regex).matcher(source);
        StringBuffer output = new StringBuffer();
        int count = 0;
        try {
            while (matcher.find() && (max == null || count < max)) {
                matcher.appendReplacement(output, replace);
                count++;
            }
            if (count == 0) return new Replacement(source, 0);
            matcher.appendTail(output);
            return new Replacement(output.toString(), count);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new IOException("Rule " + ruleId + " has an invalid regex replacement: " + e.getMessage(), e);
        }
    }

    private record Replacement(String text, int count) {}

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
        updateMinecraftVersionRanges(root, mc, report);
    }

    /**
     * Keep NeoForge's Minecraft compatibility range in sync with the selected
     * target version. MDKs may place this property in settings.gradle,
     * gradle.properties, or another Gradle/TOML configuration file.
     */
    private static void updateMinecraftVersionRanges(Path root, String mc, MigrationReport report) throws IOException {
        Pattern range = Pattern.compile("(?m)^(\\s*minecraft_version_range\\s*=\\s*)([\"']?)\\[[^\\r\\n]*\\]\\2(\\s*(?:#.*)?)$");
        List<String> configNames = List.of(
                "gradle.properties", "settings.gradle", "settings.gradle.kts",
                "build.gradle", "build.gradle.kts", "neoforge.mods.toml", "mods.toml"
        );
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> configNames.contains(path.getFileName().toString()))
                    .toList()) {
                String before = Files.readString(file);
                Matcher matcher = range.matcher(before);
                StringBuffer updated = new StringBuffer();
                boolean found = false;
                while (matcher.find()) {
                    found = true;
                    String replacement = matcher.group(1) + matcher.group(2) + "[" + mc + "]"
                            + matcher.group(2) + matcher.group(3);
                    matcher.appendReplacement(updated, Matcher.quoteReplacement(replacement));
                }
                if (found) {
                    matcher.appendTail(updated);
                    String after = updated.toString();
                    if (!before.equals(after)) {
                        Files.writeString(file, after);
                        report.changed(root.relativize(file).toString().replace('\\', '/') +
                                " (minecraft_version_range -> [" + mc + "])");
                    }
                }
            }
        }
    }

    private static String setProperty(String source, String key, String value) {
        Pattern pattern = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=.*$");
        if (pattern.matcher(source).find()) {
            return pattern.matcher(source).replaceAll(Matcher.quoteReplacement(key + "=" + value));
        }
        return source + (source.endsWith("\n") || source.isEmpty() ? "" : "\n") + key + "=" + value + "\n";
    }

    private static void validateJava(Path root, MigrationReport report) throws IOException {
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                ParseResult<CompilationUnit> parsed = parser.parse(file);
                if (!parsed.isSuccessful()) {
                    report.warning("JavaParser could not fully parse " + root.relativize(file) + ": " +
                            parsed.getProblems().stream().limit(2).map(Object::toString).toList());
                }
            }
        }
    }

    public static class RulePack { public List<MigrationRule> rules = new ArrayList<>(); }
    public static class RuleIndex { public List<String> files = new ArrayList<>(); }
    public static class MigrationRule {
        public String id, from, to, find, findRegex, replace, skipIfContains;
        public List<String> includes = new ArrayList<>();
        public Integer maxReplacements;
        public boolean required;
    }
}
