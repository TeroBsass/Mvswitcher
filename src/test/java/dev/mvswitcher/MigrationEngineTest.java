package dev.mvswitcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationEngineTest {
    @TempDir Path temp;

    @Test void migratesKnownNeoForgeEdgeForwardAndBackWithoutTouchingSource() throws Exception {
        Path source = temp.resolve("ExampleMod");
        Path metadata = source.resolve("src/main/resources/META-INF");
        Path java = source.resolve("src/main/java/example");
        Files.createDirectories(metadata);
        Files.createDirectories(java);
        Files.writeString(source.resolve("gradle.properties"),
                "minecraft_version=26.3\nneo_version=26.3.0.99\n");
        Files.writeString(source.resolve("build.gradle"),
                "plugins { id 'net.neoforged.gradle.userdev' }\n");
        Files.writeString(metadata.resolve("neoforge.mods.toml"), "modLoader=\"javafml\"\n");
        String original = "package example; import net.minecraft.resources.Identifier; class Example { Identifier id; }";
        Files.writeString(java.resolve("Example.java"), original);

        Path forward = temp.resolve("forward");
        var forwardReport = MigrationEngine.migrate(new MigrationOptions(
                source, "26.3", "1.21.11", null, null, forward, null, false, false, true));

        assertFalse(forwardReport.hasErrors());
        assertTrue(Files.readString(source.resolve("gradle.properties")).contains("minecraft_version=26.3"));
        assertTrue(Files.readString(forward.resolve("gradle.properties")).contains("minecraft_version=1.21.11"));
        assertTrue(Files.readString(forward.resolve("src/main/java/example/Example.java")).contains("ResourceLocation"));

        Path reverse = temp.resolve("reverse");
        var reverseReport = MigrationEngine.migrate(new MigrationOptions(
                forward, "1.21.11", "26.3", null, null, reverse, null, false, false, true));

        assertFalse(reverseReport.hasErrors());
        assertTrue(Files.readString(reverse.resolve("src/main/java/example/Example.java")).contains("Identifier"));
    }
}
