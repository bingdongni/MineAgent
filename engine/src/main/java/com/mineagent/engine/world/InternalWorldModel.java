package com.mineagent.engine.world;

import java.util.HashMap;
import java.util.Map;

/**
 * 内部世界模型预演 — 让 AI 在执行前"预演"动作后果。
 *
 * <p>灵感来自 Dreamer 4 (DeepMind): 在脑内世界模型中预演
 * "如果我跳下去会不会摔死""如果我挖这块会不会淌出岩浆"。
 *
 * <p>当前版本是简化实现：基于 Minecraft 物理常识做快速预判，
 * 不训练神经网络世界模型（那需要大量数据和算力）。
 *
 * <p>预演能力：
 * <ul>
 *   <li>跳跃伤害预判：从高处跳下是否会摔伤</li>
 *   <li>熔岩/水预判：挖某方块是否会暴露液体</li>
 *   <li>爆炸预判：苦力怕爆炸范围预估</li>
 *   <li>窒息预判：在水下能待多久</li>
 * </ul>
 */
public class InternalWorldModel {

    /** 坠落伤害起始高度（3格以上开始受伤）。 */
    private static final int FALL_DAMAGE_THRESHOLD = 3;
    /** 每格坠落伤害。 */
    private static final float FALL_DAMAGE_PER_BLOCK = 1.0f;
    /** 苦力怕爆炸半径。 */
    private static final float CREEPER_BLAST_RADIUS = 3.0f;
    /** 水下窒息时间（秒）。 */
    private static final int DROWN_TIME_SECONDS = 15;

    /**
     * 预测从某高度跳下的后果。
     *
     * @param fallDistance 坠落距离（格）
     * @param currentHealth 当前血量
     * @return 预测结果描述
     */
    public String predictFall(int fallDistance, float currentHealth) {
        if (fallDistance < FALL_DAMAGE_THRESHOLD) {
            return "安全：这个高度不会受伤";
        }
        float damage = (fallDistance - FALL_DAMAGE_THRESHOLD + 1) * FALL_DAMAGE_PER_BLOCK;
        if (damage >= currentHealth) {
            return "危险！会摔死（伤害" + (int) damage + " > 血量" + (int) currentHealth + "）";
        }
        if (damage >= currentHealth * 0.5f) {
            return "警告：会受重伤（伤害" + (int) damage + "，剩余血量约" + (int)(currentHealth - damage) + "）";
        }
        return "可接受：会受轻伤（伤害" + (int) damage + "）";
    }

    /**
     * 预测在水中能待多久。
     *
     * @param currentAir 当前气泡数
     * @return 预测结果
     */
    public String predictDrowning(int currentAir) {
        if (currentAir <= 0) return "紧急：已经开始溺水！";
        int seconds = currentAir / 2; // 大约每 2 tick 减 1 气泡
        if (seconds < 5) return "危险：约 " + seconds + " 秒后开始溺水";
        if (seconds < 10) return "警告：约 " + seconds + " 秒后需要换气";
        return "安全：还有约 " + seconds + " 秒的气泡";
    }

    /**
     * 预测苦力怕爆炸的影响。
     *
     * @param distanceToCreeper 与苦力怕的距离（格）
     * @param hasArmor 是否穿着护甲
     * @return 预测结果
     */
    public String predictCreeperExplosion(float distanceToCreeper, boolean hasArmor) {
        if (distanceToCreeper > CREEPER_BLAST_RADIUS * 2) {
            return "安全：距离足够远";
        }
        if (distanceToCreeper <= CREEPER_BLAST_RADIUS) {
            if (hasArmor) {
                return "警告：在爆炸范围内，护甲能减伤但仍会受伤";
            }
            return "危险！在爆炸范围内，没有护甲可能被炸死";
        }
        return "警告：在爆炸边缘，可能受轻伤";
    }

    /**
     * 预测挖某方块的危险性。
     *
     * @param blockY 方块 Y 坐标
     * @param blockType 方块类型名
     * @return 预测结果
     */
    public String predictMineRisk(int blockY, String blockType) {
        String lower = blockType.toLowerCase();

        // 深层挖掘可能遇到熔岩
        if (blockY < 11) {
            return "警告：深层挖掘，注意熔岩！先在周围放火把或方块挡住";
        }

        // 砾岩可能引发坠落
        if (lower.contains("gravel") || lower.contains("sand")) {
            return "注意：砾岩/沙子可能引发坠落，小心下方是否有空洞";
        }

        // 矿石旁边可能有熔岩（深层）
        if (lower.contains("ore") && blockY < 15) {
            return "注意：深层矿石旁边可能有熔岩，挖之前做好准备";
        }

        return "安全：没有明显风险";
    }

    /**
     * 生成供 LLM 使用的能力描述。
     */
    public String toPromptString() {
        return """
            ## 你的预判能力
            你可以预判动作后果：
            - 跳跃前评估高度是否会摔伤（3格以上开始受伤）
            - 在水下注意气泡剩余时间（约15秒后开始溺水）
            - 看到苦力怕评估爆炸范围（3格内危险）
            - 深层挖掘（Y<11）注意熔岩风险
            做危险动作前先用这些常识预判，不要盲目行动。
            """;
    }
}
