package dev.mvswitcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
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
                "minecraft_version=26.3\\nneo_version=26.3.0.99\\n");
        Files.writeString(source.resolve("build.gradle"),
                "plugins { id 'net.neoforged.gradle.userdev' }\\n");
        Files.writeString(metadata.resolve("neoforge.mods.toml"), "modLoader=\\\"javafml\\\"\\n");
        String original = "package example; import net.minecraft.resources.Identifier; class Example { Identifier id; }";
        Files.writeString(java.resolve("Example.java"), original);

        Path output = temp.resolve("forward");
        var report = MigrationEngine.migrate(new MigrationOptions(
                source, "26.3", "1.21.11", null, null, output, null, false, false, true));

        assertFalse(report.hasErrors());
        assertEquals(original, Files.readString(java.resolve("Example.java")));
        assertTrue(Files.readString(output.resolve("src/main/java/example/Example.java")).contains("Identifier"));
        assertTrue(Files.readString(output.resolve("gradle.properties")).contains("minecraft_version=1.21.11"));
        assertTrue(Files.readString(output.resolve("settings.gradle")).contains("minecraft_version_range=[1.21.11]"));
    }

    @Test void appliesKnownNeoForgeApiPatches() throws Exception {
        Path source = temp.resolve("src");
        Files.createDirectories(source);
        Files.writeString(source.resolve("RailCreatorScreen.java"), """
                import net.minecraft.client.gui.GuiGraphics;
                class RailCreatorScreen {
                    RailCreatorScreen() {
                        super(menu, inventory, title);
                        this.imageWidth = 176;
                        this.imageHeight = 166;
                    }
                    public void renderBg(@NonNull GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {}
                    protected void renderLabels(@NonNull GuiGraphics graphics, int mouseX, int mouseY) {}
                }
                """);
        Files.writeString(source.resolve("PlayerDetectorRailBlock.java"), """
                public class PlayerDetectorRailBlock extends BaseRailBlock {
                    @Override protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) { return 15; }
                }
                """);
        Files.writeString(source.resolve("RailCreatorBlock.java"), """
                public class RailCreatorBlock extends BaseEntityBlock {
                    @Override public void playerDestroy(@NonNull Level level, @NonNull Player player,
                        @NonNull BlockPos pos, @NonNull BlockState state, @Nullable BlockEntity blockEntity, @NonNull ItemStack stack) {
                        if (level instanceof ServerLevel serverLevel && blockEntity instanceof RailCreatorEntity entity) {
                            entity.dropInputs(serverLevel);
                        }
                    }
                }
                """);
        Files.writeString(source.resolve("RailCreatorEntity.java"),
                "class RailCreatorEntity { void update() { recipe.value().assemble(input); } }");
        Files.writeString(source.resolve("TeroMod.java"),
                "class TeroMod { void config() { modContainer.registerConfig(ModConfig.Type.LOCAL, Config.SPEC); } }");

        var report = new MigrationReport();
        NeoForgeApiPatcher.apply(source, "26.3", "1.21.11", report);

        String screen = Files.readString(source.resolve("RailCreatorScreen.java"));
        assertTrue(screen.contains("GuiGraphicsExtractor"));
        assertTrue(screen.contains("extractBackground"));
        assertTrue(screen.contains("extractLabels"));
        assertTrue(screen.contains("super(menu, inventory, title, 176, 166)"));
        String rail = Files.readString(source.resolve("PlayerDetectorRailBlock.java"));
        assertFalse(rail.contains("MapCodec<PlayerDetectorRailBlock>"));
        assertTrue(rail.contains("ownSignal(BlockState state, BlockGetter level, BlockPos pos)"));
        String block = Files.readString(source.resolve("RailCreatorBlock.java"));
        assertFalse(block.contains("MapCodec<RailCreatorBlock>"));
        assertTrue(block.contains("ServerLevel level, @NonNull ServerPlayer player"));
        assertTrue(block.contains("entity.dropInputs(level)"));
        assertTrue(Files.readString(source.resolve("RailCreatorEntity.java")).contains("assemble(input, serverLevel.registryAccess())"));
        assertTrue(Files.readString(source.resolve("TeroMod.java")).contains("ModConfig.Type.COMMON"));
    }

    @Test void reversesKnownNeoForgeApiPatchesFrom12111To263() throws Exception {
        Path source = temp.resolve("ReverseSource");
        Path metadata = source.resolve("src/main/resources/META-INF");
        Path java = source.resolve("src/main/java/com/tero/teromod");
        Files.createDirectories(metadata);
        Files.createDirectories(java);
        Files.writeString(source.resolve("gradle.properties"),
                "minecraft_version=1.21.11\nneo_version=21.11.48\n");
        Files.writeString(source.resolve("settings.gradle"), "minecraft_version_range=[1.21.11]\n");
        Files.writeString(source.resolve("build.gradle"),
                "java.toolchain.languageVersion = JavaLanguageVersion.of(21)\n");
        Files.writeString(metadata.resolve("neoforge.mods.toml"), "modLoader=\"javafml\"\n");
        Files.writeString(java.resolve("RailCreatorScreen.java"), """
                import net.minecraft.client.gui.GuiGraphicsExtractor;
                class RailCreatorScreen {
                    RailCreatorScreen() { super(menu, inventory, title, 176, 166); }
                    public void extractBackground(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {}
                    protected void extractLabels(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY) {}
                }
                """);
        Files.writeString(java.resolve("PlayerDetectorRailBlock.java"), """
                public class PlayerDetectorRailBlock extends BaseRailBlock {
                    @Override protected int ownSignal(BlockState state, BlockGetter level, BlockPos pos) { return 15; }
                }
                """);
        Files.writeString(java.resolve("RailCreatorBlock.java"), """
                public class RailCreatorBlock extends BaseEntityBlock {
                    @Override public void playerDestroy(@NonNull ServerLevel level, @NonNull ServerPlayer player,
                        @NonNull BlockPos pos, @NonNull BlockState state, @Nullable BlockEntity blockEntity, @NonNull ItemStack stack) {
                        if (blockEntity instanceof RailCreatorEntity entity) {
                            entity.dropInputs(level);
                        }
                    }
                }
                """);
        Files.writeString(java.resolve("RailCreatorEntity.java"),
                "class RailCreatorEntity { void update() { recipe.value().assemble(input); } }");
        Files.writeString(java.resolve("TeroMod.java"),
                "class TeroMod { void config() { modContainer.registerConfig(ModConfig.Type.LOCAL, Config.SPEC); } }");
        Files.writeString(java.resolve("Ids.java"),
                "import net.minecraft.resources.ResourceLocation; class Ids { ResourceLocation id = ResourceLocation.fromNamespaceAndPath(\"teromod\", \"x\"); }");

        Path output = temp.resolve("reverse");
        var report = MigrationEngine.migrate(new MigrationOptions(
                source, "1.21.11", "26.3", null, null, output, null, false, false, true));

        assertFalse(report.hasErrors());
        assertTrue(Files.readString(output.resolve("gradle.properties")).contains("minecraft_version=26.3"));
        assertTrue(Files.readString(output.resolve("settings.gradle")).contains("minecraft_version_range=[26.3]"));
        assertTrue(Files.readString(output.resolve("build.gradle")).contains("JavaLanguageVersion.of(25)"));
        assertTrue(Files.readString(output.resolve("src/main/java/com/tero/teromod/Ids.java")).contains("ResourceLocation.fromNamespaceAndPath"));
        assertTrue(Files.readString(output.resolve("src/main/java/com/tero/teromod/RailCreatorScreen.java")).contains("GuiGraphics"));
        assertTrue(Files.readString(output.resolve("src/main/java/com/tero/teromod/RailCreatorScreen.java")).contains("renderBg"));
        assertTrue(Files.readString(output.resolve("src/main/java/com/tero/teromod/PlayerDetectorRailBlock.java")).contains("MapCodec<PlayerDetectorRailBlock>"));
        assertTrue(Files.readString(output.resolve("src/main/java/com/tero/teromod/RailCreatorBlock.java")).contains("MapCodec<RailCreatorBlock>"));
        assertTrue(Files.readString(output.resolve("src/main/java/com/tero/teromod/RailCreatorBlock.java")).contains("entity.dropInputs(serverLevel)"));
        assertTrue(Files.readString(output.resolve("src/main/java/com/tero/teromod/RailCreatorEntity.java")).contains("assemble(input, serverLevel.registryAccess())"));
        assertTrue(Files.readString(output.resolve("src/main/java/com/tero/teromod/TeroMod.java")).contains("ModConfig.Type.COMMON"));
    }

}
