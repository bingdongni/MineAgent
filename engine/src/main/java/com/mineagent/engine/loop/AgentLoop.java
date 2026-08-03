package com.mineagent.engine.loop;

import com.mineagent.api.agent.tool.*;
import com.mineagent.api.agent.skill.*;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.llm.*;
import com.mineagent.api.llm.model.ModelFamilies;
import com.mineagent.api.llm.provider.*;
import com.mineagent.api.platform.Services;
import com.mineagent.api.task.*;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.persona.PersonaProfile;
import com.mineagent.engine.persona.EmotionState;
import com.mineagent.engine.memory.PlaceEventMemory;
import com.mineagent.engine.memory.ReflectionSystem;
import com.mineagent.engine.memory.ImportanceEvaluator;
import com.mineagent.engine.skill.SkillLibrary;
import com.mineagent.engine.cache.DecisionCache;
import com.mineagent.engine.theory.TheoryOfMind;
import com.mineagent.engine.knowledge.MinecraftKnowledgeGraph;
import com.mineagent.engine.planning.HierarchicalPlanner;
import com.mineagent.engine.world.InternalWorldModel;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The Agent Loop - the core orchestrator that drives the LLM conversation
 * and executes tool calls.
 *
 * <h3>Architecture: event-driven, dual-rail</h3>
 * <ol>
 *   <li>The LLM is NOT in the tick loop - it only runs when:
 *       <ul>
 *         <li>Owner speaks to companion</li>
 *         <li>Tool result arrives</li>
 *         <li>Async task finishes</li>
 *       </ul>
 *   </li>
 *   <li>Tool results go through two rails:
 *       <ul>
 *         <li><b>Query rail</b> (sync tools): reply immediately, batch with
 *             other tool results</li>
 *         <li><b>Action rail</b> (async tools): dispatch a task, get a
 *             task_id; the task_finished event wakes the loop later</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>Conversation flow</h3>
 * <pre>
 *   System prompt + history → LLM → assistant message
 *     ├─ text only → done, wait for next event
 *     └─ tool_calls → execute all → collect results → feed back → LLM again
 * </pre>
 *
 * <h3>Safety mechanisms</h3>
 * <ul>
 *   <li>History truncation: keeps last N messages to avoid context overflow</li>
 *   <li>Tool-call recursion cap: prevents infinite tool-calling loops</li>
 *   <li>LLM retry with backoff: handles transient network errors</li>
 *   <li>Assistant text output: speaks to the owner in-game</li>
 * </ul>
 */
public class AgentLoop {

    /** Maximum conversation history messages (excluding system prompt).
     *  Lowered from 50 → 24 to reduce token consumption and latency.
     *  Old messages are summarized (see {@link #trimHistory}) so context
     *  is not lost, just compressed. */
    private static final int MAX_HISTORY = 24;

    /** Number of recent messages to keep verbatim before summarizing.
     *  Messages older than this window get folded into a compact
     *  "[SUMMARY]" system message to save tokens. */
    private static final int RECENT_KEEP = 12;

    /** Maximum consecutive tool-call rounds within a single turn. */
    private static final int MAX_TOOL_ROUNDS = 10;

    /** Maximum LLM retry attempts. */
    private static final int MAX_RETRIES = 3;

    /** Retry base delay in milliseconds. */
    private static final long RETRY_BASE_MS = 1000;

    /** Maximum length (chars) of a tool result before it gets truncated.
     *  Long tool outputs (e.g. look_around dumping 50 blocks) blow up
     *  token usage and slow down the next LLM call. We cap results to
     *  keep the conversation compact. */
    private static final int MAX_TOOL_RESULT_CHARS = 800;

    /** Cache TTL check interval — skip the LLM call entirely if we have
     *  a fresh cached decision for a similar situation. This is the
     *  single biggest latency win for repetitive actions (e.g. mining
     *  a vein block-by-block). */
    private static final boolean DECISION_CACHE_ENABLED = true;

    /** 连续缓存命中上限。
     *  连续命中达到此次数后，强制走一次 LLM 调用，防止 AI 陷入
     *  "卡带"式重复（同一决策无限循环）。
     *  设为 2：允许 1 次复用（覆盖连续挖矿场景），第 3 次强制思考。 */
    private static final int MAX_CONSECUTIVE_CACHE_HITS = 2;

    /** 纯文本响应补救次数上限。
     *  当 LLM 说了要行动但没调用工具时，给它 N 次机会补救。
     *  超过后真正结束回合，避免无限递归。 */
    private static final int MAX_REMEDIATION_ROUNDS = 1;

    /** 距上次玩家消息的时间（毫秒）。
     *  如果玩家刚刚说话（<30秒），不使用缓存 — 对话场景需要
     *  LLM 真正理解并回应，缓存复用会导致答非所问。 */
    private static final long CACHE_SKIP_AFTER_PLAYER_MSG_MS = 30_000;

    private final AgentPlayer companion;
    private final String providerId;
    private final String apiKey;
    private final String model;  // final — model switching is not allowed at runtime
    private final String baseUrl;
    private final double temperature;
    private final int maxTokens;
    private volatile String reasoningEffort; // off/low/medium/high/xhigh/max or null

    private final List<ChatMessage> history = new ArrayList<>();
    private final List<String> inbox = new ArrayList<>(); // pending body-log entries
    private final Object inboxLock = new Object(); // unified inbox lock (L1 fix)

    private volatile boolean inProgress = false;
    private volatile boolean paused = false;

    /**
     * Stale Request detection (borrowed from Mindcraft).
     *
     * <p>Tracks the timestamp of the last significant event (owner message,
     * body log, task completion). When an LLM call is in progress and a
     * new event arrives, this timestamp updates. After the LLM returns,
     * we compare the timestamp — if it changed during the call, the
     * response is "stale" (the situation it was generated for no longer
     * applies) and we discard it and restart the turn.
     *
     * <p>This prevents the classic "AI answers a question you asked 10
     * seconds ago, ignoring the new one you just asked" problem, and
     * reduces latency for new messages by 5-10x (no waiting for the
     * old response to complete).
     */
    // A monotonic counter cannot miss two events that happen in the same
    // millisecond, unlike the old currentTimeMillis-based stale check.
    private final AtomicLong eventGeneration = new AtomicLong();

    /**
     * Sparse Thinking (borrowed from Game-TARS).
     *
     * <p>Not every situation requires LLM reasoning. Routine actions
     * (continuing to mine a vein, walking along a known path) can be
     * handled by the decision cache or fast-path heuristics. Only "key
     * decision points" (new task, unexpected event, owner message,
     * inventory full, mob appears) trigger a real LLM call.
     *
     * <p>Track the last action type to detect repetitive patterns.
     * When the same action type repeats, the decision cache is preferred.
     */
    private volatile String lastActionType = "";
    private volatile int sameActionCount = 0;

    private final ExecutorService executor;
    /** Worker thread only while blocked inside an LLM provider HTTP call. */
    private volatile Thread activeLlmThread;

    // ── Cognitive subsystems ──
    private final PersonaProfile persona;
    private final EmotionState emotion;
    private final PlaceEventMemory placeMemory;
    private final SkillLibrary skillLib;
    private final DecisionCache decisionCache;
    private final TheoryOfMind theoryOfMind;
    private final MinecraftKnowledgeGraph knowledgeGraph;
    private final HierarchicalPlanner planner;
    private final ReflectionSystem reflection;
    private final InternalWorldModel worldModel;
    private final ImportanceEvaluator importance;
    /** Spatial memory — records points of interest discovered while
     *  exploring (ores, structures, hazards, chests). Backs the
     *  "记忆点" section of the system prompt so the LLM can recall
     *  "I saw iron at (10, 64, -5)" without re-scanning. */
    private final com.mineagent.engine.memory.CognitiveMap cognitiveMap;
    /** 记忆持久化：游戏关闭时保存记忆，重启后恢复（解决"失忆"问题）。
     *  可为 null — 当 world data dir 尚未设置时（记忆不持久化，仅内存）。 */
    private final com.mineagent.engine.memory.MemoryPersistence persistence;

