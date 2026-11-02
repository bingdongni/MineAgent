package com.mineagent.engine.llm;

import java.util.List;
import java.net.http.HttpRequest;

/** ByteDance Volcengine Ark native route (the Ark v3 JSON is OpenAI-shaped). */
public final class ByteDanceArkProvider extends OpenAICompatibleProvider {
    @Override public ByteDanceArkProvider newSession() { return new ByteDanceArkProvider(); }
    public ByteDanceArkProvider() {
        super("bytedance-ark", "ByteDance Ark",
                "https://ark.cn-beijing.volces.com/api/v3",
                List.of("doubao-seed-1-6-250615", "doubao-pro-32k"),
                java.util.Set.of("doubao-", "ep-"));
    }

    @Override public boolean supportsModel(String model) {
        return model != null && !model.isBlank();
    }

    @Override protected String chatEndpoint(String base, String model) {
        return ProviderSupport.endpoint(base, "/chat/completions");
    }

    @Override protected void addAuthentication(HttpRequest.Builder builder, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("ByteDance Ark API key is not configured");
        }
        builder.header("Authorization", "Bearer " + apiKey);
    }
}
