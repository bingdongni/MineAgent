package com.mineagent.engine.entity;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.InputDriver;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Concrete AgentPlayer implementation — wraps a vanilla ServerPlayer
 * and provides the input driver for AI control.
 *
 * <p>Bridges the API's plain-Java abstract methods to Minecraft ServerPlayer internals.
 * The serverPlayer() and serverPlayerOwner() methods are engine-specific
 * and available only to engine/tools code.
 */
public class CompanionEntity extends AgentPlayer {

    private final ServerPlayer serverPlayer;
    private final UUID companionId;
    private volatile String companionName;
    private final ServerPlayer owner;
    private final CompanionInputDriver inputDriver;

    public CompanionEntity(ServerPlayer serverPlayer, ServerPlayer owner, String name) {
        this.serverPlayer = serverPlayer;
        this.companionId = UUID.randomUUID();
        this.companionName = name;
        this.owner = owner;
        this.inputDriver = new CompanionInputDriver(serverPlayer);
    }

    /**
     * Rename this companion (display name + persistence key).
     * Called by MineAgentEngine.renameCompanion — updates the name used
     * for persistence and memory storage keys.
     */
    public void rename(String newName) {
        this.companionName = newName;
    }

    // ── Engine-specific methods (not in API) ──

    /** The underlying vanilla ServerPlayer. Engine/tools only. */
    public ServerPlayer serverPlayer() {
        return serverPlayer;
    }

    /** The owner as a ServerPlayer. Engine/tools only. */
    public ServerPlayer serverPlayerOwner() {
        return owner;
    }

    /** Get the input driver for this companion. */
    public InputDriver inputDriver() {
        return inputDriver;
    }

    // ── AgentPlayer abstract method implementations ──

    @Override
    public UUID companionId() {
        return companionId;
    }

    @Override
    public String companionName() {
        return companionName;
    }

    @Override
    public UUID ownerUuid() {
        return owner.getUUID();
    }

    @Override
    public String ownerName() {
        return owner.getName().getString();
    }

    @Override
    public String blockPositionStr() {
        var pos = serverPlayer.blockPosition();
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    @Override
    public double posX() {
        return serverPlayer.getX();
    }

    @Override
    public double posY() {
        return serverPlayer.getY();
    }

    @Override
    public double posZ() {
        return serverPlayer.getZ();
    }

    @Override
    public float health() {
        return serverPlayer.getHealth();
    }

    @Override
    public float maxHealth() {
        return serverPlayer.getMaxHealth();
    }

    @Override
    public int foodLevel() {
        return serverPlayer.getFoodData().getFoodLevel();
    }

    @Override
    public int airSupply() {
        return serverPlayer.getAirSupply();
    }

    @Override
    public boolean isInWater() {
        return serverPlayer.isInWater();
    }

    @Override
    public boolean isAlive() {
        return serverPlayer.isAlive();
    }

    @Override
    public void holdInHand(int slot) {
        if (slot < 0 || slot > 8) return;
        serverPlayer.getInventory().selected = slot;
        // Sync selected slot + inventory to all viewers.
        // containerMenu.broadcastChanges() alone is NOT enough for the
        // fake player's own slot change — we must also send the
        // ClientboundContainerSetSlotPacket for the main hand so the
        // client sees the new held item. For real players this is
        // handled by the client→server ServerboundSetCarriedItemPacket,
        // but our fake player has no client to send that packet.
        serverPlayer.containerMenu.broadcastChanges();
        // Also force a re-sync of the inventory to any viewer (the owner)
        serverPlayer.getInventory().setChanged();
    }

    @Override
    public String mainHandItemId() {
        var stack = serverPlayer.getMainHandItem();
        if (stack.isEmpty()) return "minecraft:air";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override
    public String dimensionKey() {
        return serverPlayer.level().dimension().location().toString();
    }
}
