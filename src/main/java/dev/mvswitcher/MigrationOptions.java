package dev.mvswitcher;

import java.nio.file.Path;

public record MigrationOptions(Path input, String from, String to, String neoForgeVersion,
                               Integer javaVersion, Path output, Path rules, boolean dryRun,
                               boolean force, boolean skipBuild) {}
