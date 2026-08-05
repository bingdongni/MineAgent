package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;
import com.mineagent.engine.world.WorldAssetObserver;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Moves real item stacks between player and open-container slots. */
public class TransferItemsTool implements Tool {

    @Override
    public String name() { return "transfer_items"; }

    @Override
    public String description() {
        return """
            Move items between inventory slots or between player inventory and
            an open container. Player slots are 0-40. Container indices are
            the indices reported by inspect_gui for the currently open menu.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("from_slot", "Source slot index", 0, 255)
                .integer("to_slot", "Destination slot index", 0, 255)
                .optionalInteger("count", "Items to move; default is the full source stack", 1, 64)
                .optionalString("source", "Source: player or container (default player)")
                .optionalString("destination", "Destination: player or container (default player)")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        Integer fromIndex = ToolArgs.getIntOrNull(args, "from_slot");
        Integer toIndex = ToolArgs.getIntOrNull(args, "to_slot");
        if (fromIndex == null) {
            reply.accept(ToolArgs.errorJson("from_slot must be an integer"));
            return;
        }
        if (toIndex == null) {
            reply.accept(ToolArgs.errorJson("to_slot must be an integer"));
            return;
        }

        Integer requestedCount = ToolArgs.getIntOrNull(args, "count");
        if (ToolArgs.has(args, "count")
                && (requestedCount == null || requestedCount < 1 || requestedCount > 64)) {
            reply.accept(ToolArgs.errorJson("count must be an integer from 1 to 64"));
            return;
        }

        String sourceType = normalizedEndpoint(args, "source", "player");
        String destinationType = normalizedEndpoint(args, "destination", "player");
        if (!validEndpoint(sourceType) || !validEndpoint(destinationType)) {
            reply.accept(ToolArgs.errorJson("source and destination must be 'player' or 'container'"));
            return;
        }

        ServerPlayer sp = ((CompanionEntity) player).serverPlayer();
        AbstractContainerMenu openMenu = sp.containerMenu;
        if (("container".equals(sourceType) || "container".equals(destinationType))
                && (openMenu == null || openMenu == sp.inventoryMenu)) {
            reply.accept(ToolArgs.errorJson("No container GUI is currently open"));
            return;
        }

        Slot source = resolveSlot(sp, sourceType, fromIndex);
        Slot destination = resolveSlot(sp, destinationType, toIndex);
        if (source == null) {
            reply.accept(ToolArgs.errorJson("Source slot " + fromIndex + " is out of range"));
            return;
        }
        if (destination == null) {
            reply.accept(ToolArgs.errorJson("Destination slot " + toIndex + " is out of range"));
            return;
        }
        if (sameBackingSlot(source, destination)) {
            reply.accept(ToolArgs.errorJson("Source and destination refer to the same inventory slot"));
            return;
        }
        if (!source.isActive() || !source.mayPickup(sp)) {
            reply.accept(ToolArgs.errorJson("Source slot cannot be taken from"));
            return;
        }

        ItemStack sourceStack = source.getItem();
        ItemStack destinationStack = destination.getItem();
        if (sourceStack.isEmpty()) {
            reply.accept(ToolArgs.errorJson("Source slot " + fromIndex + " is empty"));
            return;
        }
        if (!destination.isActive() || !destination.mayPlace(sourceStack)) {
            reply.accept(ToolArgs.errorJson("Destination slot does not accept this item"));
            return;
        }
        // Keep the identity before safeTake/shrink mutates the source stack to
        // empty. Reading sourceStack.getItem() after the transfer previously
        // produced minecraft:air for a full-stack move.
        String movedItemId = BuiltInRegistries.ITEM
                .getKey(sourceStack.getItem()).toString();

        int requested = requestedCount == null
                ? sourceStack.getCount() : Math.min(requestedCount, sourceStack.getCount());
        int moved;
        String action;

        if (!destinationStack.isEmpty()
                && !ItemStack.isSameItemSameComponents(sourceStack, destinationStack)) {
            // A partial swap has no unambiguous inventory meaning. The old
            // implementation ignored count and exchanged both complete stacks.
            if (requested != sourceStack.getCount()) {
                reply.accept(ToolArgs.errorJson(
                        "A destination containing a different item requires moving the full source stack"));
                return;
            }
            if (!source.mayPlace(destinationStack)
                    || sourceStack.getCount() > destination.getMaxStackSize(sourceStack)
                    || destinationStack.getCount() > source.getMaxStackSize(destinationStack)) {
                reply.accept(ToolArgs.errorJson("The two slots cannot accept each other's stacks"));
                return;
            }

            source.setByPlayer(destinationStack);
            destination.setByPlayer(sourceStack);
            source.setChanged();
            destination.setChanged();
            moved = requested;
            action = "swap";
        } else {
            int capacity = destinationStack.isEmpty()
                    ? destination.getMaxStackSize(sourceStack)
                    : Math.min(destination.getMaxStackSize(sourceStack),
                            destinationStack.getMaxStackSize()) - destinationStack.getCount();
            moved = Math.min(requested, Math.max(0, capacity));
            if (moved <= 0) {
                reply.accept(ToolArgs.errorJson("Destination slot is full"));
                return;
            }

            // safeTake enforces source-slot behavior such as crafting-result
            // consumption. Capacity is checked first so no taken stack is ever
            // stranded or duplicated during a failed insertion.
            ItemStack taken = source.safeTake(moved, moved, sp);
            if (taken.isEmpty()) {
                reply.accept(ToolArgs.errorJson("Source slot refused the transfer"));
                return;
            }
            moved = taken.getCount();
            if (destinationStack.isEmpty()) {
                destination.setByPlayer(taken);
            } else {
                destinationStack.grow(moved);
                destination.setChanged();
            }
            source.setChanged();
            action = "transfer";
        }

        TaskContext.syncInventory(sp);
        if (openMenu != null) openMenu.broadcastChanges();
        var loop = TaskContext.agentLoop(player);
        if (loop != null && openMenu != null && openMenu != sp.inventoryMenu) {
            // Update the exact post-transfer menu contents. This prevents the
            // persistent planner from believing that retrieved equipment is
            // still in the container on the next decision.
            WorldAssetObserver.observeOpenMenu(loop.worldAssetIndex(), sp, openMenu);
        }

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("action", action);
        result.addProperty("item", movedItemId);
        result.addProperty("source", sourceType);
        result.addProperty("destination", destinationType);
        result.addProperty("from_slot", fromIndex);
        result.addProperty("to_slot", toIndex);
        result.addProperty("count", moved);
        reply.accept(result.toString());
    }

    private static String normalizedEndpoint(JsonObject args, String key, String fallback) {
        String value = ToolArgs.getString(args, key, fallback);
        return value == null ? fallback : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean validEndpoint(String value) {
        return "player".equals(value) || "container".equals(value);
    }

    private static Slot resolveSlot(ServerPlayer sp, String endpoint, int index) {
        if (index < 0) return null;
        if ("container".equals(endpoint)) {
            AbstractContainerMenu menu = sp.containerMenu;
            return menu != null && index < menu.slots.size() ? menu.getSlot(index) : null;
        }

        Inventory inventory = sp.getInventory();
        if (index >= inventory.getContainerSize()) return null;
        AbstractContainerMenu menu = sp.containerMenu == null
                ? sp.inventoryMenu : sp.containerMenu;
        for (Slot slot : menu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() == index) return slot;
        }
        // The inventory menu always maps all 41 player slots, but use it as a
        // defensive fallback for modded menus that omit an offhand/armor slot.
        for (Slot slot : sp.inventoryMenu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() == index) return slot;
        }
        return null;
    }

    private static boolean sameBackingSlot(Slot a, Slot b) {
        return a == b || (a.container == b.container
                && a.getContainerSlot() == b.getContainerSlot());
    }
}
