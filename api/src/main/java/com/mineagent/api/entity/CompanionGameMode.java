package com.mineagent.api.entity;

import java.util.Locale;
import java.util.Optional;

/**
 * The human-controlled game mode of one companion.
 *
 * <p>Vanilla exposes {@code HARDCORE} as a world rule rather than a
 * per-player {@code GameType}. MineAgent therefore stores it explicitly and
 * applies the survival GameType plus the companion's permanent-death rule.
 * The value is intentionally loader-neutral so the client and server share
 * exactly the same wire vocabulary.
 */
public enum CompanionGameMode {
    SURVIVAL("survival"),
    CREATIVE("creative"),
    ADVENTURE("adventure"),
    HARDCORE("hardcore");

    private final String wireName;

    CompanionGameMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public boolean isHardcore() {
        return this == HARDCORE;
    }

    public static Optional<CompanionGameMode> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (CompanionGameMode mode : values()) {
            if (mode.wireName.equals(normalized) || mode.name().equalsIgnoreCase(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    /** Backward-compatible normalization for old configs and packets. */
    public static CompanionGameMode orDefault(String raw) {
        return parse(raw).orElse(SURVIVAL);
    }
}
