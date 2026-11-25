package dev.mvswitcher.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Compares the public API of two source trees (for example decompiled Minecraft sources of two
 * neighbouring versions) and produces candidate migration rules in the MVSwitcher rules.json format.
 *
 * <p>What it detects:
 * <ul>
 *   <li>classes that moved to another package (same simple name),</li>
 *   <li>classes that were renamed (very similar method shape, mutual best match),</li>
 *   <li>methods that were renamed with an identical signature inside the same class.</li>
 * </ul>
 * Class moves and renames become {@code text} rules. Method renames become {@code warn} rules, because
 * a text replacement of a method name cannot know the receiver type; with
 * {@link Options#autoReplaceMethods()} the unambiguous ones become {@code text} rules instead.
 * Everything else (removed classes, methods without a match) is only listed in the notes.
 * The output is a set of <em>candidates</em> to be reviewed by a human, not a finished migration.
 */
public final class ApiDiffGenerator {
    private static final double CLASS_SIMILARITY = 0.8;
    private static final int MIN_METHODS_FOR_CLASS_RENAME = 3;

    private ApiDiffGenerator() {}

    public record Method(String name, String params, String returnType) {}

    public record ClassApi(String fqn, String simple, boolean topLevel, Set<Method> methods) {}

    public record Scan(Map<String, ClassApi> classes, int parsedFiles, int failedFiles) {}

    public record Options(String from, String to, String loader, boolean autoReplaceMethods,
                          boolean includeRemoved) {}

    public record Result(List<Map<String, Object>> rules, List<String> notes) {}

    // ---------------------------------------------------------------- scanning

    /**
     * Scans a directory tree of {@code .java} files, or a {@code .jar}/{@code .zip} archive of sources
     * (for example a {@code *-sources.jar}), which is read in place without unpacking it.
     */
    public static Scan scan(Path root) throws IOException {
        JavaParser parser = new JavaParser(
                new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
        Map<String, ClassApi> classes = new TreeMap<>();
        int parsed = 0;
        int failed = 0;
        if (Files.isRegularFile(root)) {
            try (ZipFile zip = new ZipFile(root.toFile())) {
                List<? extends ZipEntry> entries = zip.stream()
                        .filter(e -> !e.isDirectory() && e.getName().endsWith(".java"))
                        .sorted(Comparator.comparing(ZipEntry::getName)).toList();
                for (ZipEntry entry : entries) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8);
                        if (collect(parser.parse(reader), classes)) parsed++; else failed++;
                    }
                }
            } catch (ZipException e) {
                throw new IOException("Not a readable .jar/.zip archive: " + root + " (" + e.getMessage() + ")", e);
            }
        } else {
            List<Path> files;
            try (Stream<Path> paths = Files.walk(root)) {
                files = paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java")).sorted().toList();
            }
            for (Path file : files) {
                if (collect(parser.parse(file), classes)) parsed++; else failed++;
            }
        }
        return new Scan(classes, parsed, failed);
    }

    /** Adds the public API of one parsed file to {@code classes}; false when the file could not be parsed. */
    private static boolean collect(ParseResult<CompilationUnit> result, Map<String, ClassApi> classes) {
        if (!result.isSuccessful() || result.getResult().isEmpty()) return false;
        CompilationUnit unit = result.getResult().get();
        String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            String name = type.getNameAsString();
            boolean topLevel = true;
            Node parent = type.getParentNode().orElse(null);
            while (parent instanceof TypeDeclaration<?> outer) {
                name = outer.getNameAsString() + "." + name;
                topLevel = false;
                parent = outer.getParentNode().orElse(null);
            }
            if (!(parent instanceof CompilationUnit)) continue; // local or anonymous type
            if (type.isPrivate() || (topLevel && !type.isPublic())) continue;
            String fqn = pkg.isEmpty() ? name : pkg + "." + name;
            boolean iface = (type instanceof ClassOrInterfaceDeclaration c && c.isInterface())
                    || type instanceof AnnotationDeclaration;
            Set<Method> methods = new TreeSet<>(Comparator.comparing(Method::name)
                    .thenComparing(Method::params).thenComparing(Method::returnType));
            for (MethodDeclaration method : type.getMethods()) {
                if (!(method.isPublic() || method.isProtected() || (iface && !method.isPrivate()))) continue;
                String params = method.getParameters().stream()
                        .map(p -> normalizeType(p.getType().asString()) + (p.isVarArgs() ? "..." : ""))
                        .collect(Collectors.joining(","));
                methods.add(new Method(method.getNameAsString(), params,
                        normalizeType(method.getType().asString())));
            }
            classes.put(fqn, new ClassApi(fqn, type.getNameAsString(), topLevel, methods));
        }
        return true;
    }

    static String normalizeType(String type) {
        String result = type.trim();
        while (result.matches("(?s).*<[^<>]*>.*")) result = result.replaceAll("<[^<>]*>", "");
        return result.replaceAll("\\s+", "");
    }

    // ---------------------------------------------------------------- diffing

    public static Result diff(Scan oldScan, Scan newScan, Options options) {
        Map<String, ClassApi> oldClasses = oldScan.classes();
        Map<String, ClassApi> newClasses = newScan.classes();
        Set<String> removed = new TreeSet<>(oldClasses.keySet());
        removed.removeAll(newClasses.keySet());
        Set<String> added = new TreeSet<>(newClasses.keySet());
        added.removeAll(oldClasses.keySet());

        List<Map<String, Object>> rules = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Ids ids = new Ids(options);
        Map<String, String> classMap = new LinkedHashMap<>();       // old fqn -> new fqn
        Map<String, String> simpleRenames = new LinkedHashMap<>();  // old simple -> new simple
        Set<String> handledRemoved = new HashSet<>();
        Set<String> handledAdded = new HashSet<>();
        List<Map<String, Object>> classRules = new ArrayList<>();
        List<Map<String, Object>> simpleNameRules = new ArrayList<>();

        // 1. Classes that kept their simple name but changed package.
        Map<String, List<String>> removedBySimple = groupBySimple(removed, oldClasses);
        Map<String, List<String>> addedBySimple = groupBySimple(added, newClasses);
        for (Map.Entry<String, List<String>> entry : removedBySimple.entrySet()) {
            List<String> candidates = addedBySimple.get(entry.getKey());
            if (candidates == null || entry.getValue().size() != 1 || candidates.size() != 1) continue;
            String oldFqn = entry.getValue().get(0);
            String newFqn = candidates.get(0);
            classRules.add(text(ids.next("class-move", oldFqn), options, wordRegex(oldFqn), newFqn));
            classMap.put(oldFqn, newFqn);
            handledRemoved.add(oldFqn);
            handledAdded.add(newFqn);
            notes.add("class moved: " + oldFqn + " -> " + newFqn);
        }

        // 2. Classes that were renamed: mutual best match by method shape.
        List<String> remainingRemoved = removed.stream().filter(c -> !handledRemoved.contains(c)).toList();
        List<String> remainingAdded = added.stream().filter(c -> !handledAdded.contains(c)).toList();
        for (String oldFqn : remainingRemoved) {
            ClassApi oldClass = oldClasses.get(oldFqn);
            if (shape(oldClass).size() < MIN_METHODS_FOR_CLASS_RENAME) continue;
            String newFqn = bestMatch(remainingAdded, c -> similarity(oldClass, newClasses.get(c)));
            if (newFqn == null) continue;
            ClassApi newClass = newClasses.get(newFqn);
            String back = bestMatch(remainingRemoved, c -> similarity(oldClasses.get(c), newClass));
            if (!oldFqn.equals(back) || handledAdded.contains(newFqn)) continue;
            classRules.add(text(ids.next("class-rename", oldFqn), options, wordRegex(oldFqn), newFqn));
            if (!oldClass.simple().equals(newClass.simple())) {
                simpleNameRules.add(text(ids.next("class-rename-simple", oldClass.simple()), options,
                        wordRegex(oldClass.simple()), newClass.simple()));
                simpleRenames.put(oldClass.simple(), newClass.simple());
            }
            classMap.put(oldFqn, newFqn);
            handledRemoved.add(oldFqn);
            handledAdded.add(newFqn);
            notes.add(String.format(Locale.ROOT, "class renamed (similarity %.2f, review it): %s -> %s",
                    similarity(oldClass, newClass), oldFqn, newFqn));
        }
        rules.addAll(classRules);
        rules.addAll(simpleNameRules);

        // 3. Methods renamed with an identical signature.
        Set<String> newMethodNames = newClasses.values().stream()
                .flatMap(c -> c.methods().stream()).map(Method::name).collect(Collectors.toSet());
        Map<String, List<String>> renamedMethods = new LinkedHashMap<>(); // "old->new" -> class names
        Map<String, Set<String>> newNamesForOld = new HashMap<>();
        List<String> pairs = new ArrayList<>();
        for (String fqn : oldClasses.keySet()) if (newClasses.containsKey(fqn)) pairs.add(fqn);
        for (String fqn : classMap.keySet()) if (!pairs.contains(fqn)) pairs.add(fqn);
        Collections.sort(pairs);
        for (String oldFqn : pairs) {
            String newFqn = classMap.getOrDefault(oldFqn, oldFqn);
            ClassApi oldClass = oldClasses.get(oldFqn);
            ClassApi newClass = newClasses.get(newFqn);
            Set<Method> oldMethods = new LinkedHashSet<>();
            for (Method method : oldClass.methods()) {
                oldMethods.add(new Method(method.name(), applyRenames(method.params(), simpleRenames),
                        applyRenames(method.returnType(), simpleRenames)));
            }
            List<Method> removedMethods = oldMethods.stream().filter(m -> !newClass.methods().contains(m)).toList();
            List<Method> addedMethods = newClass.methods().stream().filter(m -> !oldMethods.contains(m)).toList();
            for (Method gone : removedMethods) {
                List<Method> sameSignatureRemoved = removedMethods.stream()
                        .filter(m -> m.params().equals(gone.params()) && m.returnType().equals(gone.returnType())).toList();
                List<Method> candidates = addedMethods.stream()
                        .filter(m -> m.params().equals(gone.params()) && m.returnType().equals(gone.returnType())).toList();
                if (sameSignatureRemoved.size() == 1 && candidates.size() == 1) {
                    String key = gone.name() + "->" + candidates.get(0).name();
                    renamedMethods.computeIfAbsent(key, k -> new ArrayList<>()).add(newClass.simple());
                    newNamesForOld.computeIfAbsent(gone.name(), k -> new TreeSet<>()).add(candidates.get(0).name());
                } else {
                    notes.add("method removed or changed (no unique match): " + oldClass.simple() + "."
                            + gone.name() + "(" + gone.params() + ") : " + gone.returnType());
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : renamedMethods.entrySet()) {
            String[] names = entry.getKey().split("->");
            String oldName = names[0];
            String newName = names[1];
            List<String> classNames = entry.getValue().stream().distinct().sorted().toList();
            boolean unambiguous = !newMethodNames.contains(oldName) && newNamesForOld.get(oldName).size() == 1;
            String regex = "\\b" + Pattern.quote(oldName) + "\\(";
            if (options.autoReplaceMethods() && unambiguous) {
                rules.add(text(ids.next("method-rename", oldName + "-" + newName), options, regex, newName + "("));
                notes.add("method rename applied as text rule: " + oldName + " -> " + newName + " in " + classNames);
            } else {
                rules.add(warn(ids.next("method-rename-warning", oldName + "-" + newName), options, regex,
                        "Method " + oldName + "(...) was renamed to " + newName + "(...) in " + classNames
                                + " (" + options.from() + " -> " + options.to() + "). Check calls and overrides."));
                notes.add("method rename reported as warning: " + oldName + " -> " + newName + " in " + classNames);
            }
        }

        // 4. Classes that no longer exist.
        for (String fqn : removed) {
            if (handledRemoved.contains(fqn)) continue;
            ClassApi gone = oldClasses.get(fqn);
            notes.add("class removed: " + fqn);
            if (options.includeRemoved() && gone.topLevel()) {
                rules.add(warnLiteral(ids.next("class-removed", fqn), options, "import " + fqn + ";",
                        "Class " + fqn + " does not exist in " + options.to() + "; port its usages manually."));
            }
        }
        return new Result(rules, notes);
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, List<String>> groupBySimple(Collection<String> fqns, Map<String, ClassApi> classes) {
        Map<String, List<String>> result = new TreeMap<>();
        for (String fqn : fqns) {
            result.computeIfAbsent(classes.get(fqn).simple(), k -> new ArrayList<>()).add(fqn);
        }
        return result;
    }

    private static Set<String> shape(ClassApi api) {
        Set<String> result = new HashSet<>();
        for (Method m : api.methods()) {
            int arity = m.params().isEmpty() ? 0 : m.params().split(",").length;
            result.add(m.name() + "/" + arity);
        }
        return result;
    }

    private static double similarity(ClassApi a, ClassApi b) {
        Set<String> first = shape(a);
        Set<String> second = shape(b);
        if (first.isEmpty() || second.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(first);
        intersection.retainAll(second);
        Set<String> union = new HashSet<>(first);
        union.addAll(second);
        return (double) intersection.size() / union.size();
    }

    /** Best candidate with a score above the threshold that is strictly better than the runner-up. */
    private static String bestMatch(List<String> candidates, java.util.function.ToDoubleFunction<String> score) {
        String best = null;
        double bestScore = 0;
        double second = 0;
        for (String candidate : candidates) {
            double value = score.applyAsDouble(candidate);
            if (value > bestScore) {
                second = bestScore;
                bestScore = value;
                best = candidate;
            } else if (value > second) {
                second = value;
            }
        }
        return best != null && bestScore >= CLASS_SIMILARITY && bestScore > second ? best : null;
    }

    private static String applyRenames(String type, Map<String, String> simpleRenames) {
        String result = type;
        for (Map.Entry<String, String> entry : simpleRenames.entrySet()) {
            result = result.replaceAll("\\b" + Pattern.quote(entry.getKey()) + "\\b",
                    Matcher.quoteReplacement(entry.getValue()));
        }
        return result;
    }

    private static String wordRegex(String name) {
        return "\\b" + name.replace(".", "\\.") + "\\b";
    }

    private static Map<String, Object> base(String id, Options options, String type) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", id);
        rule.put("from", options.from());
        rule.put("to", options.to());
        rule.put("loader", options.loader());
        if (type != null) rule.put("type", type);
        rule.put("includes", List.of("**/*.java"));
        return rule;
    }

    private static Map<String, Object> text(String id, Options options, String regex, String replacement) {
        Map<String, Object> rule = base(id, options, null);
        rule.put("findRegex", regex);
        rule.put("replace", Matcher.quoteReplacement(replacement));
        return rule;
    }

    private static Map<String, Object> warn(String id, Options options, String regex, String message) {
        Map<String, Object> rule = base(id, options, "warn");
        rule.put("findRegex", regex);
        rule.put("message", message);
        return rule;
    }

    private static Map<String, Object> warnLiteral(String id, Options options, String literal, String message) {
        Map<String, Object> rule = base(id, options, "warn");
        rule.put("find", literal);
        rule.put("message", message);
        return rule;
    }

    private static final class Ids {
        private final Options options;
        private final Set<String> used = new HashSet<>();

        Ids(Options options) { this.options = options; }

        String next(String kind, String name) {
            String base = ("auto-" + options.from() + "-" + options.to() + "-" + kind + "-" + name)
                    .replaceAll("[^A-Za-z0-9._-]", "_");
            String id = base;
            for (int i = 2; !used.add(id); i++) id = base + "-" + i;
            return id;
        }
    }

    // ---------------------------------------------------------------- output

    public static void write(Result result, Options options, Path outputRoot) throws IOException {
        Path directory = outputRoot.resolve(options.from()).resolve(options.to());
        Files.createDirectories(directory);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(directory.resolve("rules.json"),
                mapper.writeValueAsString(Map.of("rules", result.rules())) + "\n");
        Files.write(directory.resolve("notes.txt"), result.notes());
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> values = new HashMap<>();
        Set<String> flags = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + args[i]);
            if (args[i].equals("--auto-replace-methods") || args[i].equals("--include-removed")) {
                flags.add(args[i]);
            } else if (i + 1 < args.length) {
                values.put(args[i], args[++i]);
            } else {
                throw new IllegalArgumentException("Missing value for " + args[i]);
            }
        }
        for (String required : List.of("--old", "--new", "--from", "--to")) {
            if (!values.containsKey(required)) {
                System.err.println("Usage: ApiDiffGenerator --old <dir|jar> --new <dir|jar> --from <version> --to <version>"
                        + " [--loader neoforge] [--out generated-rules] [--auto-replace-methods] [--include-removed]");
                System.exit(2);
            }
        }
        Options options = new Options(values.get("--from"), values.get("--to"),
                values.getOrDefault("--loader", "neoforge").toLowerCase(Locale.ROOT),
                flags.contains("--auto-replace-methods"), flags.contains("--include-removed"));
        Scan oldScan = scan(Path.of(values.get("--old")));
        Scan newScan = scan(Path.of(values.get("--new")));
        System.out.printf("Scanned old: %d classes (%d files, %d unparsable); new: %d classes (%d files, %d unparsable)%n",
                oldScan.classes().size(), oldScan.parsedFiles(), oldScan.failedFiles(),
                newScan.classes().size(), newScan.parsedFiles(), newScan.failedFiles());
        Result result = diff(oldScan, newScan, options);
        Path out = Path.of(values.getOrDefault("--out", "generated-rules"));
        write(result, options, out);
        System.out.printf("Wrote %d candidate rule(s) and %d note(s) to %s%n",
                result.rules().size(), result.notes().size(),
                out.resolve(options.from()).resolve(options.to()));
    }
}
