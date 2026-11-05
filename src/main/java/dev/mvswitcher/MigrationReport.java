package dev.mvswitcher;

import java.nio.file.*;
import java.util.*;

public class MigrationReport {
    private final List<String> changed = new ArrayList<>();
    private final List<String> info = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> ruleResults = new ArrayList<>();
    private Path output;
    private String from, to;
    private String buildStatus = "not run";
    private final List<String> buildOutput = new ArrayList<>();
    private String templateBuildStatus = "not used";
    private final List<String> templateBuildOutput = new ArrayList<>();
    public void changed(String s) { changed.add(s); }
    public void info(String s) { info.add(s); }
    public void warning(String s) { warnings.add(s); }
    public void error(String s) { errors.add(s); }
    public void ruleResult(String s) { ruleResults.add(s); }
    public void output(Path p) { output = p; }
    public void versions(String f, String t) { from = f; to = t; }
    public void build(String status, List<String> output) { buildStatus = status; buildOutput.addAll(output); }
    public void templateBuild(String status, List<String> output) {
        templateBuildStatus = status;
        templateBuildOutput.addAll(output);
    }
    public boolean hasErrors() { return !errors.isEmpty(); }
    public String toText() {
        return "MVSwitcher migration report\nFrom: " + from + "\nTo: " + to +
                "\nOutput: " + (output == null ? "(dry run)" : output) +
                "\nChanged (" + changed.size() + "):\n" + lines(changed) +
                "\nRule applications (" + ruleResults.size() + "):\n" + lines(ruleResults) +
                "\nPlan (" + info.size() + "):\n" + lines(info) +
                "\nWarnings (" + warnings.size() + "):\n" + lines(warnings) +
                "\nErrors (" + errors.size() + "):\n" + lines(errors) +
                "\nNeoForge template build: " + templateBuildStatus + "\n" + lines(templateBuildOutput) +
                "\nGradle build: " + buildStatus + "\n" + lines(buildOutput) +
                "\nNote: source project is never modified. A successful Gradle build is not guaranteed by source rewriting alone.\n";
    }
    private String lines(List<String> list) { return list.isEmpty() ? "  none\n" : list.stream().map(s -> "  - " + s + "\n").reduce("", String::concat); }
}
