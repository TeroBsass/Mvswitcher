package dev.mvswitcher;

import java.io.*;
import java.nio.file.*;
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
    private static boolean excluded(Path rel) {
        if (rel.getNameCount() == 0) return false;
        String first = rel.getName(0).toString();
        return first.equals(".git") || first.equals(".gradle") || first.equals("build") ||
                first.equals("converted") || first.equals(".idea");
    }
}
