package com.mineagent.engine.client.screen;

/** Input normalization used before GUI values are inserted into commands. */
final class ClientCommandInput {

    private ClientCommandInput() {}

    /** Removes control characters while preserving printable URL/model characters. */
    static String greedy(String raw, int maxLength) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder clean = new StringBuilder(Math.min(raw.length(), maxLength));
        for (int i = 0; i < raw.length() && clean.length() < maxLength; i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) clean.append(c);
        }
        return clean.toString().trim();
    }

    /** Accepts only the character set understood by Brigadier word arguments. */
    static String word(String raw, int maxLength) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder clean = new StringBuilder(Math.min(raw.length(), maxLength));
        for (int i = 0; i < raw.length() && clean.length() < maxLength; i++) {
            char c = raw.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-'
                    || c == '+') {
                clean.append(c);
            }
        }
        return clean.toString();
    }

    /** Quotes a Brigadier string argument without permitting a second command. */
    static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
