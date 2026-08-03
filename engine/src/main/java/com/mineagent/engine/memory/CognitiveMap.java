package com.mineagent.engine.memory;

import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cognitive Map（认知地图）— 受启于海马体的空间记忆系统。
 *
 * <p>核心功能：
 * <ul>
 *   <li><b>空间记忆</b>：按类别记录 POI（resource:iron_ore / hazard:creeper / structure:village）的位置</li>
 *   <li><b>类别检索</b>：支持精确类别（"resource:iron_ore"）和前缀类别（"resource"）查询</li>
 *   <li><b>邻近检索</b>：查找指定半径内的所有记忆点</li>
 *   <li><b>语义摘要</b>：生成供 LLM prompt 使用的紧凑空间记忆文本</li>
 * </ul>
 *
 * <p><b>改进:</b>
 * <ul>
 *   <li>去重机制: 同类别且位置相近的 POI 自动合并强化，避免重复记忆</li>
 *   <li>重要性评分: 稀有资源/危险地点重要性高，淘汰时优先保留</li>
 *   <li>POI验证: 可标记已失效的 POI（如资源已挖走），避免过时信息误导</li>
 *   <li>访问追踪: 记录最近访问时间，淘汰时综合考虑重要性/访问频率/新近度</li>
 * </ul>
 *
 * <p>神经科学原理：海马体中的 place cells 编码空间位置，
 * grid cells 提供度量框架。AI 的认知地图结合两者，
 * 支持空间推理（"家在北边"、"矿洞在脚下"）。
 *
 * <p>线程安全：所有读写操作都通过 ConcurrentHashMap。
 */
public class CognitiveMap {

    /** 默认最大 POI 数量 */
    private static final int DEFAULT_MAX_POIS = 200;

    /** 去重半径（方块）：同类别 POI 在此半径内视为同一地点 */
    private static final int DEDUP_RADIUS = 16;

    /**
     * 兴趣点 (Point of Interest)。
     *
     * @param category     类别（如 "resource:iron_ore", "hazard:creeper", "structure:village"）
     * @param label        显示名称（如 "iron_ore (resource)"）
     * @param x            X 坐标
     * @param y            Y 坐标
     * @param z            Z 坐标
     * @param timestamp    首次发现时间（毫秒）
     * @param importance   重要性评分 (0.0-1.0)
     * @param visitCount   强化/访问次数
     * @param verified     是否已验证仍存在
     * @param lastVisitTime 最近强化时间（毫秒）
     */
    public record PointOfInterest(
            String category,
            String label,
            int x, int y, int z,
            String dimension,
            long timestamp,
            float importance,
            int visitCount,
            boolean verified,
            long lastVisitTime
    ) {
        /** 创建强化副本 */
        PointOfInterest reinforced(long now) {
            return new PointOfInterest(category, label, x, y, z, dimension, timestamp,
                    Math.min(1.0f, importance + 0.05f), visitCount + 1, true, now);
        }

        /** 标记为已失效 */
        PointOfInterest invalidated() {
            return new PointOfInterest(category, label, x, y, z, dimension, timestamp,
                    importance * 0.3f, visitCount, false, lastVisitTime);
        }
    }

    /** POI 存储：key -> PointOfInterest */
    private final Map<String, PointOfInterest> pois = new ConcurrentHashMap<>();
    private final int maxPOIs;

    public CognitiveMap() {
        this(DEFAULT_MAX_POIS);
    }

    public CognitiveMap(int maxPOIs) {
        this.maxPOIs = maxPOIs;
    }

    /**
     * 计算 POI 重要性分数。
     * 基于类别和标签内容评估：危险 > 稀有资源 > 普通资源 > 实体。
     */
    private float calculateImportance(String category, String label) {
        float score = 0.5f; // 基础分

        String lowerCat = category != null ? category.toLowerCase(Locale.ROOT) : "";
        String lowerLabel = label != null ? label.toLowerCase(Locale.ROOT) : "";
        String combined = lowerCat + " " + lowerLabel;

        // 类别基础分
        if (lowerCat.startsWith("hazard")) score += 0.3f;
        else if (lowerCat.startsWith("structure")) score += 0.15f;
        else if (lowerCat.startsWith("resource")) score += 0.1f;

        // 稀有度加分
        if (combined.contains("diamond") || combined.contains("emerald")
                || combined.contains("ancient") || combined.contains("netherite")
                || combined.contains("钻石")) {
            score += 0.25f;
        } else if (combined.contains("iron") || combined.contains("gold")
                || combined.contains("village") || combined.contains("chest")
                || combined.contains("redstone") || combined.contains("lapis")) {
            score += 0.15f;
        }

        return Math.min(1.0f, score);
    }

