package dev.mvswitcher.tools;

import dev.mvswitcher.MigrationOptions;
import dev.mvswitcher.MigrationEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ApiDiffGeneratorTest {
    @TempDir Path temp;

    private void source(Path root, String pkg, String name, String body) throws IOException {
        Path dir = root.resolve(pkg.replace('.', '/'));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".java"), "package " + pkg + ";\npublic class " + name + " {\n" + body + "\n}\n");
    }

    private ApiDiffGenerator.Result diff(boolean auto, boolean removed) throws IOException {
        Path oldRoot = temp.resolve("old");
        Path newRoot = temp.resolve("new");
        source(oldRoot, "net.mc.world", "Block",
                "public int getSignal(int power) { return 0; }\npublic void keep() {}\nprotected void tick() {}");
        source(newRoot, "net.mc.world", "Block",
                "public int ownSignal(int power) { return 0; }\npublic void keep() {}\nprotected void tick() {}");
        source(oldRoot, "net.mc.util", "Helper", "public void help() {}");
        source(newRoot, "net.mc.core", "Helper", "public void help() {}");
        String shape = "public static Object of(String s) { return null; }\npublic static Object parse(String s) { return null; }\n"
                + "public String getPath() { return \"\"; }\npublic String getNamespace() { return \"\"; }\n"
                + "public Object withPath(String s) { return null; }";
        source(oldRoot, "net.mc.res", "ResourceLocation", shape);
        source(newRoot, "net.mc.res", "Identifier", shape);
        source(oldRoot, "net.mc.gone", "Gone", "public void bye() {}");
        var options = new ApiDiffGenerator.Options("1.21.11", "26.3", "neoforge", auto, removed);
        return ApiDiffGenerator.diff(ApiDiffGenerator.scan(oldRoot), ApiDiffGenerator.scan(newRoot), options);
    }

    private Map<String, Object> rule(ApiDiffGenerator.Result result, String idPart) {
        return result.rules().stream().filter(r -> r.get("id").toString().contains(idPart)).findFirst().orElse(null);
    }

    @Test void detectsClassMoveRenameAndMethodRename() throws Exception {
        var result = diff(false, false);
        assertNotNull(rule(result, "class-move-net.mc.util.Helper"));
        assertNotNull(rule(result, "class-rename-net.mc.res.ResourceLocation"));
        assertNotNull(rule(result, "class-rename-simple-ResourceLocation"));
        var method = rule(result, "method-rename-warning-getSignal-ownSignal");
        assertNotNull(method);
        assertEquals("warn", method.get("type"));
        assertNull(rule(result, "class-removed"));
        assertTrue(result.notes().contains("class removed: net.mc.gone.Gone"));
    }

    @Test void autoReplaceTurnsUnambiguousMethodRenamesIntoTextRules() throws Exception {
        var method = rule(diff(true, false), "method-rename-getSignal-ownSignal");
        assertNotNull(method);
        assertNull(method.get("type"));
        assertEquals("ownSignal(", method.get("replace"));
    }

    @Test void removedClassesBecomeWarningsOnlyOnRequest() throws Exception {
        var rule = rule(diff(false, true), "class-removed-net.mc.gone.Gone");
        assertNotNull(rule);
        assertEquals("import net.mc.gone.Gone;", rule.get("find"));
    }

    private Path zip(Path root, Path target) throws IOException {
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(target));
             var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new java.util.zip.ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return target;
    }

    @Test void sourceArchivesGiveTheSameResultAsDirectories() throws Exception {
        var options = new ApiDiffGenerator.Options("1.21.11", "26.3", "neoforge", false, true);
        var fromDirectories = diff(false, true);
        Path oldJar = zip(temp.resolve("old"), temp.resolve("old-sources.jar"));
        Path newJar = zip(temp.resolve("new"), temp.resolve("new-sources.zip"));

        var oldScan = ApiDiffGenerator.scan(oldJar);
        assertEquals(0, oldScan.failedFiles());
        var fromArchives = ApiDiffGenerator.diff(oldScan, ApiDiffGenerator.scan(newJar), options);

        assertEquals(fromDirectories.rules(), fromArchives.rules());
        assertEquals(fromDirectories.notes(), fromArchives.notes());
    }

    @Test void nonArchiveFileIsReportedAsAnError() throws Exception {
        Path notAZip = temp.resolve("notes.txt");
        Files.writeString(notAZip, "not an archive");
        var error = assertThrows(IOException.class, () -> ApiDiffGenerator.scan(notAZip));
        assertTrue(error.getMessage().contains("Not a readable .jar/.zip archive"));
    }

    @Test void generatedRulesPassEngineValidationAndMigrateCode() throws Exception {
        var options = new ApiDiffGenerator.Options("1.21.11", "26.3", "neoforge", true, true);
        var result = diff(true, true);
        Path rulesRoot = temp.resolve("generated");
        ApiDiffGenerator.write(result, options, rulesRoot);

        Path project = temp.resolve("Mod");
        Files.createDirectories(project.resolve("src/main/resources/META-INF"));
        Files.createDirectories(project.resolve("src/main/java/example"));
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'net.neoforged.gradle.userdev' }\n");
        Files.writeString(project.resolve("gradle.properties"), "minecraft_version=1.21.11\nneo_version=21.11.48\n");
        Files.writeString(project.resolve("src/main/resources/META-INF/neoforge.mods.toml"), "modLoader=\"javafml\"\n");
        Files.writeString(project.resolve("src/main/java/example/Use.java"),
                "import net.mc.util.Helper;\nimport net.mc.res.ResourceLocation;\n"
                        + "class Use { Object x = ResourceLocation.of(\"a\"); int s(Block b) { return b.getSignal(1); } }\n");
        var report = MigrationEngine.migrate(new MigrationOptions(project, "1.21.11", "26.3", null, null,
                temp.resolve("out"), rulesRoot, null, false, false, true));

        assertFalse(report.hasErrors());
        String migrated = Files.readString(temp.resolve("out/src/main/java/example/Use.java"));
        assertTrue(migrated.contains("import net.mc.core.Helper;"));
        assertTrue(migrated.contains("import net.mc.res.Identifier;"));
        assertTrue(migrated.contains("Identifier.of(\"a\")"));
        assertTrue(migrated.contains("b.ownSignal(1)"));
    }
}
