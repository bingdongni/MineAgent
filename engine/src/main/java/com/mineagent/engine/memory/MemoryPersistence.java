package com.mineagent.engine.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final int FORMAT_VERSION = 1;

    /** 自动保存间隔（毫秒） */
    private static final long AUTO_SAVE_INTERVAL = 60_000;

    private volatile Path memoryDir;
    private final CognitiveMap cognitiveMap;
    private final PlaceEventMemory placeEventMemory;
    private final ImportanceEvaluator importanceEvaluator;
    private final ReflectionSystem reflectionSystem;

    /** 上次保存时间 */
    private volatile long lastSaveTime = 0;

    /**
     * @param memoryDir 该伴游专属的记忆目录（由调用方确保唯一性）
     */
    public MemoryPersistence(Path memoryDir,
                             CognitiveMap cognitiveMap,
                             PlaceEventMemory placeEventMemory,
                             ImportanceEvaluator importanceEvaluator,
                             ReflectionSystem reflectionSystem) {
        this.memoryDir = memoryDir;
        this.cognitiveMap = cognitiveMap;
        this.placeEventMemory = placeEventMemory;
        this.importanceEvaluator = importanceEvaluator;
        this.reflectionSystem = reflectionSystem;
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
        try { loadCognitiveMap(); } catch (Exception e) {
            System.err.println("[MineAgent] Load cognitive map failed: " + e.getMessage());
        }
        try { loadPlaceEventMemory(); } catch (Exception e) {
            System.err.println("[MineAgent] Load place events failed: " + e.getMessage());
        }
        try { loadImportanceWeights(); } catch (Exception e) {
            System.err.println("[MineAgent] Load importance weights failed: " + e.getMessage());
        }
        try { loadReflections(); } catch (Exception e) {
            System.err.println("[MineAgent] Load reflections failed: " + e.getMessage());
        }
        // Loading is disk activity too. Without this update the first agent
        // turn immediately rewrote every file despite no memory changing.
        lastSaveTime = System.currentTimeMillis();
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
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> data = GSON.fromJson(Files.readString(file), type);
        validateSnapshot(data, "pois", file);

        Type poiListType = new TypeToken<List<CognitiveMap.PointOfInterest>>() {}.getType();
        List<CognitiveMap.PointOfInterest> pois =
                GSON.fromJson(GSON.toJsonTree(data.get("pois")), poiListType);
        cognitiveMap.importAll(pois);
    }

    // ─── PlaceEventMemory ───

    private void savePlaceEventMemory() throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("version", FORMAT_VERSION);
        data.put("timestamp", System.currentTimeMillis());
        data.put("events", placeEventMemory.exportAll());
        data.put("exploredChunks", placeEventMemory.exportExploredChunks());
        writeAtomic(memoryDir.resolve(PLACE_EVENT_FILE), GSON.toJson(data));
    }

    private void loadPlaceEventMemory() throws IOException {
        Path file = memoryDir.resolve(PLACE_EVENT_FILE);
        if (!Files.exists(file)) return;
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> data = GSON.fromJson(Files.readString(file), type);
        validateSnapshot(data, "events", file);

        // Parse every member before changing either live collection. A corrupt
        // chunk list must not produce a mixed snapshot in memory.
        Type eventListType = new TypeToken<List<PlaceEventMemory.PlaceEvent>>() {}.getType();
        List<PlaceEventMemory.PlaceEvent> events =
                GSON.fromJson(GSON.toJsonTree(data.get("events")), eventListType);
        Type chunksType = new TypeToken<List<Long>>() {}.getType();
        List<Long> exploredChunks = data.containsKey("exploredChunks")
                ? GSON.fromJson(GSON.toJsonTree(data.get("exploredChunks")), chunksType)
                : new ArrayList<>();
        placeEventMemory.importSnapshot(events, exploredChunks);
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
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> data = GSON.fromJson(Files.readString(file), type);
        validateSnapshot(data, "weights", file);

        Type weightsType = new TypeToken<Map<String, Float>>() {}.getType();
        Map<String, Float> weights =
                GSON.fromJson(GSON.toJsonTree(data.get("weights")), weightsType);
        int learnCount = data.get("learnCount") instanceof Number n ? n.intValue() : 0;
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
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        IOException failure = null;
        try {
            Files.writeString(tmp, content);
            try {
                Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // The completed temporary file remains safer than modifying
                // the target in place when atomic replacement is unavailable.
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            failure = e;
            throw e;
        } finally {
            try {
                // Failed writes and moves otherwise leave a partial .tmp file
                // which migrateTo() could later move into another companion.
                Files.deleteIfExists(tmp);
            } catch (IOException cleanupError) {
                if (failure != null) {
                    failure.addSuppressed(cleanupError);
                } else {
                    throw cleanupError;
                }
            }
        }
    }

    /** Reject incomplete or newer snapshots before they replace live memory. */
    private static void validateSnapshot(Map<String, Object> data, String requiredField,
                                         Path file) throws IOException {
        if (data == null || !data.containsKey(requiredField)
                || data.get(requiredField) == null) {
            throw new IOException("Incomplete memory snapshot: " + file.getFileName());
        }
        Object rawVersion = data.get("version");
        if (rawVersion != null && (!(rawVersion instanceof Number number)
                || number.intValue() != FORMAT_VERSION)) {
            throw new IOException("Unsupported memory format in " + file.getFileName()
                    + ": " + rawVersion);
        }
    }

    private void loadReflections() throws IOException {
        Path file = memoryDir.resolve(REFLECTION_FILE);
        if (!Files.exists(file)) return;
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> data = GSON.fromJson(Files.readString(file), type);
        validateSnapshot(data, "reflections", file);

        Type reflListType = new TypeToken<List<ReflectionSystem.Reflection>>() {}.getType();
        List<ReflectionSystem.Reflection> reflections =
                GSON.fromJson(GSON.toJsonTree(data.get("reflections")), reflListType);
        Type patternsType = new TypeToken<Map<String, Integer>>() {}.getType();
        Map<String, Integer> patterns =
                GSON.fromJson(GSON.toJsonTree(data.get("patterns")), patternsType);
        reflectionSystem.importAll(reflections, patterns);
    }

    /**
     * 迁移记忆目录（伴游改名时调用）。
     * 先把当前记忆刷到旧目录，再把文件移动到新目录，后续读写都走新目录。
     */
    public synchronized void migrateTo(Path newDir) {
        if (newDir == null || newDir.equals(memoryDir)) return;
        Path oldDir = memoryDir;
        // Switch the active directory only after every move has completed.  A
        // failed/partial migration must keep saving to the old directory so a
        // later save can reconstruct files that were already moved.
        boolean migrated = false;
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
            migrated = true;
        } catch (IOException e) {
            System.err.println("[MineAgent] Memory migration failed: " + e.getMessage());
        }
        // Keep writing to the old directory after a partial move failure. A
        // later save reconstructs any files already moved and avoids splitting
        // one companion's memory permanently across two directories.
        if (migrated) this.memoryDir = newDir;
    }

    /**
     * 清空该伴游的所有持久化记忆文件。
     */
    public synchronized void clearAll() {
        for (String fileName : List.of(COGNITIVE_MAP_FILE, PLACE_EVENT_FILE,
                IMPORTANCE_FILE, REFLECTION_FILE)) {
            try {
                // Each file is independent. One locked/corrupt entry must not
                // prevent every later memory file from being removed.
                Files.deleteIfExists(memoryDir.resolve(fileName));
                // saveAll() uses this same monitor, so a .tmp file cannot be
                // recreated between deleting the final and temporary files.
                Files.deleteIfExists(memoryDir.resolve(fileName + ".tmp"));
            } catch (IOException e) {
                System.err.println("[MineAgent] Failed to clear memory file "
                        + fileName + ": " + e.getMessage());
            }
        }
    }
}
