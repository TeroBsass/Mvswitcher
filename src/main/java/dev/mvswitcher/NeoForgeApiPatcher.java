package dev.mvswitcher;

import java.io.File;
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
            patchReverse(root, report);
        }
    }

    private static void patchForward(Path root, MigrationReport report) throws IOException {
        patchSources(root, report, true);
    }

    private static void patchReverse(Path root, MigrationReport report) throws IOException {
        patchSources(root, report, false);
    }

    private static void patchSources(Path root, MigrationReport report, boolean toLegacy) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                String before = Files.readString(file);
                String after = before;

                if (toLegacy) {
                    after = after.replace("import net.minecraft.resources.ResourceLocation;", "import net.minecraft.resources.Identifier;")
                            .replace("ResourceLocation.fromNamespaceAndPath(", "Identifier.fromNamespaceAndPath(")
                            .replace("ResourceLocation.parse(", "Identifier.parse(")
                            .replace("ResourceLocation", "Identifier");
                } else {
                    after = after.replace("import net.minecraft.resources.Identifier;", "import net.minecraft.resources.ResourceLocation;")
                            .replace("Identifier.fromNamespaceAndPath(", "ResourceLocation.fromNamespaceAndPath(")
                            .replace("Identifier.parse(", "ResourceLocation.parse(")
                            .replace("Identifier", "ResourceLocation");
                }

                if (relative.endsWith("/RailCreatorScreen.java") || relative.equals("RailCreatorScreen.java")) {
                    if (toLegacy) {
                        after = after.replace("import net.minecraft.client.gui.GuiGraphics;", "import net.minecraft.client.gui.GuiGraphicsExtractor;")
                                .replace("GuiGraphics graphics", "GuiGraphicsExtractor graphics")
                                .replace("renderBg(@NonNull GuiGraphicsExtractor graphics, float partialTick, int mouseX, int mouseY)",
                                        "extractBackground(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)")
                                .replace("renderLabels(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY)",
                                        "extractLabels(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY)")
                                .replace("super(menu, inventory, title);\n        this.imageWidth = 176;\n        this.imageHeight = 166;",
                                        "super(menu, inventory, title, 176, 166);");
                    } else {
                        after = after.replace("import net.minecraft.client.gui.GuiGraphicsExtractor;", "import net.minecraft.client.gui.GuiGraphics;")
                                .replace("GuiGraphicsExtractor graphics", "GuiGraphics graphics")
                                .replace("extractBackground(@NonNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick)",
                                        "renderBg(@NonNull GuiGraphics graphics, float partialTick, int mouseX, int mouseY)")
                                .replace("extractLabels(@NonNull GuiGraphics graphics, int mouseX, int mouseY)",
                                        "renderLabels(@NonNull GuiGraphics graphics, int mouseX, int mouseY)")
                                .replace("super(menu, inventory, title, 176, 166);",
                                        "super(menu, inventory, title);\n        this.imageWidth = 176;\n        this.imageHeight = 166;");
                    }
                }

                if (relative.endsWith("/PlayerDetectorRailBlock.java") || relative.equals("PlayerDetectorRailBlock.java")) {
                    if (toLegacy) {
                        after = after.replace("protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {",
                                "protected int ownSignal(BlockState state, BlockGetter level, BlockPos pos) {");
                        after = removeCodec(after, "PlayerDetectorRailBlock", "BaseRailBlock");
                    } else {
                        after = after.replace("protected int ownSignal(BlockState state, BlockGetter level, BlockPos pos) {",
                                "protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {");
                        if (!after.contains("MapCodec<PlayerDetectorRailBlock>")) {
                            after = after.replace("public class PlayerDetectorRailBlock extends BaseRailBlock {",
                                    "public class PlayerDetectorRailBlock extends BaseRailBlock {\n" +
                                    "    private static final com.mojang.serialization.MapCodec<PlayerDetectorRailBlock> CODEC = simpleCodec(PlayerDetectorRailBlock::new);\n" +
                                    "    @Override protected com.mojang.serialization.MapCodec<? extends BaseRailBlock> codec() { return CODEC; }");
                        }
                    }
                }

                if (relative.endsWith("/RailCreatorBlock.java") || relative.equals("RailCreatorBlock.java")) {
                    if (toLegacy) {
                        after = removeCodec(after, "RailCreatorBlock", "BaseEntityBlock");
                        after = after.replace("public void playerDestroy(@NonNull Level level, @NonNull Player player,",
                                        "public void playerDestroy(@NonNull ServerLevel level, @NonNull ServerPlayer player,")
                                .replace("if (level instanceof ServerLevel serverLevel && blockEntity instanceof RailCreatorEntity entity) {\n            entity.dropInputs(serverLevel);",
                                        "if (blockEntity instanceof RailCreatorEntity entity) {\n            entity.dropInputs(level);");
                    } else {
                        after = after.replace("public class RailCreatorBlock extends BaseEntityBlock {",
                                "public class RailCreatorBlock extends BaseEntityBlock {\n" +
                                "    private static final com.mojang.serialization.MapCodec<RailCreatorBlock> CODEC = simpleCodec(RailCreatorBlock::new);\n" +
                                "    @Override protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }");
                        after = after.replace("public void playerDestroy(@NonNull ServerLevel level, @NonNull ServerPlayer player,",
                                        "public void playerDestroy(@NonNull Level level, @NonNull Player player,")
                                .replace("if (blockEntity instanceof RailCreatorEntity entity) {\n            entity.dropInputs(level);",
                                        "if (level instanceof ServerLevel serverLevel && blockEntity instanceof RailCreatorEntity entity) {\n            entity.dropInputs(serverLevel);");
                    }
                }

                if (relative.endsWith("/RailCreatorEntity.java") || relative.equals("RailCreatorEntity.java")) {
                    after = toLegacy
                            ? after.replace("recipe.value().assemble(input, serverLevel.registryAccess())", "recipe.value().assemble(input)")
                            : after.replace("recipe.value().assemble(input)", "recipe.value().assemble(input, serverLevel.registryAccess())");
                }

                if (relative.endsWith("/TeroMod.java") || relative.equals("TeroMod.java")) {
                    after = toLegacy
                            ? after.replace("ModConfig.Type.COMMON", "ModConfig.Type.LOCAL")
                            : after.replace("ModConfig.Type.LOCAL", "ModConfig.Type.COMMON");
                }

                if (!after.equals(before)) {
                    Files.writeString(file, after);
                    report.changed(relative + (toLegacy
                            ? " (NeoForge 1.21.11 API reverse patch)"
                            : " (NeoForge 1.21.11 API patch)"));
                }
            }
        }
    }

    private static String removeCodec(String source, String type, String parent) {
        String escapedType = java.util.regex.Pattern.quote(type);
        String escapedParent = java.util.regex.Pattern.quote(parent);
        source = source.replaceAll("(?m)^\\s*private static final com\\.mojang\\.serialization\\.MapCodec<" +
                escapedType + "> CODEC = simpleCodec\\(" + escapedType + "::new\\);\\r?\\n", "");
        source = source.replaceAll("(?m)^\\s*@Override\\s*\\r?\\n\\s*protected com\\.mojang\\.serialization\\.MapCodec<\\? extends " +
                escapedParent + "> codec\\(\\) \\{ return CODEC; \\}\\r?\\n", "");
        return source;
    }
}
