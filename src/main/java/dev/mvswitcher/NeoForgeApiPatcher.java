package dev.mvswitcher;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Carefully-scoped API migrations for known NeoForge/Minecraft version pairs.
 * Each transformation is constrained to source files and exact API signatures.
 */
final class NeoForgeApiPatcher {
    private NeoForgeApiPatcher() {}

    static void apply(Path root, String from, String to, MigrationReport report) throws IOException {
        if ("26.3".equals(from) && "1.21.11".equals(to)) {
            patchForward(root, report);
        } else if ("1.21.11".equals(from) && "26.3".equals(to)) {
            report.warning("Reverse semantic patches for 1.21.11 -> 26.3 are not yet implemented; only reversible text rules and build metadata are applied.");
        }
    }

    private static void patchForward(Path root, MigrationReport report) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString().replace('\\\\', '/');
                String before = Files.readString(file);
                String after = before;

                // Resource identifiers were renamed in this direction in the early rule pack by mistake.
                after = after.replace("import net.minecraft.resources.ResourceLocation;", "import net.minecraft.resources.Identifier;")
                        .replace("ResourceLocation.fromNamespaceAndPath(", "Identifier.fromNamespaceAndPath(")
                        .replace("ResourceLocation.parse(", "Identifier.parse(")
                        .replace("ResourceLocation", "Identifier");

                if (relative.endsWith("/RailCreatorScreen.java") || relative.equals("RailCreatorScreen.java")) {
                    after = after.replace("import net.minecraft.client.gui.GuiGraphicsExtractor;", "import net.minecraft.client.gui.GuiGraphics;")
                            .replace("GuiGraphicsExtractor graphics", "GuiGraphics graphics")
                            .replace("extractBackground(@NonNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick)",
                                    "renderBg(@NonNull GuiGraphics graphics, float partialTick, int mouseX, int mouseY)")
                            .replace("extractLabels(@NonNull GuiGraphics graphics, int mouseX, int mouseY)",
                                    "renderLabels(@NonNull GuiGraphics graphics, int mouseX, int mouseY)")
                            .replace("super(menu, inventory, title, 176, 166);",
                                    "super(menu, inventory, title);\\n        this.imageWidth = 176;\\n        this.imageHeight = 166;");
                }

                if (relative.endsWith("/PlayerDetectorRailBlock.java") || relative.equals("PlayerDetectorRailBlock.java")) {
                    after = after.replace("protected int ownSignal(BlockState state, BlockGetter level, BlockPos pos) {",
                                    "protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {");
                    if (!after.contains("MapCodec<PlayerDetectorRailBlock>")) {
                        after = after.replace("public class PlayerDetectorRailBlock extends BaseRailBlock {",
                                "public class PlayerDetectorRailBlock extends BaseRailBlock {\\n" +
                                "    private static final com.mojang.serialization.MapCodec<PlayerDetectorRailBlock> CODEC = simpleCodec(PlayerDetectorRailBlock::new);\\n" +
                                "    @Override protected com.mojang.serialization.MapCodec<? extends BaseRailBlock> codec() { return CODEC; }");
                    }
                }

                if (relative.endsWith("/RailCreatorBlock.java") || relative.equals("RailCreatorBlock.java")) {
                    after = after.replace("public class RailCreatorBlock extends BaseEntityBlock {",
                            "public class RailCreatorBlock extends BaseEntityBlock {\\n" +
                            "    private static final com.mojang.serialization.MapCodec<RailCreatorBlock> CODEC = simpleCodec(RailCreatorBlock::new);\\n" +
                            "    @Override protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }");
                    after = after.replace("public void playerDestroy(@NonNull ServerLevel level, @NonNull ServerPlayer player,",
                                    "public void playerDestroy(@NonNull Level level, @NonNull Player player,")
                            .replace("if (blockEntity instanceof RailCreatorEntity entity) {\\n            entity.dropInputs(level);",
                                    "if (level instanceof ServerLevel serverLevel && blockEntity instanceof RailCreatorEntity entity) {\\n            entity.dropInputs(serverLevel);");
                }

                if (relative.endsWith("/RailCreatorEntity.java") || relative.equals("RailCreatorEntity.java")) {
                    after = after.replace("recipe.value().assemble(input)", "recipe.value().assemble(input, serverLevel.registryAccess())");
                }

                if (relative.endsWith("/TeroMod.java") || relative.equals("TeroMod.java")) {
                    after = after.replace("ModConfig.Type.LOCAL", "ModConfig.Type.COMMON");
                }

                if (!after.equals(before)) {
                    Files.writeString(file, after);
                    report.changed(relative + " (NeoForge 1.21.11 API patch)");
                }
            }
        }
    }
}
