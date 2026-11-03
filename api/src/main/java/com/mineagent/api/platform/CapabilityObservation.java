package com.mineagent.api.platform;

import java.util.Map;
import java.util.TreeMap;

/** Bounded, read-only automation-port observations. Missing channels are unknown, never zero. */
public record CapabilityObservation(Map<String, String> fields, Map<String, String> channels,
                                    boolean truncated) {
    public CapabilityObservation {
        int originalSize = fields == null ? 0 : fields.size();
        fields = bounded(fields, 96);
        channels = bounded(channels, 8);
        truncated |= originalSize > fields.size();
    }

    public static CapabilityObservation unsupported() {
        return new CapabilityObservation(Map.of(), Map.of("adapter", "unavailable"), false);
    }

    private static Map<String, String> bounded(Map<String, String> source, int limit) {
        if (source == null) return Map.of();
        TreeMap<String, String> values = new TreeMap<>();
        source.forEach((key, value) -> {
            if (key != null && key.matches("[a-zA-Z0-9_.:-]{1,96}") && value != null && value.length() <= 256)
                values.put(key, value);
        });
        return java.util.Collections.unmodifiableMap(values.entrySet().stream().limit(limit)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, java.util.LinkedHashMap::new)));
    }
}
