package com.mineagent.engine.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.engine.planning.PlanGraph;
import com.mineagent.engine.world.BeliefState;
import com.mineagent.engine.world.WorldAssetIndex;
import com.mineagent.engine.skill.SkillLibrary;
import com.mineagent.api.llm.ChatMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆持久化管理器 — 将认知系统的记忆保存到磁盘，并在启动时恢复。
 *
 * <p>解决的问题：所有记忆系统（CognitiveMap、PlaceEventMemory、ReflectionSystem、
 * ImportanceEvaluator）都是纯内存的，游戏关闭后全部丢失，
 * 伴游每次重启都"失忆"。
 *
 * <p>设计：
 * <ul>
 *   <li>真实数据保存：完整序列化各记忆系统的条目（非元数据）</li>
 *   <li>启动恢复：伴游创建时自动恢复上次保存的记忆</li>
 *   <li>定期自动保存：由 AgentLoop 每回合触发（内部限频 1 分钟）</li>
 *   <li>关闭保存：伴游移除/服务器关闭时强制保存</li>
 *   <li>容错：单个文件损坏不影响其他记忆恢复</li>
 * </ul>
 *
 * <p>每个伴游拥有独立的记忆目录（world/data/mineagent_memory/&lt;companionId&gt;/），
 * 多个伴游之间记忆互不干扰。
 */
public class MemoryPersistence {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String COGNITIVE_MAP_FILE = "cognitive_map.json";
    private static final String PLACE_EVENT_FILE = "place_events.json";
    private static final String IMPORTANCE_FILE = "importance_weights.json";
    private static final String REFLECTION_FILE = "reflections.json";
    private static final String PLAN_FILE = "plan_state.json";
    private static final String BELIEF_FILE = "belief_state.json";
    private static final String ASSET_FILE = "world_assets.json";
    private static final String EXPERIENCE_FILE = "experiences.json";
    private static final String SKILL_FILE = "learned_skills.json";
    private static final String CONVERSATION_FILE = "conversation.json";
    private static final int FORMAT_VERSION = 6;
    private static final int MAX_DIALOGUE_PAIRS = 12;
    private static final int MAX_DIALOGUE_CHARS = 4_000;

    /** 自动保存间隔（毫秒） */
    private static final long AUTO_SAVE_INTERVAL = 60_000;

    private volatile Path memoryDir;
    private final CognitiveMap cognitiveMap;
    private final PlaceEventMemory placeEventMemory;
    private final ImportanceEvaluator importanceEvaluator;
    private final ReflectionSystem reflectionSystem;
    private final PlanGraph planGraph;
    private final BeliefState beliefState;
    private final WorldAssetIndex worldAssetIndex;
    private final ExperienceStore experienceStore;
    private final SkillLibrary skillLibrary;
    private final List<ChatMessage> conversationHistory;

    /** 上次保存时间 */
    private volatile long lastSaveTime = 0;

    /**
     * @param memoryDir 该伴游专属的记忆目录（由调用方确保唯一性）
     */
    public MemoryPersistence(Path memoryDir,
                             CognitiveMap cognitiveMap,
                             PlaceEventMemory placeEventMemory,
                             ImportanceEvaluator importanceEvaluator,
                             ReflectionSystem reflectionSystem,
                             PlanGraph planGraph,
                             BeliefState beliefState,
                             WorldAssetIndex worldAssetIndex,
                             ExperienceStore experienceStore,
                             SkillLibrary skillLibrary,
                             List<ChatMessage> conversationHistory) {
        this.memoryDir = memoryDir;
        this.cognitiveMap = cognitiveMap;
        this.placeEventMemory = placeEventMemory;
        this.importanceEvaluator = importanceEvaluator;
        this.reflectionSystem = reflectionSystem;
        this.planGraph = planGraph;
        this.beliefState = beliefState;
        this.worldAssetIndex = worldAssetIndex;
        this.experienceStore = experienceStore;
        this.skillLibrary = skillLibrary;
        this.conversationHistory = conversationHistory;
    }

