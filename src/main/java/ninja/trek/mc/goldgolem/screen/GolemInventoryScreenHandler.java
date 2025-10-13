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
    public static final int GOLEM_SLOT_COUNT = 27;
    public static final int CONTROLS_MARGIN = 59; // title(6+font) +4 +ghost(16) +6 +slider(12) +6 gap
    private final Inventory golemInventory;
    private final int entityId; // links to the golem entity

    // Client-side constructor (from ExtendedScreenHandlerType buffer): use a client-side simple inventory
    public GolemInventoryScreenHandler(int syncId, PlayerInventory playerInventory, int entityId) {
        super(ModScreenHandlers.GOLEM_SCREEN_HANDLER, syncId);
        this.entityId = entityId;
        this.golemInventory = new SimpleInventory(GOLEM_SLOT_COUNT);
        this.golemInventory.onOpen(playerInventory.player);
        setupSlots(playerInventory);
    }

    // Server-side constructor: use the actual golem inventory
    public GolemInventoryScreenHandler(int syncId, PlayerInventory playerInventory, Inventory golemInventory, int entityId) {
        super(ModScreenHandlers.GOLEM_SCREEN_HANDLER, syncId);
        this.entityId = entityId;
        this.golemInventory = golemInventory;
        this.golemInventory.onOpen(playerInventory.player);
        setupSlots(playerInventory);
    }

    private void setupSlots(PlayerInventory playerInventory) {
        // Layout golem inventory in a chest-like 9-column grid (rows = ceil(56/9) = 7)
        int index = 0;
        int rows = (GOLEM_SLOT_COUNT + 8) / 9;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                if (index >= GOLEM_SLOT_COUNT) break;
                addSlot(new Slot(golemInventory, index++, 8 + col * 18, 18 + CONTROLS_MARGIN + row * 18));
            }
        }

        // Player inventory (3 rows x 9), positioned below golem rows similar to chest
        int baseY = 18 + CONTROLS_MARGIN + rows * 18 + 14;
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

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        // Basic shift-click behavior between golem inventory and player inventory
        ItemStack newStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasStack()) {
            ItemStack stack = slot.getStack();
            newStack = stack.copy();
            int golemEnd = GOLEM_SLOT_COUNT;
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
