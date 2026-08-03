package com.mineagent.engine.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 决策缓存 — 缓存相似情境下的 LLM 决策结果。
 *
 * <p>目的：减少 LLM 调用次数，降低延迟。
 * 当 AI 在相似状态下收到相似指令时，直接复用之前的决策。
 *
 * <p>Key 设计：(位置量化 + 血量区间 + 最近实体类型 + 玩家指令哈希)
 * Value：LLM 的决策结果（assistant message 的 content + tool_calls）
 *
 * <p>TTL 30 秒，避免缓存过旧导致行为不合理。
 */
public class DecisionCache {

    /** 缓存条目。 */
    private record CacheEntry(
            String decisionContent,
            String toolCallsJson,
            long expiresAt
    ) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 缓存 TTL（毫秒）。
     *  从 30 秒缩短到 10 秒，降低情境变化导致决策错乱的风险。
     *  10 秒足够覆盖"挖矿连续操作"等重复场景，又不会让 AI
     *  在玩家已离开后仍然重复旧决策。 */
    private static final long TTL_MS = 10_000;

    /** 最大缓存条目数。 */
    private static final int MAX_SIZE = 500;

    /**
     * 生成缓存 key。
     *
     * @param posX       AI 玩家 X 坐标（量化到 4 格）
     * @param posY       AI 玩家 Y 坐标（量化到 4 格）
     * @param posZ       AI 玩家 Z 坐标（量化到 4 格）
     * @param healthTier 血量区间（0-4 / 5-9 / 10-14 / 15-20）
     * @param hungerTier 饥饿度区间
     * @param nearestEntity 最近实体类型（或 null）
     * @param command    玩家指令（或 null）
     * @return 缓存 key
     */
    public static String buildKey(int posX, int posY, int posZ,
                                    int healthTier, int hungerTier,
                                    String nearestEntity, String command) {
        // 量化位置到 4 格精度
        int qx = posX >> 2; // posX / 4
        int qy = posY >> 2;
        int qz = posZ >> 2;
        return qx + "," + qy + "," + qz + "|"
                + healthTier + "|" + hungerTier + "|"
                + (nearestEntity != null ? nearestEntity : "-") + "|"
                + (command != null ? command.hashCode() : "-");
    }

    /**
     * 查询缓存。
     *
     * @return 缓存的决策，或 null（未命中/已过期）
     */
    public CacheEntry get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null || entry.isExpired()) {
            if (entry != null) cache.remove(key);
            return null;
        }
        return entry;
    }

    /**
     * 查询缓存中的决策文本。
     */
    public String getDecision(String key) {
        CacheEntry entry = get(key);
        return entry != null ? entry.decisionContent() : null;
    }

    /**
     * 查询缓存中的工具调用 JSON。
     */
    public String getToolCalls(String key) {
        CacheEntry entry = get(key);
        return entry != null ? entry.toolCallsJson() : null;
    }

    /**
     * 存入决策缓存。
     */
    public void put(String key, String decisionContent, String toolCallsJson) {
        // 容量控制
        if (cache.size() >= MAX_SIZE) {
            // 简单清理：移除已过期的
            cache.entrySet().removeIf(e -> e.getValue().isExpired());
            // 如果还是太大，清空一半
            if (cache.size() >= MAX_SIZE) {
                int half = cache.size() / 2;
                var iter = cache.keySet().iterator();
                for (int i = 0; i < half && iter.hasNext(); i++) {
                    iter.next();
                    iter.remove();
                }
            }
        }
        cache.put(key, new CacheEntry(
                decisionContent, toolCallsJson,
                System.currentTimeMillis() + TTL_MS));
    }

    /**
     * 清空缓存。
     */
    public void clear() {
        cache.clear();
    }

    /**
     * 当前缓存大小。
     */
    public int size() {
        return cache.size();
    }

    /**
     * 清理过期条目。
     */
    public void cleanExpired() {
        cache.entrySet().removeIf(e -> e.getValue().isExpired());
    }
}
