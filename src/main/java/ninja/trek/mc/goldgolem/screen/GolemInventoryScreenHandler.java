package ninja.trek.mc.goldgolem.screen;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import ninja.trek.mc.goldgolem.registry.ModScreenHandlers;

public class GolemInventoryScreenHandler extends AbstractContainerMenu {
    private final Container golemInventory;
    private final int entityId;
    private final int golemSlotCount;
    private final int controlsMargin;
    private final int golemRows;
    private final boolean sliderEnabled;
    private final int sliderMode; // 0=none, 1=path, 2=excavation, 3=mining
    private final String jsonName;

    // Client-side constructor (from ExtendedScreenHandlerType buffer)
    public GolemInventoryScreenHandler(int syncId, Inventory playerInventory, GolemOpenData data) {
        super(ModScreenHandlers.GOLEM_SCREEN_HANDLER, syncId);
        this.entityId = data.entityId();
        this.golemSlotCount = Math.max(0, data.golemSlots());
        this.golemRows = (this.golemSlotCount + 8) / 9;
        int titleLine = 10;
        this.controlsMargin = GolemOpenData.computeControlsMargin(data.gradientRows(), data.slider(), titleLine);
        this.sliderEnabled = data.sliderEnabled();
        this.sliderMode = data.slider();
        this.jsonName = data.jsonName() == null ? "" : data.jsonName();
        this.golemInventory = new SimpleContainer(this.golemSlotCount);
        this.golemInventory.startOpen(playerInventory.player);
        setupSlots(playerInventory);
    }

    // Server-side constructor: use the actual golem inventory
    public GolemInventoryScreenHandler(int syncId, Inventory playerInventory, Container golemInventory, GolemOpenData data) {
        super(ModScreenHandlers.GOLEM_SCREEN_HANDLER, syncId);
        this.entityId = data.entityId();
        this.golemSlotCount = Math.min(golemInventory.getContainerSize(), Math.max(0, data.golemSlots()));
        this.golemRows = (this.golemSlotCount + 8) / 9;
        this.controlsMargin = GolemOpenData.computeControlsMargin(data.gradientRows(), data.slider(), 10);
        this.sliderEnabled = data.sliderEnabled();
        this.sliderMode = data.slider();
        this.jsonName = data.jsonName() == null ? "" : data.jsonName();
        this.golemInventory = golemInventory;
        this.golemInventory.startOpen(playerInventory.player);
        setupSlots(playerInventory);
    }

    private void setupSlots(Inventory playerInventory) {
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
    public boolean stillValid(Player player) {
        return true;
    }

    public int getEntityId() {
        return entityId;
    }

    public int getControlsMargin() { return controlsMargin; }
    public int getGolemSlotCount() { return golemSlotCount; }
    public int getGolemRows() { return golemRows; }
    public boolean isSliderEnabled() { return sliderEnabled; }
    public int getSliderMode() { return sliderMode; }
    public String getJsonName() { return jsonName; }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // Clear GUI viewer tracking on the golem entity
        if (!player.level().isClientSide()) {
            var entity = player.level().getEntity(this.entityId);
            if (entity instanceof ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity golem) {
                golem.clearGuiViewer();
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Basic shift-click behavior between golem inventory and player inventory
        ItemStack newStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            newStack = stack.copy();
            int golemEnd = this.golemSlotCount;
            if (index < golemEnd) {
                if (!this.moveItemStackTo(stack, golemEnd, this.slots.size(), true)) return ItemStack.EMPTY;
            } else {
                if (!this.moveItemStackTo(stack, 0, golemEnd, false)) return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
            else slot.setChanged();
        }
        return newStack;
    }
}
