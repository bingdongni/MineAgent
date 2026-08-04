package com.mineagent.engine.entity;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.CompanionLifecycle;
import com.mineagent.api.task.*;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.loop.AgentLoop;

import java.util.*;

/**
 * Handles companion lifecycle events — spawn, death, respawn, and despawn.
 * <p>
 * This is the central coordinator that connects the companion's Minecraft
 * entity lifecycle to the AI agent system:
 * <ul>
 *   <li><b>onSpawn:</b> initialize the agent loop, register instinct chains,
 *       start the body driver</li>
 *   <li><b>onDeath:</b> pause the agent loop, notify the owner via chat</li>
 *   <li><b>onRespawn:</b> resume the agent loop with fresh state</li>
 *   <li><b>onDespawn:</b> shut down the agent loop, clean up all resources</li>
 * </ul>
 * <p>
 * Each companion has its own lifecycle handler instance, created when the
 * companion is spawned and destroyed when the companion is despawned.
 */
public class CompanionLifecycleHandler implements CompanionLifecycle {

    private final AgentPlayer companion;
    private final AgentLoop loop;
    private final List<TaskChain> chains;
    private volatile boolean paused = false;
    private volatile boolean dead = false;

    /**
     * Guard flag ensuring onDeath() fires at most once per death.
     *
     * <p>onServerTick() re-evaluates the companion's health every tick, so
     * once HP hits 0 the death condition stays true for several ticks
     * until the body is removed. Without this guard, each of those ticks
     * would re-enter onDeath(), re-cancel the agent loop, and re-spam the
     * owner with "has died!" messages. The flag is reset in onSpawn() and
     * onRespawn() so the companion can die again after being revived.
     *
     * <p>Note: the caller (MineAgentEngine.onServerTick) also guards with
     * {@code !state.lifecycle.isDead()}, but isDead() is only flipped to
     * true inside onDeath() itself, so without deathProcessed there is a
     * race window where the same tick could enqueue the event twice on
     * concurrent code paths. This flag is the authoritative dedup point.
     */
    private volatile boolean deathProcessed = false;

    /** Track registered chains so we can clean them up on despawn. */
    private final List<TaskChain> registeredChains = new ArrayList<>();

    public CompanionLifecycleHandler(AgentPlayer companion, AgentLoop loop,
                                      List<TaskChain> chains) {
        this.companion = companion;
        this.loop = loop;
        this.chains = chains;
    }

    /**
     * Called when the companion entity is spawned into the world.
     * <p>
     * Initializes the agent loop, registers all instinct chains with the
     * brain chain registry, and starts the body driver.
     */
    @Override
    public void onSpawn(AgentPlayer companion) {
        if (this.companion != companion) return;

        dead = false;
        paused = false;
        // Reset the death guard so a freshly spawned companion can die again.
        deathProcessed = false;

        // Note: chains are already registered in MineAgentEngine.init() via BrainChains.register().
        // They are per-companion instances created in spawnCompanion().
        // Do NOT re-register them here (H4 fix — was causing duplicate global registration).

        // Wake the agent loop — give it an initial perception of the world
        loop.wake("companion_spawned");

        // Notify the owner
        notifyOwner("§a[MineAgent] " + companion.companionName() + " has spawned!");

        System.out.println("[MineAgent] Companion '" + companion.companionName()
                + "' spawned (owner: " + companion.ownerName() + ")");
    }

    /**
     * Called when the companion dies.
     * <p>
     * Pauses the agent loop (the companion can't act while dead) and
     * notifies the owner. The loop will be resumed when the companion
     * is respawned.
     */
    @Override
    public void onDeath(AgentPlayer companion) {
        if (this.companion != companion) return;

        // Idempotent guard — see deathProcessed javadoc. onServerTick()
        // re-checks health every tick, so without this guard the death
        // sequence (loop.cancel + owner notification + body log) would
        // fire on every tick where HP is still 0, spamming the owner and
        // re-cancelling an already-cancelled loop.
        if (deathProcessed) return;
        deathProcessed = true;

        dead = true;
        paused = true;

        // Idle cleanup is not represented by the normal body task slot. Stop
        // its progressive break state explicitly when the body dies.
        com.mineagent.engine.task.TaskContext.temporaryBlocks(companion).interrupt();

        // Suspend, rather than merely cancelling one response. Body events are
        // retained but cannot generate tools that would unexpectedly execute
        // after the body is revived.
        loop.pause();

        // Send a body log to inform the LLM that it died
        loop.onBodyLog("I died! My health reached zero. I need to be respawned.");

        // Notify the owner
        notifyOwner("§c[MineAgent] " + companion.companionName() + " has died! "
                + "Use /mineagent respawn to bring them back.");

        System.out.println("[MineAgent] Companion '" + companion.companionName() + "' died");
    }