    /**
     * 记录一个 POI（带去重）。
     * 同类别且距离 <= 16 格的已有 POI 会被强化而非新建。
     *
     * @param pos       位置
     * @param category  类别（如 "resource:iron_ore"）
     * @param label     显示标签
     * @param timestamp 发现时间（毫秒）
     */
    public synchronized void recordPoi(BlockPos pos, String category, String label, long timestamp) {
        recordPoi(pos, category, label, "minecraft:overworld", timestamp);
    }

    public synchronized void recordPoi(BlockPos pos, String category, String label,
                          String dimension, long timestamp) {
        String cat = category != null ? category.toLowerCase(Locale.ROOT) : "event:unknown";
        String dim = dimension == null || dimension.isBlank()
                ? "minecraft:overworld" : dimension;

        // 去重：同类别 + 近距离 → 强化现有 POI
        String existingKey = findNearbyKey(cat, pos.getX(), pos.getY(), pos.getZ(),
                dim, DEDUP_RADIUS);
        if (existingKey != null) {
            PointOfInterest existing = pois.get(existingKey);
            if (existing != null) {
                pois.put(existingKey, existing.reinforced(timestamp));
            }
            return;
        }

        float importance = calculateImportance(cat, label);
        PointOfInterest poi = new PointOfInterest(cat, label,
                pos.getX(), pos.getY(), pos.getZ(),
                dim,
                timestamp, importance, 1, true, timestamp);
        // Y is part of identity: an ore vein in a cave must not overwrite a
        // same-X/Z landmark on the surface.
        pois.put(poiKey(cat, pos.getX(), pos.getY(), pos.getZ(), dim), poi);

        evictIfNeeded();
    }

    /**
     * 查找附近的记忆点（只返回已验证的）。
     *
     * @param x      中心 X 坐标
     * @param z      中心 Z 坐标
     * @param radius 半径（方块）
     * @return 按重要性降序排列的 POI 列表
     */
    public List<PointOfInterest> findNearby(int x, int z, int radius) {
        return findNearby(x, z, radius, null);
    }

    public List<PointOfInterest> findNearby(int x, int z, int radius, String dimension) {
        long r2 = (long) radius * radius;
        List<PointOfInterest> result = new ArrayList<>();
        for (PointOfInterest poi : pois.values()) {
            if (!poi.verified()) continue;
            if (dimension != null && !dimension.equals(poi.dimension())) continue;
            long dx = poi.x() - x;
            long dz = poi.z() - z;
            if (dx * dx + dz * dz <= r2) {
                result.add(poi);
            }
        }
        result.sort((a, b) -> Float.compare(b.importance(), a.importance()));
        return result;
    }

    /**
     * 按类别查找记忆点（只返回已验证的）。
     * 支持精确匹配（"resource:iron_ore"）和前缀匹配（"resource" 匹配所有资源）。
     *
     * @param category 类别查询串
     * @return 按重要性降序排列的 POI 列表
     */
    public List<PointOfInterest> findByCategory(String category) {
        return findByCategory(category, null);
    }

    public List<PointOfInterest> findByCategory(String category, String dimension) {
        String query = category != null ? category.toLowerCase(Locale.ROOT) : "";
        List<PointOfInterest> result = new ArrayList<>();
        for (PointOfInterest poi : pois.values()) {
            if (!poi.verified()) continue;
            if (dimension != null && !dimension.equals(poi.dimension())) continue;
            String cat = poi.category();
            // 精确匹配，或前缀匹配（"resource" 匹配 "resource:iron_ore"）
            if (cat.equals(query) || cat.startsWith(query + ":")) {
                result.add(poi);
            }
        }
        result.sort((a, b) -> Float.compare(b.importance(), a.importance()));
        return result;
    }

    /**
     * 标记指定位置附近的 POI 为已失效（如资源已被挖走）。
     */
    public void invalidateNearby(int x, int z, int radius) {
        long r2 = (long) radius * radius;
        for (Map.Entry<String, PointOfInterest> entry : pois.entrySet()) {
            PointOfInterest poi = entry.getValue();
            long dx = poi.x() - x;
            long dz = poi.z() - z;
            if (dx * dx + dz * dz <= r2) {
                entry.setValue(poi.invalidated());
            }
        }
    }

    /**
     * 生成供 LLM prompt 使用的空间记忆摘要。
     * 按类别分组，只显示已验证且重要的 POI，避免 token 膨胀。
     */
    public String summarizeForPrompt() {
        return summarizeForPrompt(null);
    }

