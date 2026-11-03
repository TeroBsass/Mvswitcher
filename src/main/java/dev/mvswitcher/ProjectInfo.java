package dev.mvswitcher;

import java.nio.file.Path;
import java.util.*;

public record ProjectInfo(Path root, String projectName, String minecraftVersion,
                          String neoForgeVersion, String loader, int javaVersion,
                          List<String> sourceRoots, List<String> resourceRoots,
                          String gitRevision) {
    public String toReport() {
        return """
                Project: %s
                Path: %s
                Loader: %s
                Minecraft: %s
                NeoForge: %s
                Java target: %s
                Java source roots: %s
                Resource roots: %s
                Git revision: %s
                """.formatted(projectName, root, loader, value(minecraftVersion),
                value(neoForgeVersion), javaVersion == 0 ? "unknown" : javaVersion,
                sourceRoots, resourceRoots, value(gitRevision));
    }
    private static String value(String s) { return s == null ? "unknown" : s; }
}
