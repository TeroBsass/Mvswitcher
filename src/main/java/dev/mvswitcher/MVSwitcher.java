package dev.mvswitcher;

import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "mvswitcher", mixinStandardHelpOptions = true, version = "MVSwitcher 0.1.0",
        description = "Bidirectional Minecraft NeoForge mod migration CLI.",
        subcommands = {MVSwitcher.Migrate.class, MVSwitcher.Analyze.class, MVSwitcher.Versions.class})
public class MVSwitcher implements Runnable {
    public static void main(String[] args) {
        int code = new CommandLine(new MVSwitcher()).execute(args);
        System.exit(code);
    }

    @Override public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "analyze", description = "Detect mod loader, Minecraft version and project structure.")
    static class Analyze implements Callable<Integer> {
        @Parameters(index = "0", description = "Mod project directory or ZIP file.") Path input;
        public Integer call() {
            try {
                ProjectInfo info = ProjectAnalyzer.analyze(input);
                System.out.println(info.toReport());
                return 0;
            } catch (Exception e) {
                System.err.println("Analysis failed: " + e.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "migrate", description = "Copy and migrate a mod into a separate converted/ directory.")
    static class Migrate implements Callable<Integer> {
        @Parameters(index = "0", description = "Source mod directory or ZIP file.") Path input;
        @Option(names = {"--from"}, description = "Source Minecraft version (auto-detected when omitted).") String from;
        @Option(names = {"--to"}, required = true, description = "Target Minecraft version, e.g. 1.21.11 or 26.3.") String to;
        @Option(names = {"--neoforge-version"}, description = "Override target NeoForge version.") String neoForgeVersion;
        @Option(names = {"--java"}, description = "Target Java major version (default from version profile).") Integer javaVersion;
        @Option(names = {"--output"}, description = "Output directory (default: converted/<project>-<target>).") Path output;
        @Option(names = {"--rules"}, description = "Custom rules JSON file (standalone) or rules directory (overrides bundled rule IDs).") Path rules;
        @Option(names = {"--template"}, description = "Prepared target NeoForge MDK directory or ZIP; build it before source files are transferred.") Path template;
        @Option(names = {"--dry-run"}, description = "Analyze and plan without writing files.") boolean dryRun;
        @Option(names = {"--force"}, description = "Allow an existing output directory to be replaced.") boolean force;
        @Option(names = {"--skip-build"}, description = "Do not run the target Gradle build after migration.") boolean skipBuild;

        public Integer call() {
            try {
                MigrationOptions options = new MigrationOptions(input, from, to, neoForgeVersion,
                        javaVersion, output, rules, template, dryRun, force, skipBuild);
                MigrationReport report = MigrationEngine.migrate(options);
                System.out.println(report.toText());
                return report.hasErrors() ? 1 : 0;
            } catch (Exception e) {
                System.err.println("Migration failed: " + e.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "versions", description = "List known NeoForge migration profiles.")
    static class Versions implements Callable<Integer> {
        public Integer call() {
            VersionCatalog.all().forEach(v -> System.out.printf("%-10s Minecraft %-10s NeoForge %-14s Java %s%n",
                    v.id(), v.minecraft(), v.neoForge() == null ? "override required" : v.neoForge(), v.javaMajor()));
            System.out.println("Unknown versions can still be targeted with --to and --neoforge-version/--java.");
            try {
                System.out.println("Bundled directed rule paths:");
                MigrationEngine.bundledRoutes().forEach(path -> System.out.println("  " + path));
            } catch (Exception e) {
                System.err.println("Could not load migration rule paths: " + e.getMessage());
                return 2;
            }
            return 0;
        }
    }
}
