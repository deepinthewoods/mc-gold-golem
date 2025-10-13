package ninja.trek.mc.goldgolem.screen;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import ninja.trek.mc.goldgolem.registry.ModScreenHandlers;
import net.minecraft.item.ItemStack;

public class GolemInventoryScreenHandler extends ScreenHandler {
    private final Inventory golemInventory;
    private final int entityId;
    private final int golemSlotCount;
    private final int controlsMargin;
    private final int golemRows;

    // Client-side constructor (from ExtendedScreenHandlerType buffer)
    public GolemInventoryScreenHandler(int syncId, PlayerInventory playerInventory, GolemOpenData data) {
        super(ModScreenHandlers.GOLEM_SCREEN_HANDLER, syncId);
        this.entityId = data.entityId();
        this.golemSlotCount = Math.max(0, data.golemSlots());
        this.golemRows = (this.golemSlotCount + 8) / 9;
        int titleLine = 10;
        this.controlsMargin = GolemOpenData.computeControlsMargin(data.gradientRows(), data.sliderEnabled(), titleLine);
        this.golemInventory = new SimpleInventory(this.golemSlotCount);
        this.golemInventory.onOpen(playerInventory.player);
        setupSlots(playerInventory);
    }

    // Server-side constructor: use the actual golem inventory
    public GolemInventoryScreenHandler(int syncId, PlayerInventory playerInventory, Inventory golemInventory, GolemOpenData data) {
        super(ModScreenHandlers.GOLEM_SCREEN_HANDLER, syncId);
        this.entityId = data.entityId();
        this.golemSlotCount = Math.min(golemInventory.size(), Math.max(0, data.golemSlots()));
        this.golemRows = (this.golemSlotCount + 8) / 9;
        this.controlsMargin = GolemOpenData.computeControlsMargin(data.gradientRows(), data.sliderEnabled(), 10);
        this.golemInventory = golemInventory;
        this.golemInventory.onOpen(playerInventory.player);
        setupSlots(playerInventory);
    }

    private void setupSlots(PlayerInventory playerInventory) {
        // Layout golem inventory in a chest-like 9-column grid (rows = ceil(56/9) = 7)
        int index = 0;
        for (int row = 0; row < golemRows; row++) {
            for (int col = 0; col < 9; col++) {
                if (index >= golemSlotCount) break;
                addSlot(new Slot(golemInventory, index++, 8 + col * 18, 1 + controlsMargin + row * 18));
            }
        }

        // Player inventory (3 rows x 9), positioned below golem rows similar to chest
        int baseY = controlsMargin + golemRows * 18 + 15;
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, baseY + row * 18));
            }
        }
        // Hotbar
        int hotbarY = baseY + 58;
        for (int i = 0; i < 9; ++i) {
            addSlot(new Slot(playerInventory, i, 8 + i * 18, hotbarY));
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    public int getEntityId() {
        return entityId;
    }

    public int getControlsMargin() { return controlsMargin; }
    public int getGolemSlotCount() { return golemSlotCount; }
    public int getGolemRows() { return golemRows; }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        // Basic shift-click behavior between golem inventory and player inventory
        ItemStack newStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasStack()) {
            ItemStack stack = slot.getStack();
            newStack = stack.copy();
            int golemEnd = this.golemSlotCount;
            if (index < golemEnd) {
                if (!this.insertItem(stack, golemEnd, this.slots.size(), true)) return ItemStack.EMPTY;
            } else {
                if (!this.insertItem(stack, 0, golemEnd, false)) return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.setStack(ItemStack.EMPTY);
            else slot.markDirty();
        }
        return newStack;
    }
}