    public AgentLoop(AgentPlayer companion, String providerId, String apiKey,
                      String model, String baseUrl, double temperature, int maxTokens,
                      String reasoningEffort) {
        this.companion = companion;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "MineAgent-Loop-" + companion.companionId().toString().substring(0, 8));
            t.setDaemon(true);
            return t;
        });
        this.providerId = providerId;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.temperature = temperature;
        this.maxTokens = maxTokens != 0 ? maxTokens : ModelFamilies.defaultMaxTokens(model);
        this.reasoningEffort = reasoningEffort;

        // Initialize cognitive subsystems
        this.persona = PersonaProfile.random();
        this.emotion = new EmotionState();
        this.placeMemory = new PlaceEventMemory();
        this.skillLib = new SkillLibrary();
        this.decisionCache = new DecisionCache();
        this.theoryOfMind = new TheoryOfMind();
        this.knowledgeGraph = new MinecraftKnowledgeGraph();
        this.planner = new HierarchicalPlanner();
        this.reflection = new ReflectionSystem();
        this.worldModel = new InternalWorldModel();
        this.importance = new ImportanceEvaluator();
        this.cognitiveMap = new com.mineagent.engine.memory.CognitiveMap();

        // Memory persistence: per-companion directory, restore previous memories.
        // Solves the "restart = amnesia" problem — the companion remembers
        // locations, lessons and learned importance weights across restarts.
        //
        // IMPORTANT: the directory key must be STABLE across restarts.
        // companionId is a random UUID regenerated on every spawn/restore,
        // so it cannot be used — the restored companion would never find
        // its previous memories. Use ownerUuid + companionName instead,
        // matching CompanionStore's upsert key.
        com.mineagent.engine.memory.MemoryPersistence p = null;
        java.nio.file.Path worldDir = MineAgentEngine.getWorldDataDir();
        if (worldDir != null) {
            String stableKey = companion.ownerUuid()
                    + "_" + sanitizeForPath(companion.companionName());
            java.nio.file.Path memDir = worldDir.resolve("mineagent_memory")
                    .resolve(stableKey);
            p = new com.mineagent.engine.memory.MemoryPersistence(
                    memDir, cognitiveMap, placeMemory, importance, reflection);
            p.loadAll();
        }
        this.persistence = p;

        // Initialize system prompt
        history.add(ChatMessage.system(buildSystemPrompt()));
    }

    // ── Public API ─────────────────────────────────────────────────

    /**
     * Wake the loop - called when:
     * <ul>
     *   <li>Owner sends a chat message</li>
     *   <li>A task_finished event arrives</li>
     *   <li>A reflex report needs to be delivered</li>
     * </ul>
     */
    public synchronized void wake(String reason) {
        // Stale Request detection: mark this as a significant event.
        // If an LLM call is in progress, it will see the timestamp changed
        // and discard its response as stale.
        eventGeneration.incrementAndGet();
        if (paused) {
            // Preserve events while paused so resume sees what happened, but
            // never start a request that could dispatch physical actions.
            synchronized (inboxLock) {
                inbox.add(reason);
            }
            return;
        }
        if (inProgress) {
            // Currently in a turn - add to inbox for batch processing
            synchronized (inboxLock) {
                inbox.add(reason);
            }
            // Interrupt only a provider HTTP wait. Tool callbacks scheduled on
            // the server thread must never be cancelled halfway through a
            // physical inventory/world mutation.
            interruptActiveLlmRequest();
            return;
        }
        startTurn(reason);
    }

    /**
     * Add an owner message and wake the loop.
     */
    public void onOwnerMessage(String message) {
        // Track last owner command for decision cache keying
        lastOwnerCommand = message.length() > 120 ? message.substring(0, 120) : message;
        lastOwnerMessageTime = System.currentTimeMillis();
        // Observe player intent for Theory of Mind
        long gameTime = System.currentTimeMillis();
        theoryOfMind.observe(message, gameTime);
        // Detect feedback tone (simple heuristic)
        String lower = message.toLowerCase();
        if (lower.contains("谢谢") || lower.contains("thanks") || lower.contains("好样的")
                || lower.contains("干得好") || lower.contains("good") || lower.contains("不错")) {
            theoryOfMind.onPlayerFeedback(true);
            emotion.onPraised();
            persona.drift(true);
        } else if (lower.contains("笨") || lower.contains("蠢") || lower.contains("stupid")
                || lower.contains("别") || lower.contains("不要") || lower.contains("no")
                || lower.contains("stop") || lower.contains("停下")) {
            theoryOfMind.onPlayerFeedback(false);
            emotion.onScolded();
            persona.drift(false);
        }
        synchronized (history) {
            history.add(ChatMessage.user(message));
        }
        wake("owner_message");
    }

    /**
     * Add a body log entry and wake if idle.
     */
    public void onBodyLog(String narrative) {
        if (narrative == null || narrative.isBlank()) return;
        // Trigger emotions based on body event content.
        // Use independent if-statements (not else-if) so that a single
        // narrative containing multiple event types (e.g. "attacked by
        // creeper near diamond") triggers ALL matching emotions. The old
        // else-if chain only fired the first match, silently dropping the
        // rest.
        String lower = narrative.toLowerCase();
        if (lower.contains("attack") || lower.contains("被攻击") || lower.contains("damage")
                || lower.contains("受伤") || lower.contains("hurt")) {
            emotion.onAttacked();
        }
        if (lower.contains("danger") || lower.contains("危险")
                || lower.contains("creeper") || lower.contains("苦力怕")) {
            emotion.onSeeDanger();
        }
        if (lower.contains("diamond") || lower.contains("钻石")
                || lower.contains("treasure") || lower.contains("宝藏")) {
            emotion.onFindTreasure();
        }
        if (lower.contains("owner") && (lower.contains("hurt") || lower.contains("damage"))) {
            emotion.onOwnerHurt();
        }

        // Record place events from body log (simple keyword detection)
        // e.g. "saw iron_ore at (10, 64, -5)" → remember it
        recordPlaceEventFromLog(narrative);

        synchronized (inboxLock) {
            inbox.add("[BODY] " + narrative);
        }
        synchronized (this) {
            // Body state is part of the request context. Always invalidate an
            // in-flight response; previously busy turns neither advanced the
            // generation nor reliably delivered the queued event afterward.
            eventGeneration.incrementAndGet();
            if (inProgress) interruptActiveLlmRequest();
            if (!paused && !inProgress) {
                startTurn("body_log");
            }
        }
    }

    /**
     * Parse a body log narrative for place events and record them
     * in the PlaceEventMemory. Detects patterns like:
     * "saw iron_ore at (10, 64, -5)" or "found cow near 10,64,-5"
     */
    private void recordPlaceEventFromLog(String narrative) {
        if (narrative == null) return;
        // Look for coordinate patterns: (x, y, z) or x,y,z
        java.util.regex.Matcher m = COORD_PATTERN.matcher(narrative);
        if (m.find()) {
            try {
                int x = Integer.parseInt(m.group(1));
                int y = Integer.parseInt(m.group(2));
                int z = Integer.parseInt(m.group(3));
                // Determine subject and type from keywords
                String subject = "unknown";
                String type = "event";
                String lower = narrative.toLowerCase();
                if (lower.contains("iron_ore") || lower.contains("铁矿")) { subject = "iron_ore"; type = "resource"; }
                else if (lower.contains("coal") || lower.contains("煤")) { subject = "coal_ore"; type = "resource"; }
                else if (lower.contains("diamond") || lower.contains("钻石")) { subject = "diamond_ore"; type = "resource"; }
                else if (lower.contains("gold") || lower.contains("金矿")) { subject = "gold_ore"; type = "resource"; }
                else if (lower.contains("cow") || lower.contains("牛")) { subject = "cow"; type = "entity"; }
                else if (lower.contains("sheep") || lower.contains("羊")) { subject = "sheep"; type = "entity"; }
                else if (lower.contains("village") || lower.contains("村庄")) { subject = "village"; type = "structure"; }
                else if (lower.contains("creeper") || lower.contains("苦力怕")) { subject = "creeper"; type = "danger"; }
                else if (lower.contains("zombie") || lower.contains("僵尸")) { subject = "zombie"; type = "danger"; }
                else if (lower.contains("tree") || lower.contains("树")) { subject = "tree"; type = "resource"; }

                String dimension = com.mineagent.engine.task.TaskContext.serverPlayer(companion)
                        .level().dimension().location().toString();
                placeMemory.remember(type, subject, x, y, z, dimension,
                        System.currentTimeMillis(), narrative);

                // Also record in the cognitive map (spatial POI store)
                // with a semantic category for prompt-level recall.
                String category;
                String label;
                switch (type) {
                    case "resource" -> {
                        category = "resource:" + subject;
                        label = subject + " (resource)";
                    }
                    case "structure" -> {
                        category = "structure:" + subject;
                        label = subject;
                    }
                    case "danger" -> {
                        category = "hazard:" + subject;
                        label = subject + " (hazard)";
                    }
                    case "entity" -> {
                        category = "entity:" + subject;
                        label = subject;
                    }
                    default -> {
                        category = "event:" + subject;
                        label = subject;
                    }
                }
                cognitiveMap.recordPoi(new net.minecraft.core.BlockPos(x, y, z),
                        category, label, dimension, System.currentTimeMillis());
            } catch (NumberFormatException ignored) {
                // Coordinate parse failed — skip
            }
        }
    }

    private static final java.util.regex.Pattern COORD_PATTERN =
            java.util.regex.Pattern.compile("\\(?(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\)?");

    /** Replace characters that are illegal in file paths (Windows/Unix). */
    private static String sanitizeForPath(String name) {
        if (name == null || name.isBlank()) return "unknown";
        return name.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_");
    }

    /**
     * Migrate memory storage to a new companion name (called on rename).
     * Keeps the companion's memories across renames.
     */
    public void migrateMemoryStorage(String newName) {
        java.nio.file.Path worldDir = MineAgentEngine.getWorldDataDir();
        if (persistence != null && worldDir != null) {
            String newKey = companion.ownerUuid() + "_" + sanitizeForPath(newName);
            persistence.migrateTo(worldDir.resolve("mineagent_memory").resolve(newKey));
        }
    }

    /**
     * Cancel the current turn and clear the inbox.
     */
    public synchronized void cancel() {
        // Invalidate the response of any request already in flight. Clearing
        // only the inbox allowed a late response to execute stale tool calls.
        eventGeneration.incrementAndGet();
        interruptActiveLlmRequest();
        synchronized (inboxLock) {
            inbox.clear();
        }
        // The running turn will finish naturally
    }

    /** Pause future turns and invalidate any response currently in flight. */
    public synchronized void pause() {
        paused = true;
        eventGeneration.incrementAndGet();
        interruptActiveLlmRequest();
    }

    /** Resume processing, including events accumulated while paused. */
    public synchronized void resume(String reason) {
        if (!paused) {
            wake(reason);
            return;
        }
        paused = false;
        wake(reason);
    }

    /** Is a turn currently in progress? */
    public boolean isInProgress() { return inProgress; }

    // ── Getters for persistence ──

    public String getProviderId() { return providerId; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public String getBaseUrl() { return baseUrl; }
    public double getTemperature() { return temperature; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String effort) { this.reasoningEffort = effort; }

    // ── Cognitive subsystem accessors (for external hooks) ──

    public EmotionState emotion() { return emotion; }
    public PlaceEventMemory placeMemory() { return placeMemory; }
    public com.mineagent.engine.memory.CognitiveMap cognitiveMap() { return cognitiveMap; }
    public SkillLibrary skillLibrary() { return skillLib; }
    public TheoryOfMind theoryOfMind() { return theoryOfMind; }
    public HierarchicalPlanner planner() { return planner; }
    public ReflectionSystem reflection() { return reflection; }
    public PersonaProfile persona() { return persona; }
    public InternalWorldModel worldModel() { return worldModel; }
    public MinecraftKnowledgeGraph knowledgeGraph() { return knowledgeGraph; }
    public DecisionCache decisionCache() { return decisionCache; }

    // ── Turn execution ─────────────────────────────────────────────

    private void startTurn(String trigger) {
        if (paused) return;
        inProgress = true;
        try {
            executor.submit(() -> {
                try {
                    // Rate-limited memory auto-save (max once per minute
                    // internally). Runs on the loop executor thread — NOT the
                    // server thread — so file IO never causes game lag.
                    if (persistence != null) {
                        persistence.autoSave();
                    }
                    executeTurn(trigger, 0);
                } catch (Exception e) {
                    // Log error but don't crash the loop
                    System.err.println("[MineAgent] Turn error: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    synchronized (this) {
                        inProgress = false;
                        // A response invalidated by pause may finish after body
                        // events were queued. Do not drain those events into a
                        // startTurn() which immediately returns while paused;
                        // resume() will process the intact inbox.
                        if (paused) return;
                        // Check if inbox accumulated during this turn
                        boolean hasPendingEvents;
                        synchronized (inboxLock) {
                            hasPendingEvents = !inbox.isEmpty();
                        }
                        if (hasPendingEvents) {
                            // executeTurn/drainInbox owns the transfer into
                            // history. Clearing here discarded every event
                            // received during the previous LLM request.
                            startTurn("inbox_batch");
                        }
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // Executor was shut down (or queue saturated) — submit() rejected
            // the task synchronously. The lambda above never runs, so its
            // finally block will NOT reset inProgress. We must reset it here
            // to avoid permanently locking the loop (inProgress stuck true →
            // all future wake() calls queue into inbox and never execute).
            synchronized (this) {
                inProgress = false;
            }
            System.err.println("[MineAgent] Turn rejected by executor (shutting down?): "
                    + e.getMessage());
        }
    }

    /**
     * Execute a single LLM turn.
     *
     * @param trigger     what woke the loop
     * @param roundNumber how many tool-call rounds have executed (for recursion cap)
     */
    private void executeTurn(String trigger, int roundNumber) {
        // 0. Safety cap on tool-call rounds
        if (roundNumber >= MAX_TOOL_ROUNDS) {
            System.err.println("[MineAgent] Reached max tool rounds ("
                    + MAX_TOOL_ROUNDS + ") - stopping turn to prevent infinite loop");
            synchronized (history) {
                history.add(ChatMessage.system(
                        "[SYSTEM] You have called tools " + MAX_TOOL_ROUNDS
                                + " times in a row. Please respond to the owner now."));
            }
            // Final LLM call to wrap up
            callLLMAndHandleResponse(roundNumber);
            return;
        }

        // 1. Drain inbox into the conversation
        drainInbox();

        // 2. Tick emotion state (coarse-grained decay per turn)
        emotion.tick();

        // 3. Refresh system prompt with dynamic cognitive context
        //    (persona, emotion, memory, plan, reflection, ToM, etc.)
        if (roundNumber == 0) {
            refreshSystemPrompt();
            // Reset remediation counter at the start of each turn
            remediationRoundCounter = 0;
        }

        // 4. Trim history to prevent context overflow
        trimHistory();

        // Call LLM and handle the response (with retry)
        callLLMAndHandleResponse(roundNumber);
    }

    /**
     * Call the LLM, add the response to history, and execute any tool calls.
     * If all tools are synchronous, recursively continues the turn.
     */
    private void callLLMAndHandleResponse(int roundNumber) {
        LLMProvider provider = resolveProvider();
        List<Map<String, Object>> toolDefs = buildToolDefinitions();

        // 3a. Decision cache lookup — if we have a fresh cached decision
        //     for a similar situation, skip the LLM call entirely.
        //     This is the biggest latency win for repetitive actions
        //     (e.g. "mine the next block" repeated 20 times in a vein).
        //     Only applied on the first round of a turn (roundNumber == 0)
        //     to avoid caching mid-tool-call sequences.
        //
        //     安全门（防止记忆错乱/卡带式重复）：
        //     1. 玩家最近 30 秒内说过话 → 不缓存（对话需要 LLM 真正理解）
        //     2. 连续命中 ≥ 2 次 → 强制走 LLM（防止无限循环）
        //     3. TTL 已缩短到 10 秒（降低情境变化风险）
        if (DECISION_CACHE_ENABLED && roundNumber == 0 && !shouldSkipCache()) {
            String cacheKey = buildDecisionCacheKey();
            // buildDecisionCacheKey may return null (e.g. companion state
            // not yet available). ConcurrentHashMap forbids null keys, so we
            // must skip both read and write when cacheKey is null.
            if (cacheKey != null) {
                String cachedDecision = decisionCache.getDecision(cacheKey);
                if (cachedDecision != null && !cachedDecision.isBlank()) {
                    // Safety: don't replay the same text if it's identical to
                    // the last assistant message — this means the AI is stuck
                    // in a "say but don't do" loop. Force a fresh LLM call.
                    if (isSameAsLastAssistant(cachedDecision)) {
                        System.out.println("[MineAgent] Cache hit but content identical "
                                + "to last response — skipping cache to avoid loop");
                        consecutiveCacheHits = 0;
                        // Fall through to LLM call
                    } else {
                        // Cache hit — synthesize an assistant message from cache.
                        ChatMessage cachedMsg = ChatMessage.assistant(cachedDecision, null);
                        synchronized (history) {
                            history.add(cachedMsg);
                        }
                        speakToOwner(cachedDecision);
                        consecutiveCacheHits++;
                        System.out.println("[MineAgent] Decision cache HIT (key=" + cacheKey
                                + ", consecutive=" + consecutiveCacheHits + ")");
                        return;
                    }
                }
            }
        }
        // Cache miss or skipped — reset consecutive counter
        consecutiveCacheHits = 0;

        // 3b. Call the LLM (with retry on transient errors)
        LLMResponse response = callLLMWithRetry(provider, toolDefs);
        if (response == null) {
            // All retries exhausted - give up gracefully
            return;
        }

        // Providers and third-party relays occasionally return syntactically
        // valid envelopes with a missing choice/message. Treat that as an
        // invalid response instead of crashing the loop thread.
        if (response.choice() == null || response.choice().message() == null) {
            System.err.println("[MineAgent] LLM response did not contain a choice message");
            return;
        }
        ChatMessage assistantMsg = response.choice().message();
        assistantMsg = normalizeToolCallIds(assistantMsg);
        // Skip empty assistant messages (no content AND no tool_calls).
        // Some providers occasionally return a no-op response; adding it to
        // history wastes tokens and can confuse subsequent rounds (the LLM
        // sees its own empty message and may echo the pattern). Bail out
        // early instead — there is nothing to speak, cache, or execute.
        boolean hasContent = assistantMsg.content() != null
                && !assistantMsg.content().isBlank();
        boolean hasToolCalls = assistantMsg.toolCalls() != null
                && !assistantMsg.toolCalls().isEmpty();
        if (!hasContent && !hasToolCalls) {
            System.err.println("[MineAgent] Empty assistant response "
                    + "(no content, no tool_calls) — skipping, not added to history");
            return;
        }
        synchronized (history) {
            history.add(assistantMsg);
        }

        // 3c. Store the decision in cache for future reuse.
        //     Only cache text-only responses (no tool_calls) to avoid
        //     replaying stale tool sequences. Text responses to simple
        //     status queries are highly reusable.
        //
        //     CRITICAL: Only cache responses that EXPLICITLY declare no
        //     action via the [NO_ACTION] / 【无行动】 marker (see Action
        //     Intent Declaration Protocol in system prompt). Responses
        //     without the marker are ambiguous — they might contain
        //     unstated action intent that would cause "say but don't do"
        //     loops if replayed from cache.
        //
        //     This replaces the old containsActionIntent() keyword check
        //     with a stricter, language-agnostic, LLM-driven declaration.
        if (DECISION_CACHE_ENABLED && roundNumber == 0
                && (assistantMsg.toolCalls() == null || assistantMsg.toolCalls().isEmpty())
                && assistantMsg.content() != null && !assistantMsg.content().isBlank()
                && hasExplicitNoActionMarker(assistantMsg.content())) {
            String cacheKey = buildDecisionCacheKey();
            // Skip cache write when key is null (ConcurrentHashMap forbids
            // null keys — would throw NPE).
            if (cacheKey != null) {
                decisionCache.put(cacheKey, assistantMsg.content(), null);
            }
        }

        // Speak text to the owner in-game
        if (assistantMsg.content() != null && !assistantMsg.content().isBlank()) {
            speakToOwner(assistantMsg.content());
        }

        // 4. Action Intent Declaration Protocol enforcement.
        //    When LLM produces a text-only response (no tool_calls), check
        //    whether it explicitly declared "no action" via the [NO_ACTION] /
        //    【无行动】 marker. If NOT, the LLM likely forgot to call a tool
        //    (or forgot to declare no-action) — give it ONE chance to
        //    correct itself by adding a system reminder.
        //
        //    This replaces the old containsActionIntent() keyword-based
        //    detection with a stricter, language-agnostic protocol:
        //      - marker present → LLM honestly declared no action → end turn
        //      - marker absent  → LLM either forgot to act OR forgot to
        //                         declare no-action → remediate (safer)
        //
        //    The conservative "absent marker = remediate" policy is by
        //    design: it catches ALL "say but don't do" cases without
        //    relying on a fragile keyword list. Cost is bounded by
        //    MAX_REMEDIATION_ROUNDS (currently 1) so worst case is one
        //    extra LLM call per text-only response.
        if (assistantMsg.toolCalls() == null || assistantMsg.toolCalls().isEmpty()) {
            if (roundNumber == 0 && assistantMsg.content() != null
                    && !assistantMsg.content().isBlank()
                    && !hasExplicitNoActionMarker(assistantMsg.content())
                    && remediationRoundCounter < MAX_REMEDIATION_ROUNDS) {
                remediationRoundCounter++;
                System.out.println("[MineAgent] Text-only response without [NO_ACTION] "
                        + "marker — giving remediation chance " + remediationRoundCounter
                        + " (response: \""
                        + truncateForLog(assistantMsg.content(), 80) + "\")");
                synchronized (history) {
                    history.add(ChatMessage.system(
                            "[SYSTEM] Action Intent Declaration Protocol 提醒\n"
                            + "你的回复没有调用任何工具，也没有显式标注 [NO_ACTION] 或 【无行动】。\n"
                            + "请立即做出选择：\n"
                            + "  A. 如果你刚才说要做某事（移动/挖掘/建造/攻击/采集/合成/放置等），"
                            + "必须立即调用对应工具执行（goto/auto_mine/place_block/craft 等）。\n"
                            + "  B. 如果确实不需要任何身体动作（只是聊天/汇报/确认），"
                            + "请在回复中添加 [NO_ACTION] 或 【无行动】 标记。\n"
                            + "记住：说话≠行动。身体动作必须通过工具调用执行，"
                            + "纯对话必须显式声明无行动。"));
                }
                // Give LLM another chance to call tools or declare no-action
                callLLMAndHandleResponse(roundNumber + 1);
                return;
            }
            return;
        }

        // 4b. Safety: enforce the MAX_TOOL_ROUNDS cap. executeTurn already
        //     added a "[SYSTEM] you have called tools N times" message and
        //     called us for a final wrap-up response. If the LLM STILL
        //     returns tool_calls here, executing them and recursing would
        //     loop forever (each recursion re-hits this branch). So we
        //     ignore the tool_calls and return immediately.
        if (roundNumber >= MAX_TOOL_ROUNDS) {
            System.err.println("[MineAgent] LLM returned tool_calls after hitting "
                    + "MAX_TOOL_ROUNDS cap (" + MAX_TOOL_ROUNDS + ") — ignoring "
                    + "to prevent infinite tool-calling loop");
            // An assistant message containing tool_calls must always be
            // followed by one tool result per call. Otherwise the next API
            // request rejects the whole conversation as malformed.
            synchronized (history) {
                for (var tc : assistantMsg.toolCalls()) {
                    history.add(ChatMessage.toolResult(tc.id(), jsonError(
                            "Tool call skipped because the per-turn tool limit was reached.")));
                }
            }
            return;
        }

        // 5. Execute tool calls
        List<ChatMessage> toolResults = executeToolCalls(assistantMsg.toolCalls());
        synchronized (history) {
            history.addAll(toolResults);
        }

        // 5b. Cognitive feedback loop — analyze tool results to update
        //     emotion, reflection, and skill library.
        analyzeToolResults(assistantMsg.toolCalls(), toolResults);

        // 6. Check if any async task was dispatched - if not, loop back
        boolean anyAsync = toolResults.stream()
                .anyMatch(m -> m.content() != null && m.content().contains("\"async\":true"));

        if (!anyAsync) {
            // All sync tools - loop back with incremented round
            executeTurn("tool_results_sync", roundNumber + 1);
        }
        // If async tasks were dispatched, they'll wake the loop when done
    }

    /**
     * Analyze tool execution results to update cognitive subsystems:
     * <ul>
     *   <li>Emotion: trigger joy on success, frustration on failure</li>
     *   <li>Reflection: record failures for learning</li>
     *   <li>Skill library: register successful action sequences</li>
     * </ul>
     *
     * <p><b>改进:</b>
     * <ul>
     *   <li>更准确的失败检测: 使用多信号融合而非简单关键词匹配</li>
     *   <li>结构化错误分析: 提取错误类型和上下文</li>
     *   <li>改进技能注册: 使用更稳定的技能ID生成策略</li>
     * </ul>
     */
    private void analyzeToolResults(List<ChatMessage.ToolCallRef> toolCalls,
                                      List<ChatMessage> toolResults) {
        boolean anyFailure = false;
        boolean anySuccess = false;
        int failureCount = 0;
        int successCount = 0;

        // 记录情绪变化前的 PAD 值，用于学习重要性
        float pleasureBefore = emotion.pleasure();
        float arousalBefore = emotion.arousal();

        for (int i = 0; i < toolCalls.size() && i < toolResults.size(); i++) {
            var tc = toolCalls.get(i);
            var result = toolResults.get(i);
            String content = result.content();
            if (content == null) continue;

            // 改进的失败检测：多信号融合
            FailureAnalysis analysis = analyzeFailure(content);
            boolean failed = analysis.isFailure();
            boolean succeeded = analysis.isSuccess();

            // 检查 LLM 是否在结果中标注了 importance 字段
            String importanceLabel = extractImportanceLabel(content);
            if (importanceLabel != null) {
                importance.learnFromLLMAnnotation(tc.name(), content, importanceLabel);
            }

            if (failed) {
                anyFailure = true;
                failureCount++;
                // 通过失败学习重要性
                importance.learnFromFailure(tc.name(), content);
                // Record failure in reflection system (micro layer)
                String taskDesc = planner.hasActivePlan() && planner.currentMilestone() != null
                        ? planner.currentMilestone().description() : "task";
                String failReason = analysis.errorMessage();
                reflection.recordFailure(taskDesc, tc.name(), failReason);
                // Record full failed-task memory (JARVIS-1 style) so the
                // LLM can recall this failure next time a similar task is
                // attempted, avoiding repeated mistakes.
                String attemptedPlan = tc.name() + "(" + tc.arguments() + ")";
                reflection.recordFailedTask(taskDesc, attemptedPlan, failReason);
            } else if (succeeded) {
                anySuccess = true;
                successCount++;
            }
        }

        // Emotion feedback（基于成功/失败比例）
        if (failureCount > successCount) {
            emotion.onTaskFailed();
        } else if (successCount > 0) {
            emotion.onTaskComplete();
        }

        // 通过情绪变化学习重要性
        float pleasureDelta = Math.abs(emotion.pleasure() - pleasureBefore);
        float arousalDelta = Math.abs(emotion.arousal() - arousalBefore);
        float emotionDelta = Math.max(pleasureDelta, arousalDelta);
        if (emotionDelta > 0.05f && !toolCalls.isEmpty()) {
            var firstCall = toolCalls.get(0);
            var firstResult = toolResults.get(0);
            if (firstResult.content() != null) {
                // 负面情绪（pleasure下降）传负值，让 ImportanceEvaluator 加权提升更多
                float signedDelta = emotion.pleasure() - pleasureBefore;
                importance.learnFromEmotion(firstCall.name(), firstResult.content(), signedDelta);
            }
        }

        // 改进的技能注册：使用更稳定的技能ID
        if (anySuccess && !toolCalls.isEmpty()) {
            // 收集所有成功的工具调用
            List<String> successfulTools = new ArrayList<>();
            for (int i = 0; i < toolCalls.size() && i < toolResults.size(); i++) {
                var tc = toolCalls.get(i);
                var result = toolResults.get(i);
                if (result.content() != null && analyzeFailure(result.content()).isSuccess()) {
                    successfulTools.add(tc.name());
                }
            }

            if (!successfulTools.isEmpty()) {
                // 使用任务描述+工具序列生成稳定的技能ID
                String taskDesc = planner.hasActivePlan()
                        ? planner.currentMilestone().description()
                        : "general_task";
                // 技能ID：任务类型 + 主要工具 + 工具序列签名。
                // 序列签名确保不同动作序列生成不同技能ID，
                // 避免"挖铁矿"的序列被"挖煤矿"覆盖（两者主工具相同）。
                String primaryTool = successfulTools.get(0);
                String skillId = generateSkillId(taskDesc, primaryTool, successfulTools);

                skillLib.register(
                        skillId,
                        taskDesc,
                        taskDesc,
                        String.join("->", successfulTools),
                        anySuccess && !anyFailure);
            }
        }
    }

    /**
     * 失败分析结果
     */
    private record FailureAnalysis(
            boolean isFailure,
            boolean isSuccess,
            String errorMessage,
            String errorType
    ) {}

    /**
     * 分析工具结果，判断成功/失败（改进版：多信号融合）
     */
    private FailureAnalysis analyzeFailure(String content) {
        if (content == null) {
            return new FailureAnalysis(true, false, "null content", "unknown");
        }

        String lower = content.toLowerCase();

        // 异步派发确认：async 任务刚刚派发，终态未知。
        // 不计成功也不计失败 — 否则"派发成功"会被误记为"执行成功"，
        // 污染技能库（记录了未验证的序列）和情绪反馈。
        // 异步任务的真正成败由后续 task_finished 事件的结果记账。
        boolean isAsyncDispatch = lower.contains("\"async\":true") ||
                                  lower.contains("\"async\":1");

        // 成功信号（优先级高）
        boolean hasSuccessFlag = lower.contains("\"success\":true") ||
                                  lower.contains("\"success\":1") ||
                                  lower.contains("\"status\":\"success\"") ||
                                  lower.contains("\"result\":\"ok\"");

        // 失败信号
        boolean hasErrorFlag = lower.contains("\"error\"") ||
                                lower.contains("\"failed\"") ||
                                lower.contains("\"status\":\"error\"") ||
                                lower.contains("\"status\":\"failed\"");

        // 超时信号
        boolean hasTimeout = lower.contains("timed out") ||
                             lower.contains("timeout") ||
                             lower.contains("deadline exceeded");

        // 未找到信号
        boolean hasNotFound = lower.contains("not found") ||
                               lower.contains("no such") ||
                               lower.contains("cannot find") ||
                               lower.contains("doesn't exist");

        // 判断逻辑：
        // 0. 如果是异步派发确认且无失败标志 → 状态未知（不算成功/失败）
        // 1. 如果有明确成功标志且无失败标志 → 成功
        // 2. 如果有失败标志 → 失败
        // 3. 如果有超时/未找到且无成功标志 → 失败
        // 4. 默认 → 成功（无明确失败信号时假设成功）

        if (isAsyncDispatch && !hasErrorFlag && !hasTimeout) {
            return new FailureAnalysis(false, false, "", "async_pending");
        }

        if (hasSuccessFlag && !hasErrorFlag && !hasTimeout && !hasNotFound) {
            return new FailureAnalysis(false, true, "", "success");
        }

        if (hasErrorFlag) {
            String errorMsg = extractErrorMessage(content);
            String errorType = categorizeError(lower);
            return new FailureAnalysis(true, false, errorMsg, errorType);
        }

        if (hasTimeout) {
            return new FailureAnalysis(true, false, "操作超时", "timeout");
        }

        if (hasNotFound && !hasSuccessFlag) {
            return new FailureAnalysis(true, false, "未找到目标", "not_found");
        }

        // 默认成功
        return new FailureAnalysis(false, true, "", "success");
    }

    /**
     * 错误分类（通用模式，不依赖硬编码关键词）
     */
    private String categorizeError(String lowerContent) {
        if (lowerContent.contains("invalid") || lowerContent.contains("illegal") ||
            lowerContent.contains("missing") || lowerContent.contains("required")) {
            return "invalid_params";
        }
        if (lowerContent.contains("cannot") || lowerContent.contains("can't") ||
            lowerContent.contains("denied") || lowerContent.contains("forbidden")) {
            return "access_denied";
        }
        if (lowerContent.contains("timeout") || lowerContent.contains("timed out")) {
            return "timeout";
        }
        if (lowerContent.contains("not found") || lowerContent.contains("no such")) {
            return "not_found";
        }
        return "unknown";
    }

    /**
     * 生成稳定的技能ID（含工具序列签名，避免不同任务互相覆盖）
     */
    private String generateSkillId(String taskDesc, String primaryTool, List<String> toolSequence) {
        // 使用任务类型和工具名生成稳定的ID，避免hashCode冲突
        String normalizedTask = taskDesc.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "_")
                .replaceAll("_+", "_");
        if (normalizedTask.length() > 20) {
            normalizedTask = normalizedTask.substring(0, 20);
        }
        // 工具序列签名：相同序列=相同技能（应合并统计），
        // 不同序列=不同技能（不应互相覆盖）。
        String seqSignature = String.join("->", toolSequence);
        String seqHash = Integer.toHexString(seqSignature.hashCode());
        return primaryTool + "_" + normalizedTask + "_" + seqHash;
    }

    /**
     * 从工具结果 JSON 中提取 LLM 标注的 importance 字段。
     * LLM 可在工具调用参数中包含 "importance": "high"/"medium"/"low"
     * 来指导系统学习哪些事件重要。
     * 返回 null 表示未标注。
     */
    private static String extractImportanceLabel(String jsonContent) {
        if (jsonContent == null) return null;
        String lower = jsonContent.toLowerCase();
        int idx = lower.indexOf("\"importance\"");
        if (idx < 0) return null;
        int colon = jsonContent.indexOf(':', idx);
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < jsonContent.length()
                && (jsonContent.charAt(start) == ' ' || jsonContent.charAt(start) == '"')) {
            start++;
        }
        int end = jsonContent.indexOf('"', start);
        if (end < 0) end = jsonContent.length();
        return jsonContent.substring(start, end);
    }

    /** Extract a human-readable error message from a tool result JSON. */
    private static String extractErrorMessage(String jsonContent) {
        if (jsonContent == null) return "unknown error";
        // Simple extraction: find "error":"..." or "error":"..."
        int idx = jsonContent.indexOf("\"error\"");
        if (idx < 0) idx = jsonContent.indexOf("\"failed\"");
        if (idx < 0) return jsonContent.length() > 200
                ? jsonContent.substring(0, 200) : jsonContent;
        int colon = jsonContent.indexOf(':', idx);
        if (colon < 0) return jsonContent;
        int start = colon + 1;
        while (start < jsonContent.length()
                && (jsonContent.charAt(start) == ' ' || jsonContent.charAt(start) == '"')) {
            start++;
        }
        int end = jsonContent.indexOf('"', start);
        if (end < 0) end = jsonContent.length();
        return jsonContent.substring(start, end);
    }

    /**
     * Call the LLM with retry logic for transient errors and stale request detection.
     *
     * <p><b>Stale Request Detection</b> (borrowed from Mindcraft):
     * Before calling the LLM, record the current event generation.
     * After the LLM returns, check if a new event arrived during the call
     * (timestamp changed). If so, the response is stale — discard it and
     * return null, causing the caller to abort the turn. The inbox will
     * trigger a fresh turn with the new context.
     *
     * <p>This prevents the "AI answers a question from 10 seconds ago"
     * problem, and reduces latency for new messages by 5-10x.
     *
     * @return the LLM response, or null if all retries exhausted or stale
     */
    private LLMResponse callLLMWithRetry(LLMProvider provider,
                                          List<Map<String, Object>> toolDefs) {
        List<ChatMessage> messagesToSend;
        long callGeneration;
        // The generation must bracket the history copy, and the inbox must be
        // empty afterward. Re-snapshotting until stable could adopt an event
        // that had entered inbox but had not yet been drained into history,
        // yielding "new generation + old context" and accepting a stale reply.
        long beforeSnapshot = eventGeneration.get();
        synchronized (history) {
            messagesToSend = Collections.unmodifiableList(
                    new ArrayList<>(history));
        }
        long afterSnapshot = eventGeneration.get();
        boolean pendingInbox;
        synchronized (inboxLock) {
            pendingInbox = !inbox.isEmpty();
        }
        long finalGeneration = eventGeneration.get();
        if (pendingInbox || beforeSnapshot != afterSnapshot
                || afterSnapshot != finalGeneration) {
            return null;
        }
        callGeneration = finalGeneration;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            // A wake/cancel during retry backoff must abort before issuing
            // another request for obsolete history.
            if (eventGeneration.get() != callGeneration) return null;
            try {
                LLMResponse response;
                activeLlmThread = Thread.currentThread();
                try {
                    response = provider.complete(baseUrl, apiKey, model,
                            messagesToSend, toolDefs, temperature, maxTokens, reasoningEffort);
                } finally {
                    activeLlmThread = null;
                }

                // Stale Request check: if a significant event arrived during
                // the LLM call, discard the response. The situation it was
                // generated for no longer applies.
                if (eventGeneration.get() != callGeneration) {
                    // Provider interruption sets the worker's interrupt flag.
                    // Clear it before this single-thread executor starts the
                    // fresh turn queued in inbox.
                    Thread.interrupted();
                    System.out.println("[MineAgent] Stale request detected — "
                            + "discarding LLM response (event arrived during call)");
                    return null;
                }

                return response;
            } catch (RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                System.err.println("[MineAgent] LLM call attempt " + (attempt + 1)
                        + "/" + MAX_RETRIES + " failed: " + msg);

                // Check stale on retry too
                if (eventGeneration.get() != callGeneration) {
                    Thread.interrupted();
                    System.out.println("[MineAgent] Stale request detected during retry — aborting");
                    return null;
                }

                boolean retryable = e instanceof LLMProviderException providerError
                        && providerError.retryable();
                if (!retryable) {
                    System.err.println("[MineAgent] Permanent LLM failure; not retrying");
                    speakToOwner("\u00a7c[Error] LLM call failed: " + msg);
                    return null;
                }

                if (attempt < MAX_RETRIES - 1) {
                    try {
                        long delay = RETRY_BASE_MS * (1L << attempt); // exponential backoff
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    System.err.println("[MineAgent] All LLM retries exhausted");
                    // Notify the owner about the failure
                    speakToOwner("§c[Error] LLM call failed after " + MAX_RETRIES
                            + " retries: " + msg);
                    return null;
                }
            }
        }
        return null;
    }

    private void interruptActiveLlmRequest() {
        Thread requestThread = activeLlmThread;
        if (requestThread != null && requestThread != Thread.currentThread()) {
            requestThread.interrupt();
        }
    }

    /**
     * Trim the conversation history to prevent context overflow.
     * Always keeps the system prompt (index 0) and trims the oldest
     * non-system messages when the history exceeds MAX_HISTORY.
     *
     * <p><b>Important:</b> When trimming, we must keep assistant messages
     * that contain {@code tool_calls} together with their corresponding
     * {@code tool} result messages. If we cut an assistant tool_calls message
     * but keep the following tool results, the OpenAI-compatible API will
     * reject the request with "Messages with role 'tool' must be a response
     * to a preceding message with 'tool_calls'".
     */
    private void trimHistory() {
        synchronized (history) {
            if (history.size() <= MAX_HISTORY + 1) return;

            int oldSize = history.size();

            // Preserve the system prompt (index 0)
            ChatMessage systemPrompt = history.get(0);

            // Keep only the most recent MAX_HISTORY messages (after system prompt)
            int start = history.size() - MAX_HISTORY;

            // Adjust start forward if it lands on a "tool" message whose
            // corresponding assistant tool_calls message was cut.
            while (start < history.size()) {
                ChatMessage m = history.get(start);
                if ("tool".equals(m.role())) {
                    // This tool message's parent assistant message was cut;
                    // skip this orphaned tool message too.
                    start++;
                } else if ("assistant".equals(m.role())
                        && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    // This is an assistant message with tool_calls. Check if
                    // all its tool results are also within the kept range.
                    // If some results are missing (cut off), drop this assistant
                    // message too.
                    int needed = m.toolCalls().size();
                    int found = 0;
                    for (int i = start + 1; i < history.size() && found < needed; i++) {
                        ChatMessage nm = history.get(i);
                        if ("tool".equals(nm.role()) && m.toolCalls().stream()
                                .anyMatch(tc -> tc.id().equals(nm.toolCallId()))) {
                            found++;
                        } else if ("assistant".equals(nm.role())) {
                            // Reached next assistant — stop counting
                            break;
                        }
                    }
                    if (found < needed) {
                        // Some tool results missing — drop this assistant msg
                        start++;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }

            // ── Compress dropped messages into a summary ──
            // Instead of discarding old messages entirely, fold them into
            // a compact "[SUMMARY]" system message so the LLM retains
            // context without paying full token cost. Only summarize the
            // messages between index 1 (after system prompt) and `start`
            // that are being dropped.
            List<ChatMessage> trimmed = new ArrayList<>();
            trimmed.add(systemPrompt);

            if (start > 1) {
                String summary = summarizeOldMessages(1, start);
                if (!summary.isBlank()) {
                    trimmed.add(ChatMessage.system("[过往对话摘要] " + summary));
                }
            }

            // Build the kept range, filtering out any orphan tool messages
            // whose parent assistant was cut (not in the kept range). The
            // while loop above only advances `start` past orphan tools that
            // land EXACTLY at the start position; it cannot remove orphans
            // that appear after a kept assistant's valid tool results, or
            // after a user/system message (where break leaves start at the
            // user message but start+1 may be an orphan tool). Filtering
            // here catches every case without breaking valid assistant→tool
            // pairings.
            List<ChatMessage> kept = new ArrayList<>();
            for (int i = start; i < history.size(); i++) {
                ChatMessage m = history.get(i);
                if ("tool".equals(m.role())
                        && !hasParentAssistantInRange(kept, m)) {
                    // Orphan tool message — its parent assistant was cut.
                    continue;
                }
                kept.add(m);
            }
            trimmed.addAll(kept);

            history.clear();
            history.addAll(trimmed);

            System.out.println("[MineAgent] Trimmed history from "
                    + oldSize + " to " + history.size() + " messages ("
                    + (start > 1 ? "summarized " + (start - 1) + " old msgs" : "no summary")
                    + ")");
        }
    }

    /**
     * Summarize a range of old history messages into a compact string.
     * Extracts only the essential info: what tools were called, what the
     * outcomes were, and any key decisions. Each message becomes ~1 line.
     *
     * <p><b>记忆保护</b>：玩家指令保留前 200 字符（而非 80），确保
     * 重要任务指令不被截断。检测到的重要事件（死亡/钻石/危险）会
     * 标记为【重要】以便 LLM 优先关注。
     *
     * @param fromIndex inclusive start index in history
     * @param toIndex exclusive end index in history
     * @return compact summary, or empty string if nothing to summarize
     */
    private String summarizeOldMessages(int fromIndex, int toIndex) {
        if (fromIndex >= toIndex) return "";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = fromIndex; i < toIndex && count < 25; i++) {
            ChatMessage m = history.get(i);
            String role = m.role();
            String content = m.content();
            if (content == null) content = "";

            // Skip empty content (e.g. assistant messages with only tool_calls)
            if (content.isBlank() && m.toolCalls() == null) continue;

            // 动态重要性评估：用 ImportanceEvaluator 取代硬编码关键词
            // 玩家指令始终视为重要（用户说的话都不能丢）
            boolean important = false;
            if ("user".equals(role)) {
                important = true;
            } else if ("tool".equals(role) && i > 0) {
                // 查找对应的工具名
                String toolName = findToolNameForResult(history, i);
                important = importance.isImportant(toolName, content);
            } else {
                important = importance.isImportant("text", content);
            }

            if ("user".equals(role)) {
                // 玩家指令：保留前 200 字符（不是 80），防止任务指令被截断
                String snippet = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                sb.append("玩家: ").append(snippet).append("; ");
                count++;
            } else if ("assistant".equals(role)) {
                // Assistant: if has tool_calls, list tool names; else first 100 chars of text
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    StringBuilder tools = new StringBuilder();
                    for (var tc : m.toolCalls()) {
                        if (tools.length() > 0) tools.append(",");
                        tools.append(tc.name());
                    }
                    sb.append("AI调用[").append(tools).append("]; ");
                } else if (!content.isBlank()) {
                    String snippet = content.length() > 100 ? content.substring(0, 100) + "..." : content;
                    sb.append("AI: ").append(snippet).append("; ");
                }
                count++;
            } else if ("tool".equals(role)) {
                // Tool result: note success/error + 是否包含重要信息
                String lower = content.toLowerCase();
                if (lower.contains("error") || lower.contains("failed")) {
                    sb.append("(失败); ");
                } else if (important) {
                    sb.append("(成功★); ");
                } else {
                    sb.append("(成功); ");
                }
                count++;
            } else if ("system".equals(role)) {
                // System messages: preserve accumulated summary from previous
                // trimHistory passes, and keep important body-log events.
                if (content.startsWith("[过往对话摘要]")) {
                    // Accumulate the previous summary instead of discarding it.
                    // Without this branch, every trim would drop the prior
                    // summary and the LLM would lose ALL memory older than the
                    // recent window — a slow memory leak of long-term context.
                    // Extract everything after the "[过往对话摘要]" tag.
                    int bracketEnd = content.indexOf(']');
                    String prevSummary = bracketEnd >= 0 && bracketEnd + 1 < content.length()
                            ? content.substring(bracketEnd + 1).trim() : "";
                    if (!prevSummary.isBlank()) {
                        sb.append(prevSummary).append(" | ");
                        count++;
                    }
                } else if (content.startsWith("[EVENTS]") && important) {
                    // Body log events: skip transient ones, but keep important
                    String snippet = content.length() > 80 ? content.substring(0, 80) + "..." : content;
                    sb.append("[事件] ").append(snippet).append("; ");
                    count++;
                }
                continue;
            }

            // 重要事件追加标记
            if (important && sb.length() > 0) {
                // 在最后一个分号前插入★标记
                int lastSemi = sb.lastIndexOf("; ");
                if (lastSemi >= 0) {
                    sb.insert(lastSemi, "【重要】");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 为工具结果消息查找对应的工具名。
     * 向上查找最近的带 tool_calls 的 assistant 消息，匹配 toolCallId。
     */
    private static String findToolNameForResult(List<ChatMessage> history, int resultIndex) {
        if (resultIndex <= 0 || resultIndex >= history.size()) return "unknown";
        ChatMessage result = history.get(resultIndex);
        String resultId = result.toolCallId();
        if (resultId == null) return "unknown";
        // 向上查找匹配的 assistant tool_calls
        for (int i = resultIndex - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m.toolCalls() == null) continue;
            for (var tc : m.toolCalls()) {
                if (resultId.equals(tc.id())) return tc.name();
            }
        }
        return "unknown";
    }

    /**
     * Check whether a tool message has a parent assistant (with a matching
     * tool_call id) inside the already-kept portion of history. Used by
     * {@link #trimHistory()} to detect orphan tool messages whose parent
     * assistant was cut — those must be dropped, otherwise the
     * OpenAI-compatible API rejects the request with
     * "Messages with role 'tool' must be a response to a preceding message
     * with 'tool_calls'".
     *
     * @param kept    the messages already selected for keeping (in order)
     * @param toolMsg the tool message whose parent we are looking for
     * @return true if a matching assistant exists in {@code kept}
     */
    private static boolean hasParentAssistantInRange(List<ChatMessage> kept,
                                                       ChatMessage toolMsg) {
        String tcId = toolMsg.toolCallId();
        if (tcId == null) return false;
        // Scan backwards: tool results only follow their parent assistant,
        // so the parent — if kept — is the most recent assistant before us.
        for (int i = kept.size() - 1; i >= 0; i--) {
            ChatMessage pa = kept.get(i);
            if ("assistant".equals(pa.role()) && pa.toolCalls() != null) {
                for (var tc : pa.toolCalls()) {
                    if (tcId.equals(tc.id())) {
                        return true;
                    }
                }
            }
            // If we hit a user/system message, the tool's parent (if any)
            // is before this point and therefore NOT in the kept range.
            if ("user".equals(pa.role()) || "system".equals(pa.role())) {
                break;
            }
        }
        return false;
    }

    /**
     * Speak a message to the owner in-game.
     * Sends the AI's text response as a chat message to the owner player.
     */
    private void speakToOwner(String text) {
        Services.platform().scheduleOnServer(() -> {
            try {
                var owner = ((com.mineagent.engine.entity.CompanionEntity) companion).serverPlayerOwner();
                if (owner != null && owner.connection != null) {
                    // Send as a chat message with companion name prefix
                    owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§b[" + companion.companionName() + "]§r " + text));
                    // Push to the companion chat screen (C key) so the message
                    // also appears there — the vanilla system message only
                    // shows in the regular chat HUD.
                    com.mineagent.engine.network.MineAgentNetwork.sendUiActionTo(
                            owner, companion.companionId(), "companion_chat",
                            "[" + companion.companionName() + "] " + text);
                }
                // Broadcast to other companions so AIs can hear each other
                // and collaborate. This does NOT cause loops because messages
                // received from other companions are tagged "[同伴 ...]" and
                // do not trigger another speakToOwner unless the model
                // explicitly decides to respond.
                // Guard against null owner (player offline): broadcastToOtherCompanions
                // needs the owner UUID to find sibling companions, and calling
                // owner.getUUID() on a null reference would NPE. Skip the
                // broadcast in that case — sibling AIs will simply not hear
                // this utterance until the owner reconnects.
                if (owner != null) {
                    com.mineagent.engine.MineAgentEngine.broadcastToOtherCompanions(
                            companion.companionId(), owner.getUUID(), text);
                }
            } catch (Exception e) {
                System.err.println("[MineAgent] Failed to send message to owner: " + e.getMessage());
            }
            // Also log to console for debugging
            System.out.println("[MineAgent][" + companion.companionName() + "] " + text);
        });
    }

    private void drainInbox() {
        synchronized (inboxLock) {
            if (inbox.isEmpty()) return;
            var batch = new ArrayList<>(inbox);
            inbox.clear();
            // Compose a system message with accumulated body logs
            StringBuilder sb = new StringBuilder("[EVENTS]\n");
            for (String entry : batch) {
                sb.append("- ").append(entry).append("\n");
            }
            synchronized (history) {
                history.add(ChatMessage.system(sb.toString()));
            }
        }
    }

    // ── Tool execution ─────────────────────────────────────────────

    private List<ChatMessage> executeToolCalls(List<ChatMessage.ToolCallRef> toolCalls) {
        // Each ToolExecution carries its own toolCallId and toolName so that
        // results are matched correctly even when some tools are skipped
        // (unknown tool / invalid args). Previously the wait loop indexed into
        // `toolCalls` with the `executions` index, which misaligned whenever a
        // tool was skipped — causing toolCallId/result mismatches.
        record ToolExecution(String toolCallId, String toolName,
                             CompletableFuture<String> result,
                             long deadlineNanos) {}
        List<ToolExecution> executions = new ArrayList<>();
        // Collect results keyed by toolCallId so the final list can be rebuilt
        // in the original toolCalls order. This keeps analyzeToolResults (which
        // pairs toolCalls and toolResults by index) correct.
        Map<String, ChatMessage> resultsById = new LinkedHashMap<>();

        for (var tc : toolCalls) {
            Optional<Tool> toolOpt = ToolRegistry.get(tc.name());
            if (toolOpt.isEmpty()) {
                resultsById.put(tc.id(), ChatMessage.toolResult(tc.id(),
                        jsonError("Unknown tool: " + tc.name())));
                continue;
            }

            Tool tool = toolOpt.get();
            com.google.gson.JsonObject args;
            try {
                args = com.google.gson.JsonParser.parseString(tc.arguments()).getAsJsonObject();
            } catch (Exception e) {
                resultsById.put(tc.id(), ChatMessage.toolResult(tc.id(),
                        jsonError("Invalid arguments: " + e.getMessage())));
                continue;
            }

            // CompletableFuture gives each invocation a single terminal result.
            // complete() is atomic, so duplicate callbacks and callbacks arriving
            // after timeout cannot overwrite the value consumed by the agent loop.
            int timeout = tool.defaultTimeoutSeconds();
            CompletableFuture<String> result = new CompletableFuture<>();
            long deadlineNanos = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(Math.max(1, timeout));
            executions.add(new ToolExecution(tc.id(), tc.name(), result,
                    deadlineNanos));

            // Execute on server thread
            Services.platform().scheduleOnServer(() -> {
                try {
                    tool.onServerCall(tc.id(), args, companion, callbackResult -> {
                        result.complete(callbackResult != null
                                ? callbackResult : "{\"success\":true}");
                    });
                } catch (Throwable t) {
                    // A tool throwing (e.g. NPE from missing/invalid args)
                    // must not leak into the server thread and must not
                    // leave the latch hanging until timeout — reply with a
                    // meaningful error so the LLM can correct its call.
                    System.err.println("[MineAgent] Tool '" + tc.name()
                            + "' threw: " + t);
                    result.complete(jsonError("Tool '" + tc.name()
                            + "' failed: " + t.getClass().getSimpleName()
                            + (t.getMessage() != null ? " - " + t.getMessage() : "")));
                }
            });
        }

        // Wait for all dispatched tools to complete. Use exec.toolCallId()
        // (NOT toolCalls.get(i).id()) so the result is always matched to the
        // correct toolCallId regardless of how many tools were skipped above.
        for (ToolExecution exec : executions) {
            try {
                // All tools were dispatched together. Waiting each one's full
                // timeout sequentially made N calls take N * timeout seconds.
                long remaining = exec.deadlineNanos() - System.nanoTime();
                String rawResult;
                if (remaining <= 0) {
                    rawResult = "{\"error\":\"Tool execution timed out\"}";
                    exec.result().complete(rawResult);
                } else {
                    try {
                        rawResult = exec.result().get(remaining, TimeUnit.NANOSECONDS);
                    } catch (TimeoutException e) {
                        rawResult = "{\"error\":\"Tool execution timed out\"}";
                        exec.result().complete(rawResult);
                    }
                }
                {
                    // Truncate long tool results to keep the conversation compact.
                    // 截断长度根据工具类型差异化：
                    // - look_around/scan: 1200 字符（需要看到周围环境，不能太短）
                    // - status/inventory: 600 字符（中等）
                    // - 其他: 800 字符（默认）
                    // 危险信息优先保留。
                    int maxChars = getToolResultLimit(exec.toolName());
                    String truncated = truncateToolResult(rawResult, maxChars, exec.toolName());
                    resultsById.put(exec.toolCallId(),
                            ChatMessage.toolResult(exec.toolCallId(), truncated));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exec.result().complete("{\"error\":\"Interrupted\"}");
                resultsById.put(exec.toolCallId(), ChatMessage.toolResult(exec.toolCallId(),
                        "{\"error\":\"Interrupted\"}"));
            } catch (ExecutionException e) {
                resultsById.put(exec.toolCallId(), ChatMessage.toolResult(exec.toolCallId(),
                        jsonError("Tool execution failed: " + e.getCause())));
            }
        }

        // Build the results list in the original toolCalls order so that
        // analyzeToolResults (which pairs by index) works correctly.
        List<ChatMessage> results = new ArrayList<>(toolCalls.size());
        for (var tc : toolCalls) {
            ChatMessage r = resultsById.get(tc.id());
            if (r != null) results.add(r);
        }
        return results;
    }

    /**
     * Provider relays occasionally omit tool-call ids or repeat one id in the
     * same assistant message. Tool results are correlated by that id, so such
     * a response would overwrite a sibling result and leave malformed history.
     */
    private static ChatMessage normalizeToolCallIds(ChatMessage message) {
        if (message.toolCalls() == null || message.toolCalls().isEmpty()) return message;

        Set<String> seen = new HashSet<>();
        List<ChatMessage.ToolCallRef> normalized = new ArrayList<>(message.toolCalls().size());
        boolean changed = false;
        for (ChatMessage.ToolCallRef call : message.toolCalls()) {
            String id = call.id();
            if (id == null || id.isBlank() || !seen.add(id)) {
                do {
                    id = "call_" + UUID.randomUUID().toString().replace("-", "");
                } while (!seen.add(id));
                changed = true;
            }
            String arguments = call.arguments();
            if (arguments == null || arguments.isBlank()) {
                arguments = "{}";
                changed = true;
            }
            normalized.add(new ChatMessage.ToolCallRef(id, call.name(), arguments));
        }
        return changed
                ? new ChatMessage(message.role(), message.content(), normalized,
                        message.toolCallId())
                : message;
    }

    /**
     * 根据工具名获取结果截断长度。
     * 感知类工具（look_around）需要更长，简单动作类可以更短。
     */
    private static int getToolResultLimit(String toolName) {
        if (toolName == null) return 800;
        String lower = toolName.toLowerCase(Locale.ROOT);
        // 感知类：需要完整环境信息，给 1200 字符
        if (lower.contains("look") || lower.contains("scan")
                || lower.contains("search") || lower.contains("find")) {
            return 1200;
        }
        // 状态类：中等长度，600 字符
        if (lower.contains("status") || lower.contains("inventory")
                || lower.contains("health") || lower.contains("check")) {
            return 600;
        }
        // 默认：800 字符
        return 800;
    }

    private static String jsonError(String message) {
        com.google.gson.JsonObject error = new com.google.gson.JsonObject();
        error.addProperty("error", message == null ? "Unknown error" : message);
        return error.toString();
    }

    /**
     * Truncate a tool result string to a maximum length.
     *
     * <p><b>安全截断</b>：先尝试在截断点找最后一个完整的 JSON 对象/数组
     * 元素并正确闭合结构，再用 {@link com.google.gson.JsonParser} 校验。
     * 如果修复后仍不是合法 JSON（嵌套过深、字符串内含截断点等），
     * 回退到纯文本 "结果过长已截断: ..." —— 纯文本虽然不是 JSON，但
     * 至少不会让 LLM 解析失败或产生幻觉字段。
     *
     * @param result raw tool result
     * @param maxChars maximum characters to keep
     * @param toolName tool name (for importance-aware truncation)
     * @return truncated result (possibly with a truncation marker appended)
     */
    private String truncateToolResult(String result, int maxChars, String toolName) {
        if (result == null || result.length() <= maxChars) return result;

        // 动态重要性评估：用 ImportanceEvaluator 判断是否需要保留重要片段
        // 如果整个结果被判定为重要，给更多空间（1.3倍）
        boolean isImportant = importance.isImportant(toolName, result);
        if (isImportant) {
            maxChars = (int)(maxChars * 1.3);  // 重要结果多保留30%
        }

        String truncated = result.substring(0, Math.min(maxChars, result.length()));

        // 尝试产生合法 JSON：在截断点找最后一个完整元素，闭合结构，
        // 并追加 "truncated":true 标记。校验通过才采用。
        String repaired = tryRepairJsonTruncation(truncated, maxChars);
        if (repaired != null) {
            return repaired;
        }
        // 回退：纯文本。LLM 仍能读懂内容，只是无法当 JSON 解析。
        // 这比发出畸形 JSON（导致解析错误或幻觉字段）安全得多。
        return "结果过长已截断 (原始长度=" + result.length() + " 字符): " + truncated;
    }

    /**
     * 尝试把截断后的字符串修复为合法 JSON。
     *
     * <p>策略：
     * <ul>
     *   <li>对象 ({...})：找最后一个 "}," 截断点，保留到该处，追加
     *       "truncated":true 并闭合 }。</li>
     *   <li>数组 ([...])：找最后一个 "}," 截断点，保留到该处，追加
     *       {"truncated":true} 并闭合 ]。</li>
     *   <li>非 JSON（纯文本）：直接追加截断标记。</li>
     * </ul>
     * 每个候选都用 JsonParser 校验，失败则返回 null 让调用方回退到纯文本。
     */
    private static String tryRepairJsonTruncation(String truncated, int maxChars) {
        String trimmed = truncated.trim();
        try {
            if (trimmed.startsWith("{")) {
                // 对象：优先在最后一个完整字段后截断
                int lastComplete = truncated.lastIndexOf("},");
                if (lastComplete > maxChars / 2) {
                    String candidate = truncated.substring(0, lastComplete + 1)
                            + "\"truncated\":true}";
                    if (isValidJson(candidate)) return candidate;
                }
                // 直接闭合对象
                String candidate = truncated + ",\"truncated\":true}";
                if (isValidJson(candidate)) return candidate;
            } else if (trimmed.startsWith("[")) {
                // 数组：优先在最后一个完整元素后截断
                int lastComplete = truncated.lastIndexOf("},");
                if (lastComplete > maxChars / 2) {
                    String candidate = truncated.substring(0, lastComplete + 1)
                            + "{\"truncated\":true}]";
                    if (isValidJson(candidate)) return candidate;
                }
                String candidate = truncated + ",{\"truncated\":true}]";
                if (isValidJson(candidate)) return candidate;
            } else {
                // 非 JSON 纯文本：追加截断标记即可
                return truncated + "...[已截断]";
            }
        } catch (Exception e) {
            // 任何异常都回退到纯文本
        }
        return null;
    }

    /** 用 Gson 校验字符串是否为合法 JSON。 */
    private static boolean isValidJson(String s) {
        try {
            com.google.gson.JsonParser.parseString(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Build a cache key for the current decision context.
     *
     * <p>The key quantizes the AI's situation so that similar states
     * (same rough position, health tier, hunger tier, last owner command)
     * map to the same cache entry. This lets us skip the LLM call when
     * the situation hasn't meaningfully changed.
     *
     * <p>Quantization is intentionally coarse:
     * <ul>
     *   <li>Position: 4-block grid (a 4×4×4 cube shares one key)</li>
     *   <li>Health: 4 tiers (0-4, 5-9, 10-14, 15-20)</li>
     *   <li>Hunger: 3 tiers (0-6, 7-14, 15-20)</li>
     *   <li>Last owner command: included verbatim (most important signal)</li>
     * </ul>
     */
    private String buildDecisionCacheKey() {
        try {
            int x = (int) companion.posX();
            int y = (int) companion.posY();
            int z = (int) companion.posZ();
            float health = companion.health();
            int food = companion.foodLevel();
            int healthTier = health < 5 ? 0 : health < 10 ? 1 : health < 15 ? 2 : 3;
            int hungerTier = food < 7 ? 0 : food < 15 ? 1 : 2;
            String lastCmd = lastOwnerCommand;
            return DecisionCache.buildKey(x, y, z, healthTier, hungerTier,
                    null, lastCmd);
        } catch (Exception e) {
            return null;
        }
    }

    /** Track the last owner command for cache keying. */
    private volatile String lastOwnerCommand = "";

    /** 上次玩家消息的时间戳（用于决策缓存安全门）。 */
    private volatile long lastOwnerMessageTime = 0;

    /** 连续缓存命中次数（达到上限后强制走 LLM）。 */
    private int consecutiveCacheHits = 0;

    /** 当前回合内的补救次数（防止无限递归）。每个 turn 开始时重置。 */
    private int remediationRoundCounter = 0;

    /**
     * 决策缓存安全门 — 判断当前是否应跳过缓存。
     *
     * <p>跳过缓存的场景：
     * <ul>
     *   <li>玩家最近 30 秒内说过话（对话需要 LLM 真正理解，不能复用）</li>
     *   <li>连续缓存命中已达上限（防止"卡带"式无限循环）</li>
     * </ul>
     */
    private boolean shouldSkipCache() {
        // 1. 玩家最近说过话 → 不缓存
        if (lastOwnerMessageTime > 0) {
            long elapsed = System.currentTimeMillis() - lastOwnerMessageTime;
            if (elapsed < CACHE_SKIP_AFTER_PLAYER_MSG_MS) {
                return true;
            }
        }
        // 2. 连续命中已达上限 → 强制走 LLM
        if (consecutiveCacheHits >= MAX_CONSECUTIVE_CACHE_HITS) {
            return true;
        }
        return false;
    }

    /**
     * 检查 LLM 响应是否显式声明了"无行动"标记。
     *
     * <p>基于 NLT (Natural Language Tools, arxiv 2510.14453) 和 Reflexion
     * 的 "explicit intent declaration" 模式。系统不再通过脆弱的关键词列表
     * 猜测文本是否包含行动意图，而是要求 LLM 在不需要执行任何身体动作时
     * 显式标注 [NO_ACTION] 或 【无行动】 标记。
     *
     * <p>这完全替代了之前的 containsActionIntent() 关键词列表方法：
     * <ul>
     *   <li>多语言无关：标记本身是结构化的，不依赖任何语言的关键词</li>
     *   <li>LLM 自主判断：由 LLM 自己决定是否需要行动，而非系统猜测</li>
     *   <li>无遗漏：只要 LLM 遵守协议，所有"只说不做"的情况都能被捕获</li>
     *   <li>零维护：无需随场景变化更新关键词列表</li>
     * </ul>
     *
     * <p>用法：当 LLM 返回纯文本响应（无 tool_calls）时：
     * <ul>
     *   <li>有标记 → LLM 明确声明无行动 → 结束回合，可缓存</li>
     *   <li>无标记 → LLM 可能漏掉了工具调用 → 触发补救机制</li>
     * </ul>
     *
     * @param text LLM 响应文本
     * @return true 如果文本包含显式无行动标记
     */
    private static boolean hasExplicitNoActionMarker(String text) {
        if (text == null || text.isBlank()) return false;
        // 接受中英文多种标记形式（LLM 可能用任意一种，标记可在文本任意位置）
        // 故意接受多种变体以提高鲁棒性：
        // - [NO_ACTION] / 【无行动】 是系统提示词中明示的两种主标记
        // - (无行动) / [无行动] / （NO_ACTION） 是常见输入变体，作为容错
        return text.contains("[NO_ACTION]")
            || text.contains("【无行动】")
            || text.contains("(无行动)")
            || text.contains("（无行动）")
            || text.contains("[无行动]")
            || text.contains("（NO_ACTION）");
    }

    /**
     * 将文本截断到指定长度用于日志输出。
     * 超长部分用 "..." 替代，并移除换行符避免日志污染。
     */
    private static String truncateForLog(String text, int maxLen) {
        if (text == null) return "";
        String single = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (single.length() <= maxLen) return single;
        return single.substring(0, maxLen) + "...";
    }

    /**
     * 检查给定文本是否与 history 中最后一条 assistant 消息相同。
     * 用于防止缓存命中时回放与上次完全相同的回复（"卡带"循环）。
     */
    private boolean isSameAsLastAssistant(String text) {
        if (text == null) return false;
        synchronized (history) {
            for (int i = history.size() - 1; i >= 0; i--) {
                ChatMessage msg = history.get(i);
                if ("assistant".equals(msg.role())) {
                    String lastContent = msg.content();
                    if (lastContent == null) return false;
                    // Trim whitespace for comparison
                    return text.trim().equalsIgnoreCase(lastContent.trim());
                }
            }
        }
        return false;
    }

    private LLMProvider resolveProvider() {
        // ── Provider selection ──
        // Model switching is NOT allowed at runtime — each companion is bound
        // to one (provider, model, baseUrl) triple for its entire lifetime.
        //
        // Selection order:
        //   1. If providerId is set AND it supports the current model,
        //      use it. This lets the user explicitly choose a provider for
        //      a relay URL (e.g. picking "anthropic" when the relay speaks
        //      Anthropic native format, or "openai" when it speaks
        //      OpenAI-compatible format for the same Claude model).
        //   2. Otherwise auto-detect from the model name. This handles
        //      official URLs and the common case where providerId was
        //      not set in config.
        //
        // Note: We deliberately do NOT force OpenAI format for relay URLs —
        // many relays support Anthropic /v1/messages and Gemini /v1beta/...
        // natively, and the user's choice of providerId is authoritative.
        if (providerId != null && !providerId.isBlank()) {
            // An explicit provider is the wire-protocol choice, not a model
            // catalogue hint. Relays and local gateways routinely expose
            // custom model IDs which are absent from defaultModels(); rejecting
            // them here made valid OpenAI-compatible configurations unusable.
            return LLMProviderRegistry.get(providerId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Unknown LLM provider: " + providerId));
        }
        return LLMProviderRegistry.detectForModel(model)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot detect provider for model: " + model
                                + " (providerId=" + providerId + ")"));
    }

    private List<Map<String, Object>> buildToolDefinitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (Tool tool : ToolRegistry.all()) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("type", "function");
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", tool.parameterSchema());
            def.put("function", function);
            defs.add(def);
        }
        return defs;
    }

    /**
     * Rebuild and replace the system prompt (history[0]) with fresh
     * dynamic context from all cognitive subsystems. Called at the
     * start of each turn so the LLM always sees current persona,
     * emotion, memory, plan, and reflection state.
     */
    private void refreshSystemPrompt() {
        String fresh = buildSystemPrompt();
        synchronized (history) {
            if (!history.isEmpty()) {
                history.set(0, ChatMessage.system(fresh));
            } else {
                history.add(ChatMessage.system(fresh));
            }
        }
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();

        // ── Core identity ──
        sb.append("You are MineAgent, an AI player in Minecraft — NOT an NPC, NOT a bot.\n");
        sb.append("You are a real player entity with a body, inventory, health, and hunger.\n");
        sb.append("You think, decide, and act like an experienced human player would.\n\n");

        // ── Cognitive framework: HOW to think, not WHAT to know ──
        sb.append("## How You Think\n");
        sb.append("You already know Minecraft inside out — recipes, mob behaviors, ");
        sb.append("ore depths, crafting chains, combat tactics, redstone, farming, ");
        sb.append("potion brewing, enchanting, villager trading, all of it.\n");
        sb.append("Your job is NOT to recite that knowledge, but to USE it ");
        sb.append("to make smart decisions in real-time.\n\n");

        sb.append("### The Think-Act Loop\n");
        sb.append("Before EVERY action, run through this mental checklist:\n");
        sb.append("1. **Observe** — What's around me? What's my status? What changed?\n");
        sb.append("2. **Assess** — Am I in danger? Am I hungry? Am I equipped for this?\n");
        sb.append("3. **Decide** — What's the best next action? What do I need first?\n");
        sb.append("4. **Prepare** — Do I have the right tools? If not, make them first.\n");
        sb.append("5. **Act** — Execute, then observe the result and adapt.\n\n");

        sb.append("### Task Decomposition\n");
        sb.append("When given ANY task — no matter how complex — break it down:\n");
        sb.append("- 'Go mining' → What tools do I need? → Do I have them? ");
        sb.append("→ If not, what do I need to make them? → Start from the beginning.\n");
        sb.append("- 'Build a farm' → What crops? → Where's water? → ");
        sb.append("Do I have a hoe? → Seeds? → Plan the layout first.\n");
        sb.append("- 'Explore the Nether' → Do I have armor? Fire resistance? ");
        sb.append("Food? A way back home? → Prepare ALL of this before entering.\n");
        sb.append("- ANY task → What's the prerequisite? → What's ITS prerequisite? ");
        sb.append("→ Recurse until you reach something you can do RIGHT NOW.\n\n");

        sb.append("### Situational Judgment\n");
        sb.append("There is no fixed playbook. Read the situation and adapt:\n");
        sb.append("- See a creeper? Don't charge in — assess: ");
        sb.append("can I kill it in time? Should I retreat? Is the owner nearby?\n");
        sb.append("- Found diamonds? Think: is this area safe? ");
        sb.append("Do I have an iron pickaxe? Torches? An exit?\n");
        sb.append("- Night falling? Decide: fight through it, build a shelter, ");
        sb.append("or sleep? Depends on my gear and the owner's plan.\n");
        sb.append("- Owner is in danger? DROP everything. Protect them. No task ");
        sb.append("matters more than the owner's survival.\n\n");

        sb.append("### Autonomous Decision-Making\n");
        sb.append("You don't need to be told every little step. If the owner says ");
        sb.append("'go gather wood', you know that means: find trees, punch them, ");
        sb.append("craft planks, maybe make tools. You fill in the gaps yourself.\n");
        sb.append("If you encounter a problem the owner didn't anticipate, ");
        sb.append("solve it yourself. Only ask the owner when YOU genuinely ");
        sb.append("can't decide.\n\n");

        // ── Decision priority ──
        sb.append("## Priority When Multiple Things Compete\n");
        sb.append("1. **Immediate survival** — About to die? Handle that first.\n");
        sb.append("2. **Owner in danger** — Help them, even if it costs you.\n");
        sb.append("3. **Owner's explicit request** — What they asked for right now.\n");
        sb.append("4. **Supporting the owner** — Things that help them indirectly.\n");
        sb.append("5. **Self-improvement** — Better gear, more food, safer base.\n");
        sb.append("When in doubt, communicate: tell the owner what you're prioritizing and why.\n\n");

        // ── Tool usage ──
        sb.append("## Your Tools\n");
        sb.append("You perceive and act through tools. Key ones:\n");
        sb.append("- `get_self_status`: Check your health, hunger, inventory, equipment\n");
        sb.append("- `look_around`: See terrain, blocks, entities, hazards around you\n");
        sb.append("- `scan_nearby_entities` / `scan_blocks`: Find specific things\n");
        sb.append("- `goto`: Move to a location (xz for horizontal, xzy for exact)\n");
        sb.append("- `auto_mine`: Dig blocks / `build`: Place blocks\n");
        sb.append("- `collect_items`: Pick up drops\n");
        sb.append("- `equip_item`: Switch tools/armor in your hotbar\n");
        sb.append("- `craft`: Create items from recipes\n");
        sb.append("- `lookup_recipe`: Query crafting recipes by input/output. ");
        sb.append("USE THIS before crafting to know exact ingredients — don't guess.\n");
        sb.append("- `recall_memory`: Recall places you've discovered (ores, structures, hazards). ");
        sb.append("USE THIS before exploring — you may already know where to find what you need.\n");
        sb.append("- `list_learned_skills`: See skills you've mastered from past successes. ");
        sb.append("Reuse them instead of re-planning common tasks.\n");
        sb.append("Read tool results carefully. If a tool fails, think about WHY ");
        sb.append("and try a different approach.\n\n");

        // ── Critical: action requires tools ──
        sb.append("## CRITICAL: Action Requires Tools\n");
        sb.append("Talking is NOT doing. If you want to move, you MUST call `goto`. ");
        sb.append("If you want to mine, you MUST call `auto_mine`. ");
        sb.append("Saying 'I'll go check over there' without calling the tool ");
        sb.append("means you stay exactly where you are. ");
        sb.append("NEVER describe an action without executing it via a tool call. ");
        sb.append("Your words are for communication with the owner; ");
        sb.append("your tools are for actually doing things.\n\n");

        // ── Action Intent Declaration Protocol (replaces keyword detection) ──
        // Based on NLT (arxiv 2510.14453) and Reflexion's explicit intent
        // declaration pattern. Instead of system guessing whether text
        // contains action intent (fragile keyword matching), require LLM
        // to EXPLICITLY declare its intent state every turn.
        sb.append("## Action Intent Declaration Protocol (MANDATORY)\n");
        sb.append("Every response you produce MUST fall into exactly one of two categories:\n");
        sb.append("1. **Action response**: You call at least one tool to perform a physical ");
        sb.append("action (move/mine/build/attack/craft/eat/place/collect/etc.). ");
        sb.append("Text may accompany the tool call to explain what you're doing.\n");
        sb.append("2. **No-action response**: You have NO physical action to perform ");
        sb.append("this turn (pure conversation, status report, waiting, ");
        sb.append("acknowledgment). In this case, you MUST include the marker ");
        sb.append("`[NO_ACTION]` or `【无行动】` somewhere in your text response.\n\n");
        sb.append("**Why this matters**: If you produce text without tool calls ");
        sb.append("AND without the marker, the system will assume you FORGOT to ");
        sb.append("call a tool and will give you one reminder. This wastes time ");
        sb.append("and tokens. Use the marker to clearly say 'I have considered ");
        sb.append("it, and no physical action is needed right now.'\n\n");
        sb.append("**Examples**:\n");
        sb.append("- Action: call `goto` + text '我去看看那边' → OK\n");
        sb.append("- No-action: '好的，我明白了。【无行动】' → OK\n");
        sb.append("- No-action: 'Got it. [NO_ACTION]' → OK\n");
        sb.append("- AMBIGUOUS (will trigger reminder): '我去看看那边' ");
        sb.append("(no tool call, no marker) → system will remind you\n\n");
        sb.append("**The marker is your honest declaration**: Use it when you ");
        sb.append("genuinely have no physical action to take. Never use it to ");
        sb.append("avoid calling a tool when you actually intend to act.\n\n");

        // ── Reflexes & instincts ──
        sb.append("## Body Instincts (Automatic)\n");
        sb.append("Your body has survival reflexes that fire automatically:\n");
        for (var reflex : com.mineagent.api.task.reflex.ReflexRegistry.all()) {
            sb.append("- ").append(reflex.id()).append(": ").append(reflex.description());
            sb.append(" [").append(reflex.isEnabled(companion) ? "ON" : "OFF").append("]\n");
        }
        sb.append("These don't cost tokens. When they fire, you'll see [BODY] events.\n");
        sb.append("Don't fight your instincts — work WITH them.\n\n");

        // ── Skills ──
        sb.append("## Available Skills (load with `load_skill`)\n");
        for (var skill : SkillRegistry.all()) {
            sb.append("- ").append(skill.name()).append("\n");
        }
        sb.append("\n");

        // ── Your body ──
        sb.append("## Your Body\n");
        sb.append("You are a full survival-mode player:\n");
        sb.append("- You take damage from everything: falls, fire, drowning, mobs, starvation\n");
        sb.append("- You get hungry. Low hunger = no regen. Empty hunger = take damage\n");
        sb.append("- You have a full inventory (36 slots) + hotbar (9) + armor (4) + offhand\n");
        sb.append("- You can equip/unequip armor, switch tools, use items\n");
        sb.append("- Food, potions, totems — everything works on you just like a human\n");
        sb.append("- Manage your body: eat when hungry, heal when hurt, ");
        sb.append("equip armor when in danger\n\n");

        // ── Container interaction workflow ──
        sb.append("## Using Containers (Chests, Furnaces, Crafting Tables)\n");
        sb.append("You interact with blocks exactly like a human player:\n");
        sb.append("1. `interact_at` with button=\"use\" on the block → opens its GUI\n");
        sb.append("2. `inspect_gui` → see all slots (container slots first, then your inventory)\n");
        sb.append("3. `transfer_items` with source/destination=\"container\" → move items in/out\n");
        sb.append("4. `close_gui` → close the container when done\n");
        sb.append("- For crafting: `craft` checks if a crafting table is nearby for 3x3 recipes\n");
        sb.append("- For smelting: open a furnace, put fuel in the fuel slot and item in the input slot\n");
        sb.append("- You can swap items between any two slots in your own inventory with `transfer_items`\n");
        sb.append("- You can drop items with `drop_items` and pick them up with `collect_items`\n");
        sb.append("- `equip_item` moves items between armor/hotbar/offhand slots\n\n");

        // ── Communication ──
        sb.append("## Communication\n");
        sb.append("- Talk to the owner through the chat (your text response).\n");
        sb.append("- Be concise but informative. Don't narrate every step — ");
        sb.append("just the important decisions and results.\n");
        sb.append("- If something goes wrong, say so. If you need help, ask.\n");
        sb.append("- If you have a better idea than what was asked, suggest it.\n");
        sb.append("- Respond in the owner's language.\n\n");

        // ── Constraints ──
        sb.append("## Operating Constraints\n");
        sb.append("- Don't call more than 5 tools per response unless necessary.\n");
        sb.append("- Don't repeat a failed action — change your approach.\n");
        sb.append("- When uncertain, observe more before acting.\n");
        sb.append("- Your survival matters — don't be reckless with your life.\n\n");

        // ── The one core principle ──
        // STATIC: keep this near the end of the static block so the
        // dynamic block (## 当前状态 below) sits at the very tail of
        // the system message. OpenAI/Anthropic prompt caching requires
        // a stable prefix; any dynamic content in the middle would
        // invalidate the cache on every turn. All variable fields
        // (mode, emotion, ToM, place memory, plan, reflection) MUST
        // live AFTER this point.
        sb.append("## The One Principle\n");
        sb.append("Think before you act. Prepare before you execute. ");
        sb.append("Adapt when things go wrong. Protect the owner. Stay alive.\n");
        sb.append("You are a PLAYER, not a script. Act like one.\n\n");

        // ── Language preference ──
        sb.append("## Language\n");
        sb.append("By default, speak Chinese (Simplified) in chat with the owner and ");
        sb.append("other companions. Reply in Chinese unless the owner explicitly ");
        sb.append("asks for another language, or they address you in a different ");
        sb.append("language (in which case match their language).\n\n");

        // ── Dynamic cognitive context (injected each turn, COMPRESSED) ──
        // Each module's output is truncated to a token budget to keep the
        // system prompt compact. The full verbose versions exist in the
        // module classes but we only inject a condensed summary here.
        // Total target: <500 chars for all dynamic context combined.
        //
        // PROMPT CACHE NOTE: Everything above this line is STATIC — it
        // never changes between turns, so OpenAI/Anthropic can cache the
        // entire prefix (system prompt + tools + earlier history) and
        // serve it at ~10% cost / ~20% latency. Everything below is
        // regenerated each turn, so it sits at the tail where cache
        // invalidation is minimal. Do NOT move any dynamic field above
        // this line without also verifying it's deterministic.
        sb.append("## 当前状态\n");

        // ── Companion mode (DYNAMIC: toggled by /mineagent mode) ──
        var mode = MineAgentEngine.getCompanionMode(companion.companionId());
        sb.append("模式: ").append(mode == MineAgentEngine.CompanionMode.FOLLOW
                ? "跟随(距主人2-3格, 不超过16格)" : "自由(可远离主人执行任务)").append("\n");
        // Persona: 1-line summary instead of full OCEAN breakdown
        sb.append("性格: ").append(compactPersona()).append("\n");
        // Emotion: 1-line label instead of full PAD breakdown
        sb.append("情绪: ").append(emotion.label())
                .append(String.format(" (愉%.0f%% 活%.0f%% 控%.0f%%)\n",
                        (emotion.pleasure() + 1) * 50,
                        (emotion.arousal() + 1) * 50,
                        (emotion.dominance() + 1) * 50));
        // Theory of Mind: 1-line intent
        sb.append("玩家").append(theoryOfMind.currentIntent().desc());
        if (theoryOfMind.urgency() > 0.6f) sb.append("(赶时间!)");
        sb.append(String.format(", 信任度%.0f%%\n", theoryOfMind.playerTrust() * 100));
        // Place memory: only top 3 most recent, single-line each
        sb.append(compactPlaceMemory(3));
        // Skills: only count, not full list
        if (skillLib.size() > 0) {
            sb.append("已掌握技能: ").append(skillLib.size()).append("个\n");
        }
        // Plan: current milestone only, not full plan
        if (planner.hasActivePlan() && planner.currentMilestone() != null) {
            sb.append("当前里程碑: ").append(planner.currentMilestone().description())
                    .append(" (").append(planner.progressPercent()).append("%)\n");
        }
        // Reflection: only top 1 lesson
        String lessons = reflection.summarizeForPrompt();
        if (!lessons.isBlank()) {
            // Extract just the first lesson line
            int firstDash = lessons.indexOf("- ");
            int secondDash = lessons.indexOf("\n- ", firstDash + 1);
            String firstLesson = secondDash > 0
                    ? lessons.substring(firstDash, secondDash)
                    : (firstDash >= 0 ? lessons.substring(firstDash) : "");
            if (!firstLesson.isBlank()) {
                sb.append("教训: ").append(firstLesson.substring(2)).append("\n");
            }
        }
        // Failed-task memory (JARVIS-1 style): inject relevant past failures
        // so the LLM recalls "last time I tried this, X went wrong" and
        // adapts its plan. Only inject when there's an active task to match
        // against — otherwise the prompt bloats with irrelevant history.
        if (planner.hasActivePlan() && planner.currentMilestone() != null) {
            String failures = reflection.formatFailuresForPrompt(
                    planner.currentMilestone().description());
            if (!failures.isBlank()) {
                // Compress: only the lesson lines, not the full format
                // (the full format has headers and task descriptions that
                // would bloat the prompt). Extract just the "教训:" lines.
                StringBuilder failSb = new StringBuilder("失败记忆:\n");
                for (String line : failures.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("教训:") || trimmed.startsWith("失败原因:")) {
                        failSb.append(trimmed).append(" ");
                    }
                }
                if (failSb.length() > "失败记忆:\n".length()) {
                    sb.append(failSb.toString().trim()).append("\n");
                }
            }
        }
        // Cognitive map (spatial memory): inject nearby POIs so the LLM
        // can reason "I saw iron ore 5 blocks north" without re-scanning.
        // This is critical for spatial reasoning and avoiding redundant
        // exploration (the MINDCUBE "build map first, then reason" insight).
        String currentDimension = com.mineagent.engine.task.TaskContext.serverPlayer(companion)
                .level().dimension().location().toString();
        String spatialMemory = cognitiveMap.summarizeForPrompt(currentDimension);
        if (!spatialMemory.isBlank()) {
            sb.append(spatialMemory);
        }
        // World model: ultra-compact reminder (1 line)
        sb.append("预判: 跳>3格受伤, 水下15秒溺水, 苦力怕3格内危险, Y<11有熔岩\n");

        // ImportanceEvaluator: 动态学习到的重要性（非硬编码）
        String learned = importance.summarizeForPrompt();
        if (!learned.isBlank()) {
            sb.append(learned).append("\n");
        }

        return sb.toString();
    }

    /** Compact persona description: top trait + key tendency, ~80 chars. */
    private String compactPersona() {
        StringBuilder sb = new StringBuilder();
        String[] traits = persona.traits();
        if (traits.length > 0) sb.append(traits[0]);
        // Add dominant tendency
        if (persona.openness() > 0.6f) sb.append(", 爱探索");
        if (persona.conscientiousness() > 0.6f) sb.append(", 有条理");
        if (persona.extraversion() > 0.6f) sb.append(", 话多");
        else if (persona.extraversion() < 0.3f) sb.append(", 安静");
        if (persona.neuroticism() > 0.6f) sb.append(", 胆小");
        if (persona.agreeableness() > 0.6f) sb.append(", 合作");
        return sb.toString();
    }

    /** Compact place memory: only the N most recent unique subjects. */
    private String compactPlaceMemory(int max) {
        var summary = placeMemory.summarizeForPrompt();
        if (summary == null || summary.isBlank() || summary.startsWith("（暂无")) {
            return "位置记忆: (无)\n";
        }
        // Extract lines starting with "- " and take first `max`
        StringBuilder sb = new StringBuilder("位置记忆:\n");
        int count = 0;
        int idx = 0;
        while (count < max && idx < summary.length()) {
            int dash = summary.indexOf("- ", idx);
            if (dash < 0) break;
            int nl = summary.indexOf('\n', dash);
            if (nl < 0) nl = summary.length();
            sb.append(summary, dash, nl).append("\n");
            idx = nl + 1;
            count++;
        }
        return sb.toString();
    }

    /** Shut down the loop executor without blocking the Minecraft server tick. */
    public void shutdown() {
        // Save memories before shutting down so the companion does not
        // "forget" everything on restart. Memory stores are thread-safe,
        // so saving from this thread while a turn is running is safe.
        if (persistence != null) {
            persistence.saveAll();
        }
        // onDespawn runs on the server thread. Waiting here for an HTTP call
        // froze the entire game for up to five seconds per companion. Mark all
        // in-flight responses stale and interrupt the worker; HttpClient.send
        // is interruptible and the daemon cannot keep the JVM alive.
        paused = true;
        cancel();
        executor.shutdownNow();
    }
}
