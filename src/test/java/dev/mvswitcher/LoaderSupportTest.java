package dev.mvswitcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LoaderSupportTest {
    @TempDir Path temp;

    /** Creates a project directory with the given gradle text and resource files (relative to src/main/resources). */
    private Path project(String name, String gradleProperties, String buildGradle, String... resourceFiles) throws IOException {
        Path root = temp.resolve(name);
        Files.createDirectories(root.resolve("src/main/java/example"));
        Files.createDirectories(root.resolve("src/main/resources/META-INF"));
        Files.writeString(root.resolve("gradle.properties"), gradleProperties);
        Files.writeString(root.resolve("build.gradle"), buildGradle);
        for (String file : resourceFiles) {
            Path path = root.resolve("src/main/resources").resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "x");
        }
        return root;
    }

    private String loaderOf(Path project) throws IOException {
        return ProjectAnalyzer.analyze(project).loader();
    }

    @Test void detectsEveryLoaderFromItsMetadata() throws Exception {
        assertEquals("NeoForge", loaderOf(project("neo", "minecraft_version=1.21.11\n", "", "META-INF/neoforge.mods.toml")));
        assertEquals("Forge", loaderOf(project("forge", "minecraft_version=1.20.1\n",
                "plugins { id 'net.minecraftforge.gradle' }", "META-INF/mods.toml")));
        assertEquals("Fabric", loaderOf(project("fabric", "minecraft_version=1.21.1\n", "", "fabric.mod.json")));
        assertEquals("Quilt", loaderOf(project("quilt", "minecraft_version=1.21.1\n", "", "quilt.mod.json")));
    }

    @Test void quiltWithFabricCompatibilityMetadataStaysQuilt() throws Exception {
        assertEquals("Quilt", loaderOf(project("quilt-compat", "minecraft_version=1.21.1\n", "",
                "quilt.mod.json", "fabric.mod.json")));
    }

    @Test void legacyNeoForgeWithModsTomlIsNotMistakenForForge() throws Exception {
        assertEquals("NeoForge", loaderOf(project("neo-legacy", "minecraft_version=1.20.1\nneo_version=47.1.106\n",
                "plugins { id 'net.neoforged.gradle.userdev' }", "META-INF/mods.toml")));
    }

    @Test void detectsLoaderFromGradleWhenThereIsNoMetadata() throws Exception {
        assertEquals("Fabric", loaderOf(project("fabric-gradle", "minecraft_version=1.21.1\n",
                "plugins { id 'fabric-loom' version '1.7' }")));
        assertEquals("Quilt", loaderOf(project("quilt-gradle", "minecraft_version=1.21.1\n",
                "plugins { id 'org.quiltmc.loom' version '1.7' }")));
    }

    @Test void multiLoaderProjectIsUnknownInsteadOfGuessed() throws Exception {
        assertEquals("unknown", loaderOf(project("multi", "minecraft_version=1.21.1\n", "",
                "META-INF/neoforge.mods.toml", "fabric.mod.json")));
        assertEquals("unknown", loaderOf(project("plain", "minecraft_version=1.21.1\n", "")));
    }

    private MigrationOptions options(Path source, Path output, Path rules, String neoVersion, Path template) {
        return new MigrationOptions(source, "1.21.11", "26.3", neoVersion, null, output, rules, template,
                false, false, true);
    }

    private Path rules() throws IOException {
        Path file = temp.resolve("loader-rules.json");
        Files.writeString(file, """
                {"rules": [
                  {"id":"fabric-only","from":"1.21.11","to":"26.3","loader":"fabric",
                   "includes":["**/*.java"],"find":"FabricOld","replace":"FabricNew"},
                  {"id":"neoforge-default","from":"1.21.11","to":"26.3",
                   "includes":["**/*.java"],"find":"NeoOld","replace":"NeoNew"},
                  {"id":"any-loader","from":"1.21.11","to":"26.3","loader":"*",
                   "includes":["**/*.java"],"find":"VanillaOld","replace":"VanillaNew"}
                ]}
                """);
        return file;
    }

    @Test void fabricProjectMigratesWithItsOwnAndWildcardRulesOnly() throws Exception {
        Path source = project("FabricMod",
                "minecraft_version=1.21.11\nloader_version=0.16.0\nfabric_version=0.100.0\n", "", "fabric.mod.json");
        Files.writeString(source.resolve("src/main/java/example/Mod.java"),
                "class Mod { FabricOld a; NeoOld b; VanillaOld c; }");
        Path output = temp.resolve("fabric-out");

        var report = MigrationEngine.migrate(options(source, output, rules(), null, null));

        assertFalse(report.hasErrors());
        assertEquals("class Mod { FabricNew a; NeoOld b; VanillaNew c; }",
                Files.readString(output.resolve("src/main/java/example/Mod.java")));
        String properties = Files.readString(output.resolve("gradle.properties"));
        assertTrue(properties.contains("minecraft_version=26.3"));
        assertTrue(properties.contains("loader_version=0.16.0"), "unknown loader versions must stay untouched");
        assertTrue(properties.contains("fabric_version=0.100.0"));
        assertFalse(properties.contains("neo_version"), "a Fabric project must not receive neo_version");
        assertFalse(properties.contains("java_version"), "java_version must not be invented for Fabric projects");
        assertTrue(report.toText().contains("No known Fabric profile for Minecraft 26.3"));
    }

    @Test void neoForgeRulesStillApplyByDefaultToNeoForgeProjects() throws Exception {
        Path source = project("NeoMod", "minecraft_version=1.21.11\nneo_version=21.11.48\n", "",
                "META-INF/neoforge.mods.toml");
        Files.writeString(source.resolve("src/main/java/example/Mod.java"),
                "class Mod { FabricOld a; NeoOld b; VanillaOld c; }");
        Path output = temp.resolve("neo-out");

        var report = MigrationEngine.migrate(options(source, output, rules(), null, null));

        assertFalse(report.hasErrors());
        assertEquals("class Mod { FabricOld a; NeoNew b; VanillaNew c; }",
                Files.readString(output.resolve("src/main/java/example/Mod.java")));
        assertTrue(Files.readString(output.resolve("gradle.properties")).contains("neo_version=26.3.0.99"));
    }

    @Test void unsupportedOrAmbiguousProjectsAreRejectedWithAnExplanation() throws Exception {
        Path multi = project("Multi", "minecraft_version=1.21.11\n", "", "META-INF/neoforge.mods.toml", "fabric.mod.json");
        IOException error = assertThrows(IOException.class, () ->
                MigrationEngine.migrate(options(multi, temp.resolve("multi-out"), rules(), null, null)));
        assertTrue(error.getMessage().contains("Multi-loader projects are not supported"));
    }

    @Test void neoForgeVersionOptionIsRejectedForOtherLoaders() throws Exception {
        Path source = project("FabricMod2", "minecraft_version=1.21.11\n", "", "fabric.mod.json");
        IOException error = assertThrows(IOException.class, () ->
                MigrationEngine.migrate(options(source, temp.resolve("o"), rules(), "21.11.48", null)));
        assertTrue(error.getMessage().contains("--neoforge-version only applies to NeoForge projects"));
    }

    @Test void templateMustUseTheSameLoaderAsTheSource() throws Exception {
        Path source = project("FabricMod3", "minecraft_version=1.21.11\n", "", "fabric.mod.json");
        Path template = project("NeoTemplate", "minecraft_version=26.3\nneo_version=26.3.0.99\n", "",
                "META-INF/neoforge.mods.toml");
        IOException error = assertThrows(IOException.class, () ->
                MigrationEngine.migrate(options(source, temp.resolve("o2"), rules(), null, template)));
        assertTrue(error.getMessage().contains("--template must be a Fabric MDK"));
    }

    @Test void profilePropertiesAreWrittenForExistingKeysAndReportedWhenMissing() throws Exception {
        Path root = project("Props", "minecraft_version=1.21.11\nloader_version=0.16.0\n", "", "fabric.mod.json");
        var profile = new VersionProfile("26.3", "26.3", "fabric", "0.19.0", 25,
                Map.of("loader_version", "0.19.0", "yarn_mappings", "26.3+build.1"));
        var report = new MigrationReport();

        MigrationEngine.updateBuildFiles(root, "26.3", false, null, 25, profile, "Fabric", report,
                MigrationLog.Listener.NONE);

        String properties = Files.readString(root.resolve("gradle.properties"));
        assertTrue(properties.contains("minecraft_version=26.3"));
        assertTrue(properties.contains("loader_version=0.19.0"));
        assertFalse(properties.contains("yarn_mappings"));
        assertTrue(report.toText().contains("[yarn_mappings]"));
    }

    @Test void catalogReadsAndValidatesProfileProperties() throws Exception {
        String ok = "{\"profiles\":[{\"minecraft\":\"26.3\",\"loader\":\"Fabric\",\"java\":25,"
                + "\"properties\":{\"loader_version\":\"0.19.0\"}}]}";
        VersionProfile profile = VersionCatalog.parse(
                new ByteArrayInputStream(ok.getBytes(StandardCharsets.UTF_8)), "test").get(0);
        assertEquals("fabric", profile.loader());
        assertEquals("Fabric", profile.loaderLabel());
        assertEquals(Map.of("loader_version", "0.19.0"), profile.properties());
        assertEquals("override required", profile.loaderVersionText());

        for (String bad : List.of(
                "{\"profiles\":[{\"minecraft\":\"26.3\",\"loader\":\"fabric\",\"java\":25,\"properties\":[]}]}",
                "{\"profiles\":[{\"minecraft\":\"26.3\",\"loader\":\"fabric\",\"java\":25,\"properties\":{\"a b\":\"x\"}}]}",
                "{\"profiles\":[{\"minecraft\":\"26.3\",\"loader\":\"fabric\",\"java\":25,\"properties\":{\"a\":1}}]}")) {
            assertThrows(IOException.class, () -> VersionCatalog.parse(
                    new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8)), "test"));
        }
    }
}
