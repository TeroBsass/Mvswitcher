import net.minecraft.client.gui.GuiGraphics;
class EnderDispenserScreen {
    EnderDispenserScreen() {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 165;
    }
    public void renderBg(@NonNull GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {}
    protected void renderLabels(@NonNull GuiGraphics graphics, int mouseX, int mouseY) {}
}
