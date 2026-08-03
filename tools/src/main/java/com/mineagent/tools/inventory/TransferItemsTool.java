package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Move items between inventory slots or an open container.
 *
 * <p>All validation is completed before either slot is mutated. This matters
 * for fake players because a rejected partial swap or illegal armor placement
 * must not leave one side changed without the other.
 */
public class TransferItemsTool implements Tool {

    private static final Set<String> LOCATIONS = Set.of("player", "container");

    @Override
    public String name() { return "transfer_items"; }

    @Override
    public String description() {
        return """
            Move items between inventory slots or between player inventory and
            an open container. Specify source slot, destination slot, and count.
            Player slots are 0-35 inventory, 36-39 armor, and 40 off-hand.
            Container slots use the indices returned by inspect_gui.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("from_slot", "Source slot index", 0, 89)
                .integer("to_slot", "Destination slot index", 0, 89)
                .optionalInteger("count", "Number to move; omitted means all", 1, 64)
                .optionalString("source", "player or container")
                .optionalString("destination", "player or container")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        Integer fromSlotValue = ToolArgs.getIntOrNull(args, "from_slot");
        Integer toSlotValue = ToolArgs.getIntOrNull(args, "to_slot");
        if (fromSlotValue == null || toSlotValue == null) {
            reply.accept("{\"error\":\"from_slot and to_slot must be valid integers.\"}");
            return;
        }

        int fromSlot = fromSlotValue;
        int toSlot = toSlotValue;
        Integer requestedCount = ToolArgs.getIntOrNull(args, "count");
        if (ToolArgs.has(args, "count") && requestedCount == null) {
            // A malformed optional count previously looked like omission and
            // unexpectedly moved the entire source stack.
            reply.accept("{\"error\":\"count must be a valid integer when supplied.\"}");
            return;
        }
        String source = ToolArgs.getString(args, "source", "player");
        String destination = ToolArgs.getString(args, "destination", "player");
        if (!LOCATIONS.contains(source) || !LOCATIONS.contains(destination)) {
            reply.accept("{\"error\":\"source and destination must be 'player' or 'container'.\"}");
            return;
        }
        if (requestedCount != null && (requestedCount <= 0 || requestedCount > 64)) {
            reply.accept("{\"error\":\"count must be between 1 and 64.\"}");
            return;
        }

        ServerPlayer sp = ((CompanionEntity) player).serverPlayer();
        Inventory inventory = sp.getInventory();
        AbstractContainerMenu menu = sp.containerMenu;
        if ((source.equals("container") || destination.equals("container"))
                && (menu == null || menu == sp.inventoryMenu)) {
            reply.accept("{\"error\":\"No container GUI is currently open.\"}");
            return;
        }

        SlotRef from = resolveSlot(sp, menu, source, fromSlot);
        SlotRef to = resolveSlot(sp, menu, destination, toSlot);
        if (from == null || to == null) {
            reply.accept("{\"error\":\"Slot index is out of range for its selected location.\"}");
            return;
        }

        ItemStack sourceStack = from.get();
        ItemStack destStack = to.get();
        if (sourceStack.isEmpty()) {
            reply.accept("{\"error\":\"Source slot is empty.\"}");
            return;
        }
        if (!from.mayExtractDirectly()) {
            // Crafting/furnace result slots apply ingredient consumption,
            // recipe accounting, and achievements from Slot#onTake. Directly
            // splitting or replacing such a slot bypasses that callback and
            // can duplicate outputs, so this low-level transfer tool rejects
            // them instead of pretending to emulate a vanilla menu click.
            reply.accept("{\"error\":\"Special output slots cannot be transferred directly.\"}");
            return;
        }
        // Menu player-inventory slots and direct player indices can alias the
        // same physical stack. Object identity catches that alias before split.
        if (sourceStack == destStack || from.samePhysicalSlot(to)) {
            reply.accept("{\"error\":\"Source and destination are the same physical slot.\"}");
            return;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).toString();
        int requested = requestedCount != null
                ? Math.min(requestedCount, sourceStack.getCount())
                : sourceStack.getCount();
        int actual;
        String action;

        if (!destStack.isEmpty()
                && !ItemStack.isSameItemSameComponents(sourceStack, destStack)) {
            // A partial transfer cannot be represented as a swap without
            // silently moving more than requested.
            if (requested != sourceStack.getCount()) {
                reply.accept("{\"error\":\"A different occupied destination requires an all-item swap.\"}");
                return;
            }
            if (!to.mayPlace(sourceStack) || !from.mayPlace(destStack)) {
                reply.accept("{\"error\":\"One of the destination slots rejects the swapped item.\"}");
                return;
            }
            from.set(destStack);
            to.set(sourceStack);
            actual = sourceStack.getCount();
            action = "swap";
        } else {
            if (!to.mayPlace(sourceStack)) {
                reply.accept("{\"error\":\"Destination slot rejects this item.\"}");
                return;
            }
            int destinationLimit = Math.min(
                    sourceStack.getMaxStackSize(), to.maxStackSize(sourceStack));
            int space = destStack.isEmpty()
                    ? destinationLimit
                    : destinationLimit - destStack.getCount();
            actual = Math.min(requested, Math.max(0, space));
            if (actual <= 0) {
                reply.accept("{\"error\":\"Destination slot is full.\"}");
                return;
            }

            if (destStack.isEmpty()) {
                to.set(sourceStack.split(actual));
            } else {
                destStack.grow(actual);
                sourceStack.shrink(actual);
                to.changed();
            }
            if (sourceStack.isEmpty()) {
                from.set(ItemStack.EMPTY);
            } else {
                from.changed();
            }
            action = "transfer";
        }

        // Every success path converges here. The previous swap return skipped
        // this synchronization and left clients showing stale inventory.
        from.changed();
        to.changed();
        if (from.playerInventory || to.playerInventory) {
            TaskContext.syncInventory(sp);
        }
        if (menu != null) {
            menu.broadcastChanges();
        }

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("action", action);
        result.addProperty("item", itemId);
        result.addProperty("from_slot", fromSlot);
        result.addProperty("to_slot", toSlot);
        result.addProperty("count", actual);
        reply.accept(result.toString());
    }

    private static SlotRef resolveSlot(ServerPlayer sp, AbstractContainerMenu menu,
                                       String location, int index) {
        if ("container".equals(location)) {
            if (menu == null || index < 0 || index >= menu.slots.size()) return null;
            Slot slot = menu.getSlot(index);
            return new SlotRef(sp, slot, -1, slot.container == sp.getInventory());
        }
        if (index < 0 || index >= sp.getInventory().getContainerSize()) return null;
        return new SlotRef(sp, null, index, true);
    }

    private static final class SlotRef {
        private final ServerPlayer player;
        private final Slot menuSlot;
        private final int inventoryIndex;
        private final boolean playerInventory;

        private SlotRef(ServerPlayer player, Slot menuSlot, int inventoryIndex,
                        boolean playerInventory) {
            this.player = player;
            this.menuSlot = menuSlot;
            this.inventoryIndex = inventoryIndex;
            this.playerInventory = playerInventory;
        }

        ItemStack get() {
            return menuSlot != null
                    ? menuSlot.getItem()
                    : player.getInventory().getItem(inventoryIndex);
        }

        void set(ItemStack stack) {
            if (menuSlot != null) menuSlot.set(stack);
            else player.getInventory().setItem(inventoryIndex, stack);
        }

        void changed() {
            if (menuSlot != null) menuSlot.setChanged();
            else player.getInventory().setChanged();
        }

        boolean mayPlace(ItemStack stack) {
            if (menuSlot != null) return menuSlot.mayPlace(stack);
            EquipmentSlot expected = switch (inventoryIndex) {
                case 36 -> EquipmentSlot.FEET;
                case 37 -> EquipmentSlot.LEGS;
                case 38 -> EquipmentSlot.CHEST;
                case 39 -> EquipmentSlot.HEAD;
                default -> null;
            };
            return expected == null || player.getEquipmentSlotForItem(stack) == expected;
        }

        boolean mayExtractDirectly() {
            // Ordinary storage/input slots accept their current stack back.
            // Result slots intentionally return false from mayPlace, which is
            // the reliable menu-level signal that extraction needs onTake.
            return menuSlot == null
                    || (menuSlot.mayPickup(player) && menuSlot.mayPlace(menuSlot.getItem()));
        }

        int maxStackSize(ItemStack stack) {
            if (menuSlot != null) return menuSlot.getMaxStackSize(stack);
            return inventoryIndex >= 36 && inventoryIndex <= 39
                    ? 1 : stack.getMaxStackSize();
        }

        boolean samePhysicalSlot(SlotRef other) {
            if (this.menuSlot == null && other.menuSlot == null) {
                return this.inventoryIndex == other.inventoryIndex;
            }
            return false;
        }
    }
}
