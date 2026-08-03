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

        // Clear pre-death inbox events before queuing the death narrative.
        // Otherwise resume can act on an old navigation request generated for
        // a body/world state which no longer exists.
        loop.cancel();

        // A true pause invalidates the current response and also prevents
        // body-log wakeups from starting new turns while the body is dead.
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
                // Reviving on the owner's exact coordinates overlaps hitboxes
                // and can push the new body into solid terrain.
                SafeTeleport.beside(sp, owner);
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

        // These registries hold strong references/per-companion UUID state and
        // otherwise grow on every owner reconnect or companion recreation.
        com.mineagent.engine.task.TaskContext.removeCaches(companion);
        com.mineagent.engine.survival.SurvivalBuiltin.remove(companion);
        for (var reflex : com.mineagent.api.task.reflex.ReflexRegistry.all()) {
            reflex.forget(companion);
        }

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

    /** User-requested pause: stop AI actions while keeping body physics alive. */
    public void pause() {
        if (dead) return;
        paused = true;
        if (companion instanceof CompanionEntity ce) ce.inputDriver().clear();
        loop.pause();
    }

    public void resume() {
        if (dead) return;
        paused = false;
        loop.resume("owner_resume");
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
