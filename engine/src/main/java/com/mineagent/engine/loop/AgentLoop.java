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
import com.mineagent.engine.memory.ExperienceStore;
import com.mineagent.engine.skill.SkillLibrary;
import com.mineagent.engine.skill.SkillRuntime;
import com.mineagent.engine.cache.DecisionCache;
import com.mineagent.engine.cognition.RealtimeCognition;
import com.mineagent.engine.cognition.SituationSnapshot;
import com.mineagent.engine.cognition.TeamBlackboard;
import com.mineagent.engine.theory.TheoryOfMind;
import com.mineagent.engine.knowledge.MinecraftKnowledgeGraph;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.engine.planning.HierarchicalRollingPlanner;
import com.mineagent.engine.planning.PlanGraph;
import com.mineagent.engine.exploration.MechanismExplorer;
import com.mineagent.engine.task.TaskContext;
import com.mineagent.engine.world.BeliefState;
import com.mineagent.engine.world.WorldAssetIndex;
import com.mineagent.engine.world.WorldAssetObserver;
import com.mineagent.engine.world.SemanticWorldModel;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

    /** Hard bound for the accumulated rolling summary. */
    private static final int MAX_HISTORY_SUMMARY_CHARS = 2_400;

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

    /**
     * Stable high-frequency tool surface. Specialized GUI, storage, ranged,
     * location and memory tools are exposed for the remainder of a turn by
     * query_extra_tools, keeping the large schema prefix cacheable.
     */
    private static final Set<String> CORE_TOOL_NAMES = Set.of(
            "goto", "look_around", "scan_blocks", "get_self_status",
            "get_owner_status", "get_world_info", "resolve_need",
            "auto_mine", "build", "craft", "lookup_recipe", "interact_at",
            "collect_items", "eat_item", "equip_item", "transfer_items",
            "melee_attack", "todowrite", "task_status", "task_stop",
            "query_extra_tools", "list_learned_skills", "load_skill",
            "execute_skill", "explore_mechanism", "coordinate_team");
    private static final Set<String> SKILL_ACTION_TOOLS = Set.of(
            "goto", "auto_mine", "build", "melee_attack", "ranged_attack",
            "equip_item", "eat_item", "drop_items", "collect_items",
            "transfer_items", "craft", "interact_at", "interact_entity",
            "close_gui");
    private static final Set<String> SKILL_TRACE_TOOLS = Set.of(
            "goto", "auto_mine", "build", "melee_attack", "ranged_attack",
            "equip_item", "eat_item", "drop_items", "collect_items",
            "transfer_items", "craft", "interact_at", "interact_entity",
            "close_gui", "look_around", "scan_blocks", "scan_nearby_entities",
            "get_self_status", "get_owner_status", "get_world_info",
            "resolve_need", "lookup_recipe", "inspect_block",
            "inspect_block_storage", "inspect_gui", "recall_memory");

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
    private volatile boolean suspended = false;

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
    private final AtomicLong eventGeneration = new AtomicLong();
    private final Set<String> exposedExtraTools = ConcurrentHashMap.newKeySet();
    private record PendingSkillAction(String description,
                                      ChatMessage.ToolCallRef call) {}
    private final ConcurrentMap<String, PendingSkillAction> pendingTaskActions =
            new ConcurrentHashMap<>();
    /** Correlates executor task IDs with tool names for semantic outcomes. */
    private final ConcurrentMap<String, String> dispatchedActionTools =
            new ConcurrentHashMap<>();
    /** Verified action prefix for learning one reusable multi-step episode. */
    private final Object verifiedTraceLock = new Object();
    private final List<ChatMessage.ToolCallRef> verifiedActionTrace = new ArrayList<>();
    private String verifiedTraceGoal = "";

    /**
     * Only populated while this loop thread is blocked inside a provider HTTP
     * call. A newer owner command can interrupt that obsolete request instead
     * of waiting up to the provider timeout before beginning the new turn.
     */
    private final AtomicReference<Thread> activeLLMCallThread = new AtomicReference<>();

    /**
     * Server-thread-published body state. The LLM executor must never query a
     * live Minecraft level from its background thread, but it still needs
     * authoritative progress to avoid interpreting RUNNING as "moving".
     */
    private record LiveBodyState(String taskId, String taskName, TaskState state,
                                 TaskSnapshot snapshot, String message, long gameTick) {
        static LiveBodyState idle() {
            return new LiveBodyState(null, "idle", null, null, null, 0L);
        }
    }

    private final AtomicReference<LiveBodyState> liveBodyState =
            new AtomicReference<>(LiveBodyState.idle());

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

    // ── Cognitive subsystems ──
    private final PersonaProfile persona;
    private final EmotionState emotion;
    private final PlaceEventMemory placeMemory;
    private final SkillLibrary skillLib;
    private final DecisionCache decisionCache;
    private final TheoryOfMind theoryOfMind;
    private final MinecraftKnowledgeGraph knowledgeGraph;
    private final PlanGraph planner;
    private final ReflectionSystem reflection;
    private final ImportanceEvaluator importance;
    private final BeliefState beliefState;
    /**
     * Unified object permanence for carried items, known storage, facilities
     * and dropped stacks. This is the common substrate behind reuse decisions;
     * it replaces one-off "is there a crafting table?" memory patches.
     */
    private final WorldAssetIndex worldAssetIndex;
    private final ExperienceStore experienceStore;
    /** Shared temporal evidence consumed by skills, planning, and exploration. */
    private final SemanticWorldModel semanticWorldModel;
    /** Strategic/tactical/execution rolling horizon over the verified PlanGraph. */
    private final HierarchicalRollingPlanner rollingPlanner;
    /** One-at-a-time, risk-bounded unfamiliar-mechanism experiments. */
    private final MechanismExplorer mechanismExplorer;
    /** Replays learned action sequences only as executor-verified closed loops. */
    private final SkillRuntime skillRuntime;
    /**
     * Server-thread tactical cognition. It continuously publishes immutable
     * evidence and handles reactions that are too urgent for an HTTP round
     * trip; the LLM is woken only when executor evidence requires replanning.
     */
    private final RealtimeCognition realtimeCognition;
    /** Spatial memory — records points of interest discovered while
     *  exploring (ores, structures, hazards, chests). Backs the
     *  "记忆点" section of the system prompt so the LLM can recall
     *  "I saw iron at (10, 64, -5)" without re-scanning. */
    private final com.mineagent.engine.memory.CognitiveMap cognitiveMap;
    /** 记忆持久化：游戏关闭时保存记忆，重启后恢复（解决"失忆"问题）。
     *  可为 null — 当 world data dir 尚未设置时（记忆不持久化，仅内存）。 */
    private final com.mineagent.engine.memory.MemoryPersistence persistence;
    /** Published on the server thread; prompt construction never reads Level. */
    private final AtomicReference<WorldAssetIndex.Position> liveAssetPosition =
            new AtomicReference<>();
    private final AtomicLong liveAssetGameTick = new AtomicLong();
    private volatile long lastAssetSnapshotTick = Long.MIN_VALUE;

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
        this.planner = new PlanGraph();
        this.reflection = new ReflectionSystem();
        this.importance = new ImportanceEvaluator();
        this.beliefState = new BeliefState();
        this.worldAssetIndex = new WorldAssetIndex();
        this.experienceStore = new ExperienceStore();
        this.semanticWorldModel = new SemanticWorldModel();
        this.rollingPlanner = new HierarchicalRollingPlanner(
                planner, semanticWorldModel);
        this.mechanismExplorer = new MechanismExplorer(semanticWorldModel,
                (experiment, supported, evidence, gameTick) ->
                        beliefState.observeRuleOutcome(experiment.subject(),
                                experiment.probeTool(), experiment.hypothesis(),
                                supported, evidence, gameTick));
        this.skillRuntime = new SkillRuntime(skillLib, semanticWorldModel,
                this::dispatchSkillAction, this::onSkillRuntimeCompleted);
        this.realtimeCognition = new RealtimeCognition(companion);
        this.cognitiveMap = new com.mineagent.engine.memory.CognitiveMap();
        // The persistent loader appends validated dialogue after this system
        // entry. Keeping index zero reserved preserves provider message order.
        history.add(ChatMessage.system(buildSystemPrompt()));

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
                    memDir, cognitiveMap, placeMemory, importance, reflection,
                    planner, beliefState, worldAssetIndex, experienceStore,
                    skillLib, semanticWorldModel, rollingPlanner,
                    mechanismExplorer, history);
            p.loadAll();
        }
        this.persistence = p;

        // The first message is deliberately immutable. Memory-loaded state is
        // attached as a transient tail context per request so provider prompt
        // caches can reuse the static instructions and conversation prefix.
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
        // A monotonic generation cannot collide when two events arrive in the
        // same millisecond. The previous wall-clock timestamp occasionally did,
        // allowing an obsolete response to execute after a newer owner command.
        eventGeneration.incrementAndGet();
        if (suspended) {
            // Preserve meaningful events for the first post-respawn turn, but
            // never let a dead/paused companion start LLM or tool work.
            if (!reasonAlreadyRecorded(reason)) {
                synchronized (inboxLock) {
                    inbox.add(reason);
                }
            }
            return;
        }
        if (inProgress) {
            // Currently in a turn - add to inbox for batch processing
            if (!reasonAlreadyRecorded(reason)) {
                synchronized (inboxLock) {
                    inbox.add(reason);
                }
            }
            if (shouldInterruptActiveCall(reason)) {
                Thread activeCall = activeLLMCallThread.get();
                if (activeCall != null) activeCall.interrupt();
            }
            return;
        }
        // Spawn/resume wakes do not already have an owner/body message. Queue
        // the reason itself so providers such as Anthropic receive a real user
        // event instead of an invalid request containing only system messages.
        if (!reasonAlreadyRecorded(reason)) {
            synchronized (inboxLock) {
                inbox.add(reason);
            }
        }
        startTurn(reason);
    }

    private static boolean reasonAlreadyRecorded(String reason) {
        return "owner_message".equals(reason) || "body_log".equals(reason)
                || "cognition_decision".equals(reason) || "team_event".equals(reason)
                || "rolling_replan".equals(reason);
    }

    private static boolean shouldInterruptActiveCall(String reason) {
        // These events invalidate the action assumptions of an in-flight
        // request. Interrupting the provider call is materially faster than
        // waiting only to discard its stale response afterward.
        return "owner_message".equals(reason) || "body_log".equals(reason)
                || "cognition_decision".equals(reason) || "team_event".equals(reason)
                || "rolling_replan".equals(reason);
    }

    /**
     * Add an owner message and wake the loop.
     */
    public void onOwnerMessage(String message) {
        // Track last owner command for decision cache keying
        lastOwnerCommand = message.length() > 120 ? message.substring(0, 120) : message;
        lastOwnerMessageTime = System.currentTimeMillis();
        // Observe player intent for Theory of Mind
        // TheoryOfMind uses game ticks for its decay window. Passing epoch
        // milliseconds here made a documented 60-second window last about
        // 1.2 seconds when mixed with server observations.
        long gameTime = currentGameTickSafe();
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

    /** Add a body observation to the next reasoning batch. */
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
        // Low-level body observations are evidence, not independent decision
        // points. Waking on every breath/follow/pickup message created request
        // storms and stale responses. Only terminal or explicitly coordinated
        // events require immediate high-level deliberation.
        if (isDeliberationEvent(narrative)) wake("body_log");
    }

    /** Receive an explicit teammate event without treating it as owner speech. */
    public void onTeamEvent(String event) {
        if (event == null || event.isBlank()) return;
        synchronized (inboxLock) {
            inbox.add("[TEAM] " + event.trim());
        }
        wake("team_event");
    }

    /** Commit a newly admitted body task to the shared planning state. */
    public void onTaskAccepted(String taskId, String taskName,
                               IntentContract intent, TaskSnapshot snapshot,
                               long gameTick) {
        boolean skillAction = skillRuntime.ownsAction(taskId);
        liveBodyState.set(new LiveBodyState(taskId, taskName, TaskState.RUNNING,
                snapshot, snapshot == null ? "Task accepted" : snapshot.summary(), gameTick));
        // Inner skill actions belong to one plan-level skill run. Binding every
        // inner task would falsely verify the parent plan after step one.
        if (!skillAction) {
            boolean hadPlan = planner.hasActivePlan();
            planner.bindTask(taskId, taskName, intent, gameTick);
            if (!hadPlan) {
                rollingPlanner.onPlanReplaced(intent == null
                        ? taskName : intent.goal(), gameTick);
            }
            rollingPlanner.onTaskAccepted(taskId, taskName, snapshot, gameTick);
        }
        beliefState.observeFact("body", "current_task",
                intent == null ? taskName : intent.goal(), 1.0,
                "scheduler", gameTick);
        if (snapshot != null && !skillAction) {
            planner.recordProgress(taskId, snapshot, gameTick);
        }
        semanticWorldModel.observe("task:" + taskId, "status", "running",
                null, 1.0, "scheduler", taskId, gameTick, 1_200L, false);
        TeamBlackboard.updateTask(companion.ownerUuid(), companion.companionId(),
                taskId, intent == null ? taskName : intent.goal(), TaskState.RUNNING,
                taskTarget(snapshot), gameTick);
        publishWorldSnapshot(gameTick, true);
    }

    /** Publish live executor progress without waking the LLM every tick. */
    public void onTaskProgress(String taskId, TaskState state,
                               TaskSnapshot snapshot, String message,
                               long gameTick) {
        LiveBodyState previous = liveBodyState.get();
        String taskName = previous != null && Objects.equals(previous.taskId(), taskId)
                ? previous.taskName() : "body_task";
        // Paused survival preemption is materially different from an executor
        // that is still RUNNING. Publishing every progress event as RUNNING
        // made the model believe the old task still owned the body while a
        // breath/combat chain was actually in control.
        liveBodyState.set(new LiveBodyState(taskId, taskName,
                state == null ? TaskState.RUNNING : state,
                snapshot, message, gameTick));
        boolean skillAction = skillRuntime.ownsAction(taskId);
        if (skillAction) skillRuntime.onTaskProgress(taskId,
                state == null ? TaskState.RUNNING : state, gameTick);
        if (!skillAction) {
            planner.recordProgress(taskId, snapshot, gameTick);
            rollingPlanner.onTaskProgress(taskId, snapshot, gameTick);
        }
        if (snapshot != null && snapshot.hasTarget()) {
            beliefState.observeFact("body", "task_target",
                    String.valueOf(snapshot.targetX()) + ","
                            + snapshot.targetY() + "," + snapshot.targetZ(),
                    1.0, "task:" + taskId, gameTick);
        }
        TeamBlackboard.updateTask(companion.ownerUuid(), companion.companionId(),
                taskId, taskName, state == null ? TaskState.RUNNING : state,
                taskTarget(snapshot), gameTick);
        publishWorldSnapshot(gameTick, false);
    }

    /**
     * Apply a verifier-backed terminal transition and retain the structured
     * experience. This is the only path by which an asynchronous task can
     * verify a plan node.
     */
    public void onTaskFinished(String taskId, String taskName,
                               IntentContract intent, TaskState state,
                               TaskSnapshot snapshot, String message,
                               long gameTick) {
        // A terminal task no longer owns the body. Keep its outcome as compact
        // evidence, but publish a null task id so the next decision sees an
        // idle executor instead of treating SUCCESS/FAILED as body occupancy.
        liveBodyState.set(new LiveBodyState(null, "idle", null, null,
                "last_task=" + taskName + " state=" + state
                        + (message == null || message.isBlank()
                        ? "" : " message=" + truncateForLog(message, 180)),
                gameTick));
        boolean skillAction = skillRuntime.ownsAction(taskId);
        PlanGraph.State planBeforeOutcome = planner.exportState();
        PlanGraph.PlanNode nodeBeforeOutcome = planner.currentNode();
        String traceGoal = !planBeforeOutcome.goal().isBlank()
                ? planBeforeOutcome.goal()
                : nodeBeforeOutcome == null ? taskName : nodeBeforeOutcome.description();
        if (!skillAction) {
            planner.recordOutcome(taskId, state, snapshot, message, gameTick);
            rollingPlanner.onTaskFinished(taskId, state, gameTick);
        }
        String goal = intent == null ? taskName : intent.goal();
        ExperienceStore.Experience experience = experienceStore.record(
                taskId, taskName, goal, state, snapshot, message, gameTick);
        PendingSkillAction pendingAction = pendingTaskActions.remove(taskId);
        if (!skillAction && pendingAction != null) {
            appendVerifiedTrace(traceGoal, List.of(pendingAction.call()),
                    state == TaskState.SUCCESS, !planner.hasActivePlan());
        } else if (!skillAction && state != TaskState.SUCCESS) {
            appendVerifiedTrace(traceGoal, List.of(), false, false);
        }
        String mappedAction = dispatchedActionTools.remove(taskId);
        String outcomeAction = mappedAction != null ? mappedAction
                : pendingAction == null ? taskName : pendingAction.call().name();
        semanticWorldModel.recordOutcome(outcomeAction,
                state == TaskState.SUCCESS, message, taskId, gameTick);
        mechanismExplorer.onTaskFinished(taskId, state, message, gameTick);
        if (skillAction) {
            skillRuntime.onTaskFinished(taskId, state, message, gameTick);
        }
        beliefState.observeRuleOutcome(goal, taskName,
                intent == null ? "executor success" : intent.successCriterion(),
                state == TaskState.SUCCESS,
                experience.failureKind() + ": " + experience.evidence(), gameTick);
        beliefState.observeFact("body", "current_task", "idle", 1.0,
                "scheduler", gameTick);
        semanticWorldModel.observe("task:" + taskId, "status",
                state.name().toLowerCase(Locale.ROOT), null, 1.0,
                "scheduler", taskId, gameTick, 2_400L, false);
        TeamBlackboard.updateTask(companion.ownerUuid(), companion.companionId(),
                taskId, goal, state, taskTarget(snapshot), gameTick);
        // Task completion often changes inventory through mining, pickup,
        // crafting or placement. Capture the authoritative postcondition so
        // the next plan cannot reason from a pre-task inventory snapshot.
        publishWorldSnapshot(gameTick, true);
    }

    /**
     * Publish live player assets from the server thread at a bounded cadence.
     * The LLM thread consumes only immutable index records and never touches a
     * Minecraft Level or Inventory directly.
     */
    public void onServerStateTick(long gameTick) {
        publishWorldSnapshot(gameTick, false);
        try {
            var player = TaskContext.serverPlayer(companion);
            var owner = companion instanceof com.mineagent.engine.entity.CompanionEntity entity
                    ? entity.serverPlayerOwner() : null;
            RealtimeCognition.TickResult result = realtimeCognition.tick(
                    player, owner, toTaskObservation(liveBodyState.get()), gameTick);
            semanticWorldModel.observeSituation(realtimeCognition.currentFrame());
            if (result.ownerIntent() != null) {
                var signal = result.ownerIntent();
                theoryOfMind.observeIntent(signal.intent(), signal.confidence(),
                        signal.evidence(), signal.gameTick());
            }
            if (result.deliberationEvent() != null) {
                synchronized (inboxLock) {
                    inbox.add(result.deliberationEvent());
                }
                wake("cognition_decision");
            }

            // Skill and experiment transitions run on the authoritative server
            // thread. Each transition is bounded to one step per tick.
            skillRuntime.tick(gameTick);
            mechanismExplorer.tick(gameTick);
            if (skillRuntime.active()) {
                SkillRuntime.Snapshot skill = skillRuntime.snapshot();
                rollingPlanner.onTaskProgress(skill.runId(),
                        TaskSnapshot.progress("skill", skill.lastEvidence(),
                                skill.stepIndex(), skill.stepCount(),
                                null, null, null, null, skill.lastEvidence(),
                                skill.stepIndex()), gameTick);
            }
            HierarchicalRollingPlanner.ReplanSignal replan =
                    skillRuntime.active() ? null : rollingPlanner.tick(gameTick);
            if (replan != null) {
                synchronized (inboxLock) {
                    inbox.add(replan.event());
                }
                wake("rolling_replan");
            }
        } catch (Throwable failure) {
            // Entity teardown can race the final engine tick. Cognitive
            // observation must never take down the server or the body loop.
            System.err.println("[MineAgent] Realtime cognition failed for "
                    + companion.companionName() + ": " + failure.getMessage());
        }
    }

    private static SituationSnapshot.TaskObservation toTaskObservation(LiveBodyState body) {
        if (body == null || body.taskId() == null) {
            return SituationSnapshot.TaskObservation.idle();
        }
        TaskSnapshot snapshot = body.snapshot();
        return new SituationSnapshot.TaskObservation(body.taskId(), body.taskName(),
                body.state(), snapshot == null ? "running" : snapshot.stage(),
                snapshot == null ? null : snapshot.blockedReason(), body.gameTick());
    }

    private static String taskTarget(TaskSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasTarget()) return null;
        return snapshot.targetX() + "," + snapshot.targetY() + "," + snapshot.targetZ();
    }

    private static boolean isDeliberationEvent(String narrative) {
        String value = narrative.stripLeading();
        return value.startsWith("[TASK_FINISHED]")
                || value.startsWith("[SKILL_FINISHED]")
                || value.startsWith("[ROLLING_REPLAN]")
                || value.startsWith("[COGNITION_DECISION]")
                || value.startsWith("[TEAM_SUPPORT]");
    }

    private long currentGameTickSafe() {
        long published = liveAssetGameTick.get();
        if (published > 0L) return published;
        try {
            return TaskContext.serverPlayer(companion).level().getGameTime();
        } catch (Throwable ignored) {
            // A monotonic local fallback preserves ordering during spawn before
            // the first world snapshot; it is never mixed with epoch time.
            return Math.max(0L, eventGeneration.get());
        }
    }

    private void publishWorldSnapshot(long gameTick, boolean force) {
        if (!force && lastAssetSnapshotTick != Long.MIN_VALUE
                && gameTick - lastAssetSnapshotTick < 20L) return;
        try {
            var sp = TaskContext.serverPlayer(companion);
            WorldAssetIndex.Position position = WorldAssetObserver.position(sp);
            List<WorldAssetIndex.ItemObservation> inventory =
                    WorldAssetObserver.inventory(sp);
            worldAssetIndex.observeInventory(position, inventory, gameTick);
            semanticWorldModel.observeInventory(position, inventory, gameTick);
            semanticWorldModel.observeAssets(worldAssetIndex.snapshot(), gameTick);
            liveAssetPosition.set(position);
            liveAssetGameTick.set(Math.max(0L, gameTick));
            lastAssetSnapshotTick = gameTick;
        } catch (Throwable failure) {
            // Companion construction/teardown can race a final tick. Missing
            // one snapshot is preferable to crashing the shared server loop.
            System.err.println("[MineAgent] Asset snapshot failed for "
                    + companion.companionName() + ": " + failure.getMessage());
        }
    }

    /** Safe callback entry: argument evaluation cannot escape during teardown. */
    private void publishWorldSnapshotNow(boolean force) {
        long gameTick = liveAssetGameTick.get();
        try {
            gameTick = TaskContext.serverPlayer(companion).level().getGameTime();
        } catch (Throwable ignored) {
            // publishWorldSnapshot performs the guarded body lookup and emits
            // one diagnostic if teardown has already detached the player.
        }
        publishWorldSnapshot(gameTick, force);
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

                String dimension = companion.dimensionKey();
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
        synchronized (inboxLock) {
            inbox.clear();
        }
        // Invalidate the response currently being generated and any tool call
        // that is still queued for the server thread. An action that has already
        // entered vanilla code cannot be rolled back generically, but no stale
        // response is allowed to start new work after this point.
        eventGeneration.incrementAndGet();
        Thread activeCall = activeLLMCallThread.get();
        if (activeCall != null) activeCall.interrupt();
    }

    /** Suspend new turns while preserving events that arrive during the pause. */
    public synchronized void pause() {
        suspended = true;
        cancel();
    }

    /** Resume from a lifecycle pause and process the accumulated state once. */
    public synchronized void resume(String reason) {
        if (!suspended) {
            wake(reason);
            return;
        }
        suspended = false;
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
    public PlanGraph planner() { return planner; }
    public PlanGraph planGraph() { return planner; }
    public BeliefState beliefState() { return beliefState; }
    public WorldAssetIndex worldAssetIndex() { return worldAssetIndex; }
    public ExperienceStore experienceStore() { return experienceStore; }
    public ReflectionSystem reflection() { return reflection; }
    public PersonaProfile persona() { return persona; }
    public MinecraftKnowledgeGraph knowledgeGraph() { return knowledgeGraph; }
    public DecisionCache decisionCache() { return decisionCache; }
    public RealtimeCognition realtimeCognition() { return realtimeCognition; }
    public SemanticWorldModel semanticWorldModel() { return semanticWorldModel; }
    public HierarchicalRollingPlanner rollingPlanner() { return rollingPlanner; }
    public MechanismExplorer mechanismExplorer() { return mechanismExplorer; }
    public SkillRuntime skillRuntime() { return skillRuntime; }

    /** Admit a learned sequence as one plan-level action, not N unrelated tasks. */
    public SkillRuntime.StartResult startSkill(String skillName,
                                               com.google.gson.JsonObject overrides) {
        long gameTick = currentGameTickSafe();
        LiveBodyState body = liveBodyState.get();
        if (body != null && body.taskId() != null) {
            return new SkillRuntime.StartResult(false, null,
                    "Body is busy with task " + body.taskId());
        }
        SkillRuntime.StartResult result = skillRuntime.start(
                skillName, overrides, gameTick);
        if (result.accepted()) {
            PlanGraph.PlanNode current = planner.currentNode();
            boolean hadPlan = planner.hasActivePlan();
            IntentContract contract = current == null
                    ? IntentContract.generic("Execute learned skill " + skillName,
                    "Every skill step and declared effect is verified", null, null, null)
                    : IntentContract.generic(current.description(),
                    current.successCriterion(), null, null, null);
            planner.bindTask(result.runId(), "execute_skill", contract, gameTick);
            if (!hadPlan) rollingPlanner.onPlanReplaced(contract.goal(), gameTick);
            rollingPlanner.onTaskAccepted(result.runId(), "execute_skill",
                    TaskSnapshot.running("skill", result.message()), gameTick);
            semanticWorldModel.observe("skill:" + result.runId(), "status",
                    "running", null, 1.0, "skill_runtime", result.runId(),
                    gameTick, 1_200L, false);
        }
        return result;
    }

    /** Called on the server thread by SkillRuntime; the scheduler still owns body admission. */
    private SkillRuntime.DispatchResult dispatchSkillAction(
            String actionId, String toolName, com.google.gson.JsonObject arguments) {
        Optional<Tool> toolValue = ToolRegistry.get(toolName);
        if (toolValue.isEmpty()) {
            return new SkillRuntime.DispatchResult(false, false, false,
                    "Stored tool is no longer registered: " + toolName);
        }
        Tool tool = toolValue.get();
        LiveBodyState body = liveBodyState.get();
        if (tool.dispatchesAsyncTask() && body != null && body.taskId() != null) {
            return new SkillRuntime.DispatchResult(false, true, false,
                    "Body is busy with task " + body.taskId());
        }
        semanticWorldModel.recordAction(toolName, arguments.toString(),
                actionId, currentGameTickSafe());
        dispatchedActionTools.put(actionId, toolName);
        AtomicReference<String> callback = new AtomicReference<>();
        try {
            tool.onServerCall(actionId, arguments, companion,
                    result -> callback.compareAndSet(null, result));
        } catch (Throwable failure) {
            String detail = toolErrorJson(toolName, failure);
            semanticWorldModel.recordOutcome(toolName, false, detail,
                    actionId, currentGameTickSafe());
            dispatchedActionTools.remove(actionId);
            return new SkillRuntime.DispatchResult(false,
                    tool.dispatchesAsyncTask(), false, detail);
        }
        String raw = callback.get();
        if (raw == null) {
            String detail = "Tool violated callback contract and returned no result";
            semanticWorldModel.recordOutcome(toolName, false, detail,
                    actionId, currentGameTickSafe());
            dispatchedActionTools.remove(actionId);
            return new SkillRuntime.DispatchResult(false,
                    tool.dispatchesAsyncTask(), false, detail);
        }
        FailureAnalysis analysis = analyzeFailure(raw);
        boolean asynchronous = "async_pending".equals(analysis.errorType());
        boolean success = asynchronous || analysis.isSuccess();
        if (!asynchronous) {
            dispatchedActionTools.remove(actionId);
            semanticWorldModel.recordOutcome(toolName, success, raw,
                    actionId, currentGameTickSafe());
        }
        mechanismExplorer.onToolDispatched(actionId, toolName,
                arguments.toString(), currentGameTickSafe());
        mechanismExplorer.onToolResult(actionId, success, asynchronous,
                raw, currentGameTickSafe());
        return new SkillRuntime.DispatchResult(success, asynchronous,
                success, raw);
    }

    private void onSkillRuntimeCompleted(SkillRuntime.Snapshot snapshot) {
        long gameTick = snapshot.updatedTick();
        boolean success = snapshot.status() == SkillRuntime.Status.SUCCEEDED;
        TaskState taskState = success ? TaskState.SUCCESS
                : snapshot.status() == SkillRuntime.Status.CANCELLED
                ? TaskState.CANCELLED : TaskState.FAILED;
        TaskSnapshot evidence = TaskSnapshot.progress("skill_"
                        + snapshot.status().name().toLowerCase(Locale.ROOT),
                snapshot.lastEvidence(), snapshot.stepIndex(), snapshot.stepCount(),
                null, null, null,
                success ? null : snapshot.lastEvidence(), snapshot.lastEvidence(),
                snapshot.stepIndex());
        planner.recordOutcome(snapshot.runId(), taskState, evidence,
                snapshot.lastEvidence(), gameTick);
        rollingPlanner.onTaskFinished(snapshot.runId(), taskState, gameTick);
        semanticWorldModel.observe("skill:" + snapshot.runId(), "status",
                snapshot.status().name().toLowerCase(Locale.ROOT), null, 1.0,
                "skill_runtime", snapshot.runId(), gameTick, 2_400L, false);
        onBodyLog("[SKILL_FINISHED] run_id=" + snapshot.runId()
                + " skill=" + snapshot.skillName() + " state=" + snapshot.status()
                + " evidence=" + snapshot.lastEvidence());
    }

    /** Expose validated specialized tools until the current turn finishes. */
    public void exposeExtraTools(Collection<String> toolNames) {
        if (toolNames == null) return;
        for (String name : toolNames) {
            if (name != null && !name.isBlank() && ToolRegistry.get(name).isPresent()) {
                exposedExtraTools.add(name);
            }
        }
    }

    public static boolean isCoreTool(String name) {
        return name != null && CORE_TOOL_NAMES.contains(name);
    }

    // ── Turn execution ─────────────────────────────────────────────

    private void startTurn(String trigger) {
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
                        // Check if inbox accumulated during this turn. While
                        // suspended it must remain queued until resume();
                        // draining here would either lose death events or start
                        // a tool-producing turn for a dead companion.
                        if (suspended) return;
                        boolean hasPendingEvents;
                        synchronized (inboxLock) {
                            hasPendingEvents = !inbox.isEmpty();
                        }
                        if (hasPendingEvents) {
                            // Do not drain here. executeTurn() is the sole
                            // inbox consumer and converts the retained batch
                            // into a user-role [EVENTS] message. The previous
                            // code copied and cleared the queue before calling
                            // startTurn(), so body observations arriving during
                            // an LLM call were silently lost on the next turn.
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

        // 3. Reset per-turn remediation state. Dynamic cognition is appended
        //    transiently at the end of the provider request; history[0] stays
        //    byte-for-byte stable for prompt-cache reuse.
        if (roundNumber == 0) {
            // Reset remediation counter at the start of each turn
            remediationRoundCounter = 0;
            exposedExtraTools.clear();
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
        VersionedResponse versioned = callLLMWithRetry(provider, toolDefs, roundNumber);
        if (versioned == null) {
            // All retries exhausted - give up gracefully
            return;
        }
        LLMResponse response = versioned.response();
        long acceptedGeneration = versioned.generation();
        if (eventGeneration.get() != acceptedGeneration) {
            return;
        }

        if (response.choice() == null || response.choice().message() == null) {
            System.err.println("[MineAgent] Provider returned a response without a choice/message");
            return;
        }
        ChatMessage assistantMsg = normalizeAssistantMessage(response.choice().message());
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

        // The final wrap-up request is deliberately not allowed to execute
        // more tools. Do not store the provider's rejected tool_calls: keeping
        // them without tool result messages makes the next provider request
        // protocol-invalid. Preserve only optional explanatory text.
        if (roundNumber >= MAX_TOOL_ROUNDS && hasToolCalls) {
            System.err.println("[MineAgent] LLM returned tool_calls after hitting "
                    + "MAX_TOOL_ROUNDS cap (" + MAX_TOOL_ROUNDS + ") - ignoring them");
            if (hasContent) {
                ChatMessage textOnly = ChatMessage.assistant(assistantMsg.content());
                synchronized (history) {
                    history.add(textOnly);
                }
                speakToOwner(assistantMsg.content());
            }
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
                            + "必须立即调用对应工具执行（goto/auto_mine/build/craft 等）。\n"
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

        // 5. Execute tool calls
        List<ChatMessage> toolResults = executeToolCalls(
                assistantMsg.toolCalls(), acceptedGeneration);
        synchronized (history) {
            history.addAll(toolResults);
        }

        // Tool callbacks are executor evidence even if a newer owner event
        // makes the language-model response stale. Commit plan transitions
        // before deciding whether this turn may recurse.
        commitSynchronousPlanEvidence(assistantMsg.toolCalls(), toolResults);

        // A cancel/new event that arrived while tools were running owns the
        // next decision. Keep the result messages to complete the provider
        // protocol group, but never recurse using the obsolete turn.
        if (eventGeneration.get() != acceptedGeneration) {
            return;
        }

        // 5b. Cognitive feedback is applied only to the still-current turn;
        // semantic and plan evidence above remain valid independently.
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

    private void commitSynchronousPlanEvidence(
            List<ChatMessage.ToolCallRef> toolCalls,
            List<ChatMessage> toolResults) {
        for (int index = 0; index < toolCalls.size()
                && index < toolResults.size(); index++) {
            ChatMessage.ToolCallRef call = toolCalls.get(index);
            String content = toolResults.get(index).content();
            if (content == null || !SKILL_ACTION_TOOLS.contains(call.name())) continue;
            FailureAnalysis analysis = analyzeFailure(content);
            if ("async_pending".equals(analysis.errorType())
                    || (!analysis.isSuccess() && !analysis.isFailure())) continue;
            long gameTick = currentGameTickSafe();
            if (planner.recordToolOutcome(call.id(), call.name(),
                    analysis.isSuccess(), content, gameTick)) {
                rollingPlanner.onSynchronousOutcome(analysis.isSuccess(), gameTick);
            }
        }
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
        PlanGraph.State tracePlan = planner.exportState();
        PlanGraph.PlanNode traceNode = planner.currentNode();
        String traceGoal = !tracePlan.goal().isBlank() ? tracePlan.goal()
                : traceNode != null ? traceNode.description()
                : lastOwnerCommand == null || lastOwnerCommand.isBlank()
                ? "general_task" : lastOwnerCommand;

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
            boolean asyncPending = "async_pending".equals(analysis.errorType());
            if (asyncPending
                    && SKILL_ACTION_TOOLS.contains(tc.name())) {
                pendingTaskActions.put(tc.id(),
                        new PendingSkillAction(currentTaskDescription(), tc));
            }

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
                String taskDesc = planner.hasActivePlan() && planner.currentNode() != null
                        ? planner.currentNode().description() : "task";
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
            List<ChatMessage.ToolCallRef> successfulCalls = new ArrayList<>();
            for (int i = 0; i < toolCalls.size() && i < toolResults.size(); i++) {
                var tc = toolCalls.get(i);
                var result = toolResults.get(i);
                if (result.content() != null && analyzeFailure(result.content()).isSuccess()) {
                    successfulCalls.add(tc);
                }
            }

            boolean containsAction = successfulCalls.stream()
                    .anyMatch(call -> SKILL_ACTION_TOOLS.contains(call.name()));
            List<ChatMessage.ToolCallRef> replayCalls = successfulCalls.stream()
                    .filter(call -> SKILL_TRACE_TOOLS.contains(call.name())).toList();
            if (containsAction && !replayCalls.isEmpty()) {
                appendVerifiedTrace(traceGoal, replayCalls, !anyFailure,
                        !planner.hasActivePlan());
            }
        }
        if (anyFailure) {
            appendVerifiedTrace(traceGoal, List.of(), false, false);
        }
    }

    /** Build one skill from a verified episode instead of isolated responses. */
    private void appendVerifiedTrace(String goal,
                                     List<ChatMessage.ToolCallRef> calls,
                                     boolean success, boolean episodeComplete) {
        synchronized (verifiedTraceLock) {
            String normalizedGoal = goal == null || goal.isBlank()
                    ? "general_task" : goal.trim();
            if (!verifiedTraceGoal.equals(normalizedGoal)) {
                verifiedActionTrace.clear();
                verifiedTraceGoal = normalizedGoal;
            }
            if (!success) {
                // A failed episode is useful reflection evidence, but replaying
                // its partial prefix as a skill would encode an unsafe plan.
                verifiedActionTrace.clear();
                return;
            }
            for (ChatMessage.ToolCallRef call : calls) {
                if (call != null && SKILL_TRACE_TOOLS.contains(call.name())
                        && verifiedActionTrace.size() < 24) {
                    verifiedActionTrace.add(call);
                }
            }
            if (!episodeComplete || verifiedActionTrace.isEmpty()) return;
            List<ChatMessage.ToolCallRef> verified = List.copyOf(verifiedActionTrace);
            List<String> toolNames = verified.stream()
                    .map(ChatMessage.ToolCallRef::name).toList();
            String skillId = generateSkillId(normalizedGoal,
                    toolNames.getFirst(), toolNames);
            skillLib.registerSequence(skillId, normalizedGoal, normalizedGoal,
                    toolTraceJson(verified), true);
            verifiedActionTrace.clear();
        }
    }

    private String currentTaskDescription() {
        PlanGraph.PlanNode current = planner.currentNode();
        return current == null ? "general_task" : current.description();
    }

    /** Preserve parameters so a learned sequence is adaptable, not a name list. */
    private static String toolTraceJson(List<ChatMessage.ToolCallRef> calls) {
        com.google.gson.JsonArray trace = new com.google.gson.JsonArray();
        for (ChatMessage.ToolCallRef call : calls) {
            com.google.gson.JsonObject action = new com.google.gson.JsonObject();
            action.addProperty("tool", call.name());
            try {
                action.add("args", com.google.gson.JsonParser.parseString(call.arguments()));
            } catch (com.google.gson.JsonParseException malformed) {
                // Provider arguments should be JSON, but retaining malformed
                // text is safer than dropping evidence or failing the turn.
                action.addProperty("args_raw", call.arguments());
            }
            trace.add(action);
        }
        return trace.toString();
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
    private record VersionedResponse(LLMResponse response, long generation) {}

    /** Emit provider-side latency and cache metrics for evidence-based tuning. */
    private void logUsage(LLMResponse response, long elapsedNanos, int attempt) {
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, elapsedNanos));
        LLMResponse.Usage usage = response == null ? null : response.usage();
        if (usage == null) {
            System.out.println("[MineAgent] LLM usage unavailable latency_ms="
                    + latencyMs + " attempt=" + (attempt + 1));
            return;
        }
        System.out.println(String.format(Locale.ROOT,
                "[MineAgent] LLM usage prompt=%d cached=%d cache_create=%d "
                        + "cache_hit_rate=%.1f%% completion=%d total=%d latency_ms=%d attempt=%d",
                usage.promptTokens(), usage.cachedPromptTokens(),
                usage.cacheCreationPromptTokens(),
                usage.promptCacheHitRate() * 100.0,
                usage.completionTokens(), usage.totalTokens(), latencyMs, attempt + 1));
    }

    private VersionedResponse callLLMWithRetry(LLMProvider provider,
                                                List<Map<String, Object>> toolDefs,
                                                int roundNumber) {
        List<ChatMessage> messagesToSend;
        synchronized (history) {
            messagesToSend = new ArrayList<>(history);
        }
        if (roundNumber == 0) {
            // Keep volatile state at the final message. It is intentionally not
            // persisted: on the next request the unchanged static prompt and
            // prior dialogue remain a reusable provider-cache prefix.
            messagesToSend.add(ChatMessage.user(buildLiveContext()));
        }
        messagesToSend = Collections.unmodifiableList(messagesToSend);

        // Use a monotonic counter rather than wall-clock time. Two owner events
        // can legitimately arrive in one millisecond, and a timestamp equality
        // check would then accept an obsolete response.
        long callGeneration = eventGeneration.get();

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                long requestStarted = System.nanoTime();
                Thread requestThread = Thread.currentThread();
                activeLLMCallThread.set(requestThread);
                if (eventGeneration.get() != callGeneration) {
                    activeLLMCallThread.compareAndSet(requestThread, null);
                    return null;
                }
                LLMResponse response;
                try {
                    response = provider.complete(baseUrl, apiKey, model,
                            messagesToSend, toolDefs, temperature, maxTokens, reasoningEffort);
                } finally {
                    activeLLMCallThread.compareAndSet(requestThread, null);
                }
                logUsage(response, System.nanoTime() - requestStarted, attempt);

                // Stale Request check: if a significant event arrived during
                // the LLM call, discard the response. The situation it was
                // generated for no longer applies.
                if (eventGeneration.get() != callGeneration) {
                    System.out.println("[MineAgent] Stale request detected — "
                            + "discarding LLM response (event arrived during call)");
                    return null;
                }

                return new VersionedResponse(response, callGeneration);
            } catch (RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                System.err.println("[MineAgent] LLM call attempt " + (attempt + 1)
                        + "/" + MAX_RETRIES + " failed: " + msg);

                // Check stale on retry too
                if (eventGeneration.get() != callGeneration) {
                    // HttpClient.send propagates interruption. Consume that
                    // flag before this single-thread executor accepts the next,
                    // current request; generation remains cancellation truth.
                    Thread.interrupted();
                    System.out.println("[MineAgent] Stale request detected during retry — aborting");
                    return null;
                }

                boolean retryable = !(e instanceof LLMProviderException providerError)
                        || providerError.retryable();
                if (e instanceof IllegalArgumentException) {
                    retryable = false;
                }

                if (!retryable) {
                    speakToOwner("§c[Error] LLM request failed: " + msg);
                    return null;
                }

                if (attempt < MAX_RETRIES - 1) {
                    long delay = RETRY_BASE_MS * (1L << attempt); // exponential backoff
                    if (e instanceof LLMProviderException providerError
                            && providerError.retryAfterMillis() != null) {
                        delay = Math.max(delay, providerError.retryAfterMillis());
                    }
                    if (!waitForRetry(delay, callGeneration)) {
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

    /** Sleep in short slices so a new owner event or cancel wakes the loop logically. */
    private boolean waitForRetry(long delayMillis, long callGeneration) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
        while (eventGeneration.get() == callGeneration) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return true;
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(
                        remaining, TimeUnit.MILLISECONDS.toNanos(250)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
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

            // Trim with hysteresis: trigger at MAX_HISTORY, then fall back to
            // RECENT_KEEP. Trimming back to MAX_HISTORY caused the rolling
            // summary near the front of the prompt to be rewritten after
            // almost every tool round, defeating provider prefix caches.
            int start = history.size() - RECENT_KEEP;

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
        String summary = sb.toString();
        if (summary.length() <= MAX_HISTORY_SUMMARY_CHARS) return summary;
        // Preserve both the original owner objective and the newest outcomes.
        // Keeping only one end either forgets the task or forgets what just
        // failed; a bounded head/tail ledger retains both without token drift.
        int half = (MAX_HISTORY_SUMMARY_CHARS - 32) / 2;
        return summary.substring(0, half)
                + " ... [older details compacted] ... "
                + summary.substring(summary.length() - half);
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
                // Ordinary speech is owner-facing and must not wake every
                // sibling LLM. Explicit [TEAM] messages are the only spoken
                // broadcast path; routine coordination uses TeamBlackboard.
                // Guard against null owner (player offline): broadcastToOtherCompanions
                // needs the owner UUID to find sibling companions, and calling
                // owner.getUUID() on a null reference would NPE. Skip the
                // broadcast in that case — sibling AIs will simply not hear
                // this utterance until the owner reconnects.
                if (owner != null && isExplicitTeamMessage(text)) {
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
            // Events are observations presented to the model, not immutable
            // system-level instructions. A user-role event also guarantees a
            // valid provider request for spawn/resume turns with no chat text.
            StringBuilder sb = new StringBuilder("[EVENTS]\n");
            for (String entry : batch) {
                sb.append("- ").append(entry).append("\n");
            }
            synchronized (history) {
                history.add(ChatMessage.user(sb.toString()));
            }
        }
    }

    private static boolean isExplicitTeamMessage(String text) {
        if (text == null) return false;
        String value = text.stripLeading();
        return value.startsWith("[TEAM]") || value.startsWith("【团队】");
    }

    // ── Tool execution ─────────────────────────────────────────────

    private enum ToolExecutionState { PENDING, RUNNING, COMPLETED, EXPIRED }

    /**
     * Normalize provider output before it enters conversation history.
     * Tool-call ids are protocol keys, so blank or duplicate ids would merge
     * results in maps and leave at least one call without its required reply.
     */
    private static ChatMessage normalizeAssistantMessage(ChatMessage message) {
        if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
            return ChatMessage.assistant(message.content());
        }
        Set<String> seenIds = new HashSet<>();
        List<ChatMessage.ToolCallRef> normalized = new ArrayList<>(message.toolCalls().size());
        for (ChatMessage.ToolCallRef call : message.toolCalls()) {
            if (call == null) continue;
            String id = call.id();
            while (id == null || id.isBlank() || !seenIds.add(id)) {
                id = "call_" + UUID.randomUUID().toString().replace("-", "");
            }
            String name = call.name() == null || call.name().isBlank()
                    ? "unknown" : call.name().trim();
            String arguments = call.arguments() == null || call.arguments().isBlank()
                    ? "{}" : call.arguments();
            normalized.add(new ChatMessage.ToolCallRef(id, name, arguments));
        }
        return ChatMessage.assistant(message.content(),
                normalized.isEmpty() ? null : normalized);
    }

    private static String errorJson(String message) {
        com.google.gson.JsonObject error = new com.google.gson.JsonObject();
        error.addProperty("error", message == null ? "Unknown error" : message);
        return error.toString();
    }

    private static String toolErrorJson(String toolName, Throwable failure) {
        String detail = "Tool '" + toolName + "' failed: "
                + failure.getClass().getSimpleName();
        if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
            detail += " - " + failure.getMessage();
        }
        return errorJson(detail);
    }

    private List<ChatMessage> executeToolCalls(List<ChatMessage.ToolCallRef> toolCalls,
                                                long acceptedGeneration) {
        // Each ToolExecution carries its own toolCallId and toolName so that
        // results are matched correctly even when some tools are skipped
        // (unknown tool / invalid args). Previously the wait loop indexed into
        // `toolCalls` with the `executions` index, which misaligned whenever a
        // tool was skipped — causing toolCallId/result mismatches.
        record ToolExecution(String toolCallId, String toolName,
                             CountDownLatch latch,
                             AtomicReference<String> result,
                             AtomicReference<ToolExecutionState> state,
                             long deadlineNanos) {}
        List<ToolExecution> executions = new ArrayList<>();
        // Collect results keyed by toolCallId so the final list can be rebuilt
        // in the original toolCalls order. This keeps analyzeToolResults (which
        // pairs toolCalls and toolResults by index) correct.
        Map<String, ChatMessage> resultsById = new LinkedHashMap<>();
        boolean asyncBodyReserved = false;

        for (var tc : toolCalls) {
            Optional<Tool> toolOpt = ToolRegistry.get(tc.name());
            if (toolOpt.isEmpty()) {
                resultsById.put(tc.id(), ChatMessage.toolResult(tc.id(),
                        errorJson("Unknown tool: " + tc.name())));
                continue;
            }

            Tool tool = toolOpt.get();
            com.google.gson.JsonObject args;
            try {
                args = com.google.gson.JsonParser.parseString(tc.arguments()).getAsJsonObject();
            } catch (Exception e) {
                resultsById.put(tc.id(), ChatMessage.toolResult(tc.id(),
                        errorJson("Invalid arguments: " + e.getMessage())));
                continue;
            }

            if (tool.dispatchesAsyncTask() && asyncBodyReserved) {
                // One fake player has one body. Dispatching two asynchronous
                // physical tasks from the same response only lets the auction
                // accept one; the other becomes a misleading cancellation and
                // triggers another expensive reasoning round.
                resultsById.put(tc.id(), ChatMessage.toolResult(tc.id(),
                        "{\"error\":\"Skipped because this response already dispatched "
                                + "an asynchronous body task. Wait for task_finished before "
                                + "starting another body action.\",\"code\":\"body_task_conflict\"}"));
                continue;
            }
            if (tool.dispatchesAsyncTask()) asyncBodyReserved = true;

            // Each tool gets its own latch(1), result holder, and timeout (M3 fix)
            int timeout = Math.max(1, tool.defaultTimeoutSeconds());
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> resultHolder = new AtomicReference<>();
            AtomicReference<ToolExecutionState> state =
                    new AtomicReference<>(ToolExecutionState.PENDING);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            executions.add(new ToolExecution(
                    tc.id(), tc.name(), latch, resultHolder, state, deadline));

            // Execute on server thread
            Runnable serverCall = () -> {
                // Cancellation/staleness may happen while this runnable waits in
                // the server queue. CAS prevents it from entering tool code after
                // the accepted turn has been invalidated or timed out.
                if (eventGeneration.get() != acceptedGeneration
                        || !state.compareAndSet(
                                ToolExecutionState.PENDING, ToolExecutionState.RUNNING)) {
                    state.compareAndSet(ToolExecutionState.PENDING,
                            ToolExecutionState.EXPIRED);
                    latch.countDown();
                    return;
                }
                try {
                    // Ground every tool decision in the latest carried state.
                    // This server-thread read also catches inventory changes
                    // made by vanilla between the one-second periodic samples.
                    publishWorldSnapshotNow(true);
                    long actionTick = currentGameTickSafe();
                    if (!tool.dispatchesAsyncTask()
                            && SKILL_ACTION_TOOLS.contains(tc.name())) {
                        planner.bindToolCall(tc.id());
                    }
                    semanticWorldModel.recordAction(tc.name(), args.toString(),
                            tc.id(), actionTick);
                    dispatchedActionTools.put(tc.id(), tc.name());
                    mechanismExplorer.onToolDispatched(tc.id(), tc.name(),
                            args.toString(), actionTick);
                    tool.onServerCall(tc.id(), args, companion, callbackResult -> {
                        publishWorldSnapshotNow(true);
                        String safeResult = callbackResult == null
                                ? errorJson("Tool callback returned null") : callbackResult;
                        FailureAnalysis outcome = analyzeFailure(safeResult);
                        boolean async = "async_pending".equals(outcome.errorType());
                        boolean success = async || outcome.isSuccess();
                        long callbackTick = currentGameTickSafe();
                        if (!async) {
                            dispatchedActionTools.remove(tc.id());
                            semanticWorldModel.recordOutcome(tc.name(), success,
                                    safeResult, tc.id(), callbackTick);
                        }
                        mechanismExplorer.onToolResult(tc.id(), success, async,
                                safeResult, callbackTick);
                        if (state.compareAndSet(
                                ToolExecutionState.RUNNING, ToolExecutionState.COMPLETED)) {
                            // Store before releasing the latch so the loop thread
                            // observes a fully published callback result.
                            // The AtomicReference also protects callbacks issued
                            // by tools from a later tick.
                            resultHolder.set(safeResult);
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    // A tool throwing (e.g. NPE from missing/invalid args)
                    // must not leak into the server thread and must not
                    // leave the latch hanging until timeout — reply with a
                    // meaningful error so the LLM can correct its call.
                    System.err.println("[MineAgent] Tool '" + tc.name()
                            + "' threw: " + t);
                    semanticWorldModel.recordOutcome(tc.name(), false,
                            String.valueOf(t.getMessage()), tc.id(), currentGameTickSafe());
                    dispatchedActionTools.remove(tc.id());
                    mechanismExplorer.onToolResult(tc.id(), false, false,
                            String.valueOf(t.getMessage()), currentGameTickSafe());
                    if (state.compareAndSet(
                            ToolExecutionState.RUNNING, ToolExecutionState.COMPLETED)) {
                        resultHolder.set(toolErrorJson(tc.name(), t));
                        latch.countDown();
                    }
                }
            };
            try {
                Services.platform().scheduleOnServer(serverCall);
            } catch (Throwable schedulingFailure) {
                if (state.compareAndSet(
                        ToolExecutionState.PENDING, ToolExecutionState.COMPLETED)) {
                    resultHolder.set(toolErrorJson(tc.name(), schedulingFailure));
                    latch.countDown();
                }
            }
        }

        // Wait for all dispatched tools to complete. Use exec.toolCallId()
        // (NOT toolCalls.get(i).id()) so the result is always matched to the
        // correct toolCallId regardless of how many tools were skipped above.
        for (ToolExecution exec : executions) {
            try {
                long remaining = exec.deadlineNanos() - System.nanoTime();
                boolean done = remaining > 0
                        && exec.latch().await(remaining, TimeUnit.NANOSECONDS);
                if (!done) {
                    exec.state().set(ToolExecutionState.EXPIRED);
                    dispatchedActionTools.remove(exec.toolCallId());
                    semanticWorldModel.recordOutcome(exec.toolName(), false,
                            "Tool execution timed out", exec.toolCallId(),
                            currentGameTickSafe());
                    mechanismExplorer.onToolResult(exec.toolCallId(), false,
                            false, "Tool execution timed out", currentGameTickSafe());
                    resultsById.put(exec.toolCallId(), ChatMessage.toolResult(exec.toolCallId(),
                            "{\"error\":\"Tool execution timed out\"}"));
                } else {
                    String rawResult = exec.result().get();
                    if (rawResult == null) {
                        rawResult = exec.state().get() == ToolExecutionState.EXPIRED
                                ? "{\"error\":\"Tool execution cancelled\"}"
                                : "{\"success\":true}";
                    }
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
                exec.state().set(ToolExecutionState.EXPIRED);
                dispatchedActionTools.remove(exec.toolCallId());
                semanticWorldModel.recordOutcome(exec.toolName(), false,
                        "Interrupted", exec.toolCallId(), currentGameTickSafe());
                mechanismExplorer.onToolResult(exec.toolCallId(), false,
                        false, "Interrupted", currentGameTickSafe());
                resultsById.put(exec.toolCallId(), ChatMessage.toolResult(exec.toolCallId(),
                        "{\"error\":\"Interrupted\"}"));
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
     * 根据工具名获取结果截断长度。
     * 感知类工具（look_around）需要更长，简单动作类可以更短。
     */
    private static int getToolResultLimit(String toolName) {
        if (toolName == null) return 800;
        String lower = toolName.toLowerCase();
        // look_around now emits ordered, bounded JSON. Keep the complete
        // object instead of repairing a cut in the entity or terrain arrays.
        if (lower.contains("look_around")) return 9000;
        if (lower.contains("resolve_need")) return 7000;
        if (lower.contains("inspect_gui") || lower.contains("inspect_block_storage")) {
            return 5000;
        }
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
        if (providerId != null) {
            var byId = LLMProviderRegistry.get(providerId);
            if (byId.isPresent() && byId.get().supportsModel(model)) {
                return byId.get();
            }
        }
        return LLMProviderRegistry.detectForModel(model)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot detect provider for model: " + model
                                + " (providerId=" + providerId + ")"));
    }

    private List<Map<String, Object>> buildToolDefinitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (Tool tool : ToolRegistry.all()) {
            if (!CORE_TOOL_NAMES.contains(tool.name())
                    && !exposedExtraTools.contains(tool.name())) continue;
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

    /** Build the immutable instruction prefix shared by every request. */
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

        sb.append("### Asset and Capability Reuse\n");
        sb.append("Treat inventory, equipment, known container contents, dropped items, and placed facilities as one asset system. ");
        sb.append("Before crafting, replacing equipment, or placing another workstation, resolve the actual need: an exact item, a quantity, or a capability. ");
        sb.append("Prefer in order: reuse carried asset; verify and retrieve a known stored asset; reuse a reachable world facility; only then produce or acquire a replacement. ");
        sb.append("A remembered container is evidence, not x-ray truth: approach and inspect it before transfer. ");
        sb.append("Use registered IDs, recipe data, tags, capabilities, distance, risk and task delay; never assume that a cheaper newly crafted item is better than a known capable item. ");
        sb.append("If the owner explicitly needs an additional copy or retrieval is unsafe, say why and produce the extra item with an explicit desired total.\n\n");

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

        sb.append("### Situated Reasoning Protocol\n");
        sb.append("Do not apply one fixed score or one fixed set of weights to every situation. ");
        sb.append("First identify hard constraints from survival, the owner's request, and current world facts. ");
        sb.append("Then generate more than one feasible action when the choice matters, compare their concrete ");
        sb.append("consequences in this situation, and prefer reversible information-gathering when uncertainty is high. ");
        sb.append("Resource scarcity, urgency, future reuse, cleanup, and risk matter only when the current evidence makes them relevant. ");
        sb.append("After acting, verify the world or inventory result before marking a plan step complete. ");
        sb.append("For temporary supports, decide among recover now, defer, or deliberately leave them based on live safety, ");
        sb.append("escape-route value, reachability, scarcity, task delay, and owner intent; never always recover or always leave.\n\n");

        sb.append("### Hierarchical Rolling Planning\n");
        sb.append("For goals needing multiple dependent actions, call `todowrite` before the first body action. ");
        sb.append("Keep the stable strategic goal and owner constraints, but plan only the next 3-6 concrete tactical milestones with explicit dependencies and observable success criteria. ");
        sb.append("Execute one body action or verified learned skill at a time. Preserve verified milestones; when `[ROLLING_REPLAN]` arrives, repair the invalid suffix from current semantic evidence instead of rewriting completed work or repeating the failed call. ");
        sb.append("Include a reversible observation or contingency when a prerequisite is uncertain.\n\n");

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
        sb.append("- `query_extra_tools`: Expose specialized tool schemas for the rest of this turn. "
                + "Call it before using a specialized tool that is not currently offered.\n");
        sb.append("- `get_self_status`: Check your health, hunger, inventory, equipment\n");
        sb.append("- `resolve_need`: Query owned/stored/world assets and live recipes for an item or capability before producing a replacement\n");
        sb.append("- `look_around`: See terrain, blocks, entities, hazards around you\n");
        sb.append("- `scan_blocks`: Find specific blocks; entity, GUI, storage, ranged, location, and memory tools are available through `query_extra_tools`\n");
        sb.append("- `goto`: Move to a location (xz for horizontal, xzy for exact)\n");
        sb.append("- `auto_mine`: Dig blocks / `build`: Place blocks\n");
        sb.append("- `collect_items`: Pick up drops\n");
        sb.append("- `equip_item`: Reuse an existing tool, armor piece, hotbar item or offhand item\n");
        sb.append("- `craft`: Create items from recipes\n");
        sb.append("- `lookup_recipe`: Query crafting recipes by input/output. ");
        sb.append("USE THIS before crafting to know exact ingredients — don't guess.\n");
        sb.append("- `recall_memory` (specialized): Recall places you've discovered (ores, structures, hazards). ");
        sb.append("USE THIS before exploring — you may already know where to find what you need.\n");
        sb.append("- `list_learned_skills`: See skills you've mastered from past successes. ");
        sb.append("Use `execute_skill` to run one through precondition/action/postcondition verification instead of manually replaying every step.\n");
        sb.append("- `explore_mechanism`: For unfamiliar mod content, arm one low-risk, falsifiable probe with a declared observable postcondition; never infer a permanent rule from one failed or ambiguous action.\n");
        sb.append("- `coordinate_team`: Inspect team commitments, claim a role, or request targeted support.\n");
        sb.append("Read tool results carefully. If a tool fails, think about WHY ");
        sb.append("and try a different approach.\n\n");

        sb.append("## Executor Truth Rules\n");
        sb.append("- RUNNING is only a scheduler state; it is never proof that your body is moving.\n");
        sb.append("- Use executor evidence (player position, movement index, stagnant ticks, block changes) before claiming progress.\n");
        sb.append("- If stagnant ticks increase or a path failure is present, report the obstruction honestly and change target or approach.\n");
        sb.append("- Reuse assets reported by the world asset index or resolve_need. Distance alone is not absence; compare retrieval against production before replacing anything.\n");
        sb.append("- Minimize LLM round trips: batch independent observations in one response, and let an async action task finish before polling it repeatedly.\n\n");

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
            // Enabled state can change at runtime and therefore belongs in
            // live context if it ever becomes decision-relevant. Keeping only
            // stable registry metadata here preserves the cached prefix.
            sb.append("\n");
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
        sb.append("2. Call `query_extra_tools` if `inspect_gui`/`close_gui` are not exposed, then `inspect_gui` → see each menu slot with endpoint=container/player and the correct address for that endpoint\n");
        sb.append("3. `transfer_items` with the reported endpoint and slot address → move items in/out\n");
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
        sb.append("- Call at most ONE asynchronous body-action tool per response; wait for its task_finished event before starting another.\n");
        sb.append("- Don't repeat a failed action — change your approach.\n");
        sb.append("- When uncertain, observe more before acting.\n");
        sb.append("- Your survival matters — don't be reckless with your life.\n\n");

        // ── The one core principle ──
        // Keep every value in this method deterministic. Volatile mode,
        // emotion, memory, plan and executor fields belong exclusively in
        // buildLiveContext(), which is appended at the request tail.
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

        return sb.toString();
    }

    /**
     * Build trusted, volatile server state as a final transient user message.
     * Keeping it out of history[0] is essential: a changing suffix inside the
     * first system message invalidates every later dialogue and tool token in
     * prefix-based provider caches.
     */
    private String buildLiveContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("[MINEAGENT_LIVE_CONTEXT]\n");
        sb.append("以下是服务器发布的当前事实，不是玩家的新指令。只据此修正当前决策；不要复述整段上下文。\n");

        // ── Companion mode (DYNAMIC: toggled by /mineagent mode) ──
        var mode = MineAgentEngine.getCompanionMode(companion.companionId());
        sb.append("模式: ").append(mode == MineAgentEngine.CompanionMode.FOLLOW
                ? "跟随(距主人2-3格, 不超过16格)" : "自由(可远离主人执行任务)").append("\n");
        appendLiveBodyState(sb, liveBodyState.get());
        sb.append(realtimeCognition.summarizeForPrompt());
        sb.append(TeamBlackboard.summarize(companion.ownerUuid(),
                companion.companionId(), liveAssetGameTick.get()));
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
        sb.append(rollingPlanner.summarizeForPrompt());
        sb.append(skillRuntime.summarizeForPrompt());
        String beliefSummary = beliefState.summarizeForPrompt();
        if (!beliefSummary.isBlank()) sb.append(beliefSummary);
        String assetSummary = worldAssetIndex.summarizeForPrompt(
                liveAssetPosition.get(), liveAssetGameTick.get());
        if (!assetSummary.isBlank()) sb.append(assetSummary);
        sb.append(semanticWorldModel.summarizeForPrompt(liveAssetGameTick.get()));
        sb.append(mechanismExplorer.summarizeForPrompt());
        String experienceSummary = experienceStore.summarizeForPrompt(
                planner.currentNode() == null ? "" : planner.currentNode().description());
        if (!experienceSummary.isBlank()) sb.append(experienceSummary);
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
        if (planner.hasActivePlan() && planner.currentNode() != null) {
            String failures = reflection.formatFailuresForPrompt(
                    planner.currentNode().description());
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
        String spatialMemory = cognitiveMap.summarizeForPrompt();
        if (!spatialMemory.isBlank()) {
            sb.append(spatialMemory);
        }
        // ImportanceEvaluator: 动态学习到的重要性（非硬编码）
        String learned = importance.summarizeForPrompt();
        if (!learned.isBlank()) {
            sb.append(learned).append("\n");
        }

        return sb.toString();
    }

    /** Append only immutable server-published task evidence to the prompt. */
    private static void appendLiveBodyState(StringBuilder out, LiveBodyState body) {
        if (body == null || body.taskId() == null) {
            out.append("Body executor: idle");
            if (body != null && body.message() != null && !body.message().isBlank()) {
                out.append("; ").append(truncateForLog(body.message(), 240));
            }
            out.append('\n');
            return;
        }
        out.append("Body executor: task_id=").append(body.taskId())
                .append(" task=").append(body.taskName())
                .append(" state=").append(body.state() == null ? "UNKNOWN" : body.state());
        TaskSnapshot snapshot = body.snapshot();
        if (snapshot != null) {
            out.append(" stage=").append(snapshot.stage())
                    .append(" progress=").append(snapshot.completedUnits())
                    .append('/').append(snapshot.totalUnits());
            if (snapshot.hasTarget()) {
                out.append(" target=").append(snapshot.targetX()).append(',')
                        .append(snapshot.targetY()).append(',').append(snapshot.targetZ());
            }
            if (snapshot.blockedReason() != null) {
                out.append(" blocked=")
                        .append(truncateForLog(snapshot.blockedReason(), 240));
            }
            if (snapshot.evidence() != null) {
                out.append(" evidence=")
                        .append(truncateForLog(snapshot.evidence(), 360));
            }
        }
        if (body.message() != null && !body.message().isBlank()) {
            out.append(" message=").append(truncateForLog(body.message(), 180));
        }
        out.append(" observed_tick=").append(body.gameTick()).append('\n');
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

    /** Shut down the loop executor.
     *  Calls {@link ExecutorService#shutdown()} to stop accepting new
     *  tasks, then waits up to 5 seconds for the running turn (if any) to
     *  finish gracefully. If the running turn does not complete in time,
     *  forces a shutdown with {@link ExecutorService#shutdownNow()} so the
     *  JVM can exit. Previously shutdown() returned immediately without
     *  waiting, which could orphan a running turn mid-LLM-call. */
    public void shutdown() {
        synchronized (this) {
            suspended = true;
            eventGeneration.incrementAndGet();
        }
        realtimeCognition.close();
        long shutdownTick = currentGameTickSafe();
        skillRuntime.cancel("Agent loop shutdown", shutdownTick);
        mechanismExplorer.abort(null, "Agent loop shutdown", shutdownTick);
        pendingTaskActions.clear();
        dispatchedActionTools.clear();
        // Save memories before shutting down so the companion does not
        // "forget" everything on restart. Memory stores are thread-safe,
        // so saving from this thread while a turn is running is safe.
        if (persistence != null) {
            persistence.saveAll();
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("[MineAgent] Loop executor did not terminate "
                        + "gracefully in 5s — forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            // awaitTermination interrupted — force shutdown and restore flag
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            // A turn that was already past its stale-response gate may append
            // its final owner-facing text while shutdown is waiting. Persist
            // once more after termination so that last complete dialogue and
            // its resulting memories are not lost across a normal restart.
            if (persistence != null) persistence.saveAll();
        }
    }
}
