package com.mineagent.engine.memory;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Lightweight multilingual lexical similarity without an embedding service. */
public final class TextSimilarity {
    private TextSimilarity() {}

    public static double score(String first, String second) {
        Set<String> left = features(first);
        Set<String> right = features(second);
        if (left.isEmpty() || right.isEmpty()) return 0.0;
        int intersection = 0;
        for (String token : left) if (right.contains(token)) intersection++;
        if (intersection == 0) return 0.0;
        return intersection / Math.sqrt((double) left.size() * right.size());
    }

    static Set<String> features(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String normalized = text.toLowerCase(Locale.ROOT);
        HashSet<String> result = new HashSet<>();
        for (String token : normalized.split("[^a-z0-9_]+")) {
            if (token.length() > 1) result.add("w:" + token);
        }
        StringBuilder hanRun = new StringBuilder();
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                hanRun.appendCodePoint(codePoint);
            } else {
                addHanNgrams(result, hanRun);
                hanRun.setLength(0);
            }
        }
        addHanNgrams(result, hanRun);
        return Set.copyOf(result);
    }

    private static void addHanNgrams(Set<String> result, StringBuilder run) {
        int[] codePoints = run.toString().codePoints().toArray();
        for (int size = 2; size <= 3; size++) {
            for (int start = 0; start + size <= codePoints.length; start++) {
                result.add("h:" + new String(codePoints, start, size));
            }
        }
        if (codePoints.length == 1) result.add("h:" + run);
    }
}
