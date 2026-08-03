package com.mineagent.engine.memory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PEM 位置事件记忆 — 记录"什么-在哪-何时"（What-Where-When）。
 *
 * <p>灵感来自 Mr.Steve (ICLR 2025) 的 Place Event Memory。
 * 解决 AI "瞎转悠"和"重复探索"的核心问题：
 *
 * <ul>
 *   <li><b>记录</b>：在哪里见过什么资源（牛、铁矿石、树）</li>
 *   <li><b>记录</b>：在哪里发生过什么事件（被苦力怕炸、找到钻石）</li>
 *   <li><b>检索</b>：需要某资源时，查记忆里上次见到的位置</li>
 *   <li><b>避免重复</b>：已经搜过的区域不再重复搜索</li>
 * </ul>
 *
 * <p><b>改进:</b>
 * <ul>
 *   <li>去重机制: 同一位置同一subject只保留最新记录</li>
 *   <li>重要性评分: 按重要性淘汰，稀有资源优先保留</li>
 *   <li>索引优化: 使用Map直接访问，避免全量重建</li>
 *   <li>POI验证: 标记已失效的资源点</li>
 * </ul>
 *
 * <p>线程安全：所有读写操作都通过 ConcurrentHashMap。
 */
public class PlaceEventMemory {

    /**
     * 一条位置事件记录。
     *
     * @param type     事件类型（resource/entity/danger/structure/loot）
     * @param subject  主体（如 "cow", "iron_ore", "creeper_explosion", "village"）
     * @param x        X 坐标
     * @param y        Y 坐标
     * @param z        Z 坐标
     * @param dimension 维度（overworld/nether/end）
     * @param timestamp 游戏时间（tick）
     * @param note     附注（可为 null）
     * @param importance 重要性评分 (0.0-1.0)
     * @param visitCount 访问/强化次数
     * @param verified 是否已验证仍存在
     */
    public record PlaceEvent(
            String type,
            String subject,
            int x, int y, int z,
            String dimension,
            long timestamp,
            String note,
            float importance,
            int visitCount,
            boolean verified
    ) {
        /** 创建强化副本 */
        PlaceEvent reinforced(long newTimestamp) {
            return new PlaceEvent(type, subject, x, y, z, dimension, newTimestamp, note,
                    Math.min(1.0f, importance + 0.05f), visitCount + 1, true);
        }

        /** 标记为已失效 */
        PlaceEvent invalidated() {
            return new PlaceEvent(type, subject, x, y, z, dimension, timestamp, note,
                    importance * 0.3f, visitCount, false);
        }

        /** 获取位置键 */
        String locationKey() {
            return subject.toLowerCase(Locale.ROOT) + ":" + x + "," + y + "," + z
                    + ":" + dimension;
        }
    }

    private final List<PlaceEvent> events = Collections.synchronizedList(new ArrayList<>());

    /** 按 subject 建索引，加速检索。 */
    private final Map<String, List<PlaceEvent>> bySubject = new ConcurrentHashMap<>();

    /** 位置去重索引: locationKey -> PlaceEvent */
    private final Map<String, PlaceEvent> locationIndex = new ConcurrentHashMap<>();

    /** FoV 覆盖：记录已探索的区块坐标集合，避免重复扫描。 */
    private final Set<Long> exploredChunks = ConcurrentHashMap.newKeySet();

    /** 最大记忆条目数 */
    private static final int MAX_EVENTS = 500;

    /**
     * 计算记忆重要性分数
     */
    private float calculateImportance(String type, String subject, String note) {
        float score = 0.5f; // 基础分

        // 危险记忆重要性高
        if ("danger".equals(type)) score += 0.35f;
        if ("loot".equals(type)) score += 0.25f;
        if ("structure".equals(type)) score += 0.15f;

        // 稀有资源加分
        String lower = (subject + " " + (note != null ? note : ""))
                .toLowerCase(Locale.ROOT);
        if (lower.contains("diamond") || lower.contains("emerald") ||
            lower.contains("ancient") || lower.contains("netherite") ||
            lower.contains("elytra") || lower.contains("beacon")) {
            score += 0.25f;
        } else if (lower.contains("iron") || lower.contains("gold") ||
                   lower.contains("redstone") || lower.contains("lapis") ||
                   lower.contains("village") || lower.contains("chest")) {
            score += 0.15f;
        }

        return Math.min(1.0f, score);
    }

    /**
     * 记录一条位置事件（带去重）。
     */
    public synchronized void remember(String type, String subject,
                          int x, int y, int z, String dimension,
                          long timestamp, String note) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(dimension, "dimension");
        // Include Y so vertically separated caves/structures do not overwrite
        // each other merely because they share a column.
        String locKey = locationKey(subject, x, y, z, dimension);

