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
                import net.minecraft.client.gui.GuiGraphicsExtractor;
                class RailCreatorScreen {
                    RailCreatorScreen() { super(menu, inventory, title, 176, 166); }
                    public void extractBackground(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {}
                    protected void extractLabels(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY) {}
                }
                """);
        Files.writeString(source.resolve("PlayerDetectorRailBlock.java"), """
                public class PlayerDetectorRailBlock extends BaseRailBlock {
                    @Override protected int ownSignal(BlockState state, BlockGetter level, BlockPos pos) { return 15; }
                }
                """);
        Files.writeString(source.resolve("RailCreatorBlock.java"), """
                public class RailCreatorBlock extends BaseEntityBlock {
                    @Override public void playerDestroy(@NonNull ServerLevel level, @NonNull ServerPlayer player,
                        @NonNull BlockPos pos, @NonNull BlockState state, @Nullable BlockEntity blockEntity, @NonNull ItemStack stack) {
                        if (blockEntity instanceof RailCreatorEntity entity) {
                            entity.dropInputs(level);
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
        assertTrue(screen.contains("GuiGraphics"));
        assertTrue(screen.contains("renderBg"));
        assertTrue(screen.contains("renderLabels"));
        assertTrue(screen.contains("this.imageWidth = 176"));
        String rail = Files.readString(source.resolve("PlayerDetectorRailBlock.java"));
        assertTrue(rail.contains("MapCodec<PlayerDetectorRailBlock>"));
        assertTrue(rail.contains("getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction)"));
        String block = Files.readString(source.resolve("RailCreatorBlock.java"));
        assertTrue(block.contains("MapCodec<RailCreatorBlock>"));
        assertTrue(block.contains("Level level, @NonNull Player player"));
        assertTrue(block.contains("entity.dropInputs(serverLevel)"));
        assertTrue(Files.readString(source.resolve("RailCreatorEntity.java")).contains("assemble(input, serverLevel.registryAccess())"));
        assertTrue(Files.readString(source.resolve("TeroMod.java")).contains("ModConfig.Type.COMMON"));
    }
}