    /**
     * 保存所有记忆到磁盘。每个系统独立保存，单个失败不影响其他。
     */
    public synchronized void saveAll() {
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            System.err.println("[MineAgent] Failed to create memory dir: " + e.getMessage());
            return;
        }
        try { saveCognitiveMap(); } catch (Exception e) {
            System.err.println("[MineAgent] Save cognitive map failed: " + e.getMessage());
        }
        try { savePlaceEventMemory(); } catch (Exception e) {
            System.err.println("[MineAgent] Save place events failed: " + e.getMessage());
        }
        try { saveImportanceWeights(); } catch (Exception e) {
            System.err.println("[MineAgent] Save importance weights failed: " + e.getMessage());
        }
        try { saveReflections(); } catch (Exception e) {
            System.err.println("[MineAgent] Save reflections failed: " + e.getMessage());
        }
        try { savePlan(); } catch (Exception e) {
            System.err.println("[MineAgent] Save plan failed: " + e.getMessage());
        }
        try { saveBeliefs(); } catch (Exception e) {
            System.err.println("[MineAgent] Save beliefs failed: " + e.getMessage());
        }
        try { saveWorldAssets(); } catch (Exception e) {
            System.err.println("[MineAgent] Save world assets failed: " + e.getMessage());
        }
        try { saveExperiences(); } catch (Exception e) {
            System.err.println("[MineAgent] Save experiences failed: " + e.getMessage());
        }
        try { saveSkills(); } catch (Exception e) {
            System.err.println("[MineAgent] Save learned skills failed: " + e.getMessage());
        }
        try { saveConversation(); } catch (Exception e) {
            System.err.println("[MineAgent] Save conversation failed: " + e.getMessage());
        }
        lastSaveTime = System.currentTimeMillis();
    }

    /**
     * 自动保存（距上次保存超过 1 分钟才真正写入）。
     * 由 AgentLoop 每回合开始时调用，开销可忽略。
     */
    public void autoSave() {
        if (System.currentTimeMillis() - lastSaveTime > AUTO_SAVE_INTERVAL) {
            saveAll();
        }
    }

    /**
     * 从磁盘恢复所有记忆。每个系统独立恢复，单个文件损坏不影响其他。
     */
    public synchronized void loadAll() {
        if (!Files.isDirectory(memoryDir)) return;
        loadWithQuarantine(COGNITIVE_MAP_FILE, "cognitive map", this::loadCognitiveMap);
        loadWithQuarantine(PLACE_EVENT_FILE, "place events", this::loadPlaceEventMemory);
        loadWithQuarantine(IMPORTANCE_FILE, "importance weights", this::loadImportanceWeights);
        loadWithQuarantine(REFLECTION_FILE, "reflections", this::loadReflections);
        loadWithQuarantine(PLAN_FILE, "plan state", this::loadPlan);
        loadWithQuarantine(BELIEF_FILE, "belief state", this::loadBeliefs);
        loadWithQuarantine(ASSET_FILE, "world assets", this::loadWorldAssets);
        loadWithQuarantine(EXPERIENCE_FILE, "experiences", this::loadExperiences);
        loadWithQuarantine(SKILL_FILE, "learned skills", this::loadSkills);
        loadWithQuarantine(CONVERSATION_FILE, "conversation", this::loadConversation);
        // Do not immediately overwrite a quarantined or partially recovered
        // file on the first agent turn. The normal one-minute autosave will
        // persist the validated in-memory subset while the original remains.
        lastSaveTime = System.currentTimeMillis();
    }

    @FunctionalInterface
    private interface MemoryLoader { void load() throws IOException; }

    private void loadWithQuarantine(String fileName, String label, MemoryLoader loader) {
        try {
            loader.load();
        } catch (Exception failure) {
            System.err.println("[MineAgent] Load " + label + " failed: "
                    + failure.getMessage());
            quarantine(memoryDir.resolve(fileName));
        }
    }

    private static void quarantine(Path file) {
        if (!Files.exists(file)) return;
        Path quarantined = file.resolveSibling(file.getFileName()
                + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, quarantined, StandardCopyOption.REPLACE_EXISTING);
            System.err.println("[MineAgent] Preserved invalid memory file as " + quarantined);
        } catch (IOException moveFailure) {
            System.err.println("[MineAgent] Could not quarantine invalid memory file: "
                    + moveFailure.getMessage());
        }
    }

    // ─── CognitiveMap ───

    private void saveCognitiveMap() throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("version", FORMAT_VERSION);
        data.put("timestamp", System.currentTimeMillis());
        data.put("pois", cognitiveMap.exportAll());
        writeAtomic(memoryDir.resolve(COGNITIVE_MAP_FILE), GSON.toJson(data));
    }

    private void loadCognitiveMap() throws IOException {
        Path file = memoryDir.resolve(COGNITIVE_MAP_FILE);
        if (!Files.exists(file)) return;
        JsonObject data = readRoot(file);
        JsonArray entries = arrayField(data, "pois");
        List<CognitiveMap.PointOfInterest> pois = new ArrayList<>();
        int skipped = 0;
        for (JsonElement entry : entries) {
            try {
                CognitiveMap.PointOfInterest poi =
                        GSON.fromJson(entry, CognitiveMap.PointOfInterest.class);
                if (poi == null || poi.category() == null || poi.category().isBlank()
                        || !Float.isFinite(poi.importance()) || poi.visitCount() < 0) {
                    skipped++;
                    continue;
                }
                pois.add(poi);
            } catch (RuntimeException malformedEntry) {
                skipped++;
            }
        }
        logSkipped(COGNITIVE_MAP_FILE, skipped);
        cognitiveMap.importAll(pois);
    }

    // ─── PlaceEventMemory ───

    private void savePlaceEventMemory() throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("version", FORMAT_VERSION);
        data.put("timestamp", System.currentTimeMillis());
        data.put("events", placeEventMemory.exportAll());
        writeAtomic(memoryDir.resolve(PLACE_EVENT_FILE), GSON.toJson(data));
    }

    private void loadPlaceEventMemory() throws IOException {
        Path file = memoryDir.resolve(PLACE_EVENT_FILE);
        if (!Files.exists(file)) return;
        JsonObject data = readRoot(file);
        JsonArray entries = arrayField(data, "events");
        List<PlaceEventMemory.PlaceEvent> events = new ArrayList<>();
        int skipped = 0;
        for (JsonElement entry : entries) {
            try {
                PlaceEventMemory.PlaceEvent event =
                        GSON.fromJson(entry, PlaceEventMemory.PlaceEvent.class);
                if (event == null || event.subject() == null || event.subject().isBlank()
                        || event.dimension() == null || event.dimension().isBlank()
                        || event.type() == null || !Float.isFinite(event.importance())
                        || event.visitCount() < 0) {
                    skipped++;
                    continue;
                }
                events.add(event);
            } catch (RuntimeException malformedEntry) {
                skipped++;
            }
        }
        logSkipped(PLACE_EVENT_FILE, skipped);
        placeEventMemory.importAll(events);
    }

    // ─── ImportanceEvaluator ───

    private void saveImportanceWeights() throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("version", FORMAT_VERSION);
        data.put("timestamp", System.currentTimeMillis());
        data.put("learnCount", importanceEvaluator.learnCount());
        data.put("weights", importanceEvaluator.exportWeights());
        writeAtomic(memoryDir.resolve(IMPORTANCE_FILE), GSON.toJson(data));
    }

    private void loadImportanceWeights() throws IOException {
        Path file = memoryDir.resolve(IMPORTANCE_FILE);
        if (!Files.exists(file)) return;
        JsonObject data = readRoot(file);
        Map<String, Float> weights = new HashMap<>();
        int skipped = 0;
        if (data.has("weights") && data.get("weights").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry
                    : data.getAsJsonObject("weights").entrySet()) {
                try {
                    float weight = entry.getValue().getAsFloat();
                    if (entry.getKey().isBlank() || !Float.isFinite(weight)) {
                        skipped++;
                    } else {
                        weights.put(entry.getKey(), weight);
                    }
                } catch (RuntimeException malformedEntry) {
                    skipped++;
                }
            }
        }
        int learnCount = data.has("learnCount") && data.get("learnCount").isJsonPrimitive()
                ? Math.max(0, data.get("learnCount").getAsInt()) : 0;
        logSkipped(IMPORTANCE_FILE, skipped);
        importanceEvaluator.importWeights(weights, learnCount);
    }

    // ─── ReflectionSystem ───

    private void saveReflections() throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("version", FORMAT_VERSION);
        data.put("timestamp", System.currentTimeMillis());
        data.put("reflections", reflectionSystem.exportAll());
        data.put("patterns", reflectionSystem.exportPatterns());
        writeAtomic(memoryDir.resolve(REFLECTION_FILE), GSON.toJson(data));
    }

    /**
     * 原子写入：先写临时文件再移动，避免游戏崩溃留下截断的 JSON
     * （截断文件会导致下次启动时该记忆系统被静默清零）。
     */
    private static void writeAtomic(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(),
                target.getFileName() + ".", ".tmp");
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(tmp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                // The rename is only useful if the complete JSON reached disk.
                channel.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void loadReflections() throws IOException {
        Path file = memoryDir.resolve(REFLECTION_FILE);
        if (!Files.exists(file)) return;
        JsonObject data = readRoot(file);
        List<ReflectionSystem.Reflection> reflections = new ArrayList<>();
        int skipped = 0;
        for (JsonElement entry : arrayField(data, "reflections")) {
            try {
                ReflectionSystem.Reflection reflection =
                        GSON.fromJson(entry, ReflectionSystem.Reflection.class);
                if (reflection == null || reflection.level() == null
                        || reflection.trigger() == null || reflection.lesson() == null
                        || reflection.applicability() == null) {
                    skipped++;
                } else {
                    reflections.add(reflection);
                }
            } catch (RuntimeException malformedEntry) {
                skipped++;
            }
        }
        Map<String, Integer> patterns = new HashMap<>();
        if (data.has("patterns") && data.get("patterns").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry
                    : data.getAsJsonObject("patterns").entrySet()) {
                try {
                    int count = entry.getValue().getAsInt();
                    if (!entry.getKey().isBlank() && count >= 0) {
                        patterns.put(entry.getKey(), count);
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException malformedEntry) {
                    skipped++;
                }
            }
        }
        logSkipped(REFLECTION_FILE, skipped);
        reflectionSystem.importAll(reflections, patterns);
    }

    // --- Unified cognitive state ---

    private void savePlan() throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("version", FORMAT_VERSION);
        data.addProperty("timestamp", System.currentTimeMillis());
        data.add("state", GSON.toJsonTree(planGraph.exportState()));
        writeAtomic(memoryDir.resolve(PLAN_FILE), GSON.toJson(data));
    }

    private void loadPlan() throws IOException {
        Path file = memoryDir.resolve(PLAN_FILE);
        if (!Files.exists(file)) return;
        JsonObject data = readRoot(file);
        if (!data.has("state") || !data.get("state").isJsonObject()) {
            throw new IOException("plan state is missing");
        }
        try {
            PlanGraph.State state = GSON.fromJson(data.get("state"), PlanGraph.State.class);
            planGraph.importState(state);
        } catch (RuntimeException malformed) {
            throw new IOException("invalid plan state", malformed);
        }
    }

    private void saveBeliefs() throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("version", FORMAT_VERSION);
        data.addProperty("timestamp", System.currentTimeMillis());
        data.add("state", GSON.toJsonTree(beliefState.exportState()));
        writeAtomic(memoryDir.resolve(BELIEF_FILE), GSON.toJson(data));
    }

    private void loadBeliefs() throws IOException {
        Path file = memoryDir.resolve(BELIEF_FILE);
        if (!Files.exists(file)) return;
        JsonObject data = readRoot(file);
        if (!data.has("state") || !data.get("state").isJsonObject()) {
            throw new IOException("belief state is missing");
        }
        try {
            BeliefState.State state = GSON.fromJson(data.get("state"), BeliefState.State.class);
            beliefState.importState(state);
        } catch (RuntimeException malformed) {
            throw new IOException("invalid belief state", malformed);
        }
    }

    /**
     * Persist only durable, observation-backed world assets. The index itself
     * excludes carried inventory, open menus and dropped entities from its
     * exported state because those must be rebuilt from live server state.
     */
    private void saveWorldAssets() throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("version", FORMAT_VERSION);
        data.addProperty("timestamp", System.currentTimeMillis());
        data.add("state", GSON.toJsonTree(worldAssetIndex.exportState()));
        writeAtomic(memoryDir.resolve(ASSET_FILE), GSON.toJson(data));
    }

    private void loadWorldAssets() throws IOException {
        Path file = memoryDir.resolve(ASSET_FILE);
        if (!Files.exists(file)) return;
        JsonObject data = readRoot(file);
        if (!data.has("state") || !data.get("state").isJsonObject()) {
            throw new IOException("world asset state is missing");
        }
        try {
            WorldAssetIndex.State state = GSON.fromJson(
                    data.get("state"), WorldAssetIndex.State.class);
            worldAssetIndex.importState(state);
        } catch (RuntimeException malformed) {
            throw new IOException("invalid world asset state", malformed);
        }
    }

    private void saveExperiences() throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("version", FORMAT_VERSION);
        data.addProperty("timestamp", System.currentTimeMillis());
        data.add("experiences", GSON.toJsonTree(experienceStore.exportAll()));
        writeAtomic(memoryDir.resolve(EXPERIENCE_FILE), GSON.toJson(data));
    }

    private void loadExperiences() throws IOException {
        Path file = memoryDir.resolve(EXPERIENCE_FILE);
        if (!Files.exists(file)) return;
        JsonObject data = readRoot(file);
        List<ExperienceStore.Experience> experiences = new ArrayList<>();
        int skipped = 0;
        for (JsonElement element : arrayField(data, "experiences")) {
            try {
                ExperienceStore.Experience experience = GSON.fromJson(
                        element, ExperienceStore.Experience.class);
                if (experience == null || experience.taskId() == null
                        || experience.action() == null || experience.outcome() == null) {
                    skipped++;
                } else {
                    experiences.add(experience);
                }
            } catch (RuntimeException malformed) {
                skipped++;
            }
        }
        logSkipped(EXPERIENCE_FILE, skipped);
        experienceStore.importAll(experiences);
    }

    private void saveSkills() throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("version", FORMAT_VERSION);
        data.addProperty("timestamp", System.currentTimeMillis());
        data.add("skills", GSON.toJsonTree(skillLibrary.exportAll()));
        writeAtomic(memoryDir.resolve(SKILL_FILE), GSON.toJson(data));
    }

    private void loadSkills() throws IOException {
        Path file = memoryDir.resolve(SKILL_FILE);
        if (!Files.exists(file)) return;
        JsonObject data = readRoot(file);
        List<SkillLibrary.Skill> skills = new ArrayList<>();
        int skipped = 0;
        for (JsonElement element : arrayField(data, "skills")) {
            try {
                SkillLibrary.Skill skill = GSON.fromJson(element, SkillLibrary.Skill.class);
                if (skill == null || skill.name() == null
                        || skill.actionSequence() == null) skipped++;
                else skills.add(skill);
            } catch (RuntimeException malformed) {
                skipped++;
            }
        }
        logSkipped(SKILL_FILE, skipped);
        skillLibrary.importAll(skills);
    }

    private record DialoguePair(String user, String assistant) {}

    private void saveConversation() throws IOException {
        List<DialoguePair> pairs = new ArrayList<>();
        String pendingUser = null;
        synchronized (conversationHistory) {
            for (ChatMessage message : conversationHistory) {
                if (message == null || message.content() == null
                        || message.content().isBlank()) continue;
                if ("user".equals(message.role())) {
                    String content = message.content().trim();
                    // Body/task events are already represented in structured
                    // memories. Persist only owner-facing conversation.
                    if (!content.startsWith("[BODY]")
                            && !content.startsWith("[TASK_")) {
                        pendingUser = bounded(content);
                    }
                } else if ("assistant".equals(message.role()) && pendingUser != null) {
                    pairs.add(new DialoguePair(pendingUser, bounded(message.content())));
                    pendingUser = null;
                }
            }
        }
        if (pairs.size() > MAX_DIALOGUE_PAIRS) {
            pairs = new ArrayList<>(pairs.subList(
                    pairs.size() - MAX_DIALOGUE_PAIRS, pairs.size()));
        }
        JsonObject data = new JsonObject();
        data.addProperty("version", FORMAT_VERSION);
        data.addProperty("timestamp", System.currentTimeMillis());
        data.add("pairs", GSON.toJsonTree(pairs));
        writeAtomic(memoryDir.resolve(CONVERSATION_FILE), GSON.toJson(data));
    }

    private void loadConversation() throws IOException {
        Path file = memoryDir.resolve(CONVERSATION_FILE);
        if (!Files.exists(file)) return;
        JsonObject data = readRoot(file);
        List<ChatMessage> restored = new ArrayList<>();
        int skipped = 0;
        for (JsonElement element : arrayField(data, "pairs")) {
            try {
                DialoguePair pair = GSON.fromJson(element, DialoguePair.class);
                if (pair == null || pair.user() == null || pair.user().isBlank()
                        || pair.assistant() == null || pair.assistant().isBlank()) {
                    skipped++;
                    continue;
                }
                restored.add(ChatMessage.user(bounded(pair.user())));
                restored.add(ChatMessage.assistant(bounded(pair.assistant())));
            } catch (RuntimeException malformed) {
                skipped++;
            }
        }
        logSkipped(CONVERSATION_FILE, skipped);
        synchronized (conversationHistory) {
            conversationHistory.addAll(restored);
        }
    }

    private static String bounded(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= MAX_DIALOGUE_CHARS ? normalized
                : normalized.substring(0, MAX_DIALOGUE_CHARS);
    }

    private static JsonObject readRoot(Path file) throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException malformedJson) {
            throw new IOException("invalid JSON", malformedJson);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("memory root must be a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        if (root.has("version")) {
            try {
                int version = root.get("version").getAsInt();
                if (version < 1 || version > FORMAT_VERSION) {
                    throw new IOException("unsupported memory version " + version);
                }
            } catch (UnsupportedOperationException | NumberFormatException badVersion) {
                throw new IOException("invalid memory version", badVersion);
            }
        }
        return root;
    }

    private static JsonArray arrayField(JsonObject root, String name) throws IOException {
        if (!root.has(name) || root.get(name).isJsonNull()) return new JsonArray();
        if (!root.get(name).isJsonArray()) {
            throw new IOException("field '" + name + "' must be an array");
        }
        return root.getAsJsonArray(name);
    }

    private static void logSkipped(String fileName, int skipped) {
        if (skipped > 0) {
            System.err.println("[MineAgent] Skipped " + skipped
                    + " invalid record(s) while loading " + fileName);
        }
    }

    /**
     * 迁移记忆目录（伴游改名时调用）。
     * 先把当前记忆刷到旧目录，再把文件移动到新目录，后续读写都走新目录。
     */
    public synchronized void migrateTo(Path newDir) {
        if (newDir == null || newDir.equals(memoryDir)) return;
        Path oldDir = memoryDir;
        // Flush current in-memory state to the old location first
        saveAll();
        try {
            if (Files.isDirectory(oldDir)) {
                Files.createDirectories(newDir);
                try (var stream = Files.list(oldDir)) {
                    for (Path f : stream.toList()) {
                        if (Files.isRegularFile(f)) {
                            Files.move(f, newDir.resolve(f.getFileName().toString()),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                Files.deleteIfExists(oldDir);
            }
        } catch (IOException e) {
            System.err.println("[MineAgent] Memory migration failed: " + e.getMessage());
        }
        this.memoryDir = newDir;
    }

    /**
     * 清空该伴游的所有持久化记忆文件。
     */
    public synchronized void clearAll() {
        try {
            Files.deleteIfExists(memoryDir.resolve(COGNITIVE_MAP_FILE));
            Files.deleteIfExists(memoryDir.resolve(PLACE_EVENT_FILE));
            Files.deleteIfExists(memoryDir.resolve(IMPORTANCE_FILE));
            Files.deleteIfExists(memoryDir.resolve(REFLECTION_FILE));
            Files.deleteIfExists(memoryDir.resolve(PLAN_FILE));
            Files.deleteIfExists(memoryDir.resolve(BELIEF_FILE));
            Files.deleteIfExists(memoryDir.resolve(ASSET_FILE));
            Files.deleteIfExists(memoryDir.resolve(EXPERIENCE_FILE));
            Files.deleteIfExists(memoryDir.resolve(SKILL_FILE));
            Files.deleteIfExists(memoryDir.resolve(CONVERSATION_FILE));
        } catch (IOException e) {
            System.err.println("[MineAgent] Failed to clear memories: " + e.getMessage());
        }
    }
}