        // 检查是否已存在相同位置相同subject的记忆
        PlaceEvent existing = locationIndex.get(locKey);
        if (existing != null) {
            // 更新现有记忆：增加访问次数，更新重要性
            float newImportance = Math.max(existing.importance(),
                    calculateImportance(type, subject, note));
            PlaceEvent updated = new PlaceEvent(type, subject, x, y, z, dimension,
                    timestamp, note, newImportance, existing.visitCount() + 1, true);

            // 从旧位置移除，添加新记录
            removeFromIndices(existing);
            addToIndices(updated);
            return;
        }

        // 创建新记忆
        float importance = calculateImportance(type, subject, note);
        PlaceEvent e = new PlaceEvent(type, subject, x, y, z, dimension,
                timestamp, note, importance, 1, true);
        addToIndices(e);

        // 按重要性淘汰：超过容量时移除最不重要的
        while (events.size() > MAX_EVENTS) {
            PlaceEvent leastImportant = findLeastImportant();
            if (leastImportant != null) {
                removeFromIndices(leastImportant);
            } else {
                break;
            }
        }
    }

    /** 添加到索引（bySubject 列表读写两侧都必须 synchronized (list)） */
    private void addToIndices(PlaceEvent e) {
        events.add(e);
        List<PlaceEvent> list = bySubject.computeIfAbsent(
                e.subject().toLowerCase(Locale.ROOT),
                k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.add(e);
        }
        locationIndex.put(e.locationKey(), e);
    }

    /** 从索引移除 */
    private void removeFromIndices(PlaceEvent e) {
        events.remove(e);
        locationIndex.remove(e.locationKey());
        List<PlaceEvent> list = bySubject.get(e.subject().toLowerCase(Locale.ROOT));
        if (list != null) {
            synchronized (list) {
                list.remove(e);
                if (list.isEmpty()) {
                    bySubject.remove(e.subject().toLowerCase(Locale.ROOT), list);
                }
            }
        }
    }

    /** 找到最不重要的记忆 */
    private PlaceEvent findLeastImportant() {
        if (events.isEmpty()) return null;

        PlaceEvent least = null;
        float minScore = Float.MAX_VALUE;

        synchronized (events) {
            for (PlaceEvent e : events) {
                // 综合评分：重要性 * 0.5 + 访问次数 * 0.2 + 时间衰减 * 0.2 + 验证状态 * 0.1
                long age = System.currentTimeMillis() - e.timestamp();
                float timeFactor = Math.max(0.1f, 1.0f - age / 7200000f); // 2小时衰减
                float verifiedBonus = e.verified() ? 0.1f : 0f;
                float score = e.importance() * 0.5f +
                             Math.min(e.visitCount() / 10f, 1.0f) * 0.2f +
                             timeFactor * 0.2f +
                             verifiedBonus;

                if (score < minScore) {
                    minScore = score;
                    least = e;
                }
            }
        }
        return least;
    }

    /**
     * 标记某个位置的资源为已失效（如已被挖掘）。
     */
    public synchronized void invalidate(String subject, int x, int y, int z, String dimension) {
        if (subject == null || dimension == null) return;
        String locKey = locationKey(subject, x, y, z, dimension);
        PlaceEvent existing = locationIndex.get(locKey);
        if (existing != null) {
            PlaceEvent invalidated = existing.invalidated();
            removeFromIndices(existing);
            addToIndices(invalidated);
        }
    }

    /**
     * 查找某个 subject 最近一次出现的位置。
     *
     * @param subject 资源名（如 "iron_ore", "cow", "village"）
     * @return 最近的 PlaceEvent，或 null
     */
    public PlaceEvent findLastSeen(String subject) {
        if (subject == null) return null;
        List<PlaceEvent> list = bySubject.get(subject.toLowerCase(Locale.ROOT));
        if (list == null) return null;
        synchronized (list) {
            // The subject list can be emptied between the map lookup and
            // acquiring its monitor. Re-check under the same lock used by
            // writers so the invalid-only fallback never indexes -1.
            if (list.isEmpty()) return null;
            // 过滤掉已失效的，返回最新的有效记录
            for (int i = list.size() - 1; i >= 0; i--) {
                PlaceEvent e = list.get(i);
                if (e.verified()) return e;
            }
            return list.get(list.size() - 1); // 都失效则返回最新的
        }
    }

    /**
     * 查找某个 subject 在指定维度内的所有出现位置。
     */
    public List<PlaceEvent> findAll(String subject, String dimension) {
        if (subject == null || dimension == null) return Collections.emptyList();
        List<PlaceEvent> list = bySubject.get(subject.toLowerCase(Locale.ROOT));
        if (list == null) return Collections.emptyList();
        List<PlaceEvent> result = new ArrayList<>();
        synchronized (list) {
            for (PlaceEvent e : list) {
                if (e.dimension().equals(dimension) && e.verified()) {
                    result.add(e);
                }
            }
        }
        // 按重要性排序
        result.sort((a, b) -> Float.compare(b.importance(), a.importance()));
        return result;
    }

    /**
     * 标记一个区块为已探索。
     */
    public void markExplored(int chunkX, int chunkZ) {
        exploredChunks.add(chunkKey(chunkX, chunkZ));
    }

    /**
     * 检查一个区块是否已探索过。
     */
    public boolean isExplored(int chunkX, int chunkZ) {
        return exploredChunks.contains(chunkKey(chunkX, chunkZ));
    }

    /**
     * 生成供 LLM 使用的记忆摘要文本。
     * 只返回最近、最相关的记忆，避免 token 膨胀。
     */
    public String summarizeForPrompt() {
        if (events.isEmpty()) return "（暂无位置记忆）";

        StringBuilder sb = new StringBuilder();
        sb.append("## 位置记忆（最近见过的资源/事件）\n");

        // 按 subject 分组，每组只取最近一条有效记录
        Map<String, PlaceEvent> latest = new LinkedHashMap<>();
        synchronized (events) {
            for (PlaceEvent e : events) {
                if (e.verified()) {
                    latest.put(e.subject().toLowerCase(Locale.ROOT), e); // 后面的覆盖前面的
                }
            }
        }

        // 按重要性排序
        List<Map.Entry<String, PlaceEvent>> sorted = new ArrayList<>(latest.entrySet());
        sorted.sort((a, b) -> Float.compare(b.getValue().importance(), a.getValue().importance()));

        int shown = 0;
        for (var entry : sorted) {
            if (shown >= 10) break; // 最多显示10条
            PlaceEvent e = entry.getValue();
            sb.append("- ").append(e.subject())
              .append(" at (").append(e.x()).append(",").append(e.y()).append(",").append(e.z()).append(")")
              .append(" [").append(e.dimension()).append("]");
            if (e.note() != null && !e.note().isBlank()) {
                sb.append(" — ").append(e.note());
            }
            sb.append("\n");
            shown++;
        }

        // 已探索区块数
        sb.append("（已探索 ").append(exploredChunks.size()).append(" 个区块）\n");

        return sb.toString();
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static String locationKey(String subject, int x, int y, int z,
                                      String dimension) {
        return subject.toLowerCase(Locale.ROOT) + ":" + x + "," + y + "," + z
                + ":" + dimension;
    }

    /** 清空所有记忆（用于测试或重置） */
    public synchronized void clear() {
        events.clear();
        bySubject.clear();
        locationIndex.clear();
        exploredChunks.clear();
    }

    // ─── 持久化支持 ───

    /**
     * 导出所有记忆事件（用于持久化保存）。
     */
    public List<PlaceEvent> exportAll() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    /** Snapshot explored chunks so restarts do not erase exploration state. */
    public Set<Long> exportExploredChunks() {
        return new HashSet<>(exploredChunks);
    }

    public synchronized void importExploredChunks(Collection<Long> imported) {
        exploredChunks.clear();
        if (imported != null) {
            imported.stream().filter(Objects::nonNull).forEach(exploredChunks::add);
        }
    }

    /** Replace both members stored in place_events.json under one lock. */
    public synchronized void importSnapshot(List<PlaceEvent> imported,
                                            Collection<Long> importedChunks) {
        importAll(imported);
        importExploredChunks(importedChunks);
    }

    /**
     * 导入记忆事件列表（用于持久化恢复），替换当前所有数据。
     */
    public synchronized void importAll(List<PlaceEvent> imported) {
        List<PlaceEvent> validated = new ArrayList<>();
        if (imported != null) {
            for (PlaceEvent event : imported) {
                if (event == null || event.subject() == null || event.subject().isBlank()
                        || !Float.isFinite(event.importance())) {
                    continue;
                }
                String dimension = event.dimension() == null || event.dimension().isBlank()
                        ? "minecraft:overworld" : event.dimension().trim();
                validated.add(new PlaceEvent(
                        event.type(), event.subject().trim(), event.x(), event.y(), event.z(),
                        dimension, event.timestamp(), event.note(),
                        Math.max(0.0f, Math.min(1.0f, event.importance())),
                        Math.max(0, event.visitCount()), event.verified()));
            }
        }
        // addToIndices updates three correlated collections. Validate the whole
        // input first so one malformed entry cannot expose a partial index.
        events.clear();
        bySubject.clear();
        locationIndex.clear();
        for (PlaceEvent event : validated) {
            addToIndices(event);
        }
        // 导入后若超容量则按重要性淘汰
        while (events.size() > MAX_EVENTS) {
            PlaceEvent leastImportant = findLeastImportant();
            if (leastImportant != null) {
                removeFromIndices(leastImportant);
            } else {
                break;
            }
        }
    }

    /** 获取记忆条目数 */
    public int size() {
        return events.size();
    }
}