    public String summarizeForPrompt(String dimension) {
        List<PointOfInterest> verified = new ArrayList<>();
        for (PointOfInterest poi : pois.values()) {
            if (poi.verified() && (dimension == null || dimension.equals(poi.dimension()))) {
                verified.add(poi);
            }
        }
        if (verified.isEmpty()) return "";

        // 按重要性排序，最多显示 8 条
        verified.sort((a, b) -> Float.compare(b.importance(), a.importance()));

        StringBuilder sb = new StringBuilder();
        sb.append("## 空间记忆\n");
        int shown = 0;
        Map<String, Integer> perTopCategory = new HashMap<>();
        for (PointOfInterest poi : verified) {
            if (shown >= 8) break;
            // 同一大类（resource/hazard/...）最多显示 3 条，保证多样性
            String topCat = poi.category().split(":")[0];
            int count = perTopCategory.getOrDefault(topCat, 0);
            if (count >= 3) continue;
            sb.append("- ").append(poi.label())
              .append(" at (").append(poi.x()).append(",")
              .append(poi.y()).append(",").append(poi.z()).append(")");
            if (poi.visitCount() > 2) {
                sb.append(" [已确认x").append(poi.visitCount()).append("]");
            }
            sb.append("\n");
            perTopCategory.put(topCat, count + 1);
            shown++;
        }
        return sb.toString();
    }

    // ─── 持久化支持 ───

    /**
     * 导出所有 POI（用于持久化保存）。
     */
    public List<PointOfInterest> exportAll() {
        return new ArrayList<>(pois.values());
    }

    /**
     * 导入 POI 列表（用于持久化恢复），替换当前所有数据。
     */
    public synchronized void importAll(List<PointOfInterest> imported) {
        Map<String, PointOfInterest> validated = new HashMap<>();
        if (imported != null) {
            for (PointOfInterest poi : imported) {
                if (poi == null || poi.category() == null || poi.category().isBlank()
                        || !Float.isFinite(poi.importance())) {
                    continue;
                }
                String category = poi.category().trim().toLowerCase(Locale.ROOT);
                String dimension = poi.dimension() == null || poi.dimension().isBlank()
                        ? "minecraft:overworld" : poi.dimension().trim();
                PointOfInterest normalized = new PointOfInterest(
                        category, poi.label(), poi.x(), poi.y(), poi.z(), dimension,
                        poi.timestamp(), Math.max(0.0f, Math.min(1.0f, poi.importance())),
                        Math.max(0, poi.visitCount()), poi.verified(), poi.lastVisitTime());
                validated.put(poiKey(category, normalized.x(), normalized.y(),
                        normalized.z(), dimension), normalized);
            }
        }
        // Build the replacement before mutation. NaN importance values from a
        // corrupt file otherwise poison ranking and eviction after restoration.
        pois.clear();
        pois.putAll(validated);
        evictIfNeeded();
    }

    // ─── 私有方法 ───

    /** 生成 POI 存储键 */
    private String poiKey(String category, int x, int y, int z, String dimension) {
        return category + ":" + x + "," + y + "," + z + ":" + dimension;
    }

    /** 查找同类别且在半径内的已有 POI 键 */
    private String findNearbyKey(String category, int x, int y, int z,
                                 String dimension, int radius) {
        long r2 = (long) radius * radius;
        for (Map.Entry<String, PointOfInterest> entry : pois.entrySet()) {
            PointOfInterest poi = entry.getValue();
            if (!poi.category().equals(category)) continue;
            if (!Objects.equals(poi.dimension(), dimension)) continue;
            long dx = poi.x() - x;
            long dy = poi.y() - y;
            long dz = poi.z() - z;
            if (dx * dx + dy * dy + dz * dz <= r2) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 按综合评分淘汰 POI。
     * 评分 = 重要性*0.4 + 访问频率*0.2 + 新近度*0.2 + 验证状态*0.2
     */
    private void evictIfNeeded() {
        while (pois.size() > maxPOIs) {
            String leastKey = null;
            float minScore = Float.MAX_VALUE;
            long now = System.currentTimeMillis();

            for (Map.Entry<String, PointOfInterest> entry : pois.entrySet()) {
                PointOfInterest poi = entry.getValue();
                long age = now - poi.lastVisitTime();
                float recencyFactor = Math.max(0.1f, 1.0f - age / 7200000f); // 2小时衰减
                float verifiedBonus = poi.verified() ? 0.2f : 0f;
                float score = poi.importance() * 0.4f
                        + Math.min(poi.visitCount() / 10f, 1.0f) * 0.2f
                        + recencyFactor * 0.2f
                        + verifiedBonus;

                if (score < minScore) {
                    minScore = score;
                    leastKey = entry.getKey();
                }
            }

            if (leastKey != null) {
                pois.remove(leastKey);
            } else {
                break;
            }
        }
    }

    /** 获取 POI 数量 */
    public int size() {
        return pois.size();
    }

    /** 清空所有数据 */
    public synchronized void clear() {
        pois.clear();
    }
}
