package dev.mvswitcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuleFormatTest {
    @TempDir Path temp;

    private MigrationEngine.MigrationRule rule(String id, String from, String to, String loader) {
        var rule = new MigrationEngine.MigrationRule();
        rule.id = id;
        rule.from = from;
        rule.to = to;
        rule.loader = loader;
        rule.find = "old";
        rule.replace = "new";
        return rule;
    }

    private Path project(String javaSource) throws IOException {
        Path source = temp.resolve("Project");
        Files.createDirectories(source.resolve("src/main/resources/META-INF"));
        Files.createDirectories(source.resolve("src/main/java/example"));
        Files.writeString(source.resolve("build.gradle"), "plugins { id 'net.neoforged.gradle.userdev' }\n");
        Files.writeString(source.resolve("gradle.properties"), "minecraft_version=1.21.11\nneo_version=21.11.48\n");
        Files.writeString(source.resolve("src/main/resources/META-INF/neoforge.mods.toml"), "modLoader=\"javafml\"\n");
        Files.writeString(source.resolve("src/main/java/example/Thing.java"), javaSource);
        return source;
    }

    @Test void catalogIsLoadedFromJsonAndKeepsOrder() {
        List<VersionProfile> all = VersionCatalog.all();
        assertEquals("1.21.11", all.get(0).id());
        assertEquals("26.3", all.get(all.size() - 1).id());
        VersionProfile profile = VersionCatalog.find("26.3", "neoforge");
        assertNotNull(profile);
        assertEquals("26.3.0.99", profile.neoForge());
        assertEquals(25, profile.javaMajor());
        assertNull(VersionCatalog.find("26.3", "fabric"));
    }

    @Test void catalogRejectsProfilesWithoutJavaVersion() {
        String json = "{\"profiles\":[{\"minecraft\":\"1.20.1\",\"loader\":\"forge\"}]}";
        assertThrows(IOException.class, () -> VersionCatalog.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test"));
    }

    @Test void nonNeoForgeProfileHasNoNeoForgeVersion() throws Exception {
        String json = "{\"profiles\":[{\"minecraft\":\"1.20.1\",\"loader\":\"forge\",\"loaderVersion\":\"47.3.0\",\"java\":17}]}";
        VersionProfile profile = VersionCatalog.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test").get(0);
        assertEquals("forge", profile.loader());
        assertEquals("47.3.0", profile.loaderVersion());
        assertNull(profile.neoForge());
    }

    @Test void routeOnlyUsesRulesOfTheRequestedLoader() {
        var rules = List.of(
                rule("neo-a", "1.20.1", "1.21.1", null),
                rule("fabric-a", "1.20.1", "1.21.1", "fabric"),
                rule("fabric-b", "1.21.1", "1.21.11", "fabric"));
        assertEquals(1, MigrationEngine.findRoute(rules, "1.20.1", "1.21.1", "neoforge").size());
        assertTrue(MigrationEngine.findRoute(rules, "1.20.1", "1.21.11", "neoforge").isEmpty());
        assertEquals(2, MigrationEngine.findRoute(rules, "1.20.1", "1.21.11", "fabric").size());
    }

    @Test void warningRuleReportsMatchesWithoutChangingFiles() throws Exception {
        Path source = project("class Thing { void run() { legacyCall(); legacyCall(); } }");
        Path rulesFile = temp.resolve("warn-rules.json");
        Files.writeString(rulesFile, """
                {
                  "rules": [{
                    "id": "legacy-call-warning",
                    "from": "1.21.11",
                    "to": "26.3",
                    "type": "warn",
                    "includes": ["**/*.java"],
                    "find": "legacyCall()",
                    "message": "legacyCall was removed; port it manually."
                  }]
                }
                """);
        Path output = temp.resolve("migrated");

        var report = MigrationEngine.migrate(new MigrationOptions(
                source, "1.21.11", "26.3", null, null, output, rulesFile, null, false, false, true));

        assertFalse(report.hasErrors());
        assertTrue(Files.readString(output.resolve("src/main/java/example/Thing.java")).contains("legacyCall();"));
        String text = report.toText();
        assertTrue(text.contains("legacyCall was removed; port it manually."));
        assertTrue(text.contains("legacy-call-warning: 2 warning(s) in 1 file(s)"));
    }

    @Test void invalidWarningAndTypeRulesAreRejected() throws Exception {
        Path noMessage = temp.resolve("no-message.json");
        Files.writeString(noMessage, """
                {"rules":[{"id":"w","from":"1.21.11","to":"26.3","type":"warn",
                  "includes":["**/*.java"],"find":"x"}]}
                """);
        Path withReplace = temp.resolve("with-replace.json");
        Files.writeString(withReplace, """
                {"rules":[{"id":"w","from":"1.21.11","to":"26.3","type":"warn","message":"m",
                  "includes":["**/*.java"],"find":"x","replace":"y"}]}
                """);
        Path badType = temp.resolve("bad-type.json");
        Files.writeString(badType, """
                {"rules":[{"id":"w","from":"1.21.11","to":"26.3","type":"magic",
                  "includes":["**/*.java"],"find":"x","replace":"y"}]}
                """);
        Path source = project("class Thing {}");
        for (Path rules : List.of(noMessage, withReplace, badType)) {
            assertThrows(IOException.class, () -> MigrationEngine.migrate(new MigrationOptions(
                    source, "1.21.11", "26.3", null, null, temp.resolve("out-" + rules.getFileName()),
                    rules, null, false, false, true)), rules.getFileName().toString());
        }
    }
}
