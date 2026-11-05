package dev.mvswitcher;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

public final class ProjectFiles {
    private ProjectFiles() {}
    public static Path resolveInput(Path input) throws IOException {
        Path absolute = input.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) return absolute;
        if (!Files.isRegularFile(absolute) || !absolute.toString().toLowerCase().endsWith(".zip"))
            throw new IOException("Input must be a project directory or .zip archive: " + input);
        Path temp = Files.createTempDirectory("mvswitcher-input-");
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(absolute))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = temp.resolve(entry.getName()).normalize();
                if (!target.startsWith(temp)) throw new IOException("Unsafe ZIP entry: " + entry.getName());
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        try (var stream = Files.list(temp)) {
            var children = stream.toList();
            if (children.size() == 1 && Files.isDirectory(children.get(0))) return children.get(0);
        }
        return temp;
    }
    public static void copyProject(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                if (excluded(rel)) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.resolve(rel));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(file);
                if (!excluded(rel)) {
                    Files.createDirectories(target.resolve(rel).getParent());
                    Files.copy(file, target.resolve(rel), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Overlays mod files onto a prepared MDK without replacing its Gradle
     * toolchain. Source Gradle properties are retained and target versions are
     * applied by MigrationEngine after the overlay.
     */
    public static void overlayProject(Path source, Path target) throws IOException {
        Path targetSources = target.resolve("src");
        if (Files.exists(targetSources)) deleteTree(targetSources);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                if (excluded(rel) || templateOwned(rel)) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.resolve(rel));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(file);
                if (!excluded(rel) && !templateOwned(rel)) {
                    Path destination = target.resolve(rel);
                    Files.createDirectories(destination.getParent());
                    if (rel.getNameCount() == 1 && rel.getFileName().toString().equals("gradle.properties")
                            && Files.isRegularFile(destination)) {
                        Files.writeString(destination, mergeGradleProperties(
                                Files.readString(destination), Files.readString(file)));
                    } else {
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String mergeGradleProperties(String template, String source) {
        String newline = template.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(template.split("\\R", -1)));
        Map<String, Integer> indexes = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String key = propertyKey(lines.get(i));
            if (key != null) indexes.putIfAbsent(key, i);
        }
        for (String line : source.split("\\R")) {
            String key = propertyKey(line);
            if (key == null) continue;
            Integer existing = indexes.get(key);
            if (existing == null) {
                indexes.put(key, lines.size());
                lines.add(line);
            } else if (key.startsWith("mod_")) {
                lines.set(existing, line);
            }
        }
        return String.join(newline, lines);
    }

    private static String propertyKey(String line) {
        Matcher matcher = Pattern.compile("^\\s*([^#!\\s:=]+)\\s*[:=].*$").matcher(line);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static boolean templateOwned(Path rel) {
        if (rel.getNameCount() == 0) return false;
        String first = rel.getName(0).toString();
        if (first.equals("gradle")) return true;
        if (rel.getNameCount() != 1) return false;
        return first.equals("build.gradle") || first.equals("build.gradle.kts") ||
                first.equals("settings.gradle") || first.equals("settings.gradle.kts") ||
                first.equals("gradlew") || first.equals("gradlew.bat");
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static boolean excluded(Path rel) {
        if (rel.getNameCount() == 0) return false;
        String first = rel.getName(0).toString();
        return first.equals(".git") || first.equals(".gradle") || first.equals("build") ||
                first.equals("converted") || first.equals(".idea");
    }
}
