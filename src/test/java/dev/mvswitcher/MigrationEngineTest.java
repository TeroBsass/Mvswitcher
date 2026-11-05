package dev.mvswitcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MigrationEngineTest {
    @TempDir Path temp;

    @Test void preservesIdentifierApiAndDoesNotModifySource() throws Exception {
        Path source = temp.resolve("ExampleMod");
        Path metadata = source.resolve("src/main/resources/META-INF");
        Path java = source.resolve("src/main/java/example");
        Files.createDirectories(metadata);
        Files.createDirectories(java);
        Files.writeString(source.resolve("settings.gradle"), "minecraft_version_range=[26.3]\n");
        Files.writeString(source.resolve("gradle.properties"),
                "minecraft_version=26.3\nneo_version=26.3.0.99\n");
        Files.writeString(source.resolve("build.gradle"),
                "plugins { id 'net.neoforged.gradle.userdev' }\n");
        Files.writeString(metadata.resolve("neoforge.mods.toml"), "modLoader=\"javafml\"\n");
        String original = "package example; import net.minecraft.resources.Identifier; class Example { Identifier id; }";
        Files.writeString(java.resolve("Example.java"), original);

        Path output = temp.resolve("forward");
        var report = MigrationEngine.migrate(new MigrationOptions(
                source, "26.3", "1.21.11", null, null, output, null, null, false, false, true));

        assertFalse(report.hasErrors());
        assertEquals(original, Files.readString(java.resolve("Example.java")));
        assertTrue(Files.readString(output.resolve("src/main/java/example/Example.java")).contains("Identifier"));
        assertTrue(Files.readString(output.resolve("gradle.properties")).contains("minecraft_version=1.21.11"));
        assertTrue(Files.readString(output.resolve("settings.gradle")).contains("minecraft_version_range=[1.21.11]"));
    }

    @Test void appliesBundledDirectedApiRules() throws Exception {
        Path source = temp.resolve("Project");
        Files.createDirectories(source.resolve("src/main/resources/META-INF"));
        Files.createDirectories(source.resolve("src/main/java/example"));
        Files.writeString(source.resolve("build.gradle"), "plugins { id 'net.neoforged.gradle.userdev' }\n");
        Files.writeString(source.resolve("gradle.properties"),
                "minecraft_version=26.3\nneo_version=26.3.0.99\n");
        Files.writeString(source.resolve("src/main/resources/META-INF/neoforge.mods.toml"), "modLoader=\"javafml\"\n");
        Files.writeString(source.resolve("src/main/java/example/RailCreatorScreen.java"), """
                import net.minecraft.client.gui.GuiGraphicsExtractor;
                class RailCreatorScreen {
                    RailCreatorScreen() { super(menu, inventory, title, 176, 166); }
                    public void extractBackground(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {}
                    protected void extractLabels(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY) {}
                }
                """);
        Files.writeString(source.resolve("src/main/java/example/EnderDispenserScreen.java"), """
                import net.minecraft.client.gui.GuiGraphicsExtractor;
                class EnderDispenserScreen {
                    EnderDispenserScreen() { super(menu, inventory, title, 176, 165); }
                    public void extractBackground(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                  float partialTick) {}
                    protected void extractLabels(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY) {}
                }
                """);
        Files.writeString(source.resolve("src/main/java/example/PlayerDetectorRailBlock.java"), """
                public class PlayerDetectorRailBlock extends BaseRailBlock {
                    @Override protected int ownSignal(BlockState state, BlockGetter level, BlockPos pos) { return 15; }
                }
                """);
        Files.writeString(source.resolve("src/main/java/example/RailCreatorBlock.java"), """
                public class RailCreatorBlock extends BaseEntityBlock {
                    @Override public void playerDestroy(@NonNull ServerLevel level, @NonNull ServerPlayer player,
                        @NonNull BlockPos pos, @NonNull BlockState state, @Nullable BlockEntity blockEntity, @NonNull ItemStack stack) {
                        if (blockEntity instanceof RailCreatorEntity entity) {
                            entity.dropInputs(level);
                        }
                    }
                }
                """);
        Files.writeString(source.resolve("src/main/java/example/RailCreatorEntity.java"),
                "class RailCreatorEntity { void update() { recipe.value().assemble(input); } }");
        Files.writeString(source.resolve("src/main/java/example/TeroMod.java"),
                "class TeroMod { void config() { modContainer.registerConfig(ModConfig.Type.LOCAL, Config.SPEC); } }");
        Files.writeString(source.resolve("src/main/java/example/EnderDispenserStorage.java"), """
                class EnderDispenserStorage {
                    private static final SavedDataType<EnderDispenserStorage> DATA_TYPE =
                        new SavedDataType<EnderDispenserStorage>(
                            Identifier.fromNamespaceAndPath(TeroMod.MODID, "ender_dispenser"),
                            EnderDispenserStorage::new, CODEC, DataFixTypes.LEVEL);
                }
                """);

        Path output = temp.resolve("migrated");
        var report = MigrationEngine.migrate(new MigrationOptions(
                source, "26.3", "1.21.11", null, null, output, null, null, false, false, true));

        assertFalse(report.hasErrors());
        String screen = Files.readString(output.resolve("src/main/java/example/RailCreatorScreen.java"));
        assertTrue(screen.contains("GuiGraphics"));
        assertTrue(screen.contains("renderBg"));
        assertTrue(screen.contains("renderLabels"));
        assertTrue(screen.contains("this.imageWidth = 176"));
        String enderScreen = Files.readString(output.resolve("src/main/java/example/EnderDispenserScreen.java"));
        assertTrue(enderScreen.contains("renderBg(@NonNull GuiGraphics graphics, float partialTick, int mouseX, int mouseY)"));
        assertTrue(enderScreen.contains("this.imageHeight = 165"));
        assertFalse(enderScreen.contains("GuiGraphicsExtractor"));
        String rail = Files.readString(output.resolve("src/main/java/example/PlayerDetectorRailBlock.java"));
        assertTrue(rail.contains("MapCodec<PlayerDetectorRailBlock>"));
        assertTrue(rail.contains("getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction)"));
        String block = Files.readString(output.resolve("src/main/java/example/RailCreatorBlock.java"));
        assertTrue(block.contains("MapCodec<RailCreatorBlock>"));
        assertTrue(block.contains("Level level, @NonNull Player player"));
        assertTrue(block.contains("entity.dropInputs(serverLevel)"));
        assertTrue(Files.readString(output.resolve("src/main/java/example/RailCreatorEntity.java"))
                .contains("assemble(input, serverLevel.registryAccess())"));
        assertTrue(Files.readString(output.resolve("src/main/java/example/TeroMod.java"))
                .contains("ModConfig.Type.COMMON"));
        String storage = Files.readString(output.resolve("src/main/java/example/EnderDispenserStorage.java"));
        assertTrue(storage.contains("TeroMod.MODID + \"_ender_dispenser\""));
        assertFalse(storage.contains("Identifier.fromNamespaceAndPath"));
    }

    @Test void targetTemplateOwnsBuildFilesWhileModSourcesAreOverlaid() throws Exception {
        Path source = temp.resolve("Source");
        Path template = temp.resolve("Template");
        Files.createDirectories(source.resolve("src/main/java/example"));
        Files.createDirectories(source.resolve("src/main/resources/META-INF"));
        Files.createDirectories(template.resolve("src/main/java/sample"));
        Files.createDirectories(template.resolve("src/main/resources/META-INF"));
        Files.writeString(source.resolve("build.gradle"), "source build file\n");
        Files.writeString(source.resolve("settings.gradle"), "source settings\n");
        Files.writeString(source.resolve("gradle.properties"),
                "minecraft_version=26.3\nneo_version=26.3.0.99\nmod_id=examplemod\n");
        Files.writeString(source.resolve("src/main/resources/META-INF/neoforge.mods.toml"), "modLoader=\"javafml\"\n");
        Files.writeString(source.resolve("src/main/java/example/Example.java"), "package example; class Example {}\n");
        Files.writeString(template.resolve("build.gradle"), "target MDK build file\n");
        Files.writeString(template.resolve("settings.gradle"), "target MDK settings\n");
        Files.writeString(template.resolve("gradle.properties"),
                "minecraft_version=1.21.11\nneo_version=21.11.48\nneo_gradle_version=7.0.0\nmod_id=samplemod\n");
        Files.writeString(template.resolve("src/main/resources/META-INF/neoforge.mods.toml"), "modLoader=\"javafml\"\n");
        Files.writeString(template.resolve("src/main/java/sample/ExampleMod.java"), "class ExampleMod {}\n");
        Files.writeString(template.resolve("template-notes.txt"), "template resource\n");

        Path output = temp.resolve("template-output");
        var report = MigrationEngine.migrate(new MigrationOptions(
                source, "26.3", "1.21.11", null, null, output, null, template,
                false, false, true));

        assertFalse(report.hasErrors());
        assertTrue(Files.readString(output.resolve("build.gradle")).contains("target MDK build file"));
        assertTrue(Files.readString(output.resolve("settings.gradle")).contains("target MDK settings"));
        assertFalse(Files.exists(output.resolve("src/main/java/sample/ExampleMod.java")));
        assertTrue(Files.exists(output.resolve("src/main/java/example/Example.java")));
        assertTrue(Files.readString(output.resolve("template-notes.txt")).contains("template resource"));
        String properties = Files.readString(output.resolve("gradle.properties"));
        assertTrue(properties.contains("minecraft_version=1.21.11"));
        assertTrue(properties.contains("mod_id=examplemod"));
        assertTrue(properties.contains("neo_gradle_version=7.0.0"));
        assertTrue(Files.readString(source.resolve("gradle.properties")).contains("minecraft_version=26.3"));
        assertTrue(report.toText().contains("NeoForge template build: SKIPPED"));
    }

    @Test void customRulesDirectoryUsesDirectedVersionFolders() throws Exception {
        Path rulesRoot = temp.resolve("custom-rules");
        Path edgeDirectory = rulesRoot.resolve("1.21.11/26.3");
        Files.createDirectories(edgeDirectory);
        Files.writeString(edgeDirectory.resolve("rules.json"), """
                {
                  "rules": [{
                    "id": "reverse-test",
                    "from": "1.21.11",
                    "to": "26.3",
                    "includes": ["**/*.java"],
                    "find": "OldName",
                    "replace": "NewName"
                  }]
                }
                """);

        var pack = MigrationEngine.loadRules(rulesRoot);
        var route = MigrationEngine.findRoute(pack.rules, "1.21.11", "26.3");
        assertEquals(1, route.size());
        assertEquals("reverse-test", route.get(0).id);
        assertEquals("26.3", route.get(0).to);
        assertEquals(List.of("26.3 -> 1.21.11"), MigrationEngine.bundledRoutes());
    }

    @Test void invalidRulesFailBeforeCreatingOutput() throws Exception {
        Path source = temp.resolve("InvalidRuleSource");
        Files.createDirectories(source.resolve("src/main/resources/META-INF"));
        Files.writeString(source.resolve("build.gradle"), "plugins { id 'net.neoforged.gradle.userdev' }\n");
        Files.writeString(source.resolve("src/main/resources/META-INF/neoforge.mods.toml"), "modLoader=\"javafml\"\n");
        Path invalidRules = temp.resolve("invalid-rules.json");
        Files.writeString(invalidRules, """
                {
                  "rules": [{
                    "id": "missing-includes",
                    "from": "26.3",
                    "to": "1.21.11",
                    "find": "OldName",
                    "replace": "NewName"
                  }]
                }
                """);
        Path output = temp.resolve("invalid-output");

        IOException error = assertThrows(IOException.class, () -> MigrationEngine.migrate(new MigrationOptions(
                source, "26.3", "1.21.11", null, null, output, invalidRules, null,
                false, false, true)));

        assertTrue(error.getMessage().contains("includes"));
        assertFalse(Files.exists(output));
    }
}
