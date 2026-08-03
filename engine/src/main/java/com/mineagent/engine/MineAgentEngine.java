package com.mineagent.engine;

import com.mineagent.api.agent.tool.ToolRegistry;
import com.mineagent.api.agent.skill.SkillRegistry;
import com.mineagent.api.config.MineAgentConfig;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.CompanionLifecycle;
import com.mineagent.api.llm.provider.LLMProviderRegistry;
import com.mineagent.api.platform.Services;
import com.mineagent.api.task.*;
import com.mineagent.api.task.reflex.ReflexRegistry;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.entity.CompanionLifecycleHandler;
import com.mineagent.engine.entity.fakeplayer.FakePlayerFactory;
import com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode;
import com.mineagent.engine.llm.*;
import com.mineagent.engine.loop.AgentLoop;
import com.mineagent.engine.scheduler.PriorityAuction;
import com.mineagent.engine.survival.*;
import com.mineagent.engine.survival.reflex.*;
import com.mineagent.engine.task.TaskRegistration;
import com.mineagent.engine.task.TaskContext;
import com.mineagent.engine.survival.CompanionBodyLog;
import com.mineagent.tools.management.TaskStatusTool;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * The MineAgent engine — the central orchestrator that manages all companions,
 * runs the priority auction, and drives the agent loops.
 *
 * <p>Initialized once at mod load time via {@link #init()}.
 * Platform modules register event handlers that delegate to the
 * server tick, chat, and lifecycle methods here.
 */
public final class MineAgentEngine {

    private static final Map<UUID, CompanionState> COMPANIONS = new ConcurrentHashMap<>();

    /** Owner UUID → their primary companion ID (first spawned). Kept for
     *  backward-compatible single-companion commands. */
    private static final Map<UUID, UUID> OWNER_TO_COMPANION = new ConcurrentHashMap<>();

    /** Owner UUID → ordered list of ALL their companion IDs (max 3). */
    private static final Map<UUID, List<UUID>> OWNER_TO_ALL_COMPANIONS = new ConcurrentHashMap<>();

    /** Bounded world reads that deliberately span multiple server ticks. */
    private static final List<CompanionTickDispatcher.PendingWork> ACTIVE_TICK_WORK =
            new ArrayList<>();

    /** Maximum companions per owner in a single world. */
    private static final int MAX_COMPANIONS_PER_OWNER = 3;

    /** The loaded configuration for the entire MineAgent system. */
    private static MineAgentConfig config = MineAgentConfig.DEFAULTS;

    /** The config directory (saved so setconfig can persist changes). */
    private static Path configDirPath = null;

    /** The world's data directory for persistence (set during init). */
    private static Path worldDataDir = null;

    /** Set of all companion ServerPlayer UUIDs for identifying companions. */
    private static final Set<UUID> COMPANION_PLAYER_UUIDS = ConcurrentHashMap.newKeySet();

    /**
     * Check if a player UUID belongs to a companion (AI player).
     * Used by mixins to identify companions without instanceof checks.
     */
    public static boolean isCompanionPlayer(UUID playerUuid) {
        return COMPANION_PLAYER_UUIDS.contains(playerUuid);
    }

    /** Verify ownership using the fake player's stable GameProfile UUID. */
    public static boolean isCompanionOwnedBy(UUID playerUuid, UUID ownerUuid) {
        if (playerUuid == null || ownerUuid == null) return false;
        for (CompanionState state : COMPANIONS.values()) {
            if (state.companion.serverPlayer().getUUID().equals(playerUuid)
                    && state.companion.ownerUuid().equals(ownerUuid)) return true;
        }
        return false;
    }

    /** Companion mode: FOLLOW (stick to owner) or FREE (autonomous). */
    private static final Map<UUID, CompanionMode> COMPANION_MODES = new ConcurrentHashMap<>();

    private static boolean initialized = false;

    private MineAgentEngine() {}

    /** Companion behavior mode. */
    public enum CompanionMode {
        FOLLOW,  // Stay close to owner at all times
        FREE     // Autonomous, can wander to complete tasks
    }

    // ── Initialization ─────────────────────────────────────────────

    /**
     * Initialize the engine. Called once at mod startup.
     * Loads configuration from the given config directory, then
     * registers all LLM providers and built-in components.
     *
     * @param configDir the platform-specific configuration directory
     */
    public static synchronized void init(Path configDir) {
        if (initialized) return;

        // Save config dir so /mineagent setconfig can persist changes
        configDirPath = configDir;

        // Load configuration from disk (or create defaults)
        config = MineAgentConfig.load(configDir);

        // Register LLM providers
        LLMProviderRegistry.register(OpenAICompatibleProvider.openai());
        LLMProviderRegistry.register(OpenAICompatibleProvider.deepseek());
        LLMProviderRegistry.register(OpenAICompatibleProvider.qwen());
        LLMProviderRegistry.register(OpenAICompatibleProvider.glm());
        LLMProviderRegistry.register(OpenAICompatibleProvider.moonshot());
        LLMProviderRegistry.register(OpenAICompatibleProvider.grok());
        LLMProviderRegistry.register(OpenAICompatibleProvider.minimax());
        LLMProviderRegistry.register(new AnthropicProvider());
        LLMProviderRegistry.register(new GeminiProvider());

        // Register survival instinct chains (H1 fix)
        // Build SurvivalConfig from the loaded MineAgentConfig
        SurvivalConfig survivalCfg = new SurvivalConfig(
                config.survival().foodLow(),
                config.survival().foodCritical(),
                (float) config.survival().healthFlee(),
                config.survival().stuckTimeTicks(),
                config.survival().autoEat(),
                config.survival().fightBack(),
                config.survival().pickupItems()
        );
        BrainChains.register(10, p -> new MLGChain(p));
        BrainChains.register(6,  p -> new BreathChain(p));
        BrainChains.register(5,  p -> new MobDefenseChain(p, survivalCfg));
        BrainChains.register(4,  p -> new FoodChain(p, survivalCfg));
        BrainChains.register(2,  p -> new UnstuckChain(p, survivalCfg));
        // FollowChain: lowest priority, only active in FOLLOW mode when
        // no LLM task is running. Lets the companion actively follow the
        // owner instead of standing still while the owner walks away.
        BrainChains.register(1,  p -> new FollowChain(p));

        // Register reflexes (H2 fix)
        ReflexRegistry.register(new AutoEatReflex());
        ReflexRegistry.register(new FightBackReflex());
        ReflexRegistry.register(new PickupItemsReflex());
        ReflexRegistry.register(new AvoidCreepersReflex());

        // Register task executors (H3 fix)
        TaskRegistration.registerAll();

        initialized = true;
        System.out.println("[MineAgent] Engine initialized with "
                + LLMProviderRegistry.all().size() + " LLM providers, "
                + BrainChains.entries().size() + " chains, "
                + ReflexRegistry.all().size() + " reflexes");
    }

    /**
     * Get the loaded MineAgent configuration.
     *
     * @return the current configuration (never null)
     */
    public static MineAgentConfig getConfig() {
        return config;
    }

    /**
     * Set the world data directory for companion persistence.
     * Called by the platform module when the server starts.
     *
     * @param dataDir the world's data directory (e.g. world/data/)
     */
    public static void setWorldDataDir(Path dataDir) {
        worldDataDir = dataDir;
        System.out.println("[MineAgent] World data directory set to " + dataDir);
    }

    /**
     * Get the world data directory (used for companion memory persistence).
     *
     * @return the world data directory, or null if not yet set
     */
    public static Path getWorldDataDir() {
        return worldDataDir;
    }

    /**
     * Restore all saved companions for the given server.
     * Called during server startup after world is loaded.
     */
    public static void restoreCompanions(net.minecraft.server.MinecraftServer server) {
        if (worldDataDir == null) {
            System.out.println("[MineAgent] No world data directory set, skipping companion restore");
            return;
        }

        var saved = com.mineagent.engine.entity.CompanionStore.loadAll(worldDataDir);
        if (saved.isEmpty()) return;

        System.out.println("[MineAgent] Restoring " + saved.size() + " companion(s)...");

        // We can't restore companions immediately because the owner's ServerPlayer
        // may not be online yet. We'll restore when the player joins.
        // Just log for now - onPlayerJoin will handle the actual restore.
    }

    /**
     * Save a single companion's state to disk.
     */
    private static void persistCompanion(CompanionState state) {
        if (worldDataDir == null) return;

        var sp = state.companion.serverPlayer();
        var owner = state.companion.serverPlayerOwner();

        // Get cached skin data if available.
        // NOTE: do NOT use config.companion().skinName() here — that is the
        // GLOBAL config default, not this companion's actual skin. After
        // /mineagent setskin the GameProfile is updated but the config is not,
        // so persisting the config value would record a misleading skin name.
        // The actual skin name is not tracked in the SkinLoader cache (only
        // value/signature are), and restore only reads skinValue/skinSignature
        // (never skinName). Persist an empty string to avoid inconsistency
        // (problem 5 fix).
        String skinName = "";
        String skinValue = null;
        String skinSignature = null;
        var cachedSkin = com.mineagent.engine.entity.fakeplayer.SkinLoader.getCachedSkin(sp.getUUID());
        if (cachedSkin.isPresent()) {
            skinValue = cachedSkin.get().value();
            skinSignature = cachedSkin.get().signature();
        }

        var saved = new com.mineagent.engine.entity.CompanionStore.SavedCompanion(
                owner.getUUID().toString(),
                owner.getName().getString(),
                state.companion.companionName(),
                state.loop.getProviderId(),
                state.loop.getApiKey(),
                state.loop.getModel(),
                state.loop.getBaseUrl(),
                state.loop.getTemperature(),
                state.loop.getReasoningEffort(),
                skinName != null ? skinName : "",
                skinValue,
                skinSignature
        );

        com.mineagent.engine.entity.CompanionStore.save(worldDataDir, saved);
    }

    /**
     * Remove a companion's saved state from disk.
     */
    private static void unpersistCompanion(UUID ownerUuid, String companionName) {
        if (worldDataDir == null) return;
        com.mineagent.engine.entity.CompanionStore.remove(
                worldDataDir, ownerUuid.toString(), companionName);
    }

    // ── Shutdown ───────────────────────────────────────────────────

    /**
     * Shut down the engine. Called when the server is stopping.
     * Saves all companion state to disk, then gracefully shuts down.
     */
    public static void shutdown() {
        // Snapshot because unregistering entities can trigger callbacks that
        // mutate server collections. onDespawn owns loop shutdown, so calling
        // loop.shutdown() separately here only duplicated persistence and
        // executor teardown.
        for (CompanionState state : List.copyOf(COMPANIONS.values())) {
            persistCompanion(state);
            state.lifecycle.onDespawn(state.companion);
            FakePlayerFactory.unregister(
                    state.companion.serverPlayer().getServer(),
                    state.companion.serverPlayer());
            COMPANION_PLAYER_UUIDS.remove(state.companion.serverPlayer().getUUID());
        }
        COMPANIONS.clear();
        OWNER_TO_COMPANION.clear();
        OWNER_TO_ALL_COMPANIONS.clear();
        COMPANION_MODES.clear();
        COMPANION_PLAYER_UUIDS.clear();
        // The dispatcher is process-static, while an integrated server can be
        // stopped and another world opened in the same client JVM. Tasks that
        // were acknowledged just before shutdown must not survive that world
        // boundary and execute against a restored companion with the same ID.
        // All agent executors have been stopped above, so draining here closes
        // the final server-thread submission window without racing live loops.
        CompanionTickDispatcher.drain();
        discardIncrementalWork();
        SurvivalBuiltin.clear();
        worldDataDir = null;
        System.out.println("[MineAgent] Engine shut down (companions saved)");
    }

    // ── Companion management ───────────────────────────────────────

    /**
     * Spawn a new companion for the given owner.
     *
     * @param owner        the human player who owns this companion
     * @param name         the companion's display name
     * @param providerId       the LLM provider id (e.g. "openai", "deepseek")
     * @param apiKey           the API key for the provider
     * @param model            the model name (e.g. "gpt-4o")
     * @param baseUrl          optional base URL override (for proxies/local models)
     * @param temperature      sampling temperature (0.0 - 2.0)
     * @param reasoningEffort  reasoning effort (off/low/medium/high/xhigh/max) or null
     * @param isRestore        true when restoring from a saved on-disk record
     *                         (skips applySkinAsync and persistCompanion, since
     *                         the caller applies the saved skin and the disk
     *                         record already exists); false for fresh spawns
     * @return the new companion entity, or null if spawn was rejected
     */
    public static CompanionEntity spawnCompanion(
            ServerPlayer owner,
            String name,
            String providerId,
            String apiKey,
            String model,
            String baseUrl,
            double temperature,
            String reasoningEffort,
            boolean isRestore) {

        String invalid = validateSpawnArguments(owner, name, providerId, apiKey,
                model, baseUrl, temperature, reasoningEffort);
        if (invalid != null) {
            if (owner != null) owner.sendSystemMessage(Component.literal(
                    "§c[MineAgent] Cannot spawn companion: " + invalid));
            return null;
        }

        // ── Multi-companion checks (max 3, unique model, unique name) ──
        List<UUID> existing = OWNER_TO_ALL_COMPANIONS.getOrDefault(owner.getUUID(), Collections.emptyList());
        if (existing.size() >= MAX_COMPANIONS_PER_OWNER) {
            owner.sendSystemMessage(Component.literal(
                    "§c[MineAgent] You already have " + MAX_COMPANIONS_PER_OWNER
                            + " companions (the maximum). Remove one first."));
            return null;
        }
        // No two companions may use the exact same model (same series different
        // variant is allowed, e.g. gpt-5.6-sol and gpt-5.6-mini are OK).
        for (UUID cid : existing) {
            CompanionState s = COMPANIONS.get(cid);
            if (s != null && model != null && model.equalsIgnoreCase(s.loop.getModel())) {
                owner.sendSystemMessage(Component.literal(
                        "§c[MineAgent] You already have a companion using model '"
                                + model + "'. Each companion must use a different model."));
                return null;
            }
            // Names must also be unique (case-insensitive)
            if (s != null && name != null
                    && name.equalsIgnoreCase(s.companion.companionName())) {
                owner.sendSystemMessage(Component.literal(
                        "§c[MineAgent] A companion named '" + name
                                + "' already exists. Choose a different name."));
                return null;
            }
        }

        // Create a fake ServerPlayer for the companion using FakePlayerFactory.
        // The Minecraft protocol limits player names to 16 chars of [A-Za-z0-9_],
        // so we sanitize the name for the GameProfile. But the companion's
        // DISPLAY name (companionName + customName above head + tab list) can
        // be the full original name — so "deepseek-v4-flash" shows completely
        // instead of being truncated to "deepseek_v4_flas".
        final String playerName;
        try {
            playerName = availableProfileName(owner, name);
        } catch (RuntimeException allocationError) {
            owner.sendSystemMessage(Component.literal(
                    "§c[MineAgent] Cannot allocate a unique player identity."));
            System.err.println("[MineAgent] Profile allocation failed: " + allocationError);
            return null;
        }
        GameProfile profile = FakePlayerFactory.createOfflineProfile(playerName);
        // Register the companion's UUID BEFORE FakePlayerFactory.create so that
        // mixins (isCompanionPlayer) can identify this player as a companion
        // during the server registration that happens inside create() (problem 7
        // fix). The UUID is derived from the offline profile, so profile.getId()
        // is exactly the UUID the ServerPlayer will carry.
        COMPANION_PLAYER_UUIDS.add(profile.getId());
        final ServerPlayer fakePlayer;
        try {
            fakePlayer = FakePlayerFactory.create(
                    owner.getServer(), profile, owner.serverLevel(), owner.blockPosition());
        } catch (RuntimeException spawnError) {
            // The UUID is registered before factory creation so mixins can
            // identify the player during registration. A factory failure must
            // undo it or a later real player can be misclassified.
            COMPANION_PLAYER_UUIDS.remove(profile.getId());
            owner.sendSystemMessage(Component.literal(
                    "§c[MineAgent] Failed to create companion: " + spawnError.getMessage()));
            System.err.println("[MineAgent] Fake-player creation failed: " + spawnError);
            return null;
        }

        AgentLoop initializingLoop = null;
        CompanionEntity initializingCompanion = null;
        try {

        // These config flags previously had no callers, leaving both behavior
        // switches stuck at FakePlayerGameMode's constructor defaults.
        if (fakePlayer.gameMode instanceof FakePlayerGameMode fakeMode) {
            fakeMode.setInstantBreak(config.companion().instantBreak());
            fakeMode.setCreativeReach(config.companion().creativeReach());
        }

        // Create the companion entity wrapping the fake player.
        // companionName stores the FULL name (not truncated) for display.
        var companion = new CompanionEntity(fakePlayer, owner, name);
        initializingCompanion = companion;

        // Registry reflex instances are global but their state is keyed by
        // companion. Seed each new body from configuration before chains bid.
        setInitialReflexState(companion, "auto_eat", config.survival().autoEat());
        setInitialReflexState(companion, "fight_back", config.survival().fightBack());
        setInitialReflexState(companion, "pickup_items", config.survival().pickupItems());
        setInitialReflexState(companion, "avoid_creeper", config.survival().avoidCreeper());

        // Set the custom display name (visible above head and in tab list)
        // to the full original name — this overrides the truncated player
        // name that the GameProfile carries.
        fakePlayer.setCustomName(Component.literal(name));
        fakePlayer.setCustomNameVisible(true);

        // CRITICAL: Set the tab list display name to the full name.
        // In Minecraft, the player's name shown above their head and in
        // the tab list comes from the PlayerList entry, NOT from
        // setCustomName(). The PlayerList entry is initialized from the
        // GameProfile, which carries the sanitized (16-char max) name.
        // Without this, the client shows "deepseek_v4_flas" (truncated)
        // instead of "deepseek-v4-flash" (full) above the head.
        // Use reflection to set the "tabListName" field on Player, since
        // the setter method name varies between mappings.
        setTabListDisplay(fakePlayer, name);

        // (Companion UUID was registered above, before FakePlayerFactory.create,
        // so mixins can identify this player as a companion during creation.)

        // Create the agent loop
        AgentLoop loop = new AgentLoop(companion, providerId, apiKey, model,
                baseUrl, temperature, config.llm().maxTokens(), reasoningEffort);
        initializingLoop = loop;

        // Create instinct chains
        List<TaskChain> chains = new ArrayList<>();
        for (BrainChains.Entry entry : BrainChains.entries()) {
            chains.add(entry.factory().apply(companion));
        }

        // Create the priority auction
        PriorityAuction auction = new PriorityAuction(companion, chains, loop);

        // Create the lifecycle handler
        CompanionLifecycleHandler lifecycle = new CompanionLifecycleHandler(
                companion, loop, chains);

        // Store state
        CompanionState state = new CompanionState(companion, loop, auction, lifecycle);
        COMPANIONS.put(companion.companionId(), state);
        // Track in multi-companion list
        OWNER_TO_ALL_COMPANIONS.computeIfAbsent(owner.getUUID(),
                k -> Collections.synchronizedList(new ArrayList<>())).add(companion.companionId());
        // Primary companion = first one spawned (for backward compatibility)
        OWNER_TO_COMPANION.putIfAbsent(owner.getUUID(), companion.companionId());
        // Per-companion mode (independent free/follow control)
        COMPANION_MODES.put(companion.companionId(), CompanionMode.FREE);

        // Fire the spawn lifecycle event
        lifecycle.onSpawn(companion);

        // CRITICAL: Broadcast updated player info to all clients so they
        // see the full display name (not the truncated 16-char GameProfile
        // name) in the tab list and above the companion's head.
        // This must happen AFTER setTabListName() and AFTER the player is
        // registered with the server (which happens in FakePlayerFactory.create).
        FakePlayerFactory.refreshPlayerInfo(fakePlayer);

        // Send the authoritative companion ID for both fresh spawns and
        // restores. The old restore early-return skipped this packet, forcing
        // the client to guess that every other online player was a companion.
        com.mineagent.engine.network.MineAgentNetwork.sendUiActionTo(
                // companionId routes C2S actions, but the rendered Player
                // entity carries the fake GameProfile UUID. Send that second
                // identity as data so the client can match the actual entity.
                owner, companion.companionId(), "companion_spawned",
                fakePlayer.getUUID().toString());

        // ── Skin loading & persistence ──
        // In restore mode (isRestore=true) the caller (onPlayerJoin) has already
        // applied the saved skin to the GameProfile, so we must NOT trigger
        // applySkinAsync (it would overwrite the restored skin — problem 2) and
        // must NOT persistCompanion (the in-memory skin cache is still empty
        // here, which would overwrite the on-disk skinValue with null —
        // problem 1). The disk record already exists; it is re-persisted later
        // on shutdown/leave/rename/seteffort.
        if (isRestore) {
            System.out.println("[MineAgent] Companion '" + name + "' restored for "
                    + owner.getName().getString());
            return companion;
        }

        // Fresh spawn: apply the config skin (if any) and persist.
        // IMPORTANT: persistCompanion must run AFTER the skin is cached, otherwise
        // the saved skinValue would be null (problem 6). We use the 3-arg
        // applySkinAsync overload whose completion callback runs on the server
        // thread in BOTH the skin-found and skin-not-found cases, and trigger
        // persistCompanion from there. When no skin is configured, persist now.
        String skinName = config.companion().skinName();
        if (skinName != null && !skinName.isBlank()) {
            System.out.println("[MineAgent] Loading skin '" + skinName + "' for companion '" + name + "'...");
            FakePlayerFactory.applySkinAsync(fakePlayer, skinName, () -> persistCompanion(state));
        } else {
            System.out.println("[MineAgent] Companion '" + name + "' using default Steve/Alex skin");
            persistCompanion(state);
        }

        System.out.println("[MineAgent] Companion '" + name + "' spawned for "
                + owner.getName().getString());

        return companion;
        } catch (RuntimeException initializationError) {
            // FakePlayerFactory has already registered the body at this point.
            // Any later constructor failure must unwind the body, loop and all
            // per-companion caches, otherwise an invisible/brain-dead player
            // remains in PlayerList and continues affecting world simulation.
            CompanionState registered = COMPANIONS.values().stream()
                    .filter(s -> s.companion.serverPlayer() == fakePlayer)
                    .findFirst().orElse(null);
            if (registered != null) {
                despawnCompanion(registered.companion.companionId(), false);
            } else {
                if (initializingLoop != null) initializingLoop.shutdown();
                if (initializingCompanion != null) {
                    TaskContext.removeCaches(initializingCompanion);
                    SurvivalBuiltin.remove(initializingCompanion);
                    for (var reflex : ReflexRegistry.all()) {
                        reflex.forget(initializingCompanion);
                    }
                }
                FakePlayerFactory.unregister(owner.getServer(), fakePlayer);
            }
            COMPANION_PLAYER_UUIDS.remove(profile.getId());
            owner.sendSystemMessage(Component.literal(
                    "§c[MineAgent] Failed to initialize companion: "
                            + initializationError.getMessage()));
            System.err.println("[MineAgent] Companion initialization failed: "
                    + initializationError);
            return null;
        }
    }

    /**
     * Remove a companion by its ID.
     *
     * @param removeFromDisk if true, also removes the companion's saved state
     *                       from disk so it won't be restored on restart
     */
    public static void despawnCompanion(UUID companionId, boolean removeFromDisk) {
        CompanionState state = COMPANIONS.remove(companionId);
        if (state != null) {
            // Interrupt while the ServerPlayer and game mode are still valid.
            // This releases navigation, progressive mining and held item use
            // before the fake connection/entity is unregistered.
            state.auction.cancelTask();

            // Fire the despawn lifecycle event
            state.lifecycle.onDespawn(state.companion);

            // Unregister the fake player from the server
            FakePlayerFactory.unregister(
                    state.companion.serverPlayer().getServer(),
                    state.companion.serverPlayer());

            // Remove from the multi-companion list
            // The owner can already be absent during disconnect teardown.
            // CompanionEntity retains the stable UUID independently.
            UUID ownerUuid = state.companion.ownerUuid();
            List<UUID> list = OWNER_TO_ALL_COMPANIONS.get(ownerUuid);
            if (list != null) {
                list.remove(companionId);
                if (list.isEmpty()) {
                    OWNER_TO_ALL_COMPANIONS.remove(ownerUuid);
                    // No companions left — clear primary mapping
                    OWNER_TO_COMPANION.remove(ownerUuid);
                } else {
                    // If the removed one was primary, promote the next
                    if (companionId.equals(OWNER_TO_COMPANION.get(ownerUuid))) {
                        OWNER_TO_COMPANION.put(ownerUuid, list.get(0));
                    }
                }
            } else {
                OWNER_TO_COMPANION.remove(ownerUuid);
            }

            // Remove per-companion mode
            COMPANION_MODES.remove(companionId);

            // Remove from companion UUID set
            COMPANION_PLAYER_UUIDS.remove(state.companion.serverPlayer().getUUID());
            com.mineagent.tools.management.TodowriteTool.forget(companionId);

            // Remove from disk if requested
            if (removeFromDisk) {
                // COMPANIONS no longer contains this state at this point. Pass
                // the already-known name directly: null means "remove all for
                // this owner" in CompanionStore and previously erased siblings.
                unpersistCompanion(ownerUuid,
                        state.companion.companionName());
            }

            // Notify the owner's client UI (chat screen, status panel)
            var owner = state.companion.serverPlayerOwner();
            if (owner != null) {
                com.mineagent.engine.network.MineAgentNetwork.sendUiActionTo(
                        owner, companionId, "companion_despawned",
                        state.companion.companionName());
            }

            System.out.println("[MineAgent] Companion despawned: " + companionId
                    + (removeFromDisk ? " (removed from disk)" : ""));
        }
    }

    /** Backward-compatible despawn without removing from disk. */
    public static void despawnCompanion(UUID companionId) {
        despawnCompanion(companionId, false);
    }

    /**
     * Get all companion IDs owned by the given player (max 3).
     */
    public static List<UUID> getCompanionIdsByOwner(UUID ownerUuid) {
        List<UUID> ids = OWNER_TO_ALL_COMPANIONS.get(ownerUuid);
        if (ids == null) return Collections.emptyList();
        // Collections.synchronizedList requires callers to hold its monitor
        // while iterating/copying; AgentLoop broadcasts run off-thread.
        synchronized (ids) {
            return new ArrayList<>(ids);
        }
    }

    /**
     * Get all companion states owned by the given player.
     */
    public static List<CompanionState> getCompanionsByOwner(UUID ownerUuid) {
        List<CompanionState> result = new ArrayList<>();
        for (UUID cid : getCompanionIdsByOwner(ownerUuid)) {
            CompanionState s = COMPANIONS.get(cid);
            if (s != null) result.add(s);
        }
        return result;
    }

    /**
     * Find a companion by name (case-insensitive) among an owner's companions.
     */
    public static Optional<CompanionState> getCompanionByName(UUID ownerUuid, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        for (CompanionState s : getCompanionsByOwner(ownerUuid)) {
            if (name.equalsIgnoreCase(s.companion.companionName())) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /**
     * Remove a companion owned by the given player.
     * This permanently removes the companion and its saved state.
     *
     * @return true if a companion was removed
     */
    public static boolean removeCompanionByOwner(UUID ownerUuid) {
        List<UUID> ids = getCompanionIdsByOwner(ownerUuid);
        if (ids.isEmpty()) {
            // Also remove from disk in case the companion wasn't loaded
            unpersistCompanion(ownerUuid, null);
            return false;
        }
        // Remove all companions belonging to this owner
        boolean any = false;
        for (UUID id : ids) {
            despawnCompanion(id, true);
            any = true;
        }
        return any;
    }

    /**
     * Get a companion's state by ID.
     */
    public static Optional<CompanionState> getCompanion(UUID companionId) {
        return Optional.ofNullable(COMPANIONS.get(companionId));
    }

    /**
     * Get the companion state for a given owner.
     */
    public static Optional<CompanionState> getCompanionByOwner(UUID ownerUuid) {
        UUID companionId = OWNER_TO_COMPANION.get(ownerUuid);
        if (companionId == null) return Optional.empty();
        return Optional.ofNullable(COMPANIONS.get(companionId));
    }

    /**
     * Get all active companions.
     */
    public static Collection<CompanionState> allCompanions() {
        return Collections.unmodifiableCollection(COMPANIONS.values());
    }

    // ── Server tick ─────────────────────────────────────────────────

    /**
     * Called every server tick to drive the priority auction and process
     * pending tasks. Also handles companion death revival and night skipping.
     */
    public static void onServerTick(net.minecraft.server.MinecraftServer server) {
        // Drain pending tasks
        for (var pending : CompanionTickDispatcher.drain()) {
            var state = COMPANIONS.get(pending.player().companionId());
            if (state != null) {
                try {
                    // TaskRecord defaults its absolute deadline to zero. Tools
                    // dispatch from the asynchronous LLM thread and cannot read
                    // world time safely, so initialize the deadline here on the
                    // server thread. Otherwise every non-lease task immediately
                    // satisfies gameTime >= 0 and fails on its first tick.
                    if (pending.record().deadline() <= 0L) {
                        pending.record().extendDeadlineTo(
                                state.companion.serverPlayer().level().getGameTime() + 6000L);
                    }
                    var task = CompanionTaskFactory.create(pending.player(), pending.record());
                    state.auction.submitTask(task);
                } catch (Throwable t) {
                    // A single bad task record (e.g. unregistered type,
                    // factory throwing) must NOT crash the entire tick loop —
                    // that would freeze every other companion too. Log and
                    // continue so the server keeps running.
                    System.err.println("[MineAgent] Failed to create task for "
                            + "companion " + pending.player().companionId()
                            + " (record=" + pending.record().getClass().getSimpleName()
                            + "): " + t.getClass().getSimpleName()
                            + " - " + t.getMessage());
                    // The async tool already returned task_id. Publish a real
                    // terminal state so task_status and the LLM do not wait
                    // forever when factory creation or task.start() fails.
                    String taskId = pending.record().toolCallId();
                    String message = "Task dispatch failed with "
                            + t.getClass().getSimpleName() + ": "
                            + String.valueOf(t.getMessage());
                    TaskStatusTool.updateTaskInfo(pending.player().companionId(),
                            taskId, pending.record().getClass().getSimpleName(),
                            TaskState.FAILED, message, null, 0L);
                    state.loop.onBodyLog("[TASK_FINISHED] task_id=" + taskId
                            + " state=FAILED message=" + message);
                }
            } else {
                // A task may have been queued immediately before despawn. It
                // can no longer run, but its acknowledged ID still needs a
                // deterministic terminal ledger entry.
                TaskStatusTool.updateTaskInfo(pending.player().companionId(),
                        pending.record().toolCallId(),
                        pending.record().getClass().getSimpleName(),
                        TaskState.CANCELLED, "Companion is no longer active",
                        null, 0L);
            }
        }

        // Incremental perception jobs are world-thread operations just like
        // tasks, but do not own the companion body. Each job enforces its own
        // per-tick budget; the engine only handles lifetime and failures.
        ACTIVE_TICK_WORK.addAll(CompanionTickDispatcher.drainWork());
        for (Iterator<CompanionTickDispatcher.PendingWork> iterator =
                ACTIVE_TICK_WORK.iterator(); iterator.hasNext();) {
            var pending = iterator.next();
            if (!COMPANIONS.containsKey(pending.player().companionId())) {
                try {
                    pending.work().onDiscarded();
                } catch (RuntimeException ignored) {
                    // The companion is already gone; no live caller remains.
                }
                iterator.remove();
                continue;
            }
            try {
                if (pending.work().tick()) iterator.remove();
            } catch (Throwable failure) {
                try {
                    pending.work().onFailure(failure);
                } catch (RuntimeException callbackFailure) {
                    failure.addSuppressed(callbackFailure);
                }
                System.err.println("[MineAgent] Incremental work failed for companion "
                        + pending.player().companionId() + ": " + failure);
                iterator.remove();
            }
        }

        // Run priority auction and flush body logs for each companion
        for (CompanionState state : COMPANIONS.values()) {
            var sp = state.companion.serverPlayer();

            // Detect death before another task tick can mutate inputs/world.
            // Cancel the current task so respawn cannot resume stale work.
            if (sp.getHealth() <= 0f && !state.lifecycle.isDead()) {
                state.auction.cancelTask();
                state.lifecycle.onDeath(state.companion);
            }

            if (!state.lifecycle.isDead() && !state.lifecycle.isPaused()) {
                state.auction.tick();
            }

            // ── CRITICAL FIX: Explicitly tick the fake player's connection ──
            //
            // Root cause of "AI doesn't move": In 1.21.x, ServerGamePacketListenerImpl.tick()
            // is NOT called by PlayerList.tickAllPlayers(). It is called by:
            //   Connection.tick() ← ServerConnectionListener.tick()
            //   ← MinecraftServer.tickConnection()
            //
            // ServerConnectionListener iterates its internal `connections` list,
            // which only contains real network connections. Our FakeConnection is
            // manually created and never registered to ServerConnectionListener,
            // so FakePlayerNetworkHandler.tick() was NEVER called.
            //
            // Without connection.tick(), player.tick() is never invoked, which
            // means LivingEntity.tick() → aiStep() → travel() never runs, so
            // movement physics (reading zza/xxa set by the pathing system) are
            // never applied. The companion stands still forever regardless of
            // what inputs the pathing system sets.
            //
            // Fix: explicitly call connection.tick() here, AFTER auction.tick()
            // (which sets the movement inputs via MoveToTask → PlayerNav →
            // PathExecutor → ExecHarness → CompanionInputDriver.setForward).
            // This ensures inputs are set first, then physics are applied in
            // the same tick — zero-latency movement.
            //
            // We call connection.tick() instead of player.tick() directly so
            // that FakePlayerNetworkHandler's keep-alive logic also runs.
            try {
                // Do not advance vanilla's death timer. ServerPlayer.doTick()
                // eventually removes a dead entity, while this mod revives the
                // same body in place. Paused-but-alive bodies still need normal
                // physics, hunger and damage ticks.
                if (!state.lifecycle.isDead()) sp.connection.tick();
            } catch (Throwable t) {
                // A single companion's connection tick failure must NOT crash
                // the entire server tick loop — log and continue.
                System.err.println("[MineAgent] Connection tick failed for companion "
                        + state.companion.companionId() + ": "
                        + t.getClass().getSimpleName() + " - " + t.getMessage());
            }

            // Flush body log messages to the agent loop inbox (M10 fix)
            CompanionBodyLog bodyLog = SurvivalBuiltin.bodyLog(state.companion);
            if (bodyLog != null) {
                bodyLog.flush(state.loop);
            }

            // Check if companion just died → fire the death lifecycle event.
            //
            // IMPORTANT: we use our own state.lifecycle.isDead() flag here,
            // NOT vanilla's sp.isDeadOrDying(). The previous condition
            //   (sp.getHealth() <= 0f && !sp.isDeadOrDying())
            // was a contradiction: by the time health <= 0 the vanilla flag
            // is already true, so the block NEVER executed. As a result
            // lifecycle.onDeath() had zero callers, `dead` stayed false
            // forever, and /mineagent respawn was always rejected with
            // "Your companion is not dead." (problems 1 & 2).
            //
            // onDeath() marks the companion as dead, pauses the agent loop,
            // and notifies the owner to use /mineagent respawn. The actual
            // revival is performed by onRespawn() (invoked from the
            // /mineagent respawn command), which clears negative effects,
            // restores health/food, and — in FOLLOW mode — teleports the
            // companion to a safe spot near the owner. A deathProcessed
            // guard inside onDeath() makes this call idempotent so repeated
            // ticks at 0 HP do not fire the event multiple times.
            if (sp.getHealth() <= 0f && !state.lifecycle.isDead()) {
                state.auction.cancelTask();
                state.lifecycle.onDeath(state.companion);
            }
        }

        // Night skip logic: if all human players are sleeping, skip to morning
        skipNightIfAllHumansSleeping(server);
    }

    private static void discardIncrementalWork() {
        ACTIVE_TICK_WORK.addAll(CompanionTickDispatcher.drainWork());
        for (var pending : ACTIVE_TICK_WORK) {
            try {
                pending.work().onDiscarded();
            } catch (RuntimeException ignored) {
                // Shutdown cannot recover a caller whose loop is also closing.
            }
        }
        ACTIVE_TICK_WORK.clear();
    }

    /**
     * Skip the night when all online human players are in bed.
     * Companions (fake players) are excluded from this check.
     */
    private static void skipNightIfAllHumansSleeping(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;

        // Find overworld level and check time
        long dayTime = -1;
        net.minecraft.server.level.ServerLevel overworld = null;
        for (var level : server.getAllLevels()) {
            if (level.dimensionType().bedWorks()) {
                dayTime = level.getDayTime() % 24000;
                overworld = level;
                break;
            }
        }
        if (overworld == null) return;
        if (dayTime < 12541 || dayTime > 23460) return; // Not night

        // Check all human players (non-companions)
        var players = server.getPlayerList().getPlayers();
        boolean anyHuman = false;
        boolean allHumansSleeping = true;

        for (var p : players) {
            if (COMPANION_PLAYER_UUIDS.contains(p.getUUID())) {
                continue; // Skip companions
            }
            anyHuman = true;
            if (!p.isSleeping()) {
                allHumansSleeping = false;
                break;
            }
        }

        if (anyHuman && allHumansSleeping) {
            // All humans sleeping — skip to morning
            overworld.setDayTime(0);
            for (var p : players) {
                p.stopSleeping();
                p.sendSystemMessage(Component.literal(
                        "§e[MineAgent] Good morning! (Night skipped)"));
            }
        }
    }

    // ── Death handling ─────────────────────────────────────────────

    /**
     * Called when a human player dies.
     * Teleports their companion to the player's respawn point.
     */
    public static void onOwnerDeath(ServerPlayer deadPlayer) {
        List<UUID> companionIds = getCompanionIdsByOwner(deadPlayer.getUUID());
        if (companionIds.isEmpty()) return;

        // A bed/anchor can be in another dimension. Using the death dimension
        // with that coordinate silently moved companions to an unrelated place.
        var server = deadPlayer.getServer();
        if (server == null) return;
        var level = server.getLevel(deadPlayer.getRespawnDimension());
        if (level == null) level = server.overworld();
        var respawnPos = deadPlayer.getRespawnPosition();
        var destination = respawnPos != null ? respawnPos : level.getSharedSpawnPos();

        int moved = 0;
        for (UUID companionId : companionIds) {
            CompanionState state = COMPANIONS.get(companionId);
            if (state != null && com.mineagent.engine.entity.SafeTeleport.near(
                    state.companion.serverPlayer(), level, destination, 0f, 0f)) {
                moved++;
            }
        }

        deadPlayer.sendSystemMessage(Component.literal(
                "§7[MineAgent] " + moved
                        + " companion(s) sent to your respawn point."));
    }

    // ── Companion mode ────────────────────────────────────────────

    /**
     * Get the current mode of a companion.
     * Default is FREE (autonomous).
     */
    public static CompanionMode getCompanionMode(UUID companionId) {
        return COMPANION_MODES.getOrDefault(companionId, CompanionMode.FREE);
    }

    /**
     * Set the companion mode for a specific companion.
     */
    public static void setCompanionMode(UUID companionId, CompanionMode mode) {
        COMPANION_MODES.put(companionId, mode);
        System.out.println("[MineAgent] Companion mode set to " + mode + " for " + companionId);
    }

    /**
     * Toggle between FOLLOW and FREE modes.
     * @return the new mode
     */
    public static CompanionMode toggleCompanionMode(UUID companionId) {
        CompanionMode current = getCompanionMode(companionId);
        CompanionMode newMode = (current == CompanionMode.FOLLOW) ? CompanionMode.FREE : CompanionMode.FOLLOW;
        setCompanionMode(companionId, newMode);
        return newMode;
    }

    /**
     * Apply a mode change to a companion: set the mode, notify the owner,
     * and teleport the companion to the owner if switching to FOLLOW from
     * a distance. Shared by the /mineagent mode command branches.
     *
     * @return 1 on success
     */
    private static int applyModeChange(CommandSourceStack source, ServerPlayer owner,
                                       CompanionState state, CompanionMode newMode) {
        setCompanionMode(state.companion.companionId(), newMode);
        String modeDesc = newMode == CompanionMode.FOLLOW
                ? "§bFOLLOW §7(Stay close to you)"
                : "§aFREE §7(Autonomous, can roam)";
        source.sendSuccess(() -> Component.literal(
                "§6[MineAgent] " + state.companion.companionName() + " → " + modeDesc), false);

        // If switching to FOLLOW mode and companion is very far, teleport it
        // to a point 3 blocks away from the owner (not on top of the owner)
        if (newMode == CompanionMode.FOLLOW) {
            var sp = state.companion.serverPlayer();
            double distance = Math.sqrt(sp.distanceToSqr(owner));
            if (distance > 48.0) {
                if (com.mineagent.engine.entity.SafeTeleport.beside(sp, owner)) {
                    source.sendSuccess(() -> Component.literal(
                            "§b[MineAgent] Companion was far away — teleported near you!"), false);
                } else {
                    source.sendFailure(Component.literal(
                            "§c[MineAgent] No safe loaded position exists near you."));
                }
            }
        }
        return 1;
    }

    // ── Rename companion ──────────────────────────────────────────

    /**
     * Rename a companion using a name tag.
     * Only changes the display name — skin, model config, and all other
     * settings remain unchanged.
     *
     * <p>NOTE: {@code companionUuid} here is the companion's <b>ServerPlayer
     * UUID</b> (the offline GameProfile id), NOT the random CompanionEntity
     * companionId used as the COMPANIONS map key. We must scan for a match —
     * a direct map lookup always misses (previous bug: rename never worked).
     *
     * @param companionUuid the companion's ServerPlayer UUID
     * @param newName       the new display name
     * @return true if renamed successfully
     */
    public static boolean renameCompanion(UUID companionUuid, String newName) {
        if (!isValidDisplayName(newName)) return false;

        // COMPANIONS is keyed by random companionId, but callers pass the
        // ServerPlayer's GameProfile UUID — scan for the matching companion.
        CompanionState state = null;
        for (CompanionState s : COMPANIONS.values()) {
            if (s.companion.serverPlayer().getUUID().equals(companionUuid)) {
                state = s;
                break;
            }
        }
        if (state == null) return false;

        // Persistence and memory directories are keyed by owner + display
        // name. Allowing two siblings to share a case-insensitive name causes
        // one save/memory set to overwrite the other.
        for (CompanionState sibling : getCompanionsByOwner(state.companion.ownerUuid())) {
            if (sibling != state
                    && newName.equalsIgnoreCase(sibling.companion.companionName())) {
                return false;
            }
        }

        String oldName = state.companion.companionName();
        var sp = state.companion.serverPlayer();

        // Update the entity's display name (drives companionName() used
        // by persistence and memory-storage keys)
        state.companion.rename(newName);

        // Update the custom name (visible above head)
        sp.setCustomName(net.minecraft.network.chat.Component.literal(newName));
        sp.setCustomNameVisible(true);

        // CRITICAL: Update the tab list name too — the player's name
        // shown above the head comes from the tab list entry, not
        // from setCustomName() for player entities.
        setTabListDisplay(sp, newName);

        // Refresh for all viewers so they see the new name
        FakePlayerFactory.refreshPlayerInfo(sp);

        // Persist under the NEW name: remove the old-name record first
        // (upsert key is ownerUuid+name, so a plain save would duplicate)
        if (worldDataDir != null) {
            com.mineagent.engine.entity.CompanionStore.remove(
                    worldDataDir, state.companion.ownerUuid().toString(), oldName);
        }
        persistCompanion(state);

        // Migrate the memory directory so the companion keeps its memories
        state.loop.migrateMemoryStorage(newName);

        System.out.println("[MineAgent] Companion renamed from '" + oldName
                + "' to '" + newName + "'");
        return true;
    }

    // ── Client tick ────────────────────────────────────────────────

    /**
     * Called every client tick to drive client-side periodic tasks.
     * Currently a no-op placeholder; client-side features (path debug
     * rendering, HUD overlay) are driven from the render thread instead.
     */
    public static void onClientTick() {
        // Client-side tasks are driven from the render thread via event handlers.
        // This hook exists for future client-tick-driven features.
    }

    // ── Player chat ────────────────────────────────────────────────

    /**
     * Called when a player sends a chat message.
     * Forwards the message to the player's companion's agent loop.
     *
     * @param sender  the player who sent the message
     * @param message the chat message content
     */
    public static void onPlayerChat(ServerPlayer sender, String message) {
        List<CompanionState> all = getCompanionsByOwner(sender.getUUID());
        if (all.isEmpty()) return;

        // ── @mention routing ──
        // "@<name> <msg>"  → route to the named companion only
        // "@all <msg>"     → broadcast to ALL companions
        // "@<msg>"         → route to PRIMARY companion (the @ is just a prefix)
        // "<msg>" (no @)   → route to the PRIMARY companion only
        String trimmed = message.trim();
        String ownerName = sender.getName().getString();

        if (trimmed.startsWith("@")) {
            // Find the first space to split @target and the rest
            int spaceIdx = trimmed.indexOf(' ');
            if (spaceIdx > 0) {
                String target = trimmed.substring(1, spaceIdx).trim();
                String rest = trimmed.substring(spaceIdx + 1).trim();
                String prefixed = "[" + ownerName + "]: " + rest;

                if ("all".equalsIgnoreCase(target)) {
                    // Broadcast to every companion
                    for (CompanionState s : all) {
                        s.loop.onOwnerMessage(prefixed);
                    }
                    return;
                }
                // Find the named companion
                for (CompanionState s : all) {
                    if (target.equalsIgnoreCase(s.companion.companionName())) {
                        s.loop.onOwnerMessage(prefixed);
                        return;
                    }
                }
                // No matching companion name — inform the player
                sender.sendSystemMessage(Component.literal(
                        "§c[MineAgent] No companion named '" + target + "'. Available: "
                                + all.stream().map(s -> s.companion.companionName())
                                        .reduce((a, b) -> a + ", " + b).orElse("none")));
                return;
            }
            // "@name" with no space — treat the whole thing as a message
            // to the primary companion (e.g. "@去砍树" → primary hears "去砍树")
            CompanionState primary = all.get(0);
            primary.loop.onOwnerMessage("[" + ownerName + "]: " + trimmed.substring(1));
            return;
        }

        // No @mention → primary companion
        CompanionState primary = all.get(0);
        primary.loop.onOwnerMessage("[" + ownerName + "]: " + message);
    }

    /**
     * Broadcast a companion's spoken message to the player's OTHER companions
     * so they can hear each other and collaborate. Used for AI-to-AI chat.
     *
     * <p>The message is delivered as a user message tagged with the speaker's
     * name. To prevent infinite loops, this is only called for messages
     * spoken by a companion in response to the owner — not for messages
     * received from other companions.
     *
     * @param speakerId the companion that spoke
     * @param ownerUuid the owner's UUID
     * @param text      what the speaker said
     */
    public static void broadcastToOtherCompanions(UUID speakerId, UUID ownerUuid, String text) {
        CompanionState speaker = COMPANIONS.get(speakerId);
        if (speaker == null || !speaker.companion.ownerUuid().equals(ownerUuid)) return;
        String speakerName = speaker.companion.companionName();
        for (CompanionState s : getCompanionsByOwner(ownerUuid)) {
            if (!s.companion.companionId().equals(speakerId)) {
                // Use the actual speaker's name. The old code inserted each
                // recipient's own name, making every AI believe it said the
                // message to itself and corrupting multi-agent coordination.
                s.loop.onOwnerMessage("[同伴 " + speakerName + " 的发言]: " + text);
            }
        }
    }

    // ── Player join/leave ──────────────────────────────────────────

    /**
     * Called when a player joins the server.
     * Automatically restores their companion if one was saved.
     */
    public static void onPlayerJoin(ServerPlayer player) {
        System.out.println("[MineAgent] Player joined: " + player.getName().getString());

        // Check if this player has a saved companion
        if (worldDataDir == null) return;

        var savedList = com.mineagent.engine.entity.CompanionStore.loadAll(worldDataDir);
        for (var saved : savedList) {
            if (saved.ownerUuid().equals(player.getUUID().toString())) {
                // Found a saved companion - restore it
                System.out.println("[MineAgent] Restoring companion '"
                        + saved.companionName() + "' for " + player.getName().getString());

                // Restore on the server thread (we should already be on it)
                String baseUrl = saved.baseUrl();
                if (baseUrl == null || baseUrl.isEmpty()) baseUrl = null;

                // Restore the companion. isRestore=true tells spawnCompanion to
                // skip applySkinAsync (the saved skin is applied below) and to
                // skip persistCompanion (the disk record already exists and the
                // in-memory skin cache is still empty — persisting now would
                // overwrite the on-disk skinValue with null).
                CompanionEntity companion = spawnCompanion(
                        player,
                        saved.companionName(),
                        saved.providerId(),
                        saved.apiKey(),
                        saved.model(),
                        baseUrl,
                        saved.temperature(),
                        saved.reasoningEffort(),
                        true
                );

                // spawnCompanion may return null (e.g. companion cap reached or
                // a duplicate model/name). Only apply the skin and notify when it
                // actually spawned (problem 4 fix: previously "has been restored!"
                // was sent even on failure).
                if (companion != null) {
                    // Apply cached skin if available (no network request needed!)
                    if (saved.skinValue() != null && !saved.skinValue().isEmpty()) {
                        var sp = companion.serverPlayer();
                        var profile = sp.getGameProfile();
                        profile.getProperties().removeAll("textures");
                        var prop = saved.skinSignature() != null
                                ? new com.mojang.authlib.properties.Property("textures",
                                        saved.skinValue(), saved.skinSignature())
                                : new com.mojang.authlib.properties.Property("textures",
                                        saved.skinValue());
                        profile.getProperties().put("textures", prop);

                        // Backfill the in-memory skin cache so later
                        // persistCompanion calls (shutdown/leave/rename) save
                        // the real skin instead of null (problem 3 fix).
                        com.mineagent.engine.entity.fakeplayer.SkinLoader.cacheSkin(
                                sp.getUUID(),
                                new com.mineagent.engine.entity.fakeplayer.SkinLoader.SkinResult(
                                        saved.skinValue(), saved.skinSignature()));

                        // Refresh for all viewers
                        FakePlayerFactory.refreshPlayerInfo(sp);

                        System.out.println("[MineAgent] Skin restored from cache for '"
                                + saved.companionName() + "'");
                    }

                    // Notify the player — only when restore actually succeeded.
                    player.sendSystemMessage(Component.literal(
                            "§a[MineAgent] Your companion '" + saved.companionName()
                                    + "' has been restored!"));
                }
            }
        }
    }

    /**
     * Called when a player leaves the server.
     * Saves the companion state to disk (but doesn't remove it)
     * so it can be restored when the player returns.
     */
    public static void onPlayerLeave(ServerPlayer player) {
        // Save and despawn ALL companions belonging to this owner (multi-AI)
        List<UUID> companionIds = getCompanionIdsByOwner(player.getUUID());
        if (!companionIds.isEmpty()) {
            for (UUID companionId : companionIds) {
                Optional<CompanionState> stateOpt = getCompanion(companionId);
                if (stateOpt.isPresent()) {
                    // Save the companion state to disk before despawning
                    persistCompanion(stateOpt.get());
                }
                // Despawn from memory (but keep on disk)
                despawnCompanion(companionId, false);
            }
            System.out.println("[MineAgent] " + companionIds.size()
                    + " companion(s) saved and despawned for owner leaving: "
                    + player.getName().getString());
        }
    }

    // ── Commands ───────────────────────────────────────────────────

    /**
     * Register the /mineagent command tree with the given dispatcher.
     * Called by the platform modules during command registration.
     *
     * <p>Command structure:
     * <pre>
     *   /mineagent help                          - Show all commands
     *   /mineagent quick [name]                  - Quick spawn using config file settings
     *   /mineagent spawn [name] [provider] [model] [apikey] [temp?]
     *   /mineagent remove                        - Remove your companion
     *   /mineagent respawn                        - Respawn dead companion
     *   /mineagent list                          - List all companions
     *   /mineagent providers                     - List all LLM providers
     *   /mineagent models [provider?]           - List models for a provider
     *   /mineagent config                        - Show current config
     *   /mineagent reload                       - Reload config from file
     * </pre>
     *
     * @param dispatcher the Brigadier command dispatcher
     */
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            literal("mineagent")
                // No permission requirement — works in single-player without OP
                .executes(ctx -> {
                    // No sub-command → show help
                    printHelp(ctx.getSource());
                    return 1;
                })
                // ── help ──
                .then(literal("help")
                    .executes(ctx -> {
                        printHelp(ctx.getSource());
                        return 1;
                    })
                )
                // ── quick: quick spawn using config file ──
                .then(literal("quick")
                    .executes(ctx -> spawnQuick(ctx.getSource(), null, null))
                    .then(argument("name", StringArgumentType.string())
                        .executes(ctx -> spawnQuick(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"), null))
                        .then(argument("effort", StringArgumentType.word())
                            .executes(ctx -> spawnQuick(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "name"),
                                    StringArgumentType.getString(ctx, "effort")))
                        )
                    )
                )
                // ── spawn: full spawn with all parameters ──
                .then(literal("spawn")
                    .then(argument("name", StringArgumentType.word())
                        .then(argument("provider", StringArgumentType.word())
                            .then(argument("model", StringArgumentType.word())
                                .then(argument("apikey", StringArgumentType.string())
                                    .executes(ctx -> {
                                        var source = ctx.getSource();
                                        ServerPlayer owner = source.getPlayerOrException();
                                        String name = StringArgumentType.getString(ctx, "name");
                                        String provider = StringArgumentType.getString(ctx, "provider");
                                        String model = StringArgumentType.getString(ctx, "model");
                                        String apikey = StringArgumentType.getString(ctx, "apikey");

                                        CompanionEntity companion = spawnCompanion(
                                                owner, name, provider, apikey, model,
                                                config.llm().baseUrl().isEmpty() ? null : config.llm().baseUrl(),
                                                config.llm().temperature(), null, false);

                                        if (companion != null) {
                                            source.sendSuccess(() -> Component.literal(
                                                    "§a[MineAgent] Companion '" + name + "' spawned!"),
                                                    false);
                                        }
                                        return 1;
                                    })
                                    .then(argument("temperature", DoubleArgumentType.doubleArg(0.0, 2.0))
                                        .executes(ctx -> {
                                            var source = ctx.getSource();
                                            ServerPlayer owner = source.getPlayerOrException();
                                            String name = StringArgumentType.getString(ctx, "name");
                                            String provider = StringArgumentType.getString(ctx, "provider");
                                            String model = StringArgumentType.getString(ctx, "model");
                                            String apikey = StringArgumentType.getString(ctx, "apikey");
                                            double temp = DoubleArgumentType.getDouble(ctx, "temperature");

                                            CompanionEntity companion = spawnCompanion(
                                                    owner, name, provider, apikey, model,
                                                    config.llm().baseUrl().isEmpty() ? null : config.llm().baseUrl(),
                                                    temp, null, false);

                                            if (companion != null) {
                                                source.sendSuccess(() -> Component.literal(
                                                        "§a[MineAgent] Companion '" + name + "' spawned!"),
                                                        false);
                                            }
                                            return 1;
                                        })
                                    )
                                )
                            )
                        )
                    )
                )
                // ── remove ──
                .then(literal("remove")
                    .executes(ctx -> {
                        var source = ctx.getSource();
                        ServerPlayer owner = source.getPlayerOrException();
                        boolean removed = removeCompanionByOwner(owner.getUUID());
                        if (removed) {
                            source.sendSuccess(() -> Component.literal(
                                    "§a[MineAgent] Companion removed."), false);
                        } else {
                            source.sendFailure(Component.literal(
                                    "§c[MineAgent] You don't have a companion."));
                        }
                        return removed ? 1 : 0;
                    })
                )
                // ── respawn ──
                .then(literal("respawn")
                    .executes(ctx -> {
                        var source = ctx.getSource();
                        ServerPlayer owner = source.getPlayerOrException();
                        Optional<CompanionState> stateOpt = getCompanionByOwner(owner.getUUID());
                        if (stateOpt.isEmpty()) {
                            source.sendFailure(Component.literal(
                                    "§c[MineAgent] You don't have a companion."));
                            return 0;
                        }
                        CompanionState state = stateOpt.get();
                        if (!state.lifecycle.isDead()) {
                            source.sendFailure(Component.literal(
                                    "§c[MineAgent] Your companion is not dead."));
                            return 0;
                        }
                        state.lifecycle.onRespawn(state.companion);
                        source.sendSuccess(() -> Component.literal(
                                "§a[MineAgent] Companion respawned!"), false);
                        return 1;
                    })
                )
                // ── list ──
                .then(literal("list")
                    .executes(ctx -> {
                        var source = ctx.getSource();
                        if (COMPANIONS.isEmpty()) {
                            source.sendSuccess(() -> Component.literal(
                                    "§7[MineAgent] No active companions."), false);
                            return 0;
                        }
                        StringBuilder sb = new StringBuilder("§7[MineAgent] Active companions:\n");
                        for (CompanionState state : COMPANIONS.values()) {
                            sb.append("§7  - ").append(state.companion.companionName());
                            sb.append(" (owner: ").append(state.companion.ownerName()).append(")");
                            sb.append(state.lifecycle.isDead() ? " §c[DEAD]" : "");
                            sb.append(state.lifecycle.isPaused() ? " §e[PAUSED]" : "");
                            sb.append("\n");
                        }
                        source.sendSuccess(() -> Component.literal(sb.toString()), false);
                        return COMPANIONS.size();
                    })
                )
                // ── providers: list all LLM providers ──
                .then(literal("providers")
                    .executes(ctx -> {
                        var source = ctx.getSource();
                        var providers = LLMProviderRegistry.all();
                        StringBuilder sb = new StringBuilder("§6[MineAgent] Available LLM Providers:\n");
                        for (var p : providers) {
                            sb.append("§e  - ").append(p.providerId())
                              .append("§7 (").append(p.displayName()).append(")§r\n");
                        }
                        sb.append("\n§7Use §e/mineagent models [provider]§7 to see models.");
                        source.sendSuccess(() -> Component.literal(sb.toString()), false);
                        return providers.size();
                    })
                )
                // ── models: list models for a provider ──
                .then(literal("models")
                    .executes(ctx -> {
                        // No argument → show all providers and their models
                        var source = ctx.getSource();
                        var providers = LLMProviderRegistry.all();
                        StringBuilder sb = new StringBuilder("§6[MineAgent] All Models:\n");
                        for (var p : providers) {
                            sb.append("\n§e").append(p.providerId())
                              .append("§7 (").append(p.displayName()).append("):§r\n");
                            var models = p.defaultModels();
                            for (int i = 0; i < models.size(); i++) {
                                sb.append("  §7").append(models.get(i));
                                if (i < models.size() - 1) sb.append(", ");
                                if ((i + 1) % 3 == 0 && i < models.size() - 1) sb.append("\n");
                            }
                            sb.append("\n");
                        }
                        source.sendSuccess(() -> Component.literal(sb.toString()), false);
                        return 1;
                    })
                    .then(argument("provider", StringArgumentType.word())
                        .executes(ctx -> {
                            var source = ctx.getSource();
                            String providerId = StringArgumentType.getString(ctx, "provider");
                            var providerOpt = LLMProviderRegistry.get(providerId);
                            if (providerOpt.isEmpty()) {
                                source.sendFailure(Component.literal(
                                        "§c[MineAgent] Unknown provider: " + providerId));
                                source.sendFailure(Component.literal(
                                        "§7Use §e/mineagent providers§7 to see available providers."));
                                return 0;
                            }
                            var p = providerOpt.get();
                            StringBuilder sb = new StringBuilder();
                            sb.append("§6[MineAgent] Models for §e").append(p.displayName()).append("§r:\n");
                            var models = p.defaultModels();
                            for (int i = 0; i < models.size(); i++) {
                                sb.append("  §7").append(models.get(i));
                                if (i < models.size() - 1) sb.append("\n");
                            }
                            source.sendSuccess(() -> Component.literal(sb.toString()), false);
                            return 1;
                        })
                    )
                )
                // ── config: show current config ──
                .then(literal("config")
                    .executes(ctx -> {
                        var source = ctx.getSource();
                        StringBuilder sb = new StringBuilder();
                        sb.append("§6[MineAgent] Current Configuration:\n");
                        sb.append("§eLLM:\n");
                        sb.append("  §7Provider: §f").append(config.llm().provider()).append("\n");
                        sb.append("  §7Model: §f").append(config.llm().model()).append("\n");
                        sb.append("  §7API Key: §f").append(config.llm().apiKey().isEmpty()
                                ? "§c(not set)" : "****(hidden)").append("\n");
                        sb.append("  §7Base URL: §f").append(config.llm().baseUrl().isEmpty()
                                ? "(default)" : config.llm().baseUrl()).append("\n");
                        sb.append("  §7Temperature: §f").append(config.llm().temperature()).append("\n");
                        sb.append("  §7Max Tokens: §f").append(config.llm().maxTokens()).append("\n");
                        sb.append("§eCompanion:\n");
                        sb.append("  §7Name: §f").append(config.companion().name()).append("\n");
                        sb.append("  §7Game Mode: §f").append(config.companion().gameMode()).append("\n");
                        sb.append("§7Config file: config/mineagent.json");
                        source.sendSuccess(() -> Component.literal(sb.toString()), false);
                        return 1;
                    })
                )
                // ── mode: set or toggle FOLLOW/FREE ──
                .then(literal("mode")
                    .executes(ctx -> {
                        // /mineagent mode — toggle primary companion
                        var source = ctx.getSource();
                        ServerPlayer owner = source.getPlayerOrException();
                        Optional<CompanionState> stateOpt = getCompanionByOwner(owner.getUUID());
                        if (stateOpt.isEmpty()) {
                            source.sendFailure(Component.literal(
                                    "§c[MineAgent] You don't have a companion!"));
                            return 0;
                        }
                        CompanionMode newMode = toggleCompanionMode(stateOpt.get().companion.companionId());
                        return applyModeChange(source, owner, stateOpt.get(), newMode);
                    })
                    .then(argument("arg1", StringArgumentType.word())
                        .executes(ctx -> {
                            // /mineagent mode <follow|free|name>
                            var source = ctx.getSource();
                            ServerPlayer owner = source.getPlayerOrException();
                            String arg1 = StringArgumentType.getString(ctx, "arg1");

                            if (arg1.equalsIgnoreCase("follow") || arg1.equalsIgnoreCase("free")) {
                                // Set primary companion mode
                                Optional<CompanionState> stateOpt = getCompanionByOwner(owner.getUUID());
                                if (stateOpt.isEmpty()) {
                                    source.sendFailure(Component.literal(
                                            "§c[MineAgent] You don't have a companion!"));
                                    return 0;
                                }
                                CompanionMode newMode = arg1.equalsIgnoreCase("follow")
                                        ? CompanionMode.FOLLOW : CompanionMode.FREE;
                                return applyModeChange(source, owner, stateOpt.get(), newMode);
                            } else {
                                // arg1 is a companion name — toggle that companion
                                Optional<CompanionState> stateOpt = getCompanionByName(owner.getUUID(), arg1);
                                if (stateOpt.isEmpty()) {
                                    source.sendFailure(Component.literal(
                                            "§c[MineAgent] No companion named '" + arg1 + "' found."));
                                    return 0;
                                }
                                CompanionMode newMode = toggleCompanionMode(stateOpt.get().companion.companionId());
                                return applyModeChange(source, owner, stateOpt.get(), newMode);
                            }
                        })
                        .then(argument("arg2", StringArgumentType.word())
                            .executes(ctx -> {
                                // /mineagent mode <name> <follow|free>
                                var source = ctx.getSource();
                                ServerPlayer owner = source.getPlayerOrException();
                                String name = StringArgumentType.getString(ctx, "arg1");
                                String modeArg = StringArgumentType.getString(ctx, "arg2");

                                Optional<CompanionState> stateOpt = getCompanionByName(owner.getUUID(), name);
                                if (stateOpt.isEmpty()) {
                                    source.sendFailure(Component.literal(
                                            "§c[MineAgent] No companion named '" + name + "' found."));
                                    return 0;
                                }
                                CompanionMode newMode;
                                if (modeArg.equalsIgnoreCase("follow")) {
                                    newMode = CompanionMode.FOLLOW;
                                } else if (modeArg.equalsIgnoreCase("free")) {
                                    newMode = CompanionMode.FREE;
                                } else {
                                    source.sendFailure(Component.literal(
                                            "§c[MineAgent] Invalid mode '" + modeArg + "'. Use follow or free."));
                                    return 0;
                                }
                                return applyModeChange(source, owner, stateOpt.get(), newMode);
                            })
                        )
                    )
                )
                // ── locate: get companion's exact position ──
                .then(literal("locate")
                    .executes(ctx -> {
                        var source = ctx.getSource();
                        ServerPlayer owner = source.getPlayerOrException();

                        Optional<CompanionState> stateOpt = getCompanionByOwner(owner.getUUID());
                        if (stateOpt.isEmpty()) {
                            source.sendFailure(Component.literal(
                                    "§c[MineAgent] You don't have a companion!"));
                            return 0;
                        }

                        var sp = stateOpt.get().companion.serverPlayer();
                        int x = sp.getBlockX();
                        int y = sp.getBlockY();
                        int z = sp.getBlockZ();
                        String dim = sp.level().dimension().location().toString();
                        float health = sp.getHealth();
                        int food = sp.getFoodData().getFoodLevel();
                        CompanionMode mode = getCompanionMode(stateOpt.get().companion.companionId());

                        source.sendSuccess(() -> Component.literal(
                                "§6[MineAgent] Companion Location:\n"
                                + "§7  Name: §f" + stateOpt.get().companion.companionName() + "\n"
                                + "§7  Position: §f" + x + ", " + y + ", " + z + "\n"
                                + "§7  Dimension: §f" + dim + "\n"
                                + "§7  Health: §c" + health + "/20\n"
                                + "§7  Food: §e" + food + "/20\n"
                                + "§7  Mode: §f" + mode), false);
                        return 1;
                    })
                )
                // ── tp: teleport to companion's location ──
                .then(literal("tp")
                    .executes(ctx -> {
                        var source = ctx.getSource();
                        ServerPlayer owner = source.getPlayerOrException();

                        Optional<CompanionState> stateOpt = getCompanionByOwner(owner.getUUID());
                        if (stateOpt.isEmpty()) {
                            source.sendFailure(Component.literal(
                                    "§c[MineAgent] You don't have a companion!"));
                            return 0;
                        }

                        var sp = stateOpt.get().companion.serverPlayer();
                        owner.teleportTo(sp.serverLevel(), sp.getX(), sp.getY(), sp.getZ(),
                                sp.getYRot(), sp.getXRot());
                        source.sendSuccess(() -> Component.literal(
                                "§a[MineAgent] Teleported to your companion!"), false);
                        return 1;
                    })
                )
                // ── reload: reload config from file ──
                .then(literal("reload")
                    .executes(ctx -> {
                        var source = ctx.getSource();
                        // Reload config
                        // Use the platform-provided config directory captured
                        // during init; the process working directory is not
                        // guaranteed to be the Minecraft game directory.
                        if (configDirPath != null) config = MineAgentConfig.load(configDirPath);
                        source.sendSuccess(() -> Component.literal(
                                "§a[MineAgent] Configuration reloaded!"), false);
                        source.sendSuccess(() -> Component.literal(
                                "§7Provider: " + config.llm().provider()
                                        + " | Model: " + config.llm().model()), false);
                        return 1;
                    })
                )
                // ── setskin: set companion skin from a player name ──
                .then(literal("setskin")
                    .then(argument("playername", StringArgumentType.word())
                        .executes(ctx -> {
                            var source = ctx.getSource();
                            ServerPlayer owner = source.getPlayerOrException();
                            String skinName = StringArgumentType.getString(ctx, "playername");

                            Optional<CompanionState> stateOpt = getCompanionByOwner(owner.getUUID());
                            if (stateOpt.isEmpty()) {
                                source.sendFailure(Component.literal(
                                        "§c[MineAgent] You don't have a companion!"));
                                return 0;
                            }

                            CompanionState state = stateOpt.get();
                            ServerPlayer companionPlayer = state.companion.serverPlayer();

                            // Default skin keywords: reset to Steve/Alex without API call
                            if (skinName.equalsIgnoreCase("default")
                                    || skinName.equalsIgnoreCase("steve")
                                    || skinName.equalsIgnoreCase("alex")) {
                                companionPlayer.getGameProfile().getProperties().removeAll("textures");
                                // Persisting consults SkinLoader's cache. Evict
                                // the old texture or reset would save it again.
                                com.mineagent.engine.entity.fakeplayer.SkinLoader
                                        .evictCachedSkin(companionPlayer.getUUID());
                                com.mineagent.engine.entity.fakeplayer.FakePlayerFactory
                                        .refreshPlayerInfo(companionPlayer);
                                persistCompanion(state);
                                source.sendSuccess(() -> Component.literal(
                                        "§a[MineAgent] Skin reset to default (" + skinName + ")!"), false);
                                return 1;
                            }

                            if (!skinName.matches("[A-Za-z0-9_]{1,16}")) {
                                source.sendFailure(Component.literal(
                                        "§c[MineAgent] Skin name must be a valid Minecraft username."));
                                return 0;
                            }

                            source.sendSuccess(() -> Component.literal(
                                    "§7[MineAgent] Loading skin from player '" + skinName + "'..."), false);

                            // Load skin asynchronously
                            com.mineagent.engine.entity.fakeplayer.SkinLoader.loadSkinAsync(skinName, skinOpt -> {
                                companionPlayer.server.execute(() -> {
                                    // Ignore a stale response after this exact
                                    // companion was removed/replaced.
                                    if (getCompanion(state.companion.companionId())
                                            .filter(current -> current == state).isEmpty()) return;
                                    if (skinOpt.isEmpty()) {
                                        owner.sendSystemMessage(Component.literal(
                                                "§c[MineAgent] Player '" + skinName
                                                        + "' not found or has no skin."));
                                        return;
                                    }

                                    var skin = skinOpt.get();
                                    com.mineagent.engine.entity.fakeplayer.SkinLoader
                                            .cacheSkin(companionPlayer.getUUID(), skin);
                                    var profile = companionPlayer.getGameProfile();
                                    profile.getProperties().removeAll("textures");
                                    profile.getProperties().put("textures", skin.toProperty());

                                    // Refresh skin for all online viewers
                                    com.mineagent.engine.entity.fakeplayer.FakePlayerFactory
                                            .refreshPlayerInfo(companionPlayer);

                                    owner.sendSystemMessage(Component.literal(
                                            "§a[MineAgent] Skin applied from player '" + skinName + "'!"));

                                    // Persist this exact companion. Looking up
                                    // the owner's primary can update a sibling
                                    // in multi-companion mode.
                                    persistCompanion(state);
                                });
                            });
                            return 1;
                        })
                    )
                )
                // ── resetskin: reset to default Steve/Alex ──
                .then(literal("resetskin")
                    .executes(ctx -> {
                        var source = ctx.getSource();
                        ServerPlayer owner = source.getPlayerOrException();

                        Optional<CompanionState> stateOpt = getCompanionByOwner(owner.getUUID());
                        if (stateOpt.isEmpty()) {
                            source.sendFailure(Component.literal(
                                    "§c[MineAgent] You don't have a companion!"));
                            return 0;
                        }

                        CompanionState state = stateOpt.get();
                        ServerPlayer companionPlayer = state.companion.serverPlayer();

                        // Remove textures property
                        companionPlayer.getGameProfile().getProperties().removeAll("textures");
                        com.mineagent.engine.entity.fakeplayer.SkinLoader
                                .evictCachedSkin(companionPlayer.getUUID());

                        // Refresh for all viewers
                        com.mineagent.engine.entity.fakeplayer.FakePlayerFactory
                                .refreshPlayerInfo(companionPlayer);

                        persistCompanion(state);

                        source.sendSuccess(() -> Component.literal(
                                "§a[MineAgent] Skin reset to default Steve/Alex!"), false);
                        return 1;
                    })
                )
                // ── setconfig: update a config value and persist to file ──
                .then(literal("setconfig")
                    .then(argument("key", StringArgumentType.word())
                        .then(argument("value", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String key = StringArgumentType.getString(ctx, "key")
                                        .toLowerCase(Locale.ROOT);
                                String value = StringArgumentType.getString(ctx, "value");
                                var source = ctx.getSource();

                                var llm = config.llm();
                                var comp = config.companion();
                                try {
                                    switch (key) {
                                        case "provider" -> llm = new MineAgentConfig.LLMConfig(
                                                value, llm.apiKey(), llm.model(),
                                                llm.baseUrl(), llm.temperature(), llm.maxTokens());
                                        case "model" -> llm = new MineAgentConfig.LLMConfig(
                                                llm.provider(), llm.apiKey(), value,
                                                llm.baseUrl(), llm.temperature(), llm.maxTokens());
                                        case "apikey", "api_key" -> llm = new MineAgentConfig.LLMConfig(
                                                llm.provider(), value, llm.model(),
                                                llm.baseUrl(), llm.temperature(), llm.maxTokens());
                                        case "baseurl", "base_url", "url" -> llm = new MineAgentConfig.LLMConfig(
                                                llm.provider(), llm.apiKey(), llm.model(),
                                                value, llm.temperature(), llm.maxTokens());
                                        case "temperature" -> {
                                            double t = Double.parseDouble(value);
                                            if (!Double.isFinite(t) || t < 0.0 || t > 2.0) {
                                                source.sendFailure(Component.literal(
                                                        "§c[MineAgent] Temperature must be from 0 to 2."));
                                                return 0;
                                            }
                                            llm = new MineAgentConfig.LLMConfig(
                                                    llm.provider(), llm.apiKey(), llm.model(),
                                                    llm.baseUrl(), t, llm.maxTokens());
                                        }
                                        case "name" -> comp = new MineAgentConfig.CompanionConfig(
                                                value, comp.gameMode(), comp.skinName(),
                                                comp.instantBreak(), comp.creativeReach());
                                        case "skin", "skinname" -> comp = new MineAgentConfig.CompanionConfig(
                                                comp.name(), comp.gameMode(), value,
                                                comp.instantBreak(), comp.creativeReach());
                                        default -> {
                                            source.sendFailure(Component.literal(
                                                    "§c[MineAgent] Unknown key: " + key));
                                            source.sendFailure(Component.literal(
                                                    "§7Valid: provider, model, apikey, baseurl, temperature, name, skin"));
                                            return 0;
                                        }
                                    }
                                } catch (NumberFormatException e) {
                                    source.sendFailure(Component.literal(
                                            "§c[MineAgent] Invalid number for " + key + ": " + value));
                                    return 0;
                                }

                                config = new MineAgentConfig(llm, comp, config.survival(), config.pathfinding());
                                if (configDirPath != null) config.save(configDirPath);

                                // API keys arrive through a command packet, but must
                                // never be repeated into chat or latest.log. Other
                                // values remain visible so configuration mistakes are
                                // diagnosable without exposing authentication secrets.
                                String displayedValue = key.equals("apikey") || key.equals("api_key")
                                        ? "(hidden)" : value;
                                source.sendSuccess(() -> Component.literal(
                                        "§a[MineAgent] Config updated: §f" + key
                                                + "§a = §f" + displayedValue), false);
                                return 1;
                            })
                        )
                    )
                )
                // ── rename: rename an existing companion (no name tag needed) ──
                .then(literal("rename")
                    .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String newName = StringArgumentType.getString(ctx, "name");
                            var source = ctx.getSource();
                            ServerPlayer owner = source.getPlayerOrException();

                            Optional<CompanionState> stateOpt = getCompanionByOwner(owner.getUUID());
                            if (stateOpt.isEmpty()) {
                                source.sendFailure(Component.literal(
                                        "§c[MineAgent] You don't have a companion!"));
                                return 0;
                            }

                            var state = stateOpt.get();
                            var sp = state.companion.serverPlayer();

                            // Use the shared rename path (updates display name,
                            // tab list, persistence and memory storage). The old
                            // implementation used ServerPlayer.class.getDeclaredField
                            // ("gameProfile"), which always threw NoSuchFieldException
                            // because the field is declared on the Player superclass.
                            boolean ok = renameCompanion(sp.getUUID(), newName);
                            if (!ok) {
                                source.sendFailure(Component.literal(
                                        "§c[MineAgent] Failed to rename companion."));
                                return 0;
                            }

                            source.sendSuccess(() -> Component.literal(
                                    "§a[MineAgent] Companion renamed to '" + newName + "'!"), false);
                            return 1;
                        })
                    )
                )
                // ── seteffort: set a companion's thinking effort level ──
                .then(literal("seteffort")
                    .then(argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String args = StringArgumentType.getString(ctx, "args");
                            var source = ctx.getSource();
                            ServerPlayer owner = source.getPlayerOrException();

                            String[] parts = args.split("\\s+", 2);
                            String companionName;
                            String effort;
                            if (parts.length >= 2) {
                                companionName = parts[0];
                                effort = parts[1].toLowerCase();
                            } else {
                                companionName = null;
                                effort = parts[0].toLowerCase();
                            }

                            // Validate effort value
                            if (!List.of("off", "low", "medium", "high", "xhigh", "max").contains(effort)) {
                                source.sendFailure(Component.literal(
                                        "§c[MineAgent] Invalid effort. Use: off, low, medium, high, xhigh, max"));
                                return 0;
                            }

                            Optional<CompanionState> stateOpt = (companionName != null)
                                    ? getCompanionByName(owner.getUUID(), companionName)
                                    : getCompanionByOwner(owner.getUUID());

                            if (stateOpt.isEmpty()) {
                                source.sendFailure(Component.literal("§c[MineAgent] Companion not found!"));
                                return 0;
                            }

                            var state = stateOpt.get();
                            state.loop.setReasoningEffort(effort);
                            persistCompanion(state);
                            source.sendSuccess(() -> Component.literal(
                                    "§a[MineAgent] Companion '" + state.companion.companionName()
                                            + "' thinking effort set to " + effort), false);
                            return 1;
                        })
                    )
                )
        );
    }

    /** Execute every /mineagent quick form through one atomic spawn path. */
    private static int spawnQuick(CommandSourceStack source, String requestedName,
                                  String reasoningEffort)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        String cfgProvider = config.llm().provider();
        String cfgApiKey = config.llm().apiKey();
        String cfgModel = config.llm().model();
        String rawConfiguredName = config.companion().name();
        String name = requestedName;
        if (name == null || name.isBlank()) {
            name = rawConfiguredName == null || rawConfiguredName.isBlank()
                    || "MineAgent".equals(rawConfiguredName)
                    ? cfgModel : rawConfiguredName;
        }

        String normalizedEffort = reasoningEffort == null
                ? null : reasoningEffort.toLowerCase(Locale.ROOT);
        if (normalizedEffort != null
                && !List.of("off", "low", "medium", "high", "xhigh", "max")
                        .contains(normalizedEffort)) {
            source.sendFailure(Component.literal(
                    "§c[MineAgent] Invalid effort. Use: off, low, medium, high, xhigh, max"));
            return 0;
        }
        if (cfgApiKey == null || cfgApiKey.isBlank()) {
            source.sendFailure(Component.literal(
                    "§c[MineAgent] No API key in config! Edit mineagent.json or use /mineagent spawn."));
            source.sendFailure(Component.literal("§7Config file: config/mineagent.json"));
            return 0;
        }

        CompanionEntity companion = spawnCompanion(
                owner, name, cfgProvider, cfgApiKey, cfgModel,
                config.llm().baseUrl().isEmpty() ? null : config.llm().baseUrl(),
                config.llm().temperature(), normalizedEffort, false);
        if (companion == null) return 0;

        String spawnedName = name;
        source.sendSuccess(() -> Component.literal(
                "§a[MineAgent] Companion '" + spawnedName + "' spawned!"), false);
        source.sendSuccess(() -> Component.literal(
                "§7Provider: " + cfgProvider + " | Model: " + cfgModel), false);
        return 1;
    }

    /**
     * Sanitize a model name into a valid Minecraft player name.
     * Minecraft names allow [A-Za-z0-9_], max 16 chars. We replace
     * disallowed characters (dots, dashes, slashes) with underscores.
     */
    private static String sanitizeName(String modelName) {
        if (modelName == null || modelName.isBlank()) return "Companion";
        String s = modelName.replaceAll("[^A-Za-z0-9_]", "_");
        if (s.length() > 16) s = s.substring(0, 16);
        return s;
    }

    private static void setInitialReflexState(AgentPlayer companion, String id,
                                              boolean enabled) {
        ReflexRegistry.get(id).ifPresent(reflex -> {
            if (enabled) reflex.enable(companion);
            else reflex.disable(companion);
        });
    }

    private static boolean isValidDisplayName(String name) {
        if (name == null || name.isBlank() || name.length() > 64) return false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) return false;
        }
        return true;
    }

    /** Validate all external spawn/config fields before creating world state. */
    private static String validateSpawnArguments(ServerPlayer owner, String name,
                                                 String providerId, String apiKey,
                                                 String model, String baseUrl,
                                                 double temperature,
                                                 String reasoningEffort) {
        if (owner == null || owner.getServer() == null) return "owner is not connected";
        if (!isValidDisplayName(name)) return "name must be 1-64 printable characters";
        if (providerId == null || providerId.isBlank()
                || providerId.length() > 64
                || LLMProviderRegistry.get(providerId).isEmpty()) {
            return "unknown LLM provider '" + providerId + "'";
        }
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 16384) {
            return "API key is missing or too long";
        }
        if (model == null || model.isBlank() || model.length() > 256) {
            return "model must be 1-256 characters";
        }
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            return "temperature must be a finite number from 0 to 2";
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            if (baseUrl.length() > 2048) return "base URL is too long";
            try {
                var uri = java.net.URI.create(baseUrl.trim());
                if (!("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getQuery() != null
                        || uri.getFragment() != null) {
                    return "base URL must be an absolute HTTP(S) URL without query or fragment";
                }
            } catch (IllegalArgumentException malformedUrl) {
                return "base URL is malformed";
            }
        }
        if (reasoningEffort != null && !reasoningEffort.isBlank()
                && !List.of("off", "low", "medium", "high", "xhigh", "max")
                        .contains(reasoningEffort.toLowerCase(Locale.ROOT))) {
            return "invalid reasoning effort '" + reasoningEffort + "'";
        }
        return null;
    }

    /**
     * Choose a protocol-safe profile name that cannot collide with a human
     * player or another companion. Different display names can sanitize to
     * the same 16 characters, and offline UUIDs are derived from this name;
     * reusing it would create two ServerPlayers with one network identity.
     */
    private static String availableProfileName(ServerPlayer owner, String displayName) {
        String base = sanitizeName(displayName);
        for (int attempt = 0; attempt < 1000; attempt++) {
            String candidate = base;
            if (attempt > 0) {
                String seed = owner.getUUID() + "\u0000" + displayName + "\u0000" + attempt;
                String suffix = "_" + UUID.nameUUIDFromBytes(
                        seed.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString().substring(0, 6);
                candidate = base.substring(0, Math.min(base.length(), 16 - suffix.length())) + suffix;
            }

            final String availableCandidate = candidate;
            GameProfile candidateProfile = FakePlayerFactory.createOfflineProfile(availableCandidate);
            boolean occupied = owner.getServer().getPlayerList().getPlayers().stream()
                    .anyMatch(existing -> existing.getUUID().equals(candidateProfile.getId())
                            || existing.getGameProfile().getName()
                                    .equalsIgnoreCase(availableCandidate));
            if (!occupied && !COMPANION_PLAYER_UUIDS.contains(candidateProfile.getId())) {
                return availableCandidate;
            }
        }
        throw new IllegalStateException("unable to allocate a unique companion profile name");
    }

    /**
     * Set the tab list display name on a ServerPlayer.
     *
     * <p>In Minecraft, the player's name shown above the head and in the
     * tab list comes from the PlayerList entry's display name, which is
     * initialized from the GameProfile. The GameProfile name is limited
     * to 16 chars, so "deepseek-v4-flash" gets truncated to "deepseek_v4_flas".
     *
     * <p>This method searches for the tab list name field/method by type
     * (Component) rather than by name, because field names differ between
     * mappings (mojmap vs intermediary) and versions. The search walks up
     * the class hierarchy from ServerPlayer to Player.
     *
     * @param player the ServerPlayer to update
     * @param name   the full display name to show
     */
    private static void setTabListDisplay(ServerPlayer player, String name) {
        var component = Component.literal(name);

        // Strategy 1: Reflection with a list of known mojmap/yarn field names
        // for the Player class's tab list display name component.
        // In 1.21.1 mojmap it is `tabListName`; in yarn named mappings it may
        // be `listName` or similar. We try each in order.
        String[] candidateFieldNames = {"tabListName", "listName", "tabName",
                "playerListName", "displayName", "customName"};
        for (String fname : candidateFieldNames) {
            try {
                var field = net.minecraft.world.entity.player.Player.class
                        .getDeclaredField(fname);
                field.setAccessible(true);
                field.set(player, component);
                return;
            } catch (NoSuchFieldException ignored) {
                // Try next name
            } catch (Exception e) {
                System.err.println("[MineAgent] setTabListDisplay reflection "
                        + "for field '" + fname + "' failed: " + e.getMessage());
            }
        }

        // Strategy 2: Scan for a Component-typed field whose name contains
        // "tab" or "list" or "display" — this is the tabListName field
        // regardless of the actual mapped name. Catches any mapping we
        // didn't enumerate in Strategy 1.
        try {
            Class<?> cls = player.getClass();
            while (cls != null && cls != Object.class) {
                for (var field : cls.getDeclaredFields()) {
                    if (field.getType() == net.minecraft.network.chat.Component.class) {
                        String fn = field.getName().toLowerCase();
                        if (fn.contains("tab") || fn.contains("list")
                                || fn.contains("display")) {
                            field.setAccessible(true);
                            field.set(player, component);
                            return;
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] setTabListDisplay field search failed: "
                    + e.getMessage());
        }

        // Strategy 3: All strategies failed — fall back to GameProfile name,
        // which will be truncated to 16 chars by Minecraft protocol.
        System.err.println("[MineAgent] setTabListDisplay: could not find tab list "
                + "name field/method. Display name will use GameProfile name (truncated).");
    }

    private static void printHelp(CommandSourceStack source) {
        String help = """
            §6═══════ MineAgent Commands ═══════§r

            §e/mineagent quick§7 [name]
              §aQuick spawn using config file (recommended)

            §e/mineagent spawn§7 <name> <provider> <model> <apikey> [temp]
              §aFull spawn with all parameters

            §e/mineagent remove
              §aRemove your companion

            §e/mineagent respawn
              §aRespawn a dead companion

            §e/mineagent list
              §aList all active companions

            §e/mineagent providers
              §aList all LLM providers

            §e/mineagent models§7 [provider]
              §aList available models

            §e/mineagent config
              §aShow current configuration

            §e/mineagent reload
              §aReload config from file

            §e/mineagent mode§7 [follow|free|<name> [follow|free]]
              §aToggle or set FOLLOW/FREE mode for primary or named companion

            §e/mineagent locate
              §aGet companion's exact position, health, food, and mode

            §e/mineagent tp
              §aTeleport to your companion's location

            §e/mineagent setskin§7 <playername|default|steve|alex>
              §aSet companion skin from a player name, or reset to default

            §e/mineagent resetskin
              §aReset companion skin to default Steve/Alex

            §e/mineagent rename§7 <newname>
              §aRename your companion (no name tag needed)

            §e/mineagent seteffort§7 [name] <off|low|medium|high|xhigh|max>
              §aSet companion's thinking effort level

            §e/mineagent setconfig§7 <key> <value>
              §aUpdate config and save to file. Keys:
              §7  provider, model, apikey, baseurl, temperature, name, skin

            §7───────────────────────────────
            §7Quick start: Edit §fconfig/mineagent.json§7, then run §e/mineagent quick§7
            §7───────────────────────────────§r""";
        source.sendSuccess(() -> Component.literal(help), false);
    }

    // ── Companion state holder ─────────────────────────────────────

    public static final class CompanionState {
        public final CompanionEntity companion;
        public final AgentLoop loop;
        public final PriorityAuction auction;
        public final CompanionLifecycleHandler lifecycle;

        public CompanionState(CompanionEntity companion, AgentLoop loop,
                               PriorityAuction auction, CompanionLifecycleHandler lifecycle) {
            this.companion = companion;
            this.loop = loop;
            this.auction = auction;
            this.lifecycle = lifecycle;
        }
    }
}
