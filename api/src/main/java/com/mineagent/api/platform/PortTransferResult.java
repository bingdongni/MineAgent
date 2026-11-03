package com.mineagent.api.platform;

/** Actual public-port transfer outcome, not a guessed inventory delta. */
public record PortTransferResult(boolean supported, boolean verified, int moved, String message) {
    public PortTransferResult {
        moved = Math.max(0, Math.min(64, moved));
        message = message == null ? "" : message;
    }
    public static PortTransferResult unavailable(String reason) { return new PortTransferResult(false, false, 0, reason); }
}