    /**
     * Called when the companion is respawned after death.
     * <p>
     * Resumes the agent loop with fresh state. The companion gets a new
     * body (full health, food, etc.) and the LLM is informed of the respawn.
     */
    @Override
    public void onRespawn(AgentPlayer companion) {
        if (this.companion != companion) return;

        dead = false;
        paused = false;
        // Reset the death guard so the companion can die again in the future.
        deathProcessed = false;

        var sp = ((CompanionEntity) companion).serverPlayer();

        // ── Reset body state ───────────────────────────────────────
        // (1) Clear ALL effects before healing. This is critical: if the
        //     companion died from Poison/Wither/Instant Damage, lingering
        //     negative effects would immediately re-drain HP after we
        //     setHealth() below, causing a second death within ticks and
        //     re-triggering onDeath() (now that deathProcessed was just
        //     reset). Removing effects first breaks that loop.
        sp.removeAllEffects();
        // (2) Full heal + food. Use getMaxHealth() instead of the literal
        //     20.0f so this stays correct if the companion ever has
        //     modified max HP (e.g. absorption/attribute modifiers).
        sp.setHealth(sp.getMaxHealth());
        sp.getFoodData().setFoodLevel(20);
        sp.setRemainingFireTicks(0);
        sp.setAirSupply(sp.getMaxAirSupply());
        sp.fallDistance = 0.0f;
        sp.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);

        // Clear the input driver (reset movement state)
        if (companion instanceof CompanionEntity ce) {
            ce.inputDriver().clear();
        }

        // ── Safe respawn location ─────────────────────────────────
        // FOLLOW mode: teleport to the owner so the companion reappears at
        //   a safe, relevant spot rather than wherever it died (which may
        //   now be in lava, mid-air, or surrounded by hostiles). Per spec,
        //   this death-teleport is the ONLY auto-teleport allowed in any
        //   mode; FREE mode revives in place and must not be teleported.
        // Only teleport if the owner is alive — a dead owner can't be a
        //   safe destination.
        var mode = MineAgentEngine.getCompanionMode(companion.companionId());
        if (mode == MineAgentEngine.CompanionMode.FOLLOW) {
            var owner = ((CompanionEntity) companion).serverPlayerOwner();
            if (owner != null && owner.getHealth() > 0f) {
                SafeTeleport.near(sp, owner.serverLevel(), owner.blockPosition(),
                        owner.getYRot(), owner.getXRot());
            }
        }

        // Resume the agent loop — inform the LLM of the respawn
        loop.onBodyLog("I've been respawned! I'm at full health and ready to continue.");
        loop.resume("companion_respawned");

        // Notify the owner
        notifyOwner("§a[MineAgent] " + companion.companionName() + " has respawned!");

        System.out.println("[MineAgent] Companion '" + companion.companionName() + "' respawned");
    }

    /**
     * Called when the companion is removed from the world.
     * <p>
     * Shuts down the agent loop, cleans up all registered instinct chains,
     * and releases all resources associated with this companion.
     */
    @Override
    public void onDespawn(AgentPlayer companion) {
        if (this.companion != companion) return;

        // Shut down the agent loop
        loop.shutdown();

        // Clean up registered chains
        registeredChains.clear();

        // Path views and temporary-block ownership are keyed by the concrete
        // CompanionEntity. Releasing them here prevents one retained world
        // reference per despawn and aborts any cleanup break before unregister.
        com.mineagent.engine.task.TaskContext.removeCaches(companion);

        dead = true;
        paused = true;

        // Notify the owner
        notifyOwner("§7[MineAgent] " + companion.companionName() + " has been removed.");

        System.out.println("[MineAgent] Companion '" + companion.companionName()
                + "' despawned and cleaned up");
    }

    /**
     * Check if the companion is currently dead.
     */
    public boolean isDead() {
        return dead;
    }

    /**
     * Check if the companion is currently paused.
     */
    public boolean isPaused() {
        return paused;
    }

    /** Pause body scheduling and LLM work without marking the companion dead. */
    public void pauseByOwner() {
        if (dead) return;
        paused = true;
        loop.pause();
        if (companion instanceof CompanionEntity entity) {
            entity.inputDriver().clear();
        }
    }

    /** Resume an owner-paused companion. Dead companions require respawn instead. */
    public boolean resumeByOwner() {
        if (dead) return false;
        paused = false;
        loop.resume("owner_resume");
        return true;
    }

    /**
     * Get the agent loop for this companion.
     */
    public AgentLoop getLoop() {
        return loop;
    }

    /**
     * Send a chat message to the companion's owner.
     */
    private void notifyOwner(String message) {
        if (companion instanceof CompanionEntity ce) {
            var owner = ce.serverPlayerOwner();
            if (owner != null && owner.connection != null) {
                owner.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal(message));
            }
        }
    }
}
