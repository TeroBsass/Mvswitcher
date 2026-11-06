package dev.mvswitcher;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class BuildRunner {
    private BuildRunner() {}
    public record Result(boolean started, boolean success, List<String> output) {}

    public static Result run(Path project) {
        return run(project, MigrationLog.Listener.NONE);
    }

    public static Result run(Path project, MigrationLog.Listener listener) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path wrapper = project.resolve(windows ? "gradlew.bat" : "gradlew");
        List<String> command;
        if (Files.isRegularFile(wrapper)) {
            if (!windows) wrapper.toFile().setExecutable(true);
            command = windows ? List.of("cmd", "/c", wrapper.toString(), "build", "--no-daemon")
                    : List.of(wrapper.toString(), "build", "--no-daemon");
        } else {
            command = List.of("gradle", "build", "--no-daemon");
        }
        MigrationLog.log(listener, MigrationLog.Level.INFO,
                "Starting Gradle build in " + project + ": " + String.join(" ", command));
        List<String> tail = new ArrayList<>();
        try {
            Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    MigrationLog.log(listener, MigrationLog.Level.DEBUG, "[Gradle] " + line);
                    tail.add(line);
                    if (tail.size() > 80) tail.remove(0);
                }
            }
            int exit = process.waitFor();
            MigrationLog.log(listener, exit == 0 ? MigrationLog.Level.SUCCESS : MigrationLog.Level.ERROR,
                    "Gradle build exited with code " + exit + (exit == 0 ? "." : "."));
            return new Result(true, exit == 0, tail);
        } catch (IOException e) {
            MigrationLog.log(listener, MigrationLog.Level.ERROR, "Could not start Gradle: " + e.getMessage());
            tail.add("Could not start Gradle: " + e.getMessage());
            tail.add("Install Gradle or include the Gradle Wrapper in the mod project.");
            return new Result(false, false, tail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            MigrationLog.log(listener, MigrationLog.Level.ERROR, "Gradle build interrupted.");
            tail.add("Gradle build interrupted.");
            return new Result(true, false, tail);
        }
    }
}
