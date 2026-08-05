package com.mineagent.engine.theory;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded first-order model of the owner's current intent.
 *
 * <p>Chat is weak evidence: a sentence may describe a future or hypothetical
 * action. Server observations are stronger evidence and enter through
 * {@link #observeIntent(PlayerIntent, float, String, long)}. Every signal uses
 * Minecraft game ticks, so decay remains stable across fast machines and is
 * never mixed with epoch milliseconds.
 */
public final class TheoryOfMind {
    public enum PlayerIntent {
        MINING("正在挖矿"),
        EXPLORING("正在探索"),
        BUILDING("正在建造"),
        FIGHTING("正在战斗"),
        FARMING("正在务农"),
        TRADING("正在交易"),
        IDLE("没有明确目标"),
        FLEEING("正在撤离危险"),
        UNKNOWN("意图尚不确定");

        private final String desc;
        PlayerIntent(String desc) { this.desc = desc; }
        public String desc() { return desc; }
    }

    /** One minute at 20 ticks/s; old evidence loses half its weight here. */
    private static final double HALF_LIFE_TICKS = 1_200.0;
    private static final float MAX_WEIGHT = 4.0f;
    private static final float DOMINANT_THRESHOLD = 0.22f;

    private final Map<PlayerIntent, Float> intentWeights =
            new EnumMap<>(PlayerIntent.class);
    private volatile PlayerIntent currentIntent = PlayerIntent.UNKNOWN;
    private volatile float urgency;
    private volatile float playerTrust = 0.5f;
    private volatile String lastIntentReason = "";
    private long lastObservationTick = Long.MIN_VALUE;
    private String lastEvidenceSignature = "";
    private long lastEvidenceTick = Long.MIN_VALUE;

    /** Infer a low-confidence intent signal from owner chat. */
    public synchronized void observe(String observedAction, long gameTick) {
        if (observedAction == null || observedAction.isBlank()) return;
        PlayerIntent inferred = inferIntent(observedAction.toLowerCase(Locale.ROOT));
        if (inferred != PlayerIntent.UNKNOWN) {
            observeIntent(inferred, 0.42f, "owner chat: " + observedAction, gameTick);
        } else {
            decayTo(gameTick);
            urgency = Math.max(0.0f, urgency - 0.03f);
        }
    }

    /**
     * Fuse a typed observation into the intent belief.
     *
     * @param confidence evidence reliability in [0,1]
     */
    public synchronized void observeIntent(PlayerIntent intent, float confidence,
                                           String evidence, long gameTick) {
        if (intent == null || intent == PlayerIntent.UNKNOWN) return;
        decayTo(gameTick);
        float boundedConfidence = clamp(confidence);
        String normalizedEvidence = evidence == null ? "server observation" : evidence.trim();
        String signature = intent.name() + "|" + normalizedEvidence;
        boolean duplicate = signature.equals(lastEvidenceSignature)
                && lastEvidenceTick != Long.MIN_VALUE && gameTick >= lastEvidenceTick
                && gameTick - lastEvidenceTick < 100L;
        if (!duplicate) {
            lastEvidenceSignature = signature;
            lastEvidenceTick = Math.max(0L, gameTick);
        }
        // A held item sampled 25 times is still one piece of evidence, not 25
        // independent confirmations. Sustained evidence may reinforce again
        // after five seconds, while urgency remains refreshed below.
        float evidenceIncrement = duplicate ? 0.0f : boundedConfidence;
        float updated = Math.min(MAX_WEIGHT,
                intentWeights.getOrDefault(intent, 0.0f) + evidenceIncrement);
        intentWeights.put(intent, updated);

        // Contradictory hypotheses are not deleted; they decay faster so a
        // rapid transition such as mining -> fleeing is represented promptly.
        for (PlayerIntent candidate : PlayerIntent.values()) {
            if (candidate == intent || candidate == PlayerIntent.UNKNOWN) continue;
            intentWeights.computeIfPresent(candidate,
                    (ignored, weight) -> Math.max(0.0f,
                            weight * (1.0f - 0.18f * evidenceIncrement)));
        }

        selectDominant();
        if (currentIntent == intent) {
            lastIntentReason = normalizedEvidence;
        }
        float urgencyTarget = switch (intent) {
            case FLEEING -> 1.0f;
            case FIGHTING -> 0.72f;
            default -> 0.18f;
        };
        urgency = clamp(Math.max(urgency * 0.92f,
                urgencyTarget * boundedConfidence));
    }

    private void decayTo(long gameTick) {
        long now = Math.max(0L, gameTick);
        if (lastObservationTick == Long.MIN_VALUE || now < lastObservationTick) {
            lastObservationTick = now;
            return;
        }
        long elapsed = now - lastObservationTick;
        if (elapsed == 0L) return;
        float factor = (float) Math.pow(0.5, elapsed / HALF_LIFE_TICKS);
        intentWeights.replaceAll((ignored, weight) -> Math.max(0.0f, weight * factor));
        urgency = Math.max(0.0f, urgency * factor);
        lastObservationTick = now;
        selectDominant();
    }

    private void selectDominant() {
        PlayerIntent best = PlayerIntent.UNKNOWN;
        float bestWeight = DOMINANT_THRESHOLD;
        for (Map.Entry<PlayerIntent, Float> entry : intentWeights.entrySet()) {
            if (entry.getValue() > bestWeight) {
                bestWeight = entry.getValue();
                best = entry.getKey();
            }
        }
        currentIntent = best;
        intentWeights.entrySet().removeIf(entry -> entry.getValue() < 0.01f);
    }

    public synchronized void onPlayerFeedback(boolean positive) {
        playerTrust = clamp(playerTrust + (positive ? 0.05f : -0.05f));
    }

    public String toPromptString() {
        StringBuilder out = new StringBuilder("## 对玩家的当前理解\n");
        out.append("- 推断意图: ").append(currentIntent.desc());
        if (!lastIntentReason.isBlank()) out.append("；证据: ").append(lastIntentReason);
        out.append('\n').append(String.format(Locale.ROOT,
                "- 紧急度: %.0f%%；信任度: %.0f%%\n",
                urgency * 100.0f, playerTrust * 100.0f));
        out.append("这是随新观测衰减的假设，不是事实；与主人明确指令冲突时以指令为准。\n");
        return out.toString();
    }

    public PlayerIntent currentIntent() { return currentIntent; }
    public float urgency() { return urgency; }
    public float playerTrust() { return playerTrust; }

    private static PlayerIntent inferIntent(String action) {
        if (containsAny(action, "挖", "mine", "dig", "break", "破坏", "ore", "矿")) {
            return PlayerIntent.MINING;
        }
        if (containsAny(action, "建", "build", "place", "放置", "craft", "合成", "construct", "搭建")) {
            return PlayerIntent.BUILDING;
        }
        if (containsAny(action, "打", "attack", "kill", "fight", "战斗", "杀", "剑", "sword", "bow")) {
            return PlayerIntent.FIGHTING;
        }
        if (containsAny(action, "种", "farm", "plant", "crop", "农", "harvest")) {
            return PlayerIntent.FARMING;
        }
        if (containsAny(action, "交易", "trade", "villager", "村民", "emerald", "绿宝石")) {
            return PlayerIntent.TRADING;
        }
        if (containsAny(action, "逃", "flee", "escape", "retreat", "撤退", "run away")) {
            return PlayerIntent.FLEEING;
        }
        if (containsAny(action, "走", "walk", "run", "跑", "move", "移动", "explore", "探索", "travel")) {
            return PlayerIntent.EXPLORING;
        }
        return PlayerIntent.UNKNOWN;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static float clamp(float value) {
        if (!Float.isFinite(value)) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
