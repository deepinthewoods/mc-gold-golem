package ninja.trek.mc.goldgolem.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.MinecraftClient;

public class GolemHandledScreen extends HandledScreen<GolemInventoryScreenHandler> {
    public GolemHandledScreen(GolemInventoryScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = 9 * 18 + 16;
        this.backgroundHeight = 7 * 18 + 12 + 76;
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        // Minimal placeholder; rely on vanilla slot rendering
    }

    @Override
    protected void init() {
        super.init();
        int bx = this.x + 8;
        int by = this.y - 20;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Path"), b -> {
            MinecraftClient.getInstance().setScreen(new GolemPathScreen());
        }).dimensions(bx, by, 50, 18).build());
    }
}
